# Syntra — Estado de la entrega 0.3.0 (Sprint 03)

## Implementado en código (v0.3.0 — Sprint 03: Núcleo inteligente IrisX)

### 1. Núcleo inteligente IrisX Core (`IrisXCore.kt`)
- **Arquitectura limpia y desacoplada**: Despachador determinista local de intenciones bajo el contrato `IrisXRepository` / `RoomIrisXRepository`.
- **Intenciones soportadas offline**:
  - `QueryLastWorkout`: Resumen de la última sesión (categoría, tipo de sesión, ejercicios, series totales y volumen de carga en kg).
  - `LearnAlias`: Aprendizaje de equivalencias personalizadas con guardado en `irisx_memories` y propagación a `exercise_aliases`.
  - `UpdateExerciseMuscle`: Actualización persistente del grupo muscular con registro de `CLASSIFICATION_OVERRIDE`.
  - `QueryExercise`: Consulta rápida de equipamiento, grupo principal y secundarios desde biblioteca y catálogo.
  - `ClassifySession`: Clasificación determinista de listas de ejercicios.
  - `GeneralQuery`: Manejo de saludos, ayuda de comandos y sugerencias contextuales sin modelos externos.

### 2. Clasificador inteligente multi-nivel (`IrisXClassifier.kt`)
- **Clasificación semántica profunda**:
  - Categoría principal (`primaryCategory`): Piernas, Pecho, Espalda, Hombros, Brazos, Full Body o Sin clasificar.
  - Categorías secundarias ordenadas por volumen de ejercicios.
  - Tipo de sesión (`sessionType`): Empuje (Push), Tirón (Pull), Piernas (Legs), Torso, Full Body, Brazos o Mixto.
  - Desglose por grupo muscular (`muscleBreakdown`) y detección de ambigüedad (`isAmbiguous`).
  - Generación de razonamiento explícito en lenguaje natural ("4 Pecho, 2 Tríceps, 1 Hombros. Patrón: Empuje (Push)").
- **Resolución de causa raíz de la auditoría**:
  - Sesiones con títulos genéricos ("Entrenamiento", "Gimnasio", "Sesión") ahora clasifican e inspeccionan los ejercicios reales con soporte para jerga de gimnasio ("banco plano", "inclinado hammer", "cruce de polea", "a la frente con w", "tríceps con triángulo", "camilla adelante").

### 3. Expansión del catálogo de ejercicios (`ExerciseCatalog.kt`)
- Incorporación de 28 ejercicios canónicos completos con soporte amplio para variantes de poleas, máquinas Hammer, mancuernas, barras y peso corporal.
- Auto-poblado inteligente inicial en `AppDatabase` si la tabla está vacía, respetando cualquier ejercicio existente.

### 4. Edición interactiva previa al guardado (`EditExercisePreviewDialog.kt`)
- En `ParserScreen`, cada tarjeta de ejercicio cuenta con el botón `✎ Editar`.
- Permite modificar nombre del ejercicio, series individuales (peso en kg y repeticiones), agregar/eliminar series y ajustar el objetivo planificado (`🎯 Plan`).
- Si el nombre del ejercicio fue modificado, ofrece la opción de **enseñar automáticamente a IrisX el nuevo alias** para que las próximas sesiones lo reconozcan sin intervención manual.

### 5. Biblioteca interactiva de ejercicios (`ExerciseLibraryDialog.kt`)
- Accesible desde la pestaña **Perfil** mediante tarjeta dedicada.
- Búsqueda en tiempo real por nombre, equipamiento o grupo muscular.
- Filtro por chips de grupos musculares (`Todos`, `Piernas`, `Pecho`, `Espalda`, `Hombros`, `Brazos`, `Glúteos`, `General`).
- Visualización de estado (`Confirmado` o `Pendiente`).
- Modal para creación de nuevos ejercicios y edición de grupos musculares existentes.

### 6. Consola interactiva de IrisX (`IrisXConsoleDialog.kt`)
- Botón flotante persistente `✦ IrisX` en la interfaz.
- Consola conversacional local offline con burbujas de diálogo y chips de sugerencias rápidas.
- Respuesta inmediata y determinista ejecutada directamente contra Room y SQLite.
- Con un GGUF cargado, las consultas libres se resuelven por inferencia neuronal local en
  streaming; las consultas reconocidas siguen resolviéndose de forma determinista.
- Un fallo de inferencia se muestra como burbuja de error no persistida: no se guarda como
  mensaje de IrisX, no se reenvía al modelo y la consola sigue usable.
- El pie de métricas (`⚡ t/s`) es decoración de UI y nunca forma parte del contexto enviado
  al modelo.

### 7. Ajustes visuales y ergonomía en Historial (`HistoryScreen`)
- Eliminación del título redundante de la categoría (ej. "Piernas" dentro de la categoría Piernas): la fecha y hora se enfatizan como encabezado principal.
- Corrección de espaciado y encapsulado del badge `🎯 Plan` con contenedor redondeado `Surface` para prevenir solapamiento con nombres largos.
- Clasificación de sesiones en el historial alimentada por `IrisXClassifier` cruzando sesiones con sus series y alias aprendidos.

---

## Verificado realmente

- **68 pruebas unitarias automatizadas ejecutadas mediante Gradle** (`.\gradlew.bat testDebugUnitTest`): 0 fallos.
  - 4 pruebas unitarias de `IrisXClassifierTest` (verificación de sesión de auditoría con 7 ejercicios de jerga -> Pecho / Empuje, sesiones de Tirón, Piernas con camilla/búlgaras, y alias aprendidos).
  - 4 pruebas unitarias de `IrisXCoreTest` (enrutamiento de intenciones, guardado y recuperación de alias, resumen de última sesión y consulta de catálogo).
  - 60 pruebas unitarias heredadas intactas (`WorkoutCategoryClassifierTest`, `WorkoutPresentationTest`, `MigrationTest`, `WorkoutSessionPersistenceTest`, `BackupManagerRollbackTest`, `BackupSerializerTest`, `WorkoutTextParserTest`, etc.).
- **Esquema de base de datos intacto en versión 2**: Sin migraciones destructivas ni roturas de esquemas existentes.
- **Modelo offline-first sin dependencias de red**: 0 llamadas externas o permisos de red.
