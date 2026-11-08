# Syntra — Asistente inteligente y privado de entrenamiento (0.1.0)

**Objetivo:** aplicación Android nativa, privada, offline-first y gratuita para registrar entrenamientos, pesajes y, progresivamente, un asistente inteligente con memoria y aprendizaje incremental denominado **IrisX** (**IrisX Core** e **IrisX Memory**).

**Identificador de aplicación:** `com.syntra.app`. **Estado:** Sprint 01 completado a nivel de arquitectura, código de dominio, wrapper y pruebas unitarias.

## Qué contiene esta entrega

- Proyecto fuente Android en Kotlin / Jetpack Compose con namespace `com.syntra.app`.
- Perfil inicial persistente: nombre, apodo, altura, peso de inicio, actividad y objetivo; edición posterior.
- Registro local de pesajes con historial ordenado cronológicamente (`weigh_ins` en `syntra.db`); la creación del perfil genera un primer pesaje transaccional.
- Diseño de tablas Room/SQLite para ejercicios, alias, sesiones y series.
- Intérprete determinista de texto de entrenamiento (`WorkoutTextParser`): genera vista previa sin escribir datos, reconociendo alias y distinguiendo comandos de ejercicios como «buenos días».
- Módulo de validación de dominio desacoplado de la interfaz (`InputValidators`).
- Gradle Wrapper oficial completo generado con Gradle 9.6.0 (`gradlew`, `gradlew.bat` y `gradle-wrapper.jar`).
- Suite de 14 pruebas unitarias JUnit automatizadas aprobadas.

## Requisitos en Windows

1. Android Studio moderno compatible con AGP 9.x y JDK 17+ (o Android SDK Command-Line Tools).
2. Android SDK API 37 y SDK Build Tools compatibles.
3. Gradle Wrapper 9.6.0 provisto en el repositorio (`.\gradlew.bat`).
4. Conexión inicial para resolver dependencias oficiales de Gradle/Google Maven; después, la app no requiere Internet para usarse.

## Advertencias de seguridad y datos

- **No desinstalar ni borrar datos de la app si se usan registros importantes**: aún no existe backup/importación (previsto para Sprint 04). `allowBackup=false` evita sincronización involuntaria con servicios en la nube.
- No hay permisos de Internet, ubicación, micrófono ni almacenamiento compartido en el manifiesto.
- No hay modelos neuronales, procesos pesados en segundo plano ni alarmas intrusivas en esta versión.
- La vista previa del parser no confirma equivalencias definitivas en base de datos. Los alias se inyectan en vista previa para demostrar la lógica.

## Arquitectura de IrisX y Desambiguación

- **IrisX**: Asistente inteligente privado que actúa como copiloto determinista antes de incorporar LLMs locales.
- **IrisX Core**: Módulo orquestador que clasifica intenciones y comandos directos (ej. `irisx: ...`).
- **IrisX Memory**: Capa de memoria aislada para hechos, preferencias y correcciones confirmadas.
- **Desambiguación**: Nombres de ejercicios como «Buenos días» (good mornings) con estructura de series (ej. `Buenos días 40kg 12 10 10`) son reconocidos inequívocamente como ejercicios y nunca colisionan con comandos ni saludos al asistente.

## Carpetas del proyecto

- `app/src/main/java/com/syntra/app/data`: entidades Room y DAOs (`syntra.db`).
- `app/src/main/java/com/syntra/app/domain`: intérprete (`WorkoutTextParser`) y validadores (`InputValidators`).
- `app/src/main/java/com/syntra/app/MainActivity.kt`: UI Jetpack Compose.
- `app/src/test/java/com/syntra/app/domain`: pruebas unitarias JUnit.
- `docs/`: arquitectura, lineamientos y registro de estado.
