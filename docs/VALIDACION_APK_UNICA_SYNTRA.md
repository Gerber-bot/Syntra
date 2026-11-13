# Syntra (`com.syntra.app`) — Validación de APK Única con IrisX Integrado

**Fecha:** 2026-11-11  
**Repositorio:** `C:\Dev\EntrenadorLocal`  
**Rama:** `feat/etapa-b1-lab-validation`  
**Commit base de partida:** `876a35e`  
**Aplicación:** Syntra (`com.syntra.app`) — `0.7.0-alpha18` (`versionCode = 27`)  
**Base de datos:** Room `v8` (sin cambios de esquema)  
**Modelo neuronal objetivo:** `Qwen3-0.6B-Q4_K_M.gguf` (offline, importado por SAF)

---

## 1. Estado Inicial Verificado

Al iniciar esta etapa sobre el commit `876a35e`:
- Rama `feat/etapa-b1-lab-validation`, `versionName = "0.7.0-alpha18"`, `versionCode = 27`, Room `v8`.
- 273 pruebas JVM aprobadas (37 suites).
- Android Lint en `0 errors, 38 warnings, 1 hint`.
- Compilación ARM64 JNI y variante `lab` aprobadas; presencia de archivos untracked de laboratorio previo (`neural-lab-report/*.jsonl`, `syntra-lab-*.txt`, `syntra-test05-diagnostico.txt`), conservados intactos fuera del control de versiones.

---

## 2. Diferencias Detectadas entre Variantes

Inspección directa sobre `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/lab/AndroidManifest.xml` y `app/src/`:

| Aspecto | `debug` (`com.syntra.app`) — **Candidata para Redmi** | `release` (`com.syntra.app`) | `lab` (`com.syntra.app.lab`) |
|---|---|---|---|
| `applicationId` | `com.syntra.app` | `com.syntra.app` | `com.syntra.app.lab` |
| `namespace` | `com.syntra.app` | `com.syntra.app` | `com.syntra.app` |
| `versionName` / `versionCode` | `0.7.0-alpha18` / `27` | `0.7.0-alpha18` / `27` | `0.7.0-alpha18-lab` / `27` |
| `android:label` | `Syntra` | `Syntra` | `Syntra Lab` (`app/src/lab/AndroidManifest.xml`) |
| `debuggable` / firma | `true` / keystore debug (instalable directo) | `false` / **sin `signingConfig`** (`unsigned`) | `true` / keystore debug |
| CMake / C++ JNI | `Release` (`-O3 -DNDEBUG`, `-DLLAMA_CURL=OFF`, `arm64-v8a`) | `Release` (`-O3 -DNDEBUG`, `-DLLAMA_CURL=OFF`, `arm64-v8a`) | `Release` (`-O3 -DNDEBUG`, `-DLLAMA_CURL=OFF`, `arm64-v8a`) |
| Dependencias de test UI en APK | Incluye `ui-tooling` (`PreviewActivity`) y `ui-test-manifest` (`androidx.activity.ComponentActivity`) | **Ninguna** (`PreviewActivity` y `ui-test-manifest` ausentes) | Incluye `ui-tooling` y `ui-test-manifest` (`labImplementation`) |
| Código productivo (`app/src/main`) | Idéntico | Idéntico | Idéntico |

**Conclusiones de compatibilidad en `app/src/main`:**
- Búsqueda completa en `app/src/main`: **0 referencias** a `com.syntra.app.lab`, `.lab`, `SyntraLab`, `/data/data`, `/sdcard` o dependencias exclusivas de laboratorio.
- Ningún componente de entrenamiento, historial, perfil, peso, planificación, IrisX, Room, backups o JNI depende de `lab`.
- En `app/src/androidTest`, `LabIsolationTest` utiliza `Assume.assumeTrue(context.packageName.endsWith(".lab"))` para ejecutarse solo en `.lab`, mientras que el resto de suites instrumentadas (`NeuralJniValidationTest`, `NeuralModelFileValidationTest`, `BackupManagerRestoreIntegrationTest`, `StageAMigrationInstrumentedTest`, `ComposeAliasRecompositionTest`, `ParserScreenIntegrationTest`) operan sobre `com.syntra.app` conservando la infraestructura intacta.
- Se mantiene `testBuildType = (project.findProperty("syntra.testBuildType") as String?) ?: "debug"` sin romper scripts ni pruebas existentes; las pruebas instrumentadas por defecto apuntan a `debug` (`com.syntra.app`).

---

## 3. Cambios Realizados (Revisión de Advertencias Lint y Privacidad)

De las **38 advertencias y 1 hint** iniciales de Android Lint, se corrigieron las que implicaban riesgos reales de privacidad, concurrencia/estabilidad o formato regional:

1. **Privacidad en respaldos del sistema (`DataExtractionRules` en Android 8–15):**
   - Añadido [data_extraction_rules.xml](file:///c:/Dev/EntrenadorLocal/app/src/main/res/xml/data_extraction_rules.xml) (excluyendo `root`, `database`, `sharedpref`, `file`, `external` tanto en `cloud-backup` como en `device-transfer` para Android 12+ / Android 15).
   - Añadido [backup_rules.xml](file:///c:/Dev/EntrenadorLocal/app/src/main/res/xml/backup_rules.xml) (`android:fullBackupContent` para cobertura API 26–30).
   - Enlazados ambos en [AndroidManifest.xml](file:///c:/Dev/EntrenadorLocal/app/src/main/AndroidManifest.xml) junto a `android:allowBackup="false"`.
2. **Riesgo de concurrencia / estado mutable (`ConstantLocale` en `IrisXContextBuilder.kt`):**
   - `private val dateFormat = SimpleDateFormat(...)` en el singleton `IrisXContextBuilder` compartía una instancia no thread-safe entre corrutinas y congelaba el `Locale` inicial. Se reemplazó por el helper inmutable y thread-safe `formatContextDate(millis)` basado en `java.time.format.DateTimeFormatter`.
3. **Formato numérico y reloj independientes de locale (`DefaultLocale` — 8 ocurrencias):**
   - Se especificó explícitamente `Locale.US` en `String.format(...)` dentro de [MainActivity.kt](file:///c:/Dev/EntrenadorLocal/app/src/main/java/com/syntra/app/MainActivity.kt), [IrisXConsoleDialog.kt](file:///c:/Dev/EntrenadorLocal/app/src/main/java/com/syntra/app/ui/irisx/IrisXConsoleDialog.kt), [ManageNeuralModelDialog.kt](file:///c:/Dev/EntrenadorLocal/app/src/main/java/com/syntra/app/ui/irisx/ManageNeuralModelDialog.kt) y [RestTimerBar.kt](file:///c:/Dev/EntrenadorLocal/app/src/main/java/com/syntra/app/ui/workout/RestTimerBar.kt).
4. **Sugerencia de estado Compose (`AutoboxingStateCreation` — 1 hint):**
   - Reemplazado `mutableStateOf(0)` por `mutableIntStateOf(0)` en [AnalyticsAndCoachScreen.kt](file:///c:/Dev/EntrenadorLocal/app/src/main/java/com/syntra/app/ui/analytics/AnalyticsAndCoachScreen.kt).

**Advertencias abiertas documentadas (28 warnings de bajo impacto, 0 errors, 0 hints):**
- `ObsoleteSdkInt` (8): guardas defensivas `SDK_INT >= M/O/Lollipop` y carpeta `mipmap-anydpi-v26`.
- `UseKtx` (7): sugerencias sintácticas `SharedPreferences.edit { }` y `.toUri()`.
- `IconLauncherShape` (5) y `MonochromeLauncherIcon` (2): diseño visual de íconos lanzadores.
- `AndroidGradlePluginVersion` (1), `GradleDependency` (1), `NewerVersionAvailable` (2) y `OldTargetApi` (1, `targetSdk = 35`): congelados por restricción de estabilidad de versiones.
- `UnusedResources` (1): `R.drawable.ic_timer`.

---

## 4. Integración Efectiva de IrisX en `com.syntra.app`

Verificado en código fuente y binario ensamblado:
- **Orquestación UI:** `TrainerApp` en [MainActivity.kt](file:///c:/Dev/EntrenadorLocal/app/src/main/java/com/syntra/app/MainActivity.kt) instancia `DeterministicInferenceEngine(db)` y `LlamaCppInferenceEngine(fallbackEngine)`, exponiendo tanto `IrisXConsoleDialog` como `ManageNeuralModelDialog` directamente sobre la app única `com.syntra.app`.
- **Herramientas locales B.2 y dominio:** `IrisXCore` (`RoomIrisXRepository`), `IrisXLocalTools` (`LastWorkout`, `RecentWorkouts`, `LastExerciseSession`, `ExerciseHistory`), `ExerciseNameResolver` y `IrisXAnalytics` operan de forma determinista sobre Room `v8`.
- **Motor neuronal ARM64:** `LLamaAndroid` y `llama-android.cpp` compilados en Release ARM64 y empaquetados dentro del APK único (`libllama-android.so`, `libllama.so`, `libggml.so`, `libggml-cpu.so`, `libggml-base.so`, `libc++_shared.so`).
- **Protección y recuperación:** `IrisXGuardian` valida RAM disponible (`CRITICAL` si `avail < modelSizeBytes`), temperatura y batería; `LlamaCppInferenceEngine` mantiene degradación controlada a `DeterministicInferenceEngine` si no hay modelo cargado o ante fallo nativo.

---

## 5. Resultados de JVM, Lint y Compilación (Tras la última edición)

Comando ejecutado:

```powershell
.\gradlew.bat --offline --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest
```

- **Resultado global:** `BUILD SUCCESSFUL in 3m 32s`
- **JVM (`:app:testDebugUnitTest`, agregado XML `TEST-*.xml`):** **`Suites=37 Tests=273 Failures=0 Errors=0 Skipped=0`**
- **Android Lint (`:app:lintDebug`):** **`0 errors, 28 warnings, 0 hints`**
- **Release LintVital (`:app:lintVitalRelease`):** **`PASSED`**
- **Compilación Kotlin + C++/JNI ARM64 (`configureCMakeRelease[arm64-v8a]`, `buildCMakeRelease[arm64-v8a]`):** **`PASSED`**
- **Ensamblados generados:** `:app:assembleDebug`, `:app:assembleRelease` y `:app:assembleDebugAndroidTest` **`PASSED`**.

---

## 6. Identificador, Permisos y Bibliotecas Nativas del APK

Inspección directa con `.android-sdk\build-tools\35.0.0\aapt2.exe` y verificación ZIP del APK candidato (`app-debug.apk`) y de su contraparte `app-release-unsigned.apk`:

- **`package` / `applicationId`:** `com.syntra.app`
- **`application-label`:** `Syntra`
- **`versionCode` / `versionName`:** `27` / `0.7.0-alpha18`
- **`launchable-activity`:** `com.syntra.app.MainActivity` (`exported=true`)
- **Permisos declarados:**
  - `android.permission.INTERNET` (para descarga HTTPS segura de modelos GGUF y consultas públicas)
  - `android.permission.ACCESS_NETWORK_STATE` (para detección de redes medidas / Wi-Fi vs datos móviles)
  - `android.permission.VIBRATE`
  - `android.permission.SCHEDULE_EXACT_ALARM`
  - `android.permission.WAKE_LOCK`
  - `android.permission.POST_NOTIFICATIONS`
  - `com.syntra.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`
- **Receptores/Servicios propios:** `com.syntra.app.ui.workout.RestTimerReceiver` (`exported=false`), `androidx.room.MultiInstanceInvalidationService` (`exported=false`), `androidx.startup.InitializationProvider` (`exported=false`, `authorities="com.syntra.app.androidx-startup"`).
- **Nota de empaquetado (`debug` vs `release`):** `app-debug.apk` está firmado con la clave debug y expone `debuggable=true` más actividades de tooling Compose (`PreviewActivity`, `androidx.activity.ComponentActivity` de `ui-test-manifest`), por lo que sirve para **validación física controlada** sobre `com.syntra.app`. `app-release-unsigned.apk` no incluye código de prueba ni `debuggable=true`, pero carece de firma (`unsigned`) al no haber keystore productivo configurado en el repositorio.
- **Bibliotecas nativas (`arm64-v8a` presentes en ambos APK):**
  - `lib/arm64-v8a/libllama-android.so` (`243792 bytes`)
  - `lib/arm64-v8a/libllama.so` (`1520920 bytes`)
  - `lib/arm64-v8a/libggml.so` (`57336 bytes`)
  - `lib/arm64-v8a/libggml-cpu.so` (`444144 bytes`)
  - `lib/arm64-v8a/libggml-base.so` (`647464 bytes`)
  - `lib/arm64-v8a/libc++_shared.so` (`1292904 bytes`)
  - `lib/arm64-v8a/libandroidx.graphics.path.so` (`10096 bytes`)

---

## 7. Riesgos Pendientes

1. **Validación física en Android 15 / Redmi Note 13 4G aún no ejecutada:** el funcionamiento real sobre hardware ARM64, Room en dispositivo, permisos en tiempo de ejecución, comportamiento HyperOS y prueba háptica en los 3 escenarios del temporizador siguen pendientes.
2. **Actualización sobre instalación existente (`com.syntra.app`):** al abandonar `.lab`, la próxima instalación se hará sobre la app real (`adb install -r`); **nunca desinstalar** previamente para no perder `/data/data/com.syntra.app`, y exportar antes un respaldo JSON por SAF.
3. **Variante `debug` vs `release` para uso final:** `app-debug.apk` es apta para esta validación controlada; para una entrega final sin `debuggable=true` ni tooling de pruebas será necesario firmar `app-release-unsigned.apk` con keystore productivo.

---

## 8. Ruta y SHA-256 de los Artefactos Generados

- **APK candidata para validación controlada en Redmi (`com.syntra.app`):**
  - Ruta: `app/build/outputs/apk/debug/app-debug.apk`
  - Tamaño: `18402635 bytes`
  - SHA-256: `F74095911BB2891389546E9EAA0CEEA794886FF1659318F22C397A5E831B80AD`
- **APK Release compilada (sin firmar, sin código de pruebas):**
  - Ruta: `app/build/outputs/apk/release/app-release-unsigned.apk`
  - Tamaño: `13836543 bytes`
  - SHA-256: `77861B96A5F660AA1436BB0722AD22084922F05FBF205CC1CDB079BD210622ED`
- **APK de pruebas instrumentadas (`com.syntra.app.test`):**
  - Ruta: `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
  - Tamaño: `1263044 bytes`
  - SHA-256: `D1BAF4EC660EAC060463C5CD99501442E658CA4D5E9930F7C9295A3C5422E33D`

---

## 9. Checklist de Validación en Android (Redmi Note 13 4G — `com.syntra.app`)

1. En la instalación actual del teléfono, abrir **Perfil → Exportar respaldo JSON** y guardar copia fuera del almacenamiento privado de la app.
2. Actualizar por encima (`adb install -r app/build/outputs/apk/debug/app-debug.apk`) sin desinstalar Syntra.
3. Verificar conservación íntegra de perfil, historial, series, rutinas, categorías y memorias/alias de IrisX.
4. En **Perfil → Administrar modelos GGUF**, comprobar carga de `Qwen3-0.6B-Q4_K_M.gguf`, métricas/guardián de RAM, generación en streaming, cancelación y descarga sin cierre inesperado.
5. Probar herramientas locales B.2 en `IrisXConsoleDialog` (`último entrenamiento`, `últimas sesiones`, `progresión` y `borrar conversación`).
6. Verificar temporizador de descanso (`POST_NOTIFICATIONS`, alarma exacta con pantalla bloqueada, recuperación tras reiniciar), analizador de texto (`[LASTRE]` en `kg`/`lb`, `BW` mixto), edición de superseries y rotación de pantalla.
7. Si se ejecutan pruebas instrumentadas contra `com.syntra.app`, verificar `StageAMigrationInstrumentedTest`, `BackupManagerRestoreIntegrationTest`, `ComposeAliasRecompositionTest`, `ParserScreenIntegrationTest` y `NeuralJniValidationTest` (recordando que `BackupManagerRestoreIntegrationTest` sobrescribe la base de pruebas — **no correrlo sobre datos personales sin respaldo previo**).
