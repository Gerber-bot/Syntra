# Syntra — Estado de la entrega 0.1.0 (Sprint 01)

## Implementado en código

- Identidad definitiva: **Syntra**, versión **0.1.0**, namespace y applicationId `com.syntra.app`.
- Asistente inteligente definitivo: **IrisX** (**IrisX Core** e **IrisX Memory**).
- UI de bienvenida/perfil y edición en Jetpack Compose con Material 3 oscuro.
- Persistencia de perfil y pesajes con Room/SQLite (`syntra.db`).
- Persistencia de peso inicial en la misma transacción que crea el primer perfil.
- Tablas iniciales de ejercicios, alias, sesiones y series (sin flujo de guardado en este sprint).
- Parser determinista (`WorkoutTextParser`):
  - Detección de títulos de sesión y ejercicios con múltiples series y cargas.
  - Reconocimiento de directivas explícitas para el asistente IrisX (`IrisX: ...`, `@irisx ...`).
  - Desambiguación semántica entre comandos de IrisX y ejercicios con nombres homónimos («buenos días»).
- Módulo de validación de dominio desacoplado de la UI (`InputValidators`) para estaturas y pesajes.
- Gradle Wrapper oficial completo generado con Gradle 9.6.0 (`gradlew`, `gradlew.bat`, `gradle-wrapper.jar`).

## Verificado realmente

- Gradle Wrapper 9.6.0 operativo: invocación y descarga de distribución verificadas.
- Compilación y ejecución exitosa de **16 pruebas unitarias automatizadas** con JUnit 4.13.2 (0 fallos):
  - Intérprete de entrenamientos: series con carga única, múltiples ejercicios en sesión ("Piernas\nSentadilla 80kg 8 8 7\nBuenos días 40kg 12 10 10"), cambios de peso en la misma línea ("Sentadilla 85kg 8. 90kg 7"), decimales con coma ("Laterales 7,5kg 12 11"), y resolución de alias con insensibilidad a mayúsculas/tildes.
  - Desambiguación de IrisX vs. ejercicios: `Buenos días 40kg 12 10 10` se reconoce como ejercicio deportivo puro; `IrisX: ...` se extrae como directiva del asistente; `Buenos días` sin datos genera advertencia orientadora.
  - Comportamiento conservador: generación de advertencias ante pesos fuera de rango (<= 0 o > 1000 kg), omisión de nombre de ejercicio o falta de series; nunca se inventan datos.
  - Validaciones de entrada: parseo estricto de pesajes (rango 20–400 kg), formateo a 2 decimales y validación de perfiles (estatura 100–250 cm).

## No verificado en este host / limitaciones de entorno

- **APK Android no ensamblado localmente**: el entorno de terminal carece de Android SDK (no se detectó variable `ANDROID_HOME`, `sdkmanager` ni plataformas API 37) y no hay dispositivo/emulador conectado (`adb` ausente).
- Las pruebas de ciclo de vida en Android Studio (reinicio de app, persistencia en SQLite real del emulador/dispositivo) deben correrse abriendo el proyecto en Android Studio con el SDK instalado.
- No se exportan ni importan backups todavía.
- No existen recordatorios, notificaciones, servicios en segundo plano ni modelos neuronales.

## Matriz de compatibilidad de build

- **Gradle**: 9.6.0 (wrapper configurado).
- **AGP**: 9.5.0 (cuenta con soporte Built-in Kotlin nativo).
- **Compose Compiler**: 2.4.10 (`org.jetbrains.kotlin.plugin.compose`).
- **KSP**: Al usar Kotlin 2.4.10, la versión de `com.google.devtools.ksp` debe mantenerse alineada a la misma línea base de Kotlin.
- **Java**: JDK 17 mínimo (entorno probado sobre OpenJDK 25).
