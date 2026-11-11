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
- Estado de validación: **11 de 13 escenarios neuronales aprobados en dispositivo real**
  (reporte del usuario, ver §9). `test06b` y `test12` siguen pendientes. B.1 no se cierra
  hasta completar el flujo conversacional en la UI.

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

Segunda ejecución (reportada por el usuario, no por este agente): `test01` a `test11` de
`NeuralJniValidationTest` aprobados individualmente con `Qwen3-0.6B-Q4_K_M.gguf` en
Redmi Note 13 4G / Android 15. Dos corridas fueron interrumpidas por `OneKeyClean` de
HyperOS y aprobaron al reintentarse. JNI carga y genera: la capa nativa no era la causa del
fallo conversacional.

## 10. Corrección del flujo conversacional (2026-11-11)

Auditoría Compose → ViewModel/orquestación (`IrisXConsoleDialog`) → `LlamaCppInferenceEngine`
→ `LLamaAndroid` → llama.cpp. Defectos verificados en código y corregidos:

| # | Defecto | Consecuencia en dispositivo |
|---|---|---|
| 1 | `generate` fijaba `state = ERROR` ante cualquier `Throwable`; `isModelLoaded()` exige `READY` | Un solo error de inferencia degradaba IrisX a determinista durante toda la sesión, en silencio |
| 2 | `loadModel` sólo liberaba la sesión nativa si `isModelLoaded()`; llama.cpp rechaza cargar sobre una sesión viva | Recargar el modelo desde Perfil fallaba con «Error al cargar»; el motor quedaba en `ERROR` |
| 3 | `loadModel` descargaba el modelo **antes** de validar el GGUF | Un archivo inválido destruía una sesión sana |
| 4 | `historyForPrompt` se calculaba después de agregar el turno del usuario | El turno actual se enviaba duplicado en el prompt ChatML |
| 5 | El pie de métricas (`⚡ t/s …`) vivía dentro del texto del mensaje | Se reinyectaba al modelo como contenido del asistente |
| 6 | El texto `[Error: …]` se persistía como mensaje IRISX | El error se reproducía en prompts posteriores |
| 7 | `isGenerating` no se reseteaba si la corrutina fallaba | El chat quedaba bloqueado en «Parar» |
| 8 | `sendMessage` abortaba si `activeConversation == null` | El primer mensaje se perdía en silencio al abrir el chat |
| 9 | La generación indexaba por posición (`botMessageIndex`) y sobrevivía al cambio de conversación | La respuesta podía escribirse en otra conversación |
| 10 | Sin librería nativa se mostraba una respuesta simulada | El usuario creía que el modelo había respondido |
| 11 | La carga fallida del modelo no se mostraba | Degradación silenciosa a determinista |

Correcciones aplicadas:

- `IrisXConsoleDialog.kt`: orquestación reescrita con `try/finally`, token de generación
  (`activeGenerationToken`) para descartar turnos obsoletos, `isSending` como guarda de
  reentrada, reemplazo de burbujas por `id` (no por índice), creación de conversación bajo
  demanda, burbujas `ERROR`/`NOTICE` no persistidas, aviso visible de fallo de carga y
  auto-recuperación de una sesión nativa perdida (`ensureNeuralReady`).
- `IrisXChatFlow.kt` (nuevo): `ChatMessageKind`, `ChatMessage` con `metricsFooter` separado,
  `IrisXChatHistory.forPrompt` (excluye errores/avisos/placeholder, tope de 6 turnos) y
  `IrisXReplyAccumulator` (contenido vs. error, no persistible si falló).
- `LlamaCppInferenceEngine.kt`: frontera `NeuralNativeAccess` inyectable, validación antes de
  descargar, liberación de sesión obsoleta, error recuperable (`READY` si la sesión nativa
  sobrevive) y error explícito cuando falta la librería nativa.

Pruebas JVM ejecutadas por este agente:

```powershell
.\gradlew.bat --offline --no-daemon :app:testDebugUnitTest
```

Resultado: **221 tests, 0 fallos, 0 errores** (202 previos + 9 de `LlamaCppEngineStateTest`
+ 10 de `IrisXChatFlowTest`). El defecto 1 se reprodujo en rojo
(`expected:<READY> but was:<ERROR>`) revirtiendo el manejo de error y volvió a verde con la
corrección.

APK de laboratorio para validación manual:

```
app/build/outputs/apk/lab/app-lab.apk
18.006.007 bytes · SHA-256 4C705CC740480E9D…
app/build/outputs/apk/androidTest/lab/app-lab-androidTest.apk
1.291.517 bytes · SHA-256 FFEB8BAD02C1C118…
```

Checklist manual en el teléfono (sin autorización no se instala nada):

1. Enviar una consulta libre con el modelo cargado: la respuesta debe streamearse y terminar
   con el pie de métricas fuera del texto de la burbuja.
2. Pulsar «⏹ Parar» a mitad de respuesta: el botón debe volver a «Enviar» y el mensaje
   detenido no debe quedar persistido como respuesta de IrisX.
3. Cerrar y reabrir el chat: el historial debe mantener el turno del usuario y no duplicar el
   pie de métricas ni errores.
4. Cambiar de conversación durante una generación: la respuesta no debe aparecer en el chat
   nuevo.
5. Forzar un error (p. ej. pregunta muy larga): debe verse una burbuja de error, el chat debe
   seguir usable y el siguiente turno debe volver a usar el motor neuronal.

No se declara B.1 cerrada: falta ejecutar este checklist en el dispositivo.

## 11. Duplicación de turnos y recomendaciones generales (2026-11-11)

Auditoría de `IrisXConsoleDialog` → `IrisXChatFlow` → `IrisXCore.ask` → `RoomIrisXRepository`
→ `IrisxConversationDao` / `IrisxMessageEntity`. Tres causas independientes explicaban la
duplicación observada; ninguna era un fallo de Room.

| # | Causa verificada | Consecuencia |
|---|---|---|
| 1 | `IrisXCore.ask()` ya persiste el turno USER y la respuesta IRISX, y el diálogo volvía a persistir ambos en el camino determinista | 4 filas por turno (2 USER + 2 IRISX idénticas) |
| 2 | `ask()` resolvía la conversación con `getOrCreateActiveConversation()`, ordenada por `updatedAtMillis DESC` y modificada por la propia llamada | La respuesta podía escribirse en otra conversación que la visible |
| 3 | La carga inicial (`LaunchedEffect`) y el primer `sendMessage` llamaban en paralelo a `getOrCreateActiveConversation()` y la carga hacía `messages.clear()` | Podían crearse dos conversaciones y el reload tardío borraba la burbuja viva, repintando las filas duplicadas |

Las duplicaciones estaban **persistidas**, no sólo en pantalla: la UI agrega una burbuja por
turno, por lo que los pares visibles aparecían al releer filas duplicadas tras un cambio de
conversación, reapertura o recomposición.

Correcciones aplicadas (sin cambio de esquema ni migración; DB v8 intacta):

- `IrisXConsoleDialog.kt`: un único escritor por turno. En el camino determinista `ask()` es
  el único que persiste USER + IRISX; el diálogo sólo persiste el USER en el camino neuronal,
  donde `ask()` no participa. `ask()` recibe la conversación visible
  (`ask(input, referenceTimeMillis, conversation)`), no la resuelve por `updatedAtMillis`.
- `IrisXConsoleDialog.kt`: `Mutex` + `ensureConversationLoaded()` serializan
  resolver→leer→limpiar→renderizar contra el envío; la burbuja del usuario se agrega después
  de la carga, bajo el mismo candado lógico. `loadedConversationId` se marca recién al
  terminar el render, así una carga cancelada se reintenta en vez de dejar el chat vacío.
- `IrisXChatFlow.kt`: `IrisXTurnLedger` reemplaza `activeGenerationToken` como autoridad
  single-flight. Cada envío recibe un `turnId`; un segundo envío concurrente obtiene `null`.
  Un turno cancelado o reemplazado deja de estar activo y no puede agregar ni persistir nada.
  Reenviar el mismo texto más tarde es válido porque genera un turno nuevo.
- `IrisXModels.kt` / `IrisXContext.kt` / `IrisXCore.kt` / `IrisXCoach.kt`: se separa
  recomendación **general** de recomendación **personalizada**. `QueryRecommendations` gana
  `general: Boolean`; `IrisXContextResolver.isGeneralRecommendationRequest` clasifica con
  marcadores generales («general», «qué hacer», «día de», «qué ejercicios»…) que las marcas
  personales («mi progreso», «mi rutina», «para mejorar mi»…) anulan.
  `IrisXCoach.generateGeneralRecommendations` responde con estructura genérica
  (`GENERAL_GUIDANCE`, `QUERY_RECOMMENDATIONS_GENERAL`) sin leer sesiones ni series, y declara
  explícitamente que no usa datos personales. «recomendaciones» sin contexto sigue siendo
  personalizada y, si no hay historial, sugiere pedir «recomendaciones generales de …».

Pruebas de regresión agregadas:

- `IrisXTurnLedgerTest` (7): envío simultáneo rechazado, turno obsoleto no libera el candado,
  cancelación y reenvío, mismo texto permitido como turno nuevo, recomposición y reapertura.
- `IrisXTurnPersistenceAndRoutingTest` (6): un turno determinista persiste exactamente 1 USER
  + 1 IRISX; dos turnos iguales persisten 2+2 sin duplicar; `ask()` escribe en la conversación
  visible aunque `getOrCreateActiveConversation()` apunte a otra; consulta general no lee
  historial (`QUERY_RECOMMENDATIONS_GENERAL`); consulta personal sí lo hace
  (`QUERY_RECOMMENDATIONS`); «recomendaciones» aislado conserva el comportamiento personalizado.

Resultado JVM de esta corrida:

```
.\gradlew.bat --offline --no-daemon :app:testDebugUnitTest
234 tests, 0 fallos, 0 errores, 0 omitidos (32 suites)
```

APK de laboratorio reconstruido para el mismo checklist:

```
app/build/outputs/apk/lab/app-lab.apk
18.236.208 bytes · SHA-256 6600ADA378C39B09…
app/build/outputs/apk/androidTest/lab/app-lab-androidTest.apk
1.291.517 bytes · SHA-256 FFEB8BAD02C1C118…
```

Riesgos pendientes:

- Las filas duplicadas ya escritas en la base de datos de laboratorio no se deduplican
  automáticamente: deduplicar a ciegas ocultaría repeticiones legítimas. Hay que borrar la
  conversación afectada desde «💬 Chats» o reinstalar los datos de laboratorio.
- Falta ejecutar el checklist manual (envío simultáneo, cambio de conversación, cierre y
  reapertura) en el dispositivo; no se instaló nada sin autorización.

## 12. Herramientas locales de consulta — Etapa B.2 (2026-11-11)

Se incorporó la capa de herramientas locales tipadas descrita en
`ARQUITECTURA_Y_REGLAS.md` (§ «Herramientas locales de consulta»). Continúa sobre `78a0fda`,
sin cambio de esquema (DB v8) y sin tocar los datos de `com.syntra.app`.

Componentes reutilizados: `IrisXCore.parseInput`/`execute`, `IrisXAnalytics.analyzeExerciseProgression`
(agregación por modalidad), `SportsMetricsCalculator.classifySetModality`, `UnitConverter.formatDisplay`,
`IrisXRepository` y la consola `IrisXConsoleDialog` con su `IrisXTurnLedger` y su mutex de B.1.

Archivos nuevos:

```
app/src/main/java/com/syntra/app/domain/irisx/tools/LocalToolContracts.kt
app/src/main/java/com/syntra/app/domain/irisx/tools/ExerciseNameResolver.kt
app/src/main/java/com/syntra/app/domain/irisx/tools/IrisXLocalTools.kt
app/src/main/java/com/syntra/app/domain/irisx/tools/LocalToolPresenter.kt
app/src/test/java/com/syntra/app/domain/irisx/IrisXLocalToolsAndHistoryTest.kt
```

Archivos modificados: `IrisXModels.kt` (intención `QueryExerciseHistory`),
`IrisXCore.kt` (rama de parseo `10b`, despacho, `handleQueryExerciseHistory`,
`handleQueryLastExerciseLoad` delegado a la herramienta con `actionFor`).

Decisiones:

- La rama de parseo de historial se ubica **después** del borrado de historial de chat y de
  rutinas, y **antes** de la progresión genérica: «borrar historial de chat» y «quiero comparar
  mi progreso» conservan su significado previo (cubierto por prueba).
- `handleQueryLastWorkout` no se modificó para no alterar el comportamiento ya aprobado; la
  herramienta `LastWorkout` existe y está probada de forma independiente.
- `handleQueryLastExerciseLoad` ahora delega en la herramienta, manteniendo el estilo y los
  valores aprobados (`95 kg`, nombre del ejercicio) y sumando la línea de sesión anterior sólo
  cuando existe una comparable.

Cobertura de pruebas (20 casos en `IrisXLocalToolsAndHistoryTest`): historial vacío, sesión sin
series, última sesión, una y múltiples sesiones, ejercicio repetido en fechas distintas, alias,
nombre similar, nombre duplicado ambiguo, conteo de series y repeticiones desde filas, datos
insuficientes para comparar, modalidades incompatibles, fallo de repositorio, conversaciones
concurrentes sin mezclar kg, cancelación, integridad de la respuesta (ningún kg inventado),
parseo/enrutamiento y códigos `actionTaken` de éxito, sin datos, insuficiente y error.

Resultado JVM de esta corrida:

```
.\gradlew.bat --offline --no-daemon :app:testDebugUnitTest
254 tests, 0 fallos, 0 errores, 0 omitidos (33 suites)
```

APK de laboratorio reconstruido (sin instalar):

```
app/build/outputs/apk/lab/app-lab.apk
18.464.926 bytes · SHA-256 C28C94763B78573A…
```

Limitaciones y riesgos pendientes:

- Las preguntas de historial siguen siendo deterministas: Qwen3 no participa en la redacción de
  esta consulta, por lo que la respuesta es correcta pero de estilo fijo.
- `ExerciseHistory` limita a 5 sesiones por consulta; no hay paginación ni filtros por fecha.
- No hay pruebas instrumentadas nuevas para B.2; falta validar en el dispositivo y ejecutar
  `test06b`/`test12` de B.1.
- La deduplicación de filas IrisX preexistentes sigue siendo manual (ver § 11).
- No se declara B.2 cerrada: falta validación en dispositivo.


