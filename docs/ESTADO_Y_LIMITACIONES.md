# Syntra — Estado de la entrega 0.2.0 (Sprint 02)

## Implementado en código (v0.2.0)

### FASE 1 — Seguridad y backups (SAF + JSON)
- Implementada exportación completa a formato JSON estándar versionado (`formatVersion = 1`, `appVersion = "0.2.0"`).
- Integración nativa con Storage Access Framework (`ActivityResultContracts.CreateDocument` y `ActivityResultContracts.OpenDocument`): no requiere permisos generales de almacenamiento ni acceso a internet.
- Validación estructural profunda previa a la restauración: rechaza JSONs vacíos, corruptos, versiones incompatibles o tipos inválidos.
- Diálogo de confirmación explícita antes de restaurar, detallando fecha del respaldo, perfil y cantidad de registros.
- Restauración transaccional atómica (`db.withTransaction`): todo o nada, sin restauraciones parciales y preservando los datos existentes en caso de error.

### FASE 2 — Biblioteca de ejercicios y catálogo canónico
- Modelo relacional normalizado:
  - `ExerciseEntity`: id, `displayName`, `normalizedName` (único), `muscleGroup`, `secondaryMuscleGroups`, `equipment`, `exerciseType`, `status`, `createdAtMillis`.
  - `ExerciseAliasEntity`: id, `exerciseId`, `normalizedAlias` (único), `createdAtMillis`.
- Creado catálogo inicial (`ExerciseCatalog.DEFAULT_CATALOG`) con 14 ejercicios estándar (Sentadilla, Press de banca, Peso muerto, Peso muerto rumano, Remo con barra, Dominadas, etc.) y sus grupos musculares y alias canónicos.
- Detección de ejercicios desconocidos como `isNewExercise = true` para propuesta e incorporación en la biblioteca sin bloquear el análisis.

### FASE 3 — Registro persistente de entrenamientos
- Entidades relacionales para registro de sesiones deportivas:
  - `WorkoutSessionEntity`: id, `title`, `startedAtMillis`, `finishedAtMillis`, `notes`.
  - `WorkoutSetEntity`: id, `sessionId`, `exerciseId`, `orderInExercise`, `executionOrder`, `loadGrams`, `repetitions`, `rir`, `isAssisted`, `isDumbbell`, `note`, `plannedTarget`.
- Almacenamiento transaccional de sesiones completas en SQLite preservando orden cronológico y series individuales.
- Formato visual compacto (`CompactExercise` y `CompactSetGroup`): `Series | Peso | Repeticiones` con sombreado sutil alternado y tabla de una única tarjeta por ejercicio.

### FASE 4 — Interpretación avanzada de entrenamientos
- `WorkoutTextParser` evolucionado para distinguir:
  1. Nombre canónico del ejercicio (sin contaminarlo con la planificación).
  2. Planificación prevista (`plannedTarget`, ej. `3 de 10 a 12` $\rightarrow$ `3 series de 10–12 repeticiones`).
  3. Series realizadas, cargas efectivas y repeticiones.
  4. Expresiones ambiguas (ej. `Dominadas 3 + 6`): no inventa interpretaciones y genera advertencia conservadora para aclaración del usuario.
  5. Cargas modificadas: mancuernas por brazo (`isDumbbell`), pesos asistidos (`isAssisted`) y pesos añadidos/lastre.
  6. Mapeo automático de alias de IrisX Memory (ej. `RDL` $\rightarrow$ `Peso muerto rumano`).

### FASE 5 — Vista previa y confirmación de guardado
- En `ParserScreen`, muestra los ejercicios interpretados, distintivos de planificación (`🎯 Plan`), advertencias y ejercicios nuevos (`✨ Nuevo`).
- Botón **"💾 Guardar entrenamiento en historial"** con diálogo modal de confirmación detallando ejercicios, series y nuevos elementos a incorporar.
- Guardado atómico en SQLite que crea ejercicios si no existían y persiste las series con su orden de ejecución.

### FASE 6 — Historial de entrenamientos
- Nueva pantalla `HistoryScreen` accesible desde la barra de navegación (`📋 Historial`):
  - Listado de entrenamientos por fecha (`startedAtMillis DESC`).
  - Tarjetas con título, fecha, contador de series y desglose expandible por ejercicio.
  - Renderizado de tablas compactas con cargas y repeticiones.
  - Eliminación segura con diálogo de confirmación transaccional atómico.
  - Enlace rápido a `Historial` desde la pantalla de Inicio.

### FASE 7 — Preparación de IrisX Memory
- Nueva entidad `IrisxMemoryEntity`: id, `memoryType` (`ALIAS`, `CORRECTION`, `PREFERENCE`), `sourceKey`, `targetValue`, `confidence`, timestamps.
- Separación estricta de la memoria respecto del historial deportivo.
- Sección "IrisX Memory" en la pantalla de Perfil para auditar, consultar y eliminar equivalencias confirmadas.

---

## Verificado realmente

- **53 pruebas unitarias automatizadas ejecutadas mediante Gradle** (`.\gradlew.bat test`): 0 fallos.
  - Pruebas de migración no destructiva SQLite v1 $\rightarrow$ v2 con JDBC SQLite en memoria (`MigrationTest`).
  - Pruebas de serialización, deserialización, validación e integridad de respaldos (`BackupSerializerTest`).
  - Pruebas de persistencia transaccional y orden de series de entrenamientos (`WorkoutSessionPersistenceTest`).
  - Pruebas de interpretación avanzada, planificación, ambigüedades y modificadores (`WorkoutTextParserSprint02Test`).
  - Pruebas de regresión del parser, desambiguación con IrisX y formato de presentación (`WorkoutTextParserTest`, `WorkoutPresentationTest`, `InputValidatorsTest`).
- **Compilación exitosa del APK debug v0.2.0** (`.\gradlew.bat :app:assembleDebug`).
- **Inspección del binario** (`apkanalyzer`):
  - Ubicación: `app/build/outputs/apk/debug/app-debug.apk` (12,240,402 bytes)
  - Paquete: `com.syntra.app`
  - VersionCode: `4`
  - VersionName: `0.2.0`
  - Permisos: Exclusivamente `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. Sin `INTERNET` ni permisos de almacenamiento externo.

---

## Matriz de compatibilidad y migración

- **Gradle**: 9.6.0 (wrapper configurado).
- **AGP**: 9.5.0 (Kotlin Built-in nativo).
- **Compose Compiler**: 2.4.10.
- **Android SDK**: API 37 (`android-37.0`), Build-Tools 35.0.0 / 36.0.0 aislados en `.android-sdk`.
- **Room Migration**: `MIGRATION_1_2` con sentencias `ALTER TABLE ... ADD COLUMN` y `CREATE TABLE IF NOT EXISTS irisx_memories`. No destructiva; conserva intactos los perfiles y pesajes de la v0.1.2.
