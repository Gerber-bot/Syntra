# Syntra — Estado de la entrega 0.1.1

## Implementado en código (v0.1.1)

- **Corrección integral de onboarding y perfil**:
  - Resuelto problema de RadioButtons sin etiquetas visibles: implementado componente `SelectableOptionRow` con etiquetas en blanco de alto contraste (`Color.White`), área táctil de ancho completo accesible y `Role.RadioButton`.
  - Tratamiento correcto de `WindowInsets.safeDrawing` y `enableEdgeToEdge()` en `MainActivity`: el contenido ya no colisiona con la barra de estado, cutouts de cámara ni navegación del sistema en Android 15.
  - Soporte de teclado virtual (`imePadding()`): los campos numéricos de estatura y peso desplazan el formulario evitando que el teclado tape los botones de acción.
  - Jerarquía visual compacta: agrupación en tarjetas por sección (Datos personales, Actividad, Objetivo) con espaciados moderados y legibles.
- **Formato compacto oficial de entrenamientos**:
  - Implementado `WorkoutPresentation` (`toCompact` y `groupConsecutiveSets`) que agrupa series consecutivas con la misma carga (`Series | Peso | Repeticiones`).
  - Conservación estricta del orden cronológico y separación de cargas idénticas no consecutivas.
  - Formateo de pesos con coma decimal en español evitando ceros redundantes (`70 kg`, `7,5 kg`).
  - Presentación en tarjetas con tabla estructurada de cabeceras y divisores claros.
- **Persistencia y datos**:
  - `applicationId = "com.syntra.app"`, base de datos `syntra.db` intacta en versión 1.
  - Incremento de versión: `versionCode = 2`, `versionName = "0.1.1"`.
  - Compatible 100% con la instalación anterior sin pérdida de datos ni migraciones destructivas.

## Verificado realmente

- **36 pruebas unitarias automatizadas ejecutadas mediante Gradle** (`.\gradlew.bat test`): 0 fallos.
  - Cobertura de agrupación de series consecutivas, cargas repetidas no consecutivas, formateo de pesos decimales, series únicas y casos borde.
  - Cobertura de parser y desambiguación con IrisX.
  - Cobertura de validaciones de dominio de estatura y peso.
- **Compilación exitosa del APK debug v0.1.1** (`.\gradlew.bat :app:assembleDebug`).
- **Inspección del binario** (`apkanalyzer`):
  - Paquete: `com.syntra.app`
  - VersionCode: `2`
  - VersionName: `0.1.1`
  - Permisos: Exclusivamente `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`. Sin `INTERNET` ni acceso a archivos externos.

## Matriz de compatibilidad de build

- **Gradle**: 9.6.0 (wrapper configurado).
- **AGP**: 9.5.0 (Kotlin Built-in nativo).
- **Compose Compiler**: 2.4.10.
- **Android SDK**: API 37 (`android-37.0`), Build-Tools 35.0.0 / 36.0.0 instalados de forma aislada en `.android-sdk`.
