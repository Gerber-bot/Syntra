# Syntra — Decisiones de arquitectura y reglas del proyecto

## Principios no negociables

1. Todo componente esencial funciona offline y sin tarifa por consulta.
2. La base local (`syntra.db`) es la fuente de verdad; ninguna IA escribe directamente en ella.
3. El asistente **IrisX** propone y la aplicación valida. Cambios destructivos o ambiguos requieren confirmación.
4. Memoria personal, correcciones, conocimiento de ejercicios y conversaciones se almacenan por separado de los datos deportivos (**IrisX Memory**).
5. Un aprendizaje se representa inicialmente como reglas/alias/recuerdos explícitos, **no** como ajuste automático de pesos neuronales.
6. Resource Guardian es independiente de IrisX, con capacidad de cortar operaciones intensivas.
7. Permisos mínimos. La activación de cada capacidad sensible requiere autorización apropiada.
8. No acceder a Internet sin una decisión explícita de la persona usuaria en la configuración de esa funcionalidad.
9. Actualizaciones con migraciones y backups verificados; no borrar datos históricos.
10. Presupuesto CPU/RAM/energía y watchdogs antes de habilitar modelos neuronales locales.

## Modelo modular esperado

- `profile`: onboarding, preferencias y configuración persistente.
- `bodymetrics`: pesajes, futuras medidas, recordatorios opcionales.
- `training`: ejercicios, variantes, sesiones, series y análisis.
- `interpreter`: texto -> propuesta estructurada -> corrección -> confirmación.
- `irisx-core`: orquestación determinista, router de intenciones y asistente conversacional.
- `irisx-memory`: hechos, preferencias, experiencias y procedencia de correcciones.
- `safety` (Resource Guardian): límites, diagnósticos, permisos, auditoría y rollback.
- `backup`: importación/exportación versionadas mediante SAF.
- `desktop-link`: emparejamiento, consulta de diagnósticos y mantenimiento autenticado.

## Diferencias de conceptos y desambiguación de lenguaje

- **Desambiguación de comandos vs. ejercicios**:
  - El asistente inteligente se denomina **IrisX** para eliminar colisiones homónimas con expresiones cotidianas (como «buenos días» o «día de pierna»).
  - Todo comando conversacional o directiva hacia el asistente se canaliza mediante prefijo explícito (`irisx: ...`, `@irisx ...`, o pantalla de chat dedicada).
  - Frases como `Buenos días 40kg 12 10 10` son inequívocamente interpretadas por el parser como el ejercicio **Buenos días** (good mornings), debido a la presencia de unidades de carga (`kg`) y estructura de series.
  - Expresiones aisladas como `Buenos días` sin datos deportivos generan advertencia aclaratoria diferenciando el saludo/asistente del ejercicio.
- Un ejercicio pertenece a una **biblioteca**, no a un día fijo de entrenamiento.
- Una sesión tiene un nombre o enfoque (por ejemplo, Piernas), y las series apuntan a ejercicios existentes.
- Variantes como `Buenos días con barra` y `Buenos días con mancuernas` se distinguen.
- Las abreviaturas confirmadas se guardan como alias normalizados.
- Una disminución de repeticiones no prueba, por sí sola, fatiga acumulada.
- El registro de peso es histórico: no sobrescribir pesajes anteriores.
- Los recordatorios son opcionales, se solicitan cuando el usuario activa la función y no requieren un polling permanente.

## Progresión de entregas

- Sprint 1: base Android (Syntra 0.1.0), perfil y pesajes persistentes, parser preview, suite de pruebas unitarias.
- Sprint 2: sesiones completas, biblioteca de ejercicios/alias, validación previa y guardado atómico.
- Sprint 3: IrisX Core (despachador determinista de intenciones), IrisX Memory (aprendizaje de correcciones); estadísticas deportivas.
- Sprint 4: recordatorios y backups, diagnóstico local, protección de recursos (Resource Guardian).
- Sprint 5: Centro de Control Windows y conexión local autenticada.
- Sprint 6: coach algorítmico y, posteriormente, LLM local opcional con benchmarks.

La secuencia puede cambiar si las pruebas del dispositivo revelan restricciones.

## Herramientas locales de consulta (Etapa B.2)

IrisX responde preguntas sobre datos deportivos reales mediante una capa de **herramientas
locales tipadas y de sólo lectura** (`com.syntra.app.domain.irisx.tools`). No hay SQL libre
generado por el modelo, no hay escrituras automáticas y no hay acceso a Internet.

Cadena de responsabilidades, en este orden y sin solapamientos:

| Etapa | Responsable | Regla |
|---|---|---|
| Interpretación de intención | `IrisXCore.parseInput` → `IrisXIntent` | Determinista; normaliza acentos y puntuación |
| Selección de herramienta | `IrisXCore` (`when` sobre la intención) | Una intención → una herramienta |
| Consulta a datos | `IrisXLocalTools` sobre `IrisXRepository` | Única fuente: Room o un doble de prueba |
| Cálculo determinista | `IrisXAnalytics` / `SportsMetricsCalculator` | Nunca mezcla modalidades de carga incompatibles |
| Presentación | `LocalToolPresenter` | Sólo repite el payload; no inventa números |
| Redacción opcional | Qwen3 (camino neuronal) | Puede reformular, nunca aportar cifras nuevas |

Herramientas disponibles:

- `LastWorkout`: sesión más reciente con su conteo real de series y ejercicios.
- `LastExerciseSession`: última sesión que contiene un ejercicio, con series, mejor serie,
  repeticiones totales y sesión anterior comparable.
- `ExerciseHistory`: sesiones anteriores del ejercicio, de la más nueva a la más vieja, con
  `requireComparison` para distinguir «mostrame» de «compará».

Resultados tipados (`LocalToolOutcome`), deliberadamente separados porque la conversación debe
poder decir cosas distintas:

- `Success<T>`: payload verificado + lista de hechos derivados sólo de él.
- `NoData`: el ejercicio o el entrenamiento no existe en la base local.
- `InsufficientData`: existe, pero no alcanza para responder (sesión sin series, una sola
  sesión para comparar, modalidades incompatibles).
- `QueryError`: petición ambigua/irresoluble o fallo de lectura recuperable.

Reglas de resolución de nombres (`ExerciseNameResolver`):

1. Un alias aprendido se normaliza y se traduce a su nombre canónico.
2. El nombre (crudo o canónico) debe coincidir exactamente con `displayName` o
   `normalizedName` de un ejercicio.
3. No hay coincidencia aproximada: «press banca inclinado» nunca resuelve a «press de banca».
4. Dos filas con el mismo nombre normalizado producen ambigüedad explícita, no una elección
   silenciosa.

Los fallos también se devuelven como `IrisXResponse.Success` con un `actionTaken` propio
(`..._NO_DATA`, `..._INSUFFICIENT_DATA`, `..._ERROR`) para que la consola muestre el mensaje
recuperable sin tratarlo como excepción. La cancelación del chat se propaga
(`CancellationException`), nunca se convierte en error de consulta.

Esta capa no modifica el esquema (DB v8 intacta) y vive en el código compartido entre la
variante `debug` y `lab`, por lo que es reutilizable tal cual cuando exista una única APK
`com.syntra.app`.
