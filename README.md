# Afinador

Afinador cromático Android offline de una sola pantalla. Paquete
`com.cuwano.afinador`.

## Estado

Implementado y verificado en host y en emulador. El APK debug es instalable
manualmente y no requiere ADB ni conexión a Internet.

- Detector: salience armónica como estimador primario, YIN como contraste y control
  de octava; discriminación de zumbido de red por estructura armónica.
- Captura: `AudioRecord` mono bloqueante, con intentos `UNPROCESSED`/`MIC` y respaldo a
  16 bits, y conmutación 48 000 → 44 100 Hz.
- Rango: 40–1500 Hz, A4 = 440 Hz fijo, notas con sostenidos.
- Sensibilidad: deslizador con cinco posiciones (Muy suave · Suave · Normal · Alta · Muy
  alta) bajo la tarjeta central, por defecto `Alta`, recordado entre sesiones y aplicado
  al instante. Los umbrales de activación son entre 9,4 y 15 veces menores que en la
  primera versión (una mejora de 19,4–23,5 dB), las ganancias máximas crecen entre 1× y
  3× según el nivel, y el arranque en frío ya no añade una puerta por encima del nivel
  elegido. Umbrales comprobados con una cuerda armónica sintética: Muy suave −49,9 dBFS ·
  Suave −55,9 · Normal −61,9 · Alta −68,0 · Muy alta −74,0.
- Última nota: la nota detectada se queda en pantalla hasta que suena otra, y pasados
  0,6 s sin señal se atenúa como «Última nota» con el aviso verde apagado.
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

- Tamaño: 11,55 MB (`11 550 593` bytes).
- Versión: `0.3.0` (`versionCode 3`), instalable como actualización de la versión anterior.
- SHA-256: `2368b79efb19b16ec891fa18d5c7687af3712125573062773fc1c169c4f084ed`.
- Verificación de firma, manifiesto y permisos: [docs/evidence/apk-verification.txt](docs/evidence/apk-verification.txt).
- Instalación sin ADB: [docs/instalacion.md](docs/instalacion.md).

## Restricciones respetadas

- Sin red, sin cuentas, sin anuncios, sin telemetría. El único permiso funcional
  declarado es `RECORD_AUDIO` (Android añade el permiso de firma interno
  `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`).
- No se copió código, binarios ni recursos propietarios de terceros. La ingeniería
  inversa previa de otra app se usó únicamente como referencia de diseño.
- Las pruebas acústicas reales quedan pendientes y se declaran como tales.
