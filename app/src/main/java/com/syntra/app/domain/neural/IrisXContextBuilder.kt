package com.syntra.app.domain.neural

import com.syntra.app.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object IrisXContextBuilder {

    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    /**
     * Builds a bounded, privacy-preserving prompt for local neural inference.
     * Incorporates verified sports facts without raw database dumps or unbounded SQL queries.
     */
    suspend fun buildPrompt(
        userMessage: String,
        db: AppDatabase,
        systemPersona: String = "Sos IrisX, la entrenadora deportiva y copiloto inteligente de Syntra. Tu tono es directo, analítico, enfocado en sobrecarga progresiva y fundamentos biomecánicos. Respondés de forma concisa y fundamentada en datos reales.",
        conversationHistory: List<Pair<String, String>> = emptyList()
    ): String = withContext(Dispatchers.IO) {
        val facts = StringBuilder()

        // 1. Profile facts
        val profile = db.profileDao().get()
        if (profile != null) {
            facts.append("- Atleta: ${profile.preferredName}, Altura: ${profile.heightCm} cm, Objetivo: ${profile.goal}, Nivel: ${profile.activityLevel}.\n")
        }

        // 2. Latest Weigh-in
        val weighIns = db.weighInDao().getAll()
        val latestWeighIn = weighIns.firstOrNull()
        if (latestWeighIn != null) {
            val dateStr = dateFormat.format(Date(latestWeighIn.measuredAtMillis))
            facts.append("- Último pesaje: ${latestWeighIn.weightGrams / 1000.0} kg ($dateStr).\n")
        }

        // 3. Relevant Exercise & Recent Sessions
        val lowerQuery = userMessage.lowercase(Locale.ROOT)
        val allExercises = db.exerciseDao().getAll()
        val matchingExercise = allExercises.firstOrNull { ex ->
            lowerQuery.contains(ex.displayName.lowercase(Locale.ROOT)) ||
                    lowerQuery.contains(ex.normalizedName)
        }

        if (matchingExercise != null) {
            val allSets = db.workoutSetDao().getAll()
            val sets = allSets.filter { it.exerciseId == matchingExercise.id }
            if (sets.isNotEmpty()) {
                val maxSet = sets.maxByOrNull { it.loadGrams }
                val maxLoadGrams = maxSet?.loadGrams ?: 0
                val totalVolumeSets = sets.size
                val loadFormatted = com.syntra.app.domain.UnitConverter.formatDisplay(maxLoadGrams, maxSet?.loadUnit ?: "KG")
                facts.append("- Ejercicio consultado '${matchingExercise.displayName}' (${matchingExercise.muscleGroup}): ")
                facts.append("Carga máxima registrada: $loadFormatted, Total series históricas: $totalVolumeSets.\n")
            }
        }

        // 4. Last 2 completed workout sessions
        val recentSessions = db.workoutSessionDao().getAll().take(2)
        if (recentSessions.isNotEmpty()) {
            facts.append("- Sesiones recientes:\n")
            for (s in recentSessions) {
                val dateStr = dateFormat.format(Date(s.startedAtMillis))
                facts.append("  • $dateStr: \"${s.title}\" (Categoría: ${s.category.ifEmpty { "General" }})\n")
            }
        }

        // 5. Relevant Memories / Preferences
        val memories = db.irisxMemoryDao().getAll()
        val relevantMemories = memories.filter { m ->
            m.memoryType == "PREFERENCE" ||
                    lowerQuery.contains(m.sourceKey.lowercase(Locale.ROOT))
        }.take(3)

        if (relevantMemories.isNotEmpty()) {
            facts.append("- Preferencias y notas aprendidas:\n")
            for (m in relevantMemories) {
                facts.append("  • ${m.sourceKey}: ${m.targetValue}\n")
            }
        }

        // 6. Format using Qwen3 Chat Template
        val sb = StringBuilder()
        sb.append("<|im_start|>system\n")
        sb.append(systemPersona).append("\n\n")
        if (facts.isNotBlank()) {
            sb.append("DATOS DEPORTIVOS REALES Y VERIFICADOS DEL USUARIO (Usalos como base fáctica, no inventes datos):\n")
            sb.append(facts.toString()).append("\n")
        }
        sb.append("Reglas de respuesta:\n")
        sb.append("1. Respondé en español rioplatense natural y directo.\n")
        sb.append("2. Si te preguntan sobre pesos, marcas o entrenamientos, usá únicamente los datos verificados provistos.\n")
        sb.append("3. Si no hay datos suficientes sobre un ejercicio o fecha, aclaralo con honestidad técnica.\n")
        sb.append("<|im_end|>\n")

        // Include bounded recent history from this active conversation (max 6 turns)
        for ((role, text) in conversationHistory.takeLast(6)) {
            val clean = if (role == "user") extractUserMessage(text) else stripChatMlTokens(text)
            if (clean.isNotBlank()) {
                sb.append("<|im_start|>$role\n").append(clean).append("\n<|im_end|>\n")
            }
        }

        sb.append("<|im_start|>user\n")
        sb.append(userMessage.trim()).append("\n")
        sb.append("<|im_end|>\n")
        sb.append("<|im_start|>assistant\n<think>\n</think>\n")

        sb.toString()
    }

    /**
     * Extracts the clean human query from a prompt or raw stored message that might contain ChatML markers.
     * Prevents internal instructions or system prompts from polluting SQLite or user-facing chat.
     */
    fun extractUserMessage(rawText: String): String {
        if (!rawText.contains("<|im_start|>")) return rawText.trim()
        val userSection = rawText.substringAfter("<|im_start|>user", "")
            .substringBefore("<|im_end|>", "")
            .trim()
        if (userSection.isNotBlank()) return userSection

        return rawText.lines()
            .filterNot { line ->
                val trimmed = line.trim()
                trimmed.startsWith("<|") ||
                    trimmed.startsWith("Sos IrisX") ||
                    trimmed.startsWith("DATOS DEPORTIVOS") ||
                    trimmed.startsWith("Reglas de respuesta") ||
                    trimmed.startsWith("1. ") ||
                    trimmed.startsWith("2. ") ||
                    trimmed.startsWith("3. ") ||
                    trimmed.startsWith("- ")
            }
            .joinToString(" ")
            .trim()
            .ifEmpty { stripChatMlTokens(rawText) }
    }

    /**
     * Strips all ChatML tags (<|im_start|>, <|im_end|>, etc.) from generated text.
     * When [trim] is false (e.g. streaming individual token pieces), leading and trailing whitespace is preserved.
     */
    fun stripChatMlTokens(text: String, trim: Boolean = false): String {
        val cleaned = text.replace("<|im_start|>", "")
            .replace("<|im_end|>", "")
            .replace("<|endoftext|>", "")
            .replace("<think>", "")
            .replace("</think>", "")
            .replace(Regex("<\\|.*?\\|>"), "")
        return if (trim) cleaned.trim() else cleaned
    }
}
