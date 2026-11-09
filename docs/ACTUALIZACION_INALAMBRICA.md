# Guía de Actualización Inalámbrica de Syntra

Syntra es una aplicación **100% offline-first y privada**. No cuenta con permisos de red externa ni telemetría en segundo plano.

Para actualizar la aplicación sin necesidad de conectar el cable USB cada vez, disponés de dos métodos seguros que **preservan íntegramente tu base de datos SQLite y tus marcas deportivas**.

---

## ⚠️ Regla de Oro: NUNCA desinstales la aplicación para actualizar

Android elimina automáticamente el directorio `/data/data/com.syntra.app/` (y con él la base de datos interna SQLite) cuando una aplicación es desinstalada manualmente.

- **Forma correcta:** Instalar la nueva versión por encima de la anterior (flag `-r` en ADB o instalador nativo del sistema).
- **Prevención:** Antes de cualquier actualización mayor, podés ir a **Perfil → Seguridad y copias de seguridad → Exportar JSON** para tener un respaldo en tu almacenamiento o Google Drive.

---

## Método 1 — ADB Inalámbrico (Recomendado para desarrollo)

Android 11+ (incluyendo Android 14 y HyperOS / Android 15 en tu Redmi Note 13) incluye **Depuración inalámbrica** nativa sin cables.

### Pasos iniciales en el teléfono (una sola vez por red):
1. Andá a **Ajustes → Opciones de desarrollador**.
2. Activá **Depuración inalámbrica**.
3. Tocá en **"Vincular dispositivo con código de vinculación"**. Verás una IP, un puerto de vinculación (ej. `192.168.1.50:41235`) y un código de 6 dígitos.
4. En tu PC ejecutá:
   ```cmd
   adb pair 192.168.1.50:41235
   # Ingresá el código de 6 dígitos cuando lo solicite
   ```

### Actualización inalámbrica en 1 clic:
Una vez vinculado, cada vez que compiles una nueva versión:
1. Verificá la IP y puerto que muestra la pantalla de Depuración inalámbrica (ej. `192.168.1.50:38475`).
2. Ejecutá:
   ```cmd
   tools\deploy_wifi.bat 192.168.1.50:38475
   ```
   El script se conecta automáticamente e instala el APK con `adb install -r`, conservando todas tus sesiones y modelos.

---

## Método 2 — Centro de Actualizaciones Locales (En la app)

1. Compilá el APK en tu PC (`.\gradlew.bat :app:assembleDebug`).
2. Compartí el archivo `app-debug.apk` a tu teléfono mediante **Quick Share**, **LocalSend** o tu método preferido en la misma red Wi-Fi.
3. En Syntra, andá a **Perfil → Centro de Actualizaciones Locales**.
4. Tocá **"Seleccionar APK para actualizar"** y elegí el archivo descargado.
5. Syntra verificará que el paquete sea `com.syntra.app` y abrirá el instalador oficial de Android para aplicar la actualización sin perder datos.
