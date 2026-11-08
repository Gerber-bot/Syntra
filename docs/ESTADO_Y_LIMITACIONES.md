# Syntra — Estado de la entrega 0.2.1

## Implementado en código (v0.2.1)

### Reorganización jerárquica del historial deportivo
- **Nivel 1: Pantalla principal de categorías**:
  - Clasificación en categorías estructuradas: Piernas (🦵), Pecho (🛡️), Espalda (🏹), Hombros (🏋️), Brazos (💪), Full Body (⚡), Otras / Sin clasificar (📁) y categoría general Todas (★).
  - Cada categoría presenta su nombre, contador de sesiones y fecha del entrenamiento más reciente.
  - Estados vacíos informativos con botón directo para analizar e iniciar un nuevo entrenamiento.
- **Nivel 2: Sesiones por categoría y fecha**:
  - Al pulsar una categoría, se despliegan sus sesiones ordenadas cronológicamente desde la más reciente (`startedAtMillis DESC`).
  - Navegación superior con botón de retorno `← Categorías` y encabezado contextual.
  - Cada tarjeta de sesión muestra fecha/hora, título, contador de ejercicios y series totales.
- **Nivel 3: Detalle de sesión en formato oficial**:
  - Vista detallada por sesión que mantiene una única tarjeta por ejercicio (`CompactExercise`).
  - Tabla de series agrupadas consecutivas (`Series | Peso | Repeticiones`) con sombreado sutil alternado.
  - Conservación del orden de ejecución y diálogo de eliminación segura con confirmación atómica.
- **Clasificador determinista (`WorkoutCategoryClassifier`)**:
  - Normalización de tildes, mayúsculas y singular/plural sin alterar la base de datos.
  - Detección de palabras clave y fallback a grupos musculares de ejercicios para títulos genéricos.
  - Clasificación conservadora: nombres ambiguos o no reconocidos van a "Otras / Sin clasificar".
  - **Cero cambios de esquema de base de datos**: Se mantiene `syntra.db` en versión 2 intacta, preservando perfiles, pesajes, ejercicios, sesiones, series, alias e IrisX Memory con 100% de compatibilidad.

---

## Verificado realmente

- **60 pruebas unitarias automatizadas ejecutadas mediante Gradle** (`.\gradlew.bat test`): 0 fallos.
  - Cobertura de clasificación de categorías exactas y derivadas (`WorkoutCategoryClassifierTest`).
  - Cobertura de clasificación de títulos genéricos según grupos musculares de ejercicios.
  - Cobertura de sesiones sin clasificar para títulos ambiguos o vacíos.
  - Cobertura de agregación de resúmenes (`buildSummaries`), contadores y timestamps más recientes.
  - Cobertura de orden cronológico en sesiones del mismo día.
  - Cobertura de pruebas de migración no destructiva y rollback de respaldos SAF (`MigrationTest`, `BackupManagerRollbackTest`, `BackupSerializerTest`).
  - Cobertura de persistencia transaccional y analizador de entrenamientos (`WorkoutSessionPersistenceTest`, `WorkoutTextParserSprint02Test`).
- **Compilación exitosa del APK debug v0.2.1** (`.\gradlew.bat :app:assembleDebug`).
- **Inspección del binario** (`apkanalyzer` y `apksigner`):
  - Ubicación: `app/build/outputs/apk/debug/app-debug.apk` (12,256,786 bytes)
  - Paquete: `com.syntra.app`
  - VersionCode: `5`
  - VersionName: `0.2.1`
  - Permisos: Exclusivamente `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. Sin permisos invasivos ni de red.
  - Firma: Certificado debug SHA-256 idéntico a las entregas anteriores.

---

## Matriz de compatibilidad

- **Gradle**: 9.6.0 (wrapper configurado).
- **AGP**: 9.5.0 (Kotlin Built-in nativo).
- **Compose Compiler**: 2.4.10.
- **Android SDK**: API 37 (`android-37.0`), Build-Tools 35.0.0 / 36.0.0 aislados en `.android-sdk`.
- **Room Database**: Versión 2 (sin cambios de esquema respecto a v0.2.0; retrocompatibilidad total garantizada).
