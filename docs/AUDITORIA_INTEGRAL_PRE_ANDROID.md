# Syntra / IrisX — Auditoría Integral Pre-Android (v0.7.0-alpha18)

**Fecha:** 2026-11-11  
**Repositorio:** `C:\Dev\EntrenadorLocal`  
**Rama:** `feat/etapa-b1-lab-validation`  
**Commit base:** `c4727e2` (checkpoint `checkpoint-auditoria-integral-start`)  
**Versión de aplicación:** `0.7.0-alpha18` (`versionCode = 27`, variante aislada `0.7.0-alpha18-lab`)  
**Esquema de base de datos:** Room `v8` (sin cambios de esquema)  
**Pila tecnológica:** Kotlin, Jetpack Compose, Room/SQLite v8, C++/JNI, `llama.cpp`, modelo cuantizado `Qwen3-0.6B-Q4_K_M.gguf`.

---

## 1. Resumen Ejecutivo y Veredicto

- **Estado general:** **LISTO PARA PLANIFICAR VALIDACIÓN EN LABORATORIO ANDROID (`.lab`)**; **NO dar por cerrada la validación Android** ni reemplazar todavía la instalación personal productiva (`com.syntra.app`) sin ejecutar antes la batería instrumentada sobre `com.syntra.app.lab` y verificar respaldo SAF previo.
- **Defectos resueltos y verificados:** Se confirmaron y corrigieron **19 defectos reales** (`4 P0`, `12 P1`, `2 P1/P2`, `1 P2` — no quedan defectos P0/P1 confirmados sin resolver en código fuente): IrisX Core/Tools (`IRX-01..03`), puente nativo/neuronal (`JNI-01..04`), persistencia/respaldos/métricas (`DAT-01..08`) y UI/ciclo de vida/Lint (`UI-01..04`).
- **Falsos positivos descartados:** Se refutaron mediante inspección directa de fuente y configuración de compilación 3 hallazgos preliminares de subagentes (entre ellos el supuesto `SIGILL` por `+dotprod` en Snapdragon 685 y la supuesta omisión de `llama_sampler_accept`).
- **Cobertura alcanzada:** **273 pruebas JVM aprobadas (37 suites, 0 fallos, 0 errores, 0 omitidas)**, **Android Lint Debug aprobado (`0 errors, 38 warnings, 1 hint`)** y ensamblado completo ARM64 NDK/C++/Kotlin para `lab` (`app-lab.apk` + `app-lab-androidTest.apk`) sin errores tras la última edición.

---

## 2. Alcance, Línea Base y Metodología

La auditoría cubrió el código productivo completo antes de cualquier nueva instalación en el dispositivo físico Redmi Note 13 4G (Android 15):

1. **IrisX Core, herramientas locales y consola conversacional (`domain/irisx`, `ui/irisx`).**
2. **Motor neuronal local, C++/JNI, Resource Guardian y gestión GGUF (`cpp/llama-android.cpp`, `android/llama/cpp/LLamaAndroid.kt`, `domain/neural`).**
3. **Persistencia Room v8, respaldos SAF, parser de entrenamientos y métricas (`data/`, `data/backup/`, `domain/`).**
4. **UI Jetpack Compose, navegación, rutinas, historial, semana y temporizador de descanso (`MainActivity.kt`, `ui/`).**

Todo hallazgo reportado como confirmado fue contrastado línea por línea contra el código fuente real antes de editar. Los hallazgos se clasificaron estrictamente en:
- **Confirmados y corregidos (Fase B):** defectos reproducibles en código productivo con corrección acotada y prueba de regresión cuando son verificables en JVM.
- **Refutados:** hipótesis iniciales descartadas con evidencia en `external/llama.cpp`, `build.ninja` o contratos existentes de prueba.
- **Diferidos / Arquitectónicos:** mejoras estructurales fuera de alcance (p. ej., cambios de esquema Room `v8 -> v9`).
- **Exclusivos de dispositivo (`DEVICE-ONLY`):** escenarios que dependen de hardware, Android Framework/Room real o permisos en Android 15.

---

## 3. Hallazgos Confirmados y Corregidos (Fase B)

### 3.1 Eje IrisX Conversacional y Herramientas (`IRX`)

| ID | Severidad | Archivo y símbolo | Causa raíz verificada | Corrección aplicada | Validación |
|---|---|---|---|---|---|
| `IRX-01` | **P1** | `ExerciseNameResolver.kt` | Alias del catálogo por defecto no resolvían de forma exacta al ejercicio canonizado si diferían de la forma guardada. | Se incorporó resolución exacta contra `ExerciseCatalog.DEFAULT_CATALOG` antes del fallback. | `IrisXAuditProbeTest` |
| `IRX-02` | **P1** | `IrisXCore.kt` | Preguntas temporales (p. ej. `"¿Cuántas series hice la semana pasada?"`) caían en `QueryLastExerciseLoad` tomando palabras de período como ejercicio. | Exclusión explícita de consultas de período respecto a `QueryLastExerciseLoad`. | `IrisXAuditProbeTest` |
| `IRX-03` | **P1** | `IrisXCore.kt`, `IrisXConsoleDialog.kt` | `CLEAR_CONVERSATIONS` borraba todas las conversaciones dejando a la UI con un `conversationId` inexistente al persistir turnos posteriores. | `ClearConversationHistory` crea y devuelve una conversación activa nueva; `IrisXConsoleDialog` actualiza `messages`, `activeConversation` y `loadedConversationId` bajo `conversationMutex`. | `IrisXAuditProbeTest` |

### 3.2 Eje Neuronal, JNI y Gestión de Modelos (`JNI`)

| ID | Severidad | Archivo y símbolo | Causa raíz verificada | Corrección aplicada | Validación |
|---|---|---|---|---|---|
| `JNI-01` | **P0** | `IrisXGuardian.kt` (`evaluateMemory`, `evaluateMemoryValues`, `evaluateBattery`) | Cuando `avail >= 600 MB` pero `avail < modelSizeBytes`, el guardián evaluaba `WARNING` (`canProceed = true`) habilitando cargas susceptibles de `LMK/OOM`. Además, los bloques `catch` devolvían `SAFE`. | Se agregó rechazo `CRITICAL` (`canProceed = false`) cuando `modelSizeBytes > 0L && avail < modelSizeBytes`; `catch` y `actManager == null` devuelven `WARNING` con razón explícita. | `NeuralAuditRegressionTest` |
| `JNI-02` | **P1** | `LlamaCppInferenceEngine.kt` (`isModelLoaded`, `generate`) | `isModelLoaded()` exigía `state == READY` devolviendo `false` durante `GENERATING`; además `catch (CancellationException)` forzaba `state = READY` incluso si `unloadModel()` había liberado el modelo nativo concurrentemente. | Propiedades `state`, `currentModelFile` y `metrics` marcadas `@Volatile`; `isModelLoaded()` acepta `READY \|\| GENERATING`; al completar o cancelar se verifica `native.isModelLoaded()` antes de publicar `READY`/`UNINITIALIZED`/`ERROR`. | `NeuralAuditRegressionTest` |
| `JNI-03` | **P1** | `llama-android.cpp`, `LLamaAndroid.kt` | (1) `env->ThrowNew(env->FindClass(...))` sin verificar nulidad ni liberar referencia local; (2) `GetStringUTFChars` sin comprobación de null en `load_model` y `completion_init`; (3) `bench_model` sin guards para punteros nulos, `pp > 2048`, `nr <= 0` ni división por cero; (4) `completion_init` no limpiaba el KV cache antes del prompt; (5) firma JNI Kotlin `backend_init(numa: Boolean)` desalineada respecto a C++ `backend_init(JNIEnv*, jobject)`; (6) `g_active_generation_id`/`g_cancelled_generation_id` persistían al cargar un nuevo modelo. | Se añadió `jni_throw` con verificación y `DeleteLocalRef`; checks en `GetStringUTFChars` y `bench_model`; `llama_kv_self_clear(context)` al inicio de `completion_init`; reseteo limpio de estado de generación en `load_model`; alineación de `backend_init()`. | Compilación CMake `arm64-v8a` (`assembleLab`) |
| `JNI-04` | **P1** | `ModelManager.kt` (`importModelFromUri`), `ManageNeuralModelDialog.kt`, `IrisXConsoleDialog.kt` | Si la copia desde `Uri` fallaba con `IOException`/cancelación, el archivo `.tmp_*` quedaba huérfano en disco; el listado inicial de modelos ejecutaba I/O de archivos en composición sobre el hilo principal. | Limpieza garantizada de `tempFile` en `finally` dentro de `importModelFromUri`; carga inicial de modelos en `LaunchedEffect` / `withContext(Dispatchers.IO)`. | Inspección + compilación `lab` |

### 3.3 Eje Datos, Persistencia Room, Backups y Métricas (`DAT`)

| ID | Severidad | Archivo y símbolo | Causa raíz verificada | Corrección aplicada | Validación |
|---|---|---|---|---|---|
| `DAT-01` | **P0** | `MainActivity.kt` (`TrainerApp`), `AppDatabase.kt` | Las 9 categorías iniciales (`Pecho`, `Espalda`, `Piernas`, `Hombros`, `Brazos`, `Full Body`, `Torso`, `Push`, `Pull`) sólo se insertaban en `MIGRATION_4_5`: una instalación limpia en v8 arrancaba con `workout_categories` vacía. | Se siembran las 9 categorías por defecto en `LaunchedEffect(db)` dentro de `db.withTransaction` cuando `db.categoryDao().getAll().isEmpty()`, sin tocar el esquema v8. | Compilación + verificación de esquema |
| `DAT-02` | **P0** | `BackupManager.kt` (`restorePayload`, `shouldReplaceOptionalSnapshotTable`) | `restorePayload` borraba incondicionalmente `routines`, `routine_exercises`, `irisx_conversations`, `irisx_messages` y `active_workout_drafts` aun al restaurar backups legados `formatVersion < 2` que no contenían dichas tablas. | Mediante `shouldReplaceOptionalSnapshotTable(formatVersion, hasIncomingData)` se preserva el contenido local en backups `formatVersion < 2` vacíos en esas tablas, manteniendo el reemplazo total en snapshots `formatVersion >= 2`. | `AuditPreAndroidRegressionTest` |
| `DAT-03` | **P0** | `ActiveWorkoutManager.kt`, `MainActivity.kt`, `RoutinesScreen.kt` | Ante conflicto de unicidad en `exerciseDao.insert()` (`IGNORE` devuelve `-1L`), el código construía un `ExerciseEntity(id = -1L)` e insertaba series huérfanas con `exerciseId = -1L`. | Si `newId <= 0L`, se resuelve el registro existente con `findByNormalizedName(norm)` antes de insertar series. | Compilación + flujo transaccional |
| `DAT-04` | **P1** | `BackupManager.kt` (`readAndValidateFromUri`, `exportToFile`), `MainActivity.kt` | `readAndValidateFromUri` ejecutaba lectura I/O síncrona en hilo principal mediante `reader.readText()` sin límite de tamaño (riesgo de ANR y `OutOfMemoryError`). | `readAndValidateFromUri` y `exportToFile` pasan a `withContext(Dispatchers.IO)`; se aplica tope de lectura `MAX_BACKUP_BYTES = 25 MB` en `readBoundedUtf8Text`; `importLauncher` invoca dentro de `scope.launch`. | `AuditPreAndroidRegressionTest` |
| `DAT-05` | **P1** | `WorkoutTextParser.kt` (`LoadMatch`, `LoadItem`, `firstLoad`) | La detección de lastre usaba `values.contains("+")` sobre toda la línea (etiquetando erróneamente `"Sentadilla 100kg 5 + 100kg 5"` como `[LASTRE]`), restringía lastre solo a `KG` excluyendo `LBS`, y `firstLoad` priorizaba `loadWithUnitRegex` sobre `bodyweightRegex` truncando líneas mixtas como `"Dominadas BW 8 + -15kg 6"`. | `isExplicitPlus` se rastrea por token en `LoadMatch`/`LoadItem`; soporta cualquier unidad explícita (`KG`, `LBS`, etc.); `firstLoad` selecciona el match más temprano en la línea (`minByOrNull { it.range.first }`). | `AuditPreAndroidRegressionTest` |
| `DAT-06` | **P1** | `SportsMetricsCalculator.kt` (`calculateSessionVolume`) | `LoadModality.MACHINE_BLOCKS` incrementaba `regularSets++`, inflando el conteo reportado en `"barra/máquina (N series)"`. | Contador dedicado `machineBlocksSets` separado de `regularSets` (manteniendo `totalSetsCount = sets.size`) e informado aparte en el desglose. | `AuditPreAndroidRegressionTest` |
| `DAT-07` | **P1** | `MainActivity.kt` (`learnAlias`, secuencia), `IrisXCore.kt` (`RoomIrisXRepository`) | Escrituras/borrados múltiples entre `irisx_memories`, `exercise_aliases`, `irisx_conversations` e `irisx_messages` se ejecutaban sin `db.withTransaction`. | Envoltura en `db.withTransaction` para `learnAlias`, `SequenceConfigDialog`, `deleteLearnedAlias`, `deleteConversation` y `clearAllConversations`. | Compilación + inspección |
| `DAT-08` | **P1** | `MainActivity.kt`, `RoutinesScreen.kt`, `EditWorkoutSessionDialog.kt` | Faltaban bloqueos anti doble-tap en guardado de analizador, rutinas, edición de historial y registro de peso; `ParserScreen` y `RoutinesScreen` omitían `sessionUuid` y `category` resuelta; `EditWorkoutSessionDialog` usaba `groupBy { it.exerciseId }` destruyendo el orden intercalado de superseries (`executionOrder`). | Flags `isSaving*`/`isApplyingRoutine` con `enabled = !isSaving`; asignación de `UUID.randomUUID().toString()` y `WorkoutCategoryClassifier.resolveSessionCategory`; preservación secuencial de `executionOrder` en `EditWorkoutSessionDialog`. | Compilación + inspección |

### 3.4 Eje UI Compose y Ciclo de Vida (`UI`)

| ID | Severidad | Archivo y símbolo | Causa raíz verificada | Corrección aplicada | Validación |
|---|---|---|---|---|---|
| `UI-01` | **P1** | `RestTimerReceiver.kt`, `RestTimerBar.kt` | Tras un reinicio del dispositivo, `SystemClock.elapsedRealtime()` vuelve a `0`, dejando una cuenta regresiva residual descomunal (`targetElapsed - nowElapsed >> totalSec`); además faltaba solicitud runtime de `POST_NOTIFICATIONS` en Android 13+ y comprobación previa al `notify()`. | `RestTimerManager.isElapsedTargetPlausible` descarta objetivos que superen `totalSec + 5s`; `RestTimerBar` solicita `POST_NOTIFICATIONS` en API 33+; `RestTimerReceiver` verifica `areNotificationsEnabled()` y protege `notify()` ante `SecurityException`. | `AuditPreAndroidRegressionTest` |
| `UI-02` | **P1/P2** | `MainActivity.kt` | `page`, `isEditingProfile`, `ProfileForm`, `weightInput`, `input` y `analyzed` en `ParserScreen` usaban `remember` en lugar de `rememberSaveable`, perdiendo estado ante recreación de actividad; además el listado compacto en `Historial` (`MainActivity.kt:2775`) calculaba `rawLoadValue = s.loadGrams / 1000.0` rompiendo valores en `LBS`. | Migración a `rememberSaveable` en navegación principal, `ProfileForm`, `WeightScreen` y `ParserScreen`; reemplazo por `UnitConverter.toRawValue(s.loadGrams, s.loadUnit)`. | Compilación + `WorkoutTextParser` / `UnitConverter` |
| `UI-03` | **P2** | `WeekScreen.kt` (`computeWeekRange`, `weekSessions`) | `Calendar.getInstance()` con mutación tardía de `firstDayOfWeek = Calendar.MONDAY` es propenso a desfase en domingos/cambio de semana según locale; además excluía sesiones importadas sin `finishedAtMillis` pese a tener series registradas. | `computeWeekRange` basado en `java.time.LocalDate` + `TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)`; inclusión de sesiones legadas que posean series (`(setsCountBySession[s.id] ?: 0) > 0`). | `AuditPreAndroidRegressionTest` |
| `UI-04` | **P1/P2** | `IrisXConsoleDialog.kt` (línea 527) | `SimpleDateFormat(..., Locale.getDefault())` dentro de composable disparaba error Lint `NonObservableLocale` ante cambios dinámicos de idioma/locale. | Se reemplazó por `LocalLocale.current.platformLocale`. | `:app:lintDebug` (`0 errors`) |

---

## 4. Hallazgos Refutados / Falsos Positivos Descartados

1. **Supuesta omisión de `llama_sampler_accept` que anularía penalizaciones (`repeat_penalty`):** **REFUTADO.** Verificado directamente en `external/llama.cpp/src/llama-sampling.cpp`: la función `llama_sampler_sample` invoca internamente `llama_sampler_accept(smpl, token)` antes de devolver el token muestreado.
2. **Supuesta compilación con `+dotprod` provocando `SIGILL` en Snapdragon 685:** **REFUTADO.** Verificado en `app/.cxx/Release/5lf6o2s3/arm64-v8a/build.ninja` que los `FLAGS` de compilación apuntan al baseline `aarch64-none-linux-android26` sin `+dotprod` ni `-march=armv8.2-a`.
3. **Validación mínima de 8 bytes en `ModelManager.validateGgufFile`:** **CONTRATO DE PRUEBA INTENCIONAL (no defectuoso en alcance actual).** Las suites `NeuralEngineTest`, `IrisXNeuralStageB1Test` y `LlamaCppEngineStateTest` generan cabeceras GGUF mínimas de 8 bytes (`magic` + `version`). Elevar el mínimo a 24 bytes rompería los tests unitarios aprobados; la carga real sigue validando el resto del archivo en `llama_model_load_from_file`.

---

## 5. Deuda Arquitectónica / Ítems Diferidos (Fuera de alcance)

1. **Ausencia de claves foráneas (`foreignKeys` / `ON DELETE CASCADE`) en `AppDatabase` v8:** Cualquier cambio en anotaciones `@Entity` o índices exige migración `v8 -> v9`, explícitamente prohibida sin autorización. Se mitigó a nivel de aplicación envolviendo borrados multi-tabla en `db.withTransaction`. Planificar en una etapa dedicada de migración `v8 -> v9`.
2. **Hardening opcional de CMake (`-DGGML_CPU_ARM_ARCH=armv8-a`):** No es necesario porque el toolchain ya compila baseline `armv8-a` sin `+dotprod`, pero puede agregarse como parámetro explícito en futuras revisiones de `CMakeLists.txt`.
3. **Listado de modelos tras click en `ManageNeuralModelDialog`:** La carga inicial en composición ya se movió a `Dispatchers.IO`; las actualizaciones puntuales posteriores al click (`onLoad`/`onUnload`/`onDelete`) siguen siendo operaciones rápidas sobre una carpeta local acotada.

---

## 6. Seguridad, Privacidad y Modelo Offline-First

Verificado en `app/src/main/AndroidManifest.xml`:
- **Sin permiso `INTERNET`:** ningún flujo de red saliente o telemetría está declarado ni habilitado.
- **`android:allowBackup="false"`:** evita extracción automática hacia nube del sistema.
- **Superficie IPC mínima:** `RestTimerReceiver` tiene `android:exported="false"`; las alarmas usan `PendingIntent` explícito; el entorno `lab` (`com.syntra.app.lab`) conserva aislamiento por UID respecto a `com.syntra.app`.
- **Importación SAF defensiva:** tanto respaldos JSON (límite de `25 MB` en memoria) como modelos GGUF (copia por streaming en archivo temporal `.tmp_*` + limpieza garantizada en `finally`) validan cabecera antes de promover el archivo final.

---

## 7. Pruebas y Evidencia de Compilación (Ejecutadas tras la última edición)

### 7.1 Suite Unitaria JVM (`:app:testDebugUnitTest`)

```powershell
.\gradlew.bat --offline --no-daemon :app:lintDebug :app:testDebugUnitTest
```

- **Resultado conjunto Lint + JVM:** `BUILD SUCCESSFUL in 3m 30s`
- **Totales agregados desde `app/build/test-results/testDebugUnitTest/TEST-*.xml`:**
  - **Suites:** `37`
  - **Tests:** `273`
  - **Failures:** `0`
  - **Errors:** `0`
  - **Skipped:** `0`
- **Nuevas suites de regresión agregadas en esta auditoría:**
  - `app/src/test/java/com/syntra/app/domain/irisx/IrisXAuditProbeTest.kt`
  - `app/src/test/java/com/syntra/app/AuditPreAndroidRegressionTest.kt`
  - `app/src/test/java/com/syntra/app/domain/neural/NeuralAuditRegressionTest.kt`

### 7.2 Android Lint (`:app:lintDebug`)

- **Resolución:** Se resolvió `com.android.tools.lint:lint-gradle:32.5.0` sin modificar versiones de Gradle (`9.6.0`), AGP (`9.5.0`) ni Kotlin (`2.4.10`).
- **Primer barrido:** Detectó 1 error bloqueante (`NonObservableLocale` en `IrisXConsoleDialog.kt:527`), corregido con `LocalLocale.current.platformLocale`.
- **Resultado final tras corrección (`--offline --no-daemon`):** **`0 errors, 38 warnings, 1 hint`** (`app/build/intermediates/lint_intermediate_text_report/debug/lintReportDebug/lint-results-debug.txt`). Los 38 avisos y 1 hint corresponden principalmente a `DefaultLocale` (8), `ObsoleteSdkInt` (8), `UseKtx` (7), iconos adaptativos/monocromo (7), versiones de dependencias (4), `OldTargetApi`, `DataExtractionRules`, `UnusedResources`, `ConstantLocale` y 1 hint `AutoboxingStateCreation` (no bloqueantes P3).

### 7.3 Compilación Nativa ARM64 + Ensamblado Lab (`assembleLab` + `assembleLabAndroidTest`)

```powershell
.\gradlew.bat --offline --no-daemon "-Psyntra.testBuildType=lab" :app:assembleLab :app:assembleLabAndroidTest
```

- **Resultado tras la última edición:** `BUILD SUCCESSFUL in 1m 17s` (`configureCMakeRelease[arm64-v8a]`, `buildCMakeRelease[arm64-v8a]`, `compileLabKotlin`, `assembleLab`, `assembleLabAndroidTest`).
- **Artefactos finales verificados en `app/build/outputs/apk/`:**
  - `app-lab.apk` — `18254890 bytes` — SHA-256 `B6D6AB24682CEDFD897967C51052BE53910B34615C3A529533B7EA43FC73143E`
  - `app-lab-androidTest.apk` — `1291517 bytes` — SHA-256 `B03B5D9A69CDF3579EFCB49A16952328C068170563EF6200E19869AB9E948B43`

---

## 8. Riesgos Pendientes, Controles y Validaciones Exclusivas de Dispositivo (`DEVICE-ONLY`)

**La validación Android física permanece ABIERTA (no ejecutada ni aprobada aún).** Las siguientes pruebas y controles obligatorios quedan pendientes sobre hardware Android antes de promover cambios a la instalación productiva:

1. `BackupManagerRestoreIntegrationTest`: restauración real SQLite/Room (`formatVersion = 1` vs `formatVersion = 3`), verificando preservación de tablas opcionales en backups legados y atomicidad ante errores.
2. `StageAMigrationInstrumentedTest`: migraciones Room de versiones previas a `v8` sobre SQLite real, incluyendo colisiones de UUID y preservación de datos.
3. `ComposeAliasRecompositionTest` y `ParserScreenIntegrationTest`: recomposición UI real, guardado transaccional y estabilidad de estado en rotaciones.
4. `LabIsolationTest`, `NeuralJniValidationTest` y `NeuralModelFileValidationTest`: aislamiento efectivo UID/archivos de `.lab`, carga/inferencia/cancelación/descarga nativa real en Redmi Note 13 4G con `Qwen3-0.6B-Q4_K_M.gguf` y rechazo real de `IrisXGuardian` bajo presión de RAM.
5. **Controles manuales en Android 15 / HyperOS:**
   - Diálogo runtime `POST_NOTIFICATIONS` al iniciar descanso y comportamiento si el permiso es denegado.
   - Vibración/alarma exacta tras descanso con pantalla bloqueada y reinicio del equipo (`RestTimerManager`).
   - Rotación de pantalla en edición de perfil, pesajes y analizador (`rememberSaveable`).
   - Arranque en frío de `.lab` verificando las 9 categorías iniciales (`DAT-01`) y ausencia de `.tmp_*` residuales tras cancelar una importación GGUF.

---

## 9. Checklist Operativo Antes de Instalar en Redmi Note 13 4G

1. No desinstalar ni sobrescribir `com.syntra.app` productivo.
2. Exportar primero un respaldo SAF desde la versión actualmente instalada en el teléfono.
3. Instalar exclusivamente `app-lab.apk` y `app-lab-androidTest.apk` (`com.syntra.app.lab`).
4. Copiar `Qwen3-0.6B-Q4_K_M.gguf` al almacenamiento privado del entorno `.lab` (ver `docs/VALIDACION_NEURAL_ANDROID.md`) y correr `androidTest` de laboratorio.
5. Verificar en frío en `.lab` que las 9 categorías por defecto aparecen sin migración previa (`DAT-01`) y probar rotación + temporizador + import/export de respaldo.

---

## 10. Inventario de Archivos Modificados y Agregados

- **Modificados (19 archivos, `+697 / -363` líneas respecto a `c4727e2`):**
  - `app/src/main/cpp/llama-android.cpp`
  - `app/src/main/java/android/llama/cpp/LLamaAndroid.kt`
  - `app/src/main/java/com/syntra/app/MainActivity.kt`
  - `app/src/main/java/com/syntra/app/data/backup/BackupManager.kt`
  - `app/src/main/java/com/syntra/app/domain/ActiveWorkoutManager.kt`
  - `app/src/main/java/com/syntra/app/domain/SportsMetricsCalculator.kt`
  - `app/src/main/java/com/syntra/app/domain/WorkoutTextParser.kt`
  - `app/src/main/java/com/syntra/app/domain/irisx/IrisXCore.kt`
  - `app/src/main/java/com/syntra/app/domain/irisx/tools/ExerciseNameResolver.kt`
  - `app/src/main/java/com/syntra/app/domain/neural/IrisXGuardian.kt`
  - `app/src/main/java/com/syntra/app/domain/neural/LlamaCppInferenceEngine.kt`
  - `app/src/main/java/com/syntra/app/domain/neural/ModelManager.kt`
  - `app/src/main/java/com/syntra/app/ui/history/EditWorkoutSessionDialog.kt`
  - `app/src/main/java/com/syntra/app/ui/irisx/IrisXConsoleDialog.kt`
  - `app/src/main/java/com/syntra/app/ui/irisx/ManageNeuralModelDialog.kt`
  - `app/src/main/java/com/syntra/app/ui/routines/RoutinesScreen.kt`
  - `app/src/main/java/com/syntra/app/ui/week/WeekScreen.kt`
  - `app/src/main/java/com/syntra/app/ui/workout/RestTimerBar.kt`
  - `app/src/main/java/com/syntra/app/ui/workout/RestTimerReceiver.kt`
- **Creados:**
  - `app/src/test/java/com/syntra/app/domain/irisx/IrisXAuditProbeTest.kt`
  - `app/src/test/java/com/syntra/app/AuditPreAndroidRegressionTest.kt`
  - `app/src/test/java/com/syntra/app/domain/neural/NeuralAuditRegressionTest.kt`
  - `docs/AUDITORIA_INTEGRAL_PRE_ANDROID.md`

---

## 11. Recomendación Final y Criterios de Salida

El código en `feat/etapa-b1-lab-validation` queda **verificado en host (JVM + Lint + ARM64 Lab build)** y sin defectos críticos confirmados sin resolver. **No se autoriza reemplazo directo de la app productiva todavía.** El siguiente hito es una **única ronda de validación física controlada sobre `com.syntra.app.lab`** en el Redmi Note 13 4G ejecutando los puntos de la Sección 8 tras respaldo SAF previo.
