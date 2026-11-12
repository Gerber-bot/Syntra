#include <android/log.h>
#include <jni.h>
#include <atomic>
#include <iomanip>
#include <math.h>
#include <mutex>
#include <string>
#include <unistd.h>
#include <vector>
#include "llama.h"
#include "common.h"

#define TAG "llama-android.cpp"
#define LOGi(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGe(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static void jni_throw(JNIEnv *env, const char *class_name, const char *message) {
    if (!env) return;
    jclass cls = env->FindClass(class_name);
    if (!cls) {
        env->ExceptionClear();
        cls = env->FindClass("java/lang/RuntimeException");
    }
    if (cls) {
        env->ThrowNew(cls, message);
        env->DeleteLocalRef(cls);
    }
}

static std::atomic<uint64_t> g_active_generation_id{0};
static std::atomic<uint64_t> g_cancelled_generation_id{0};
static uint64_t g_buffer_generation_id{0};
static std::mutex g_token_chars_mutex;
static std::string cached_token_chars;

// Robust Streaming UTF-8 to Java UTF-16 String decoder.
// Decodes all complete valid UTF-8 sequences from buffer into UTF-16.
// Rejects overlong encodings, out-of-range codepoints (> 0x10FFFF), and surrogate halves (0xD800..0xDFFF).
// Emits U+FFFD (0xFFFD) for malformed bytes without dropping subsequent valid text.
// If an incomplete valid sequence remains at the buffer boundary, it is retained for the next token chunk.
static jstring decode_utf8_buffer_to_java_string(JNIEnv *env, std::string &buffer) {
    if (buffer.empty()) {
        jchar empty_char = 0;
        return env->NewString(&empty_char, 0);
    }

    std::vector<jchar> utf16;
    utf16.reserve(buffer.size());

    const unsigned char *p = reinterpret_cast<const unsigned char *>(buffer.data());
    const size_t total = buffer.size();
    size_t i = 0;
    size_t last_consumed = 0;

    while (i < total) {
        unsigned char b0 = p[i];

        if (b0 < 0x80) {
            // 1-byte ASCII (0x00..0x7F)
            utf16.push_back(static_cast<jchar>(b0));
            i += 1;
            last_consumed = i;
        } else if (b0 >= 0xC2 && b0 <= 0xDF) {
            // 2-byte sequence (0xC2..0xDF) -> 0x80..0x7FF (0xC0..0xC1 are overlong and rejected)
            if (i + 1 >= total) {
                // Incomplete sequence at buffer boundary: stop and retain for next token
                break;
            }
            unsigned char b1 = p[i + 1];
            if ((b1 & 0xC0) == 0x80) {
                uint32_t cp = ((b0 & 0x1F) << 6) | (b1 & 0x3F);
                utf16.push_back(static_cast<jchar>(cp));
                i += 2;
                last_consumed = i;
            } else {
                // Invalid continuation byte: replace b0 with U+FFFD and resume scan from b1
                utf16.push_back(0xFFFD);
                i += 1;
                last_consumed = i;
            }
        } else if (b0 >= 0xE0 && b0 <= 0xEF) {
            // 3-byte sequence (0xE0..0xEF) -> 0x800..0xFFFF
            if (i + 1 >= total) {
                break;
            }
            unsigned char b1 = p[i + 1];

            // Overlong check: if b0 == 0xE0, b1 must be >= 0xA0
            // Surrogate check: if b0 == 0xED, b1 must be <= 0x9F (reject 0xD800..0xDFFF)
            bool valid_b1 = ((b1 & 0xC0) == 0x80);
            if (b0 == 0xE0 && b1 < 0xA0) valid_b1 = false;
            if (b0 == 0xED && b1 > 0x9F) valid_b1 = false;

            if (!valid_b1) {
                utf16.push_back(0xFFFD);
                i += 1;
                last_consumed = i;
                continue;
            }

            if (i + 2 >= total) {
                break;
            }
            unsigned char b2 = p[i + 2];
            if ((b2 & 0xC0) == 0x80) {
                uint32_t cp = ((b0 & 0x0F) << 12) | ((b1 & 0x3F) << 6) | (b2 & 0x3F);
                utf16.push_back(static_cast<jchar>(cp));
                i += 3;
                last_consumed = i;
            } else {
                utf16.push_back(0xFFFD);
                i += 1;
                last_consumed = i;
            }
        } else if (b0 >= 0xF0 && b0 <= 0xF4) {
            // 4-byte sequence (0xF0..0xF4) -> 0x10000..0x10FFFF
            if (i + 1 >= total) {
                break;
            }
            unsigned char b1 = p[i + 1];

            // Overlong check: if b0 == 0xF0, b1 must be >= 0x90
            // Out of range check: if b0 == 0xF4, b1 must be <= 0x8F
            bool valid_b1 = ((b1 & 0xC0) == 0x80);
            if (b0 == 0xF0 && b1 < 0x90) valid_b1 = false;
            if (b0 == 0xF4 && b1 > 0x8F) valid_b1 = false;

            if (!valid_b1) {
                utf16.push_back(0xFFFD);
                i += 1;
                last_consumed = i;
                continue;
            }

            if (i + 2 >= total) {
                break;
            }
            unsigned char b2 = p[i + 2];
            if ((b2 & 0xC0) != 0x80) {
                utf16.push_back(0xFFFD);
                i += 1;
                last_consumed = i;
                continue;
            }

            if (i + 3 >= total) {
                break;
            }
            unsigned char b3 = p[i + 3];
            if ((b3 & 0xC0) == 0x80) {
                uint32_t cp = ((b0 & 0x07) << 18) | ((b1 & 0x3F) << 12) | ((b2 & 0x3F) << 6) | (b3 & 0x3F);
                cp -= 0x10000;
                jchar high = static_cast<jchar>(0xD800 + (cp >> 10));
                jchar low = static_cast<jchar>(0xDC00 + (cp & 0x3FF));
                utf16.push_back(high);
                utf16.push_back(low);
                i += 4;
                last_consumed = i;
            } else {
                utf16.push_back(0xFFFD);
                i += 1;
                last_consumed = i;
            }
        } else {
            // Invalid lead byte (0x80..0xBF, 0xC0..0xC1, 0xF5..0xFF)
            utf16.push_back(0xFFFD);
            i += 1;
            last_consumed = i;
        }
    }

    if (last_consumed >= total) {
        buffer.clear();
    } else {
        buffer.erase(0, last_consumed);
    }

    if (utf16.empty()) {
        jchar empty_char = 0;
        return env->NewString(&empty_char, 0);
    }

    return env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
}

static void log_callback(ggml_log_level level, const char * fmt, void * data) {
    if (level == GGML_LOG_LEVEL_ERROR)     __android_log_print(ANDROID_LOG_ERROR, TAG, fmt, data);
    else if (level == GGML_LOG_LEVEL_INFO) __android_log_print(ANDROID_LOG_INFO, TAG, fmt, data);
    else if (level == GGML_LOG_LEVEL_WARN) __android_log_print(ANDROID_LOG_WARN, TAG, fmt, data);
    else __android_log_print(ANDROID_LOG_DEFAULT, TAG, fmt, data);
}

extern "C"
JNIEXPORT jlong JNICALL
Java_android_llama_cpp_LLamaAndroid_load_1model(JNIEnv *env, jobject, jstring filename) {
    if (!filename) {
        jni_throw(env, "java/lang/IllegalArgumentException", "Model filename cannot be null");
        return 0;
    }

    llama_model_params model_params = llama_model_default_params();

    auto path_to_model = env->GetStringUTFChars(filename, 0);
    if (!path_to_model) {
        LOGe("load_model(): GetStringUTFChars failed");
        return 0;
    }
    LOGi("Loading model from %s", path_to_model);

    auto model = llama_model_load_from_file(path_to_model, model_params);
    env->ReleaseStringUTFChars(filename, path_to_model);

    if (!model) {
        LOGe("load_model() failed");
        jni_throw(env, "java/lang/IllegalStateException", "load_model() failed");
        return 0;
    }

    // Reset cooperative generation state cleanly for the new native model lifecycle
    g_active_generation_id.store(0);
    g_cancelled_generation_id.store(0);
    {
        std::lock_guard<std::mutex> lock(g_token_chars_mutex);
        cached_token_chars.clear();
        g_buffer_generation_id = 0;
    }

    return reinterpret_cast<jlong>(model);
}

extern "C"
JNIEXPORT void JNICALL
Java_android_llama_cpp_LLamaAndroid_free_1model(JNIEnv *, jobject, jlong model) {
    if (!model) return;
    llama_model_free(reinterpret_cast<llama_model *>(model));
}

extern "C"
JNIEXPORT jlong JNICALL
Java_android_llama_cpp_LLamaAndroid_new_1context(JNIEnv *env, jobject, jlong jmodel) {
    auto model = reinterpret_cast<llama_model *>(jmodel);

    if (!model) {
        LOGe("new_context(): model cannot be null");
        jni_throw(env, "java/lang/IllegalArgumentException", "Model cannot be null");
        return 0;
    }

    int n_threads = std::max(1, std::min(4, (int) sysconf(_SC_NPROCESSORS_ONLN) - 2));
    LOGi("Using %d threads", n_threads);

    llama_context_params ctx_params = llama_context_default_params();

    ctx_params.n_ctx           = 2048;
    ctx_params.n_threads       = n_threads;
    ctx_params.n_threads_batch = n_threads;

    llama_context * context = llama_init_from_model(model, ctx_params);

    if (!context) {
        LOGe("llama_init_from_model() returned null)");
        jni_throw(env, "java/lang/IllegalStateException",
                  "llama_init_from_model() returned null)");
        return 0;
    }

    return reinterpret_cast<jlong>(context);
}

extern "C"
JNIEXPORT void JNICALL
Java_android_llama_cpp_LLamaAndroid_free_1context(JNIEnv *, jobject, jlong context) {
    if (!context) return;
    llama_free(reinterpret_cast<llama_context *>(context));
}

extern "C"
JNIEXPORT void JNICALL
Java_android_llama_cpp_LLamaAndroid_backend_1free(JNIEnv *, jobject) {
    llama_backend_free();
}

extern "C"
JNIEXPORT void JNICALL
Java_android_llama_cpp_LLamaAndroid_log_1to_1android(JNIEnv *, jobject) {
    llama_log_set(log_callback, NULL);
}

extern "C"
JNIEXPORT jstring JNICALL
Java_android_llama_cpp_LLamaAndroid_bench_1model(
        JNIEnv *env,
        jobject,
        jlong context_pointer,
        jlong model_pointer,
        jlong batch_pointer,
        jint pp,
        jint tg,
        jint pl,
        jint nr
        ) {
    if (!context_pointer || !model_pointer || !batch_pointer) {
        jni_throw(env, "java/lang/IllegalArgumentException", "bench_model(): invalid null pointers");
        return env->NewStringUTF("");
    }
    if (nr <= 0 || pp <= 0 || tg < 0 || pl <= 0) {
        jni_throw(env, "java/lang/IllegalArgumentException", "bench_model(): parameters must be positive");
        return env->NewStringUTF("");
    }

    auto pp_avg = 0.0;
    auto tg_avg = 0.0;
    auto pp_std = 0.0;
    auto tg_std = 0.0;

    const auto context = reinterpret_cast<llama_context *>(context_pointer);
    const auto model = reinterpret_cast<llama_model *>(model_pointer);
    const auto batch = reinterpret_cast<llama_batch *>(batch_pointer);

    const int n_ctx = llama_n_ctx(context);
    const int safe_pp = std::min(pp, std::min(2048, std::max(1, n_ctx)));

    LOGi("n_ctx = %d", n_ctx);

    int i, j;
    int nri;
    for (nri = 0; nri < nr; nri++) {
        LOGi("Benchmark prompt processing (pp)");

        common_batch_clear(*batch);

        const int n_tokens = safe_pp;
        for (i = 0; i < n_tokens; i++) {
            common_batch_add(*batch, 0, i, { 0 }, false);
        }

        batch->logits[batch->n_tokens - 1] = true;
        llama_kv_self_clear(context);

        const auto t_pp_start = ggml_time_us();
        if (llama_decode(context, *batch) != 0) {
            LOGi("llama_decode() failed during prompt processing");
        }
        const auto t_pp_end = ggml_time_us();

        // bench text generation

        LOGi("Benchmark text generation (tg)");

        llama_kv_self_clear(context);
        const auto t_tg_start = ggml_time_us();
        for (i = 0; i < tg; i++) {

            common_batch_clear(*batch);
            for (j = 0; j < pl; j++) {
                common_batch_add(*batch, 0, i, { j }, true);
            }

            LOGi("llama_decode() text generation: %d", i);
            if (llama_decode(context, *batch) != 0) {
                LOGi("llama_decode() failed during text generation");
            }
        }

        const auto t_tg_end = ggml_time_us();

        llama_kv_self_clear(context);

        const auto t_pp = std::max(1e-9, double(t_pp_end - t_pp_start) / 1000000.0);
        const auto t_tg = std::max(1e-9, double(t_tg_end - t_tg_start) / 1000000.0);

        const auto speed_pp = double(safe_pp) / t_pp;
        const auto speed_tg = (tg > 0) ? (double(pl * tg) / t_tg) : 0.0;

        pp_avg += speed_pp;
        tg_avg += speed_tg;

        pp_std += speed_pp * speed_pp;
        tg_std += speed_tg * speed_tg;

        LOGi("pp %f t/s, tg %f t/s", speed_pp, speed_tg);
    }

    pp_avg /= double(nr);
    tg_avg /= double(nr);

    if (nr > 1) {
        pp_std = sqrt(pp_std / double(nr - 1) - pp_avg * pp_avg * double(nr) / double(nr - 1));
        tg_std = sqrt(tg_std / double(nr - 1) - tg_avg * tg_avg * double(nr) / double(nr - 1));
    } else {
        pp_std = 0;
        tg_std = 0;
    }

    char model_desc[128];
    llama_model_desc(model, model_desc, sizeof(model_desc));

    const auto model_size     = double(llama_model_size(model)) / 1024.0 / 1024.0 / 1024.0;
    const auto model_n_params = double(llama_model_n_params(model)) / 1e9;

    const auto backend    = "(Android)"; // TODO: What should this be?

    std::stringstream result;
    result << std::setprecision(2);
    result << "| model | size | params | backend | test | t/s |\n";
    result << "| --- | --- | --- | --- | --- | --- |\n";
    result << "| " << model_desc << " | " << model_size << "GiB | " << model_n_params << "B | " << backend << " | pp " << pp << " | " << pp_avg << " ± " << pp_std << " |\n";
    result << "| " << model_desc << " | " << model_size << "GiB | " << model_n_params << "B | " << backend << " | tg " << tg << " | " << tg_avg << " ± " << tg_std << " |\n";

    return env->NewStringUTF(result.str().c_str());
}

extern "C"
JNIEXPORT jlong JNICALL
Java_android_llama_cpp_LLamaAndroid_new_1batch(JNIEnv *, jobject, jint n_tokens, jint embd, jint n_seq_max) {
    auto batch = new llama_batch(llama_batch_init(n_tokens, embd, n_seq_max));
    return reinterpret_cast<jlong>(batch);
}

extern "C"
JNIEXPORT void JNICALL
Java_android_llama_cpp_LLamaAndroid_free_1batch(JNIEnv *, jobject, jlong batch_pointer) {
    if (!batch_pointer) return;
    auto batch = reinterpret_cast<llama_batch *>(batch_pointer);
    llama_batch_free(*batch);
    delete batch;
}

extern "C"
JNIEXPORT jlong JNICALL
Java_android_llama_cpp_LLamaAndroid_new_1sampler(JNIEnv *, jobject) {
    auto sparams = llama_sampler_chain_default_params();
    sparams.no_perf = true;
    llama_sampler * smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_penalties(64, 1.15f, 0.0f, 0.0f));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.85f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    return reinterpret_cast<jlong>(smpl);
}

extern "C"
JNIEXPORT void JNICALL
Java_android_llama_cpp_LLamaAndroid_free_1sampler(JNIEnv *, jobject, jlong sampler_pointer) {
    if (!sampler_pointer) return;
    llama_sampler_free(reinterpret_cast<llama_sampler *>(sampler_pointer));
}

extern "C"
JNIEXPORT void JNICALL
Java_android_llama_cpp_LLamaAndroid_cancel_1generation(JNIEnv *, jobject, jlong gen_id) {
    uint64_t target_gen_id = (gen_id <= 0) ? g_active_generation_id.load() : static_cast<uint64_t>(gen_id);

    // Monotonic non-regressing cancellation marker
    uint64_t current = g_cancelled_generation_id.load();
    while (target_gen_id > current) {
        if (g_cancelled_generation_id.compare_exchange_weak(current, target_gen_id)) {
            break;
        }
    }

    // Only clear token buffer if the cancellation matches the active generation
    std::lock_guard<std::mutex> lock(g_token_chars_mutex);
    if (target_gen_id == g_active_generation_id.load() && g_buffer_generation_id == target_gen_id) {
        cached_token_chars.clear();
    }
    LOGi("cancel_generation() called from JNI for gen_id %llu (active: %llu)",
         (unsigned long long)target_gen_id, (unsigned long long)g_active_generation_id.load());
}

extern "C"
JNIEXPORT void JNICALL
Java_android_llama_cpp_LLamaAndroid_backend_1init(JNIEnv *, jobject) {
    llama_backend_init();
}

extern "C"
JNIEXPORT jstring JNICALL
Java_android_llama_cpp_LLamaAndroid_system_1info(JNIEnv *env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}

extern "C"
JNIEXPORT jint JNICALL
Java_android_llama_cpp_LLamaAndroid_completion_1init(
        JNIEnv *env,
        jobject,
        jlong context_pointer,
        jlong batch_pointer,
        jlong sampler_pointer,
        jstring jtext,
        jboolean format_chat,
        jint n_len,
        jlong gen_id
    ) {

    {
        std::lock_guard<std::mutex> lock(g_token_chars_mutex);
        cached_token_chars.clear();
        g_buffer_generation_id = static_cast<uint64_t>(gen_id);
    }

    g_active_generation_id.store(static_cast<uint64_t>(gen_id));

    if (g_cancelled_generation_id.load() >= static_cast<uint64_t>(gen_id)) {
        LOGi("completion_init: generation %llu was cancelled prior to initialization", (unsigned long long)gen_id);
        jni_throw(env, "java/util/concurrent/CancellationException",
                  "Generación cancelada antes de inicializar");
        return -1;
    }

    if (!context_pointer || !batch_pointer || !jtext) {
        LOGe("completion_init: invalid null pointers passed");
        jni_throw(env, "java/lang/IllegalArgumentException", "Punteros nulos pasados a completion_init");
        return -1;
    }

    // Reset sampler and KV cache across generations to avoid state contamination
    if (sampler_pointer) {
        llama_sampler_reset(reinterpret_cast<llama_sampler *>(sampler_pointer));
    }

    const auto text = env->GetStringUTFChars(jtext, 0);
    if (!text) {
        LOGe("completion_init: GetStringUTFChars failed");
        return -1;
    }
    const auto context = reinterpret_cast<llama_context *>(context_pointer);
    const auto batch = reinterpret_cast<llama_batch *>(batch_pointer);
    llama_kv_self_clear(context);

    bool has_chatml = (format_chat == JNI_TRUE) || (strstr(text, "<|im_start|>") != nullptr);
    bool parse_special = has_chatml;
    bool add_special = !has_chatml; // Avoid double BOS token corruption when ChatML is present
    const auto tokens_list = common_tokenize(context, text, add_special, parse_special);

    auto n_ctx = llama_n_ctx(context);
    auto n_kv_req = tokens_list.size() + n_len;

    LOGi("n_len = %d, n_ctx = %u, n_kv_req = %zu, add_special = %d, parse_special = %d",
         n_len, n_ctx, n_kv_req, add_special ? 1 : 0, parse_special ? 1 : 0);

    if (tokens_list.empty()) {
        LOGe("completion_init: prompt produced 0 tokens");
        env->ReleaseStringUTFChars(jtext, text);
        jni_throw(env, "java/lang/IllegalArgumentException",
                  "El prompt no produjo tokens válidos");
        return -1;
    }

    if (tokens_list.size() >= n_ctx || n_kv_req > n_ctx) {
        LOGe("completion_init: prompt tokens (%zu) + n_len (%d) > n_ctx (%u), context overflow",
             tokens_list.size(), n_len, n_ctx);
        env->ReleaseStringUTFChars(jtext, text);
        jni_throw(env, "java/lang/IllegalArgumentException",
                  "El prompt excede el tamaño máximo de contexto del modelo");
        return -1;
    }

    if (g_cancelled_generation_id.load() >= static_cast<uint64_t>(gen_id)) {
        LOGi("completion_init: aborted due to cancellation");
        env->ReleaseStringUTFChars(jtext, text);
        jni_throw(env, "java/util/concurrent/CancellationException",
                  "Inferencia cancelada durante la preparación");
        return -1;
    }

    for (auto id : tokens_list) {
        LOGi("token: `%s`-> %d ", common_token_to_piece(context, id).c_str(), id);
    }

    common_batch_clear(*batch);

    // evaluate the initial prompt
    for (auto i = 0; i < tokens_list.size(); i++) {
        common_batch_add(*batch, tokens_list[i], i, { 0 }, false);
    }

    // llama_decode will output logits only for the last token of the prompt
    batch->logits[batch->n_tokens - 1] = true;

    if (llama_decode(context, *batch) != 0) {
        LOGe("llama_decode() failed on prompt evaluation");
        llama_kv_self_clear(context);
        env->ReleaseStringUTFChars(jtext, text);
        jni_throw(env, "java/lang/IllegalStateException",
                  "llama_decode() falló al evaluar el prompt inicial");
        return -2;
    }

    env->ReleaseStringUTFChars(jtext, text);

    return batch->n_tokens;
}

extern "C"
JNIEXPORT jstring JNICALL
Java_android_llama_cpp_LLamaAndroid_completion_1loop(
        JNIEnv * env,
        jobject,
        jlong context_pointer,
        jlong batch_pointer,
        jlong sampler_pointer,
        jint n_len,
        jint n_cur,
        jlong gen_id
) {
    if (g_cancelled_generation_id.load() >= static_cast<uint64_t>(gen_id) ||
        static_cast<uint64_t>(gen_id) != g_active_generation_id.load()) {
        LOGi("completion_loop: generation %llu was cancelled cooperatively", (unsigned long long)gen_id);
        std::lock_guard<std::mutex> lock(g_token_chars_mutex);
        if (g_buffer_generation_id == static_cast<uint64_t>(gen_id)) {
            cached_token_chars.clear();
        }
        jni_throw(env, "java/util/concurrent/CancellationException",
                  "Inferencia cancelada por el usuario");
        return nullptr;
    }

    if (!context_pointer || !batch_pointer || !sampler_pointer) {
        LOGe("completion_loop: invalid null pointers passed");
        jni_throw(env, "java/lang/IllegalArgumentException",
                  "Punteros nativos nulos en completion_loop");
        return nullptr;
    }

    const auto context = reinterpret_cast<llama_context *>(context_pointer);
    const auto batch   = reinterpret_cast<llama_batch   *>(batch_pointer);
    const auto sampler = reinterpret_cast<llama_sampler *>(sampler_pointer);
    const auto model = llama_get_model(context);
    const auto vocab = llama_model_get_vocab(model);

    // sample the most likely token
    const auto new_token_id = llama_sampler_sample(sampler, context, -1);

    if (g_cancelled_generation_id.load() >= static_cast<uint64_t>(gen_id) ||
        static_cast<uint64_t>(gen_id) != g_active_generation_id.load()) {
        LOGi("completion_loop: cancelled after sampling");
        std::lock_guard<std::mutex> lock(g_token_chars_mutex);
        if (g_buffer_generation_id == static_cast<uint64_t>(gen_id)) {
            cached_token_chars.clear();
        }
        jni_throw(env, "java/util/concurrent/CancellationException",
                  "Inferencia cancelada por el usuario");
        return nullptr;
    }

    if (llama_vocab_is_eog(vocab, new_token_id) || n_cur >= n_len) {
        // COMPLETED cleanly. Clear buffer and return nullptr without exception.
        std::lock_guard<std::mutex> lock(g_token_chars_mutex);
        if (g_buffer_generation_id == static_cast<uint64_t>(gen_id)) {
            cached_token_chars.clear();
        }
        return nullptr;
    }

    auto new_token_chars = common_token_to_piece(context, new_token_id);
    jstring new_token = nullptr;

    {
        std::lock_guard<std::mutex> lock(g_token_chars_mutex);
        if (g_buffer_generation_id != static_cast<uint64_t>(gen_id)) {
            cached_token_chars.clear();
            g_buffer_generation_id = static_cast<uint64_t>(gen_id);
        }
        cached_token_chars += new_token_chars;
        new_token = decode_utf8_buffer_to_java_string(env, cached_token_chars);
    }

    common_batch_clear(*batch);
    common_batch_add(*batch, new_token_id, n_cur, { 0 }, true);

    if (llama_decode(context, *batch) != 0) {
        LOGe("llama_decode() failed during generation loop");
        llama_kv_self_clear(context);
        std::lock_guard<std::mutex> lock(g_token_chars_mutex);
        if (g_buffer_generation_id == static_cast<uint64_t>(gen_id)) {
            cached_token_chars.clear();
        }
        jni_throw(env, "java/lang/IllegalStateException",
                  "llama_decode() falló durante la generación");
        return nullptr;
    }

    return new_token;
}

extern "C"
JNIEXPORT void JNICALL
Java_android_llama_cpp_LLamaAndroid_kv_1cache_1clear(JNIEnv *, jobject, jlong context) {
    if (context) {
        llama_kv_self_clear(reinterpret_cast<llama_context *>(context));
    }
    std::lock_guard<std::mutex> lock(g_token_chars_mutex);
    cached_token_chars.clear();
}
