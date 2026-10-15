# Validación

## Estado

Proyecto inicializado. No hay todavía resultados acústicos ni APK funcional.

## Ciclos TDD

### NoteMapper — A4

- RED verificado: `./gradlew :dsp:test --tests '*NoteMapperTest'`.
- Fallo esperado: `Unresolved reference 'NoteMapper'` en `NoteMapperTest.kt:10`.
- GREEN verificado: el test enfocado y la suite completa `:dsp:test` terminaron con `BUILD SUCCESSFUL`.
- Implementación deliberadamente mínima: solo el caso A4 probado; el resto continúa por ciclos TDD posteriores.
- Revisión de especificación: aprobada sin bloqueos.

### Launcher Android

- RED verificado: `./gradlew :app:testDebugUnitTest --tests 'com.cuwano.afinador.LauncherBootstrapTest'`.
- Fallos esperados: `ClassNotFoundException` para `MainActivity` y ausencia de actividad `MAIN` + `LAUNCHER` en el manifiesto.
- GREEN verificado: test enfocado, suite completa de app, suite DSP y `:app:assembleDebug` terminaron correctamente.
- APK generado: `app/build/outputs/apk/debug/app-debug.apk`, paquete `com.cuwano.afinador`, SHA-256 `5cd14380a307a718bd5a89e614d398b3ea4c7a36dbbd64e92199b366d0ccf92d`.

Registrar aquí cada ciclo RED/GREEN, comandos ejecutados, resultados y validaciones pendientes en dispositivo.
