# Syntra — Estado de la entrega 0.1.2

## Implementado en código (v0.1.2)

- **Analizar: una sola tarjeta por ejercicio con tabla estructurada**:
  - Implementado `WorkoutPresentation.toCompactList(...)` para consolidar múltiples bloques o líneas de un mismo ejercicio en una única tarjeta visual `CompactExercise`.
  - Dentro de la tarjeta se renderiza la tabla de series agrupadas consecutivas (`Series | Peso | Repeticiones`).
  - Sombreado sutil alternado (`Color(0xFF1C1C1C)`) y divisores limpios para facilitar la lectura en pantalla móvil.
  - Se respeta estrictamente el orden de ejecución y no se fusionan bloques no consecutivos aunque compartan carga.
  - El modelo interno de series (`ParsedSet`) se preserva íntegro e inalterado.
- **Perfil: vista y edición separadas**:
  - **Modo lectura (por defecto)**: Muestra nombre completo, apodo para IrisX, estatura, actividad cotidiana, objetivo principal, bloque "Tu plan y enfoque" y botón de acción "Editar perfil".
  - **Modo edición**: Solo se abre al pulsar "Editar perfil". Permite modificar los datos, guardar cambios (persistiendo y retornando a lectura) o "Volver sin cambios" (cancelando sin modificar el estado guardado).
- **Reorganización de Inicio**:
  - El bloque "Tu plan y enfoque" se reubicó en Perfil como resumen estable.
  - La pantalla Inicio queda más ágil y orientada a la acción: saludo personalizado, tarjeta de último peso registrado, contador de pesajes, accesos directos ("⚖ Registrar peso", "✎ Analizar sesión") y estado de IrisX Core.
- **Persistencia y datos**:
  - `applicationId = "com.syntra.app"`, base de datos `syntra.db` intacta en versión 1.
  - Incremento de versión: `versionCode = 3`, `versionName = "0.1.2"`.
  - Actualización limpia sobre v0.1.1 sin pérdida de datos ni migraciones destructivas.

## Verificado realmente

- **37 pruebas unitarias automatizadas ejecutadas mediante Gradle** (`.\gradlew.bat test`): 0 fallos.
  - Cobertura de consolidación en una sola tarjeta por ejercicio (`toCompactList`), múltiples ejercicios intercalados, agrupación consecutiva y mantenimiento de orden.
  - Cobertura de parser y desambiguación con IrisX.
  - Cobertura de validaciones de dominio de estatura y peso.
- **Compilación exitosa del APK debug v0.1.2** (`.\gradlew.bat :app:assembleDebug`).
- **Inspección del binario** (`apkanalyzer`):
  - Ubicación: `app/build/outputs/apk/debug/app-debug.apk`
  - Paquete: `com.syntra.app`
  - VersionCode: `3`
  - VersionName: `0.1.2`
  - Permisos: Exclusivamente `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. Sin permisos invasivos ni de red (offline-first).

## Matriz de compatibilidad de build

- **Gradle**: 9.6.0 (wrapper configurado).
- **AGP**: 9.5.0 (Kotlin Built-in nativo).
- **Compose Compiler**: 2.4.10.
- **Android SDK**: API 37 (`android-37.0`), Build-Tools 35.0.0 / 36.0.0 instalados de forma aislada en `.android-sdk`.
