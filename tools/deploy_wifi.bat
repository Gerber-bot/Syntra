@echo off
chcp 65001 >nul
echo ========================================================
echo   SYNTRA — Despliegue Inalambrico ADB (Wi-Fi)
echo ========================================================
echo.

if "%1"=="" (
    echo Uso: deploy_wifi.bat ^<IP_DEL_TELEFONO^>:^<PUERTO^>
    echo Ejemplo: deploy_wifi.bat 192.168.1.50:38475
    echo.
    echo Pasos en tu Redmi Note 13:
    echo  1. Ajustes -^> Opciones de desarrollador.
    echo  2. Activar "Depuracion inalambrica".
    echo  3. Tocar "Vincular dispositivo con codigo de vinculacion" (la primera vez).
    echo     o mirar la IP y puerto mostrados en la pantalla.
    echo.
    set /p PHONE_ADDR="Ingresa IP:PUERTO del telefono: "
) else (
    set PHONE_ADDR=%1
)

echo.
echo [1/3] Conectando por ADB Wi-Fi a %PHONE_ADDR%...
adb connect %PHONE_ADDR%

echo.
echo [2/3] Verificando dispositivo...
adb devices

echo.
echo [3/3] Instalando APK (actualizacion segura sin borrar datos: -r)...
if exist "app\build\outputs\apk\debug\app-debug.apk" (
    adb -s %PHONE_ADDR% install -r app\build\outputs\apk\debug\app-debug.apk
    echo.
    echo [EXITO] Syntra actualizado de forma inalambrica conservando la base SQLite.
) else (
    echo [ERROR] No se encontro app\build\outputs\apk\debug\app-debug.apk.
    echo Compila primero con: .\gradlew.bat :app:assembleDebug
)

echo.
pause
