# Afinador

Afinador cromático Android offline de una sola pantalla. Paquete
`com.cuwano.afinador`.

## Estado

Implementado y verificado en host y en emulador. El APK debug es instalable
manualmente y no requiere ADB ni conexión a Internet.

- Detector: salience armónica como estimador primario, YIN como contraste y control
  de octava.
- Captura: `AudioRecord` mono bloqueante con fallback 48 000 → 44 100 Hz.
- Rango: 40–1500 Hz, A4 = 440 Hz fijo, notas con sostenidos.
- Interfaz oscura de una pantalla con tarjeta central, aguja de ±50 cents, estados de
  permiso, silencio y error, y textos en español.
- El micrófono se libera al salir de primer plano: no hay grabación en background.

Detalle de ciclos TDD, corpus, latencias y evidencia en emulador:
[docs/validation.md](docs/validation.md). Constantes y justificación:
[docs/dsp-decisions.md](docs/dsp-decisions.md). Instalación manual y ficha de pruebas:
[docs/instalacion.md](docs/instalacion.md). Registros en bruto (instrumentación,
captura de audio, benchmark, verificación del APK, capturas y volcados de UI):
[docs/evidence/](docs/evidence/).

Pendiente: aceptación acústica real en el teléfono del usuario (no probada).

## Requisitos de compilación

- JDK 17.
- Android SDK Platform 37 y Build Tools 36.0.0.
- El wrapper descarga Gradle 9.5.0 con checksum fijado.
- `ANDROID_HOME` debe apuntar al SDK.

## Módulos

- `:dsp`: Kotlin/JVM puro. Toda la cadena de detección, sin dependencias de Android,
  para poder probarla rápido y de forma reproducible.
- `:app`: Compose, captura y sesión. No contiene algoritmos.

## Verificación

```bash
export JAVA_HOME=$(/usr/local/bin/mise where java@17.0.2)
export ANDROID_HOME=/workspace/tools/android-sdk

./gradlew --no-daemon --no-configuration-cache :dsp:test :app:testDebugUnitTest
./gradlew --no-daemon --no-configuration-cache :app:lintDebug :app:assembleDebug
```

Con emulador o dispositivo disponible, los tests de instrumentación cubren la UI y el
motor en tiempo de ejecución de Android:

```bash
./gradlew --no-daemon --no-configuration-cache :app:assembleDebugAndroidTest
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -r com.cuwano.afinador.test/androidx.test.runner.AndroidJUnitRunner
```

`am instrument` puede devolver éxito de shell aunque JUnit falle: hay que leer el
resumen (`OK (N tests)`) y los códigos `INSTRUMENTATION_STATUS_CODE`.

## APK

```text
app/build/outputs/apk/debug/app-debug.apk
```

APK debug firmado con la clave de depuración (no sirve para publicar en tiendas).

- Tamaño: 11,53 MB (`11 534 209` bytes).
- SHA-256: `a6cc1185e9f941be86230f277d9c1518cfd51ff8c216006f2a4c1220a2a1696a`.
- Verificación de firma, manifiesto y permisos: [docs/evidence/apk-verification.txt](docs/evidence/apk-verification.txt).
- Instalación sin ADB: [docs/instalacion.md](docs/instalacion.md).

## Restricciones respetadas

- Sin red, sin cuentas, sin anuncios, sin telemetría. El manifiesto solo declara
  `RECORD_AUDIO`.
- No se copió código, binarios ni recursos propietarios de terceros. La ingeniería
  inversa previa de otra app se usó únicamente como referencia de diseño.
- Las pruebas acústicas reales quedan pendientes y se declaran como tales.
