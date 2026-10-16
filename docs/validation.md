# Validación

## Estado

Afinador cromático implementado con TDD y verificado en host y en emulador. Todas las
cifras de esta página proceden de ejecuciones reales; los registros en bruto están en
`docs/evidence/`.

El APK de depuración existe y es instalable manualmente, sin ADB ni conexión a
Internet. Lo que **no** está demostrado es la calidad acústica en tu teléfono: eso solo
puede comprobarlo una persona tocando un instrumento real. Ver `docs/instalacion.md`.

## Suites automatizadas

| Ámbito | Comando | Resultado |
| --- | --- | --- |
| DSP (JVM puro) | `:dsp:test --rerun-tasks` | 91 tests, 0 fallos |
| App host (JVM) | `:app:testDebugUnitTest --rerun-tasks` | 36 tests, 0 fallos |
| Instrumentación (emulador) | `AndroidJUnitRunner` | 9 tests, 0 fallos |
| Lint Android | `:app:lintDebug` | 0 errores, 7 avisos (versión/UseTomlInstead) |
| Ensamblado | `:app:assembleDebug` | APK generado, 11,53 MB |

Total host: **127 tests, 0 fallos, 0 errores**.

Los 9 tests de instrumentación cubren la captura real completa: pantalla oscura con
nota centrada, estados de permiso, silencio sin nota, gauge con cents acotados y
detección de A440 en el dispositivo (≤5 cents) sobre el motor DSP real.

## Ciclos TDD

Cada slice siguió RED (test que falla por la razón esperada) → GREEN (implementación
mínima) → revisión independiente.

1. `NoteMapper`: RED por `Unresolved reference`; GREEN con `440 Hz → A4`.
2. Primitivas DSP (`NoteMapper` completo, `SampleRingBuffer`, `SignalMetrics`): 19 tests.
3. Detector primario (`HannWindow`, `Radix2RealFft`, `HarmonicSalienceDetector`): 33 tests.
4. YIN, ruido adaptativo y tracker: 54 → 66 → 74 tests tras tres rondas de revisión
   (suavizado y expiración dependientes del tiempo, suelo absoluto de ruido, clipping,
   bounds inclusivos de lag, glissando, confianza consciente de evidencia).
5. Motor integrado (`TunerEngine`, `TunerReading`): 88 tests.
6. Capa Android (audio, sesión, estado de UI, pantalla Compose): 36 tests host.
7. Fallback de captura PCM16 y rechazo fuera de rango: 91 tests DSP, 127 host.

## Defectos encontrados por revisión y corregidos

- Suavizado del tracker dependiente de la cadencia de callbacks → reescrito en espacio
  log-frecuencia y en tiempo.
- Suelo de ruido cayendo a cero tras silencio → suelo absoluto.
- Confianza fusionando la ausencia de YIN como cero → confianza consciente de evidencia.
- YIN inventando pitches en los límites fuera de rango → rechazo explícito.
- Clipping no rechazado → cierra el gate de ruido y no actualiza el tracker.
- **Tonos fuera de rango apareciendo como nota válida** (30 Hz → D♯1 40 Hz,
  1700 Hz → G♯5 849,99 Hz, 2000 Hz → 1000 Hz) → `TunerEngine` ahora abstiene cuando el
  pico espectral dominante cae fuera de 40–1500 Hz (tolerancia 3% para no romper los
  bordes exactos). Compromiso aceptado y documentado: una fundamental cuyo parcial más
  fuerte esté por encima de 1500 Hz puede abstenerse; se prefiere mostrar ausencia antes
  que inventar una nota.
- `generation` en `TunerSession` era un `Long` no volátil → marcado `@Volatile`.

## Criterios de aceptación del plan

- Precisión en seno limpio: ≤3 cents de 80 a 1000 Hz y ≤5 cents en 40 y 1500 Hz, con
  ≥95% de frames válidos. Verificado en `TunerEngineTest`.
- Corpus armónico: ≥95% de frames sostenidos dentro de ±5 cents, incluido el caso de
  fundamental ausente. Verificado.
- Silencio: ninguna nota estable, sin fallos. Ruido blanco con semilla: <1% de notas
  falsas. Verificado.
- Latencia: ≤300 ms hasta la primera lectura y hasta el cambio de nota, ≤350 ms hasta
  la liberación, medida en muestras. Verificado.
- Fuera de rango: no inventar nota. Verificado con 20/30/1700/2000 Hz.
- UI: estados sin captura real y parada/liberación con fuente falsa. Verificado.

## Rendimiento

`docs/evidence/benchmark.txt`: mediana 2,39 ms y p95 2,79 ms por frame en JVM de host
(Ryzen 7 9700X), con hop de 1024 muestras (21,3 ms a 48 kHz). El análisis consume en
torno al 13% del presupuesto de tiempo real, con margen. La medición en dispositivo
sigue pendiente y no se presenta como hecha.

## Evidencia en emulador

Emulador `fretboard-api37`, API 37, x86_64. Registros en `docs/evidence/`:

- `instrumentacion.txt`: 9 tests, `OK (9 tests)`.
- `captura-audio.txt`: con la app en primer plano hay una sesión activa
  (`source UNPROCESSED`, dispositivo a 48000 Hz 16 bits, cliente `ENCODING_PCM_FLOAT`);
  el historial muestra `rec start` al entrar en primer plano y `rec stop` al pulsar
  Inicio, y tras Inicio no queda ninguna sesión de grabación.
- `ui-permiso.png` y `ui-escucha.png` con `ui-permiso.xml` y `ui-escucha.xml`: la
  luminancia de las esquinas es 0,058–0,106 (tema oscuro real) y los textos son los
  esperados (`Afinador cromático`, `Toca una nota sostenida`,
  `Acerca tu instrumento al micrófono`, `Permite el acceso para escuchar y afinar tu
  instrumento`).
- `apk-verification.txt`: APK firmado con esquema v2, certificado de depuración,
  `com.cuwano.afinador`, `versionName 0.1.0`, `minSdk 26`, `targetSdk 37`, lanzable
  `com.cuwano.afinador.MainActivity`, único permiso `RECORD_AUDIO`.
- SHA-256 del APK entregado:
  `a6cc1185e9f941be86230f277d9c1518cfd51ff8c216006f2a4c1220a2a1696a`.

No hay `FATAL EXCEPTION` en logcat durante ninguna de las ejecuciones.

## Pendiente y no demostrado

- Calidad acústica con instrumento real: precisión percibida, cuerdas graves agudas,
  ataque inarmónico, salas ruidosas y comportamiento a volumen bajo.
- Rendimiento y consumo en tu teléfono (p95 por frame, temperatura y batería en sesiones
  largas).
- La observación «sin grabación en segundo plano» está registrada en emulador; conviene
  reconfirmarla en el teléfono.
- Los avisos de lint restantes son de versión de dependencias, no de corrección.
