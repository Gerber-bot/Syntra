# Validación Android de IrisX Neural — Syntra Lab

Documento de la Etapa B.1.3. Prepara un entorno Android aislado para ejecutar el motor
JNI real (llama.cpp ARM64) y las validaciones de integridad pendientes de la Etapa A,
sin tocar la instalación ni los datos personales de Syntra.

> Estado: **preparado, compilado y con la corrección de infraestructura aplicada**. La primera
> ejecución real (Redmi Note 13 4G) falló al inicializar `NeuralJniValidationTest` con
> `InvalidTestClassError: Method tearDown() should be void`; la causa está corregida y
> verificada en bytecode. Los escenarios neuronales **todavía no produjeron resultados**.
> Ningún resultado de este documento debe interpretarse como una prueba superada.

## 1. Aislamiento de Syntra Lab

La variante `lab` es un *build type* de Gradle; comparte todo el código, recursos y fuentes
JNI con el producto y sólo cambia la identidad de la aplicación.

| Aspecto | Producto | Syntra Lab |
|---|---|---|
| `applicationId` | `com.syntra.app` | `com.syntra.app.lab` |
| UID de Android | propio | distinto (sandbox separado) |
| `versionName` | `0.7.0-alpha18` | `0.7.0-alpha18-lab` |
| `versionCode` | 27 | 27 |
| Etiqueta | Syntra | Syntra Lab |
| Directorio de datos | `/data/data/com.syntra.app` | `/data/data/com.syntra.app.lab` |
| Room (`syntra.db`) | base personal | base nueva y descartable |
| Modelos GGUF | `files/models` del producto | `files/models` del laboratorio |
| Proveedor androidx.startup | `com.syntra.app.androidx-startup` | `com.syntra.app.lab.androidx-startup` |
| `allowBackup` | `false` | `false` |
| Permisos | VIBRATE, SCHEDULE_EXACT_ALARM, WAKE_LOCK, POST_NOTIFICATIONS | idénticos (sin INTERNET) |
| `libarm64-v8a/libllama-android.so` | SHA-256 `b60103d7…` (242112 bytes) | **mismo binario** |

Garantías:

- El `applicationId` distinto implica UID distinto: Android impide leer archivos, preferencias
  o bases del producto. No se declara ningún `sharedUserId`.
- Las autoridades de proveedores usan el placeholder `${applicationId}` y quedan sufijadas
  (verificado en el manifiesto fusionado), por lo que ambas apps pueden convivir instaladas.
- `LabIsolationTest` comprueba en el dispositivo que el directorio de datos y el de modelos
  pertenecen al paquete `.lab` y que `/data/data/com.syntra.app/files` no es legible.
- El receptor del temporizador conserva la acción literal `com.syntra.app.ACTION_REST_TIMER_EXPIRED`,
  pero es `exported=false` y las alarmas usan un `PendingIntent` con componente explícito:
  no hay entrega cruzada entre ambas apps.
- No se modificaron Room, migraciones, backups, historial, rutinas, Analytics, Coach,
  temporizador ni interfaz. La variante no cambia ninguna versión alpha del producto.

## 2. Compilación

```powershell
# APK de laboratorio + APK de pruebas instrumentadas
.\gradlew.bat --offline --no-daemon "-Psyntra.testBuildType=lab" `
  :app:assembleLab :app:assembleLabAndroidTest
```

Artefactos:

- `app/build/outputs/apk/lab/app-lab.apk`
- `app/build/outputs/apk/androidTest/lab/app-lab-androidTest.apk`

El *build type* `debug` sigue siendo el tipo de prueba por defecto: sin la propiedad
`-Psyntra.testBuildType=lab`, los flujos habituales (`assembleDebug`, `testDebugUnitTest`,
`connectedDebugAndroidTest`) no cambian.

## 3. Instalación (manual, autorizada)

No se instala automáticamente. Con un dispositivo ARM64 conectado y **sin desinstalar Syntra**:

```powershell
adb install -r app\build\outputs\apk\lab\app-lab.apk
adb install -r app\build\outputs\apk\androidTest\lab\app-lab-androidTest.apk
```

Alternativa equivalente sin `adb install` directo:

```powershell
.\gradlew.bat --offline --no-daemon "-Psyntra.testBuildType=lab" :app:installLab :app:installLabAndroidTest
```

Comprobaciones previas:

```powershell
adb devices -l
adb shell pm list packages | Select-String syntra   # deben aparecer com.syntra.app y com.syntra.app.lab
```

Si el dispositivo no es ARM64, `LlamaCppInferenceEngine.isNativeAvailable()` será falso y
`NeuralJniValidationTest` se omitirá explícitamente (no fallará).

## 4. Carga de un modelo Qwen3 GGUF

El modelo debe quedar dentro del sandbox del laboratorio. Opciones, en orden de preferencia:

1. **Importación desde la interfaz del laboratorio** (misma ruta que el producto):
   abrir Syntra Lab → diálogo de modelo neuronal de IrisX → importar el `.gguf` con el
   selector de archivos. Es el flujo real y no requiere `adb`.
2. **Directorio externo propio de la app**:

   ```powershell
   adb shell mkdir -p /sdcard/Android/data/com.syntra.app.lab/files/models
   adb push qwen3-1.7b-q4_k_m.gguf /sdcard/Android/data/com.syntra.app.lab/files/models/
   ```

3. **Copia al almacenamiento interno con `run-as`** (si el fabricante bloquea el punto 2):

   ```powershell
   adb push qwen3-1.7b-q4_k_m.gguf /data/local/tmp/qwen3.gguf
   adb shell "cat /data/local/tmp/qwen3.gguf | run-as com.syntra.app.lab sh -c 'mkdir -p files/models && cat > files/models/qwen3.gguf'"
   adb shell rm /data/local/tmp/qwen3.gguf
   ```

4. **Ruta explícita por argumento de instrumentación**:

   ```powershell
   adb shell am instrument -w -e neuralModelPath /sdcard/Android/data/com.syntra.app.lab/files/models/qwen3.gguf `
     com.syntra.app.lab.test/androidx.test.runner.AndroidJUnitRunner
   ```

Rutas que inspecciona `LabModelLocator`, en este orden:

1. el argumento de instrumentación `-e neuralModelPath <ruta absoluta>`;
2. `<filesDir>/models` y `<filesDir>/neural-lab` (almacenamiento interno);
3. `<externalFilesDir>/models` y `<externalFilesDir>/neural-lab` de **cada volumen externo
   propio de la app** (`getExternalFilesDirs(null)`): almacenamiento primario y microSD.

En un dispositivo sin almacenamiento adoptado, la ruta primaria esperada es:

```
/storage/emulated/0/Android/data/com.syntra.app.lab/files/models/Qwen3-0.6B-Q4_K_M.gguf
```

Un modelo en `.../files/neural-lab/` —la carpeta de reportes, también en la microSD— **sí se
encuentra** desde esta corrección y no hace falta moverlo. Si el volumen no está montado
cuando arranca el proceso de pruebas, usar `-e neuralModelPath` con la ruta exacta.

Sólo se aceptan archivos con cabecera GGUF válida (magic y versión 1..4) y se elige el de
mayor tamaño. No se busca en `/sdcard/Download` ni en el almacenamiento de otras
aplicaciones. La línea `preflight` del reporte incluye `modelPath` y `searchedDirs`, con el
conteo de `.gguf` por directorio, para diagnosticar la búsqueda sin adivinar.

## 5. Ejecución de las pruebas

Sólo el preflight (no genera texto; informa disponibilidad JNI, estado nativo, ruta y tamaño
del modelo, y los directorios inspeccionados):

```powershell
adb shell am instrument -w `
  -e class "com.syntra.app.neural.NeuralJniValidationTest#test01_preflight_reports_native_and_model" `
  com.syntra.app.lab.test/androidx.test.runner.AndroidJUnitRunner
```

Suite neuronal completa:

```powershell
adb shell am instrument -w `
  -e class com.syntra.app.neural.NeuralJniValidationTest,com.syntra.app.neural.NeuralModelFileValidationTest,com.syntra.app.neural.LabIsolationTest `
  com.syntra.app.lab.test/androidx.test.runner.AndroidJUnitRunner
```

Toda la suite instrumentada (incluye Etapa A y Compose):

```powershell
.\gradlew.bat --offline --no-daemon "-Psyntra.testBuildType=lab" :app:connectedLabAndroidTest
```

Equivalente con `adb`:

```powershell
adb shell am instrument -w com.syntra.app.lab.test/androidx.test.runner.AndroidJUnitRunner
```

Escenarios de `NeuralJniValidationTest` (orden fijo, alfabético):

| # | Escenario | Qué verifica |
|---|---|---|
| 01 | `preflight` | JNI disponible, estado nativo inicial, modelo localizado |
| 02 | `load_valid_gguf` | carga real, tiempo de carga, estado `READY`, métricas |
| 03 | `first_generation` | primer token (TTFT), tokens/s, un único chunk terminal |
| 04 | `consecutive_generations` | tres generaciones seguidas sin estados inconsistentes |
| 05 | `cancel_during_generation` | cancelación cooperativa, latencia, terminal único, recuperación |
| 06 | `cancel_suspended_emission` | consumidor retiene la emisión; cancelación; unload posterior |
| 06b | `cancel_collector_suspended` | cancelación del Job colector (ruta real de la UI) + unload |
| 07 | `concurrent_unloads` | dos `unloadModel()` concurrentes sin doble liberación |
| 08 | `unload_reload` | descarga y recarga con generación posterior |
| 09 | `unload_during_load` | descarga durante carga; coherencia de propiedad; recuperación |
| 10 | `excessive_context` | prompt > `n_ctx` (2048) llega como error a Kotlin y se recupera |
| 11 | `utf8_streaming` | tildes/emoji sin U+FFFD ni surrogates huérfanos, chunk a chunk |
| 12 | `truncated_model_native` | GGUF truncado falla en nativo sin caída; recarga posterior |

`NeuralModelFileValidationTest` cubre archivo inexistente, magic GGUF inválido, versión no
soportada y cabecera truncada sin invocar JNI.

## 6. Reporte y métricas

Cada escenario escribe **una línea JSON** en:

```
/sdcard/Android/data/com.syntra.app.lab/files/neural-lab/neural-lab-<fecha>.jsonl
```

y en logcat con la etiqueta `SyntraLab`. Se escribe por *append* para conservar los
resultados previos si el proceso muere en un escenario posterior.

```powershell
adb pull /sdcard/Android/data/com.syntra.app.lab/files/neural-lab/ .\neural-lab-report\
adb logcat -d -s SyntraLab
```

Campos registrados: `scenario`, `status` (`PASS`/`FAIL`/`SKIP`/`INFO`), `pssKb`,
`nativeHeapBytes`, `javaHeapUsedBytes`, `javaHeapMaxBytes`, `loadMillis`, `ttftMillis`,
`tokens`, `tokensPerSecond`, `cancelMillis`/`cancelToTerminalMillis`, `engineState`,
`nativeState`, `nativeHasModel`, `perChunkStable`, `hasReplacementChar`, `loneSurrogate`.
Nunca se guardan prompts, texto generado ni datos personales; sólo longitudes y banderas.

Fallos nativos:

```powershell
adb logcat -b crash -d
adb shell dumpsys dropbox --print data_app_crash
```

## 7. Validaciones de integridad de la Etapa A

Se mantienen separadas de las pruebas neuronales y corren sobre datos descartables en el
sandbox del laboratorio:

| Validación | Dónde |
|---|---|
| Migración v7→v8 con base poblada, UUID históricos, duplicados y colisiones | `StageAMigrationInstrumentedTest` (nuevo) |
| Base v8 existente con UUID históricos que se abre sin migrar | `StageAMigrationInstrumentedTest` (nuevo) |
| Restauración completa y rollback transaccional | `BackupManagerRestoreIntegrationTest` (existente) |
| Edición, alias y recomposición Compose | `ComposeAliasRecompositionTest` (existente) |
| Edición de series y confirmación sobre Room | `ParserScreenIntegrationTest` (existente) |

```powershell
adb shell am instrument -w `
  -e class com.syntra.app.data.StageAMigrationInstrumentedTest,com.syntra.app.BackupManagerRestoreIntegrationTest,com.syntra.app.ComposeAliasRecompositionTest,com.syntra.app.ui.parser.ParserScreenIntegrationTest `
  com.syntra.app.lab.test/androidx.test.runner.AndroidJUnitRunner
```

`StageAMigrationInstrumentedTest` usa SQLite real del dispositivo sobre un archivo en
`cacheDir` que se borra al terminar; nunca abre `syntra.db`.

## 8. Seguridad y límites de esta entrega

- No se desinstala ni modifica Syntra; no se ejecutan migraciones ni restauraciones sobre la
  base personal.
- No se fuerza la descarga de recursos nativos en uso: `unload` espera a que terminen carga
  y generación antes de liberar.
- Las pruebas son cortas (`maxTokens` 16–32) para evitar estrés térmico; no hay benchmarks
  prolongados ni cambios de prioridad de CPU o cuantización.
- Detección de doble liberación: es indirecta (caída del proceso o estado inconsistente).
  Un doble `free` que el asignador tolere no se detecta desde Kotlin.
- `test12_truncated_model_native` se ejecuta al final a propósito: alimentar un GGUF
  estructuralmente truncado puede provocar una caída nativa en algunas compilaciones.
- La presencia de tildes o emojis en `utf8_streaming` depende de la salida del modelo; la
  prueba siempre valida integridad UTF-8, aunque no garantiza que el modelo emita emojis.
- Limitación principal: **las pruebas todavía no se ejecutaron**. Falta un dispositivo ARM64
  con el modelo cargado para producir el reporte real y cerrar B.1.

## 9. Historial de ejecución real

### 2026-11-11 — Redmi Note 13 4G

Primera ejecución instrumentada. El runner no pudo inicializar la clase:

```
InvalidTestClassError: Method tearDown() should be void
com.syntra.app.neural.NeuralJniValidationTest
```

Causa: `tearDown()` se declaró con cuerpo de expresión (`= runBlocking { … }`). El `catch`
terminaba en `Log.w(...)`, que devuelve `Int`, así que `runBlocking` se infería como `Any` y
el método compilaba como `()Ljava/lang/Object;`. JUnit 4 exige `()V` en `@Before`/`@After`.

Corrección: cuerpo de bloque explícito, conservando la misma lógica de `unloadModel()` con
`withTimeout(120_000)`.

Verificación estática de las 74 clases compiladas del APK de pruebas (36 métodos `@Before`,
`@After` y `@Test`): todos son `public`, sin argumentos y `void`. El chequeo se validó con un
control negativo que reproduce el descriptor defectuoso.

Sin resultados de escenarios: los 13 tests neuronales siguen pendientes de ejecución.
