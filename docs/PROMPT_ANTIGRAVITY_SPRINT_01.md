# Prompt de arranque para Antigravity — Sprint 01

Trabajaremos sobre el proyecto Android personal **EntrenadorLocal**, ubicado en la carpeta raíz que voy a abrir en Antigravity. El proyecto es para uso propio, offline-first, sin costos monetarios obligatorios y con una alta exigencia de confiabilidad y UX.

**Antes de ejecutar cualquier comando que pueda borrar, sobreescribir datos, cambiar configuraciones del equipo, instalar software o modificar permisos, informá los riesgos y efectos. Nunca ejecutés limpiezas automáticas ni cambios fuera de esta carpeta.**

Usá realmente **Gentle AI 4.3.1** si está disponible: recuperá el preset/metodología ODD y skills pertinentes, aplicándolos a planificación, implementación y verificación sin ceremonia innecesaria. Usá **Engram MCP** para recuperar contexto técnico relevante del proyecto y registrar decisiones estables / errores resueltos, evitando secretos o datos personales innecesarios. Si alguna herramienta no está disponible, informalo: no simules su uso.

## Tarea de este sprint

1. Leé `README.md`, `docs/ARQUITECTURA_Y_REGLAS.md` y `docs/ESTADO_Y_LIMITACIONES.md` antes de cambiar el código.
2. Realizá auditoría de la estructura, SDK Android y plugins de compilación **sin modificar otros proyectos**.
3. Comprobá compatibilidad de Android Gradle Plugin, Gradle, Compose Compiler, KSP, Room y Kotlin Built-in de AGP 9. Ajustá versiones a releases estables verificables si el build no sincroniza; documentá cualquier cambio. Evitá versiones alpha/RC salvo necesidad justificada.
4. Generá de forma segura un Gradle Wrapper oficial completo (`gradlew`, `gradlew.bat`, wrapper JAR) para la versión escogida. Nunca descargues binarios desde fuentes no verificadas.
5. Lográ `assembleDebug` + tests unitarios aprobados y revisá problemas del parser.
6. En emulador o teléfono conectado, verificá onboarding una sola vez, edición de perfil, guardado de pesajes, persistencia tras reiniciar y vista previa del parser. Si no existe dispositivo, verificá lo que se pueda y reportá explícitamente la limitación.
7. Agregá pruebas automatizadas para validaciones de entrada y persistencia si el entorno lo permite.
8. Mantené **sin permiso INTERNET**, sin acceso global a archivos, sin ejecución continua en segundo plano y sin APIs externas durante este sprint.
9. No implementes aún Gemini, LLM local, Centro de Control Windows, recordatorios o autoactualizaciones. Conservá los puntos de extensión documentados.
10. Antes de dar el sprint por terminado, entregá: archivos cambiados, comandos de prueba y resultados verificables, riesgos remanentes, instrucciones para generar/instalar el APK debug y próximos pasos precisos.

## Criterio de aceptación

- El proyecto abre y compila con Android Studio.
- El primer perfil se guarda y no vuelve a requerir onboarding luego de reiniciar.
- Los pesajes aparecen ordenados cronológicamente y siguen presentes tras reiniciar.
- El parser interpreta `Piernas\nSentadilla 80kg 8 8 7\nBuenos días 40kg 12 10 10`, `Sentadilla 85kg 8. 90kg 7`, `Laterales 7,5kg 12 11`, y reconoce alias confirmados cuando se le proporcionan.
- Los textos ambiguos generan advertencias en lugar de inventar registros.
- No se solicitan permisos innecesarios.
- No se instala ni desinstala nada sin indicarlo primero.

## Nota de seguridad de datos

La versión es experimental y **todavía no dispone de exportación de backups**. Usá datos ficticios para validación. No desinstalarla ni limpiar almacenamiento si se incorpora algún dato que interese conservar.
