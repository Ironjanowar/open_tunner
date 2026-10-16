# Validación

## Estado final

Afinador cromático implementado con TDD y verificado en host y en emulador API 37.
Los registros de instrumentación, captura, UI, benchmark y APK están en
`docs/evidence/`.

El APK debug es instalable manualmente sin ADB ni Internet. Lo que no está demostrado es
la calidad acústica, el consumo o la temperatura en el teléfono real: esos puntos solo
pueden validarse tocando un instrumento en el dispositivo del usuario.

## Suites automatizadas

| Ámbito | Resultado final |
| --- | --- |
| DSP JVM | 129 tests, 0 fallos, 0 errores |
| App host JVM | 67 tests, 0 fallos, 0 errores |
| Total host | 196 tests, 0 fallos, 0 errores |
| Instrumentación API 37 | 18 tests, `OK (18 tests)` |
| Lint Android | 0 errores, 7 avisos de versión/`UseTomlInstead` |
| Ensamblado | APK debug generado y firmado |

La instrumentación final cubre la pantalla oscura, estados de permiso y error, silencio,
gauge, A440 sobre ART, las cinco etiquetas de sensibilidad, cambio del deslizador,
`Última nota` y persistencia real mediante `SharedPreferences` (incluido un valor corrupto
de otro tipo).

## Ciclos TDD de esta entrega

1. Normalización de entrada y cinco perfiles: tests RED de ganancia, clipping, continua,
   monotonicidad y detección floja; implementación en Kotlin sin Android.
2. Última nota: tests RED para mantener nota/frecuencia/cents, marcarla no viva tras
   0,6 s, apagar `isTuned`, sustituirla por una nota nueva y borrarla solo con `reset`.
3. Capa Android: tests RED para deslizador, cinco etiquetas, persistencia, aplicación
   inmediata sin reiniciar captura y supervivencia a pausa/reanudación.
4. Primera revisión bloqueante: la normalización antes del gate convertía zumbidos flojos
   de 50/60 Hz en notas. Se movieron RMS, clipping y ruido al dominio original.
5. Segunda revisión bloqueante: un umbral fijo bajo 150 Hz eliminaba el zumbido pero
   dejaba mudas E2/A2/D3. Se reemplazó por discriminación de estructura armónica (2f/3f),
   dejando `minimumNoteRms` como umbral único en todas las frecuencias.
6. Revisión de reanudación: el coordinador ahora exige una lectura viva en la ejecución
   actual; una nota anterior nunca aparece como `Afinado` al volver del segundo plano.

## Garantías comprobadas

- Seno limpio: ≤3 cents de 80 a 1000 Hz y ≤5 cents en 40/1500 Hz, con ≥95 % de frames
  válidos.
- Corpus armónico/fundamental ausente: ≥95 % dentro de ±5 cents.
- Silencio: ninguna nota. Ruido blanco con semilla: <1 % de notas falsas en los cinco
  perfiles (en las ejecuciones finales: cero adquisiciones).
- Zumbido puro 50/60/100/120 Hz a amplitudes 0,002–0,020: ninguna lectura en los cinco
  perfiles y a 44,1/48 kHz.
- Nota grave armónica y floja (82/110/146,8 Hz, RMS 0,008 ≈ −42 dBFS): detectada en
  `Alta`/`Muy alta`, ignorada en los tres niveles menos sensibles.
- Umbral medido con cuerda sintética armónica en `Alta`: ≈−44,5 dBFS en E2, A2, D3, G3,
  B3 y E4; en `Muy alta`: ≈−50,5 dBFS. El deslizador vuelve a actuar por igual en graves.
- Fuera de rango: 20/30/1700/2000 Hz no inventan nota; 40/1500 Hz sí se detectan.
- Latencia de adquisición/cambio ≤300 ms. Una lectura adquirida ya no se libera: pasa a
  `Última nota` tras 0,6 s y se conserva hasta otra nota o reinicio.
- Histéresis de afinado: entra en ±5 cents y sale al superar ±8.
- Al salir al Inicio, `dumpsys audio` pasa de una sesión activa `UNPROCESSED` 48 kHz a
  cero sesiones; no se graba en segundo plano.

## Rendimiento

`docs/evidence/benchmark.txt`: en JVM de host (Ryzen 7 9700X), mediana 2,61 ms y p95
3,11 ms por frame, frente a un hop de 21,3 ms a 48 kHz. Es medición de host; no sustituye
una medición de rendimiento/temperatura en el teléfono.

## Evidencia final en emulador

Emulador `fretboard-api37`, API 37, x86_64:

- `instrumentacion.txt`: `OK (18 tests)`, sin `FATAL EXCEPTION`.
- `captura-audio.txt`: sesión de grabación en primer plano, `rec stop` y cero sesiones
  activas tras pulsar Inicio.
- `ui-final-sensibilidad.png` / `.xml`: la UI final contiene `Sensibilidad`, `Muy suave`,
  `Suave`, `Normal`, `Alta` y `Muy alta` además de la tarjeta del afinador.
- `apk-verification.txt`: firma v2 válida, certificado debug, paquete
  `com.cuwano.afinador`, launcher `MainActivity`, único permiso `RECORD_AUDIO`.

## APK final

- Tamaño: `11 550 593` bytes.
- Versión: `0.2.0` (`versionCode 2`).
- SHA-256: `8519da0af932f0b54481bb0dcc1f81da32e7be4e01f2585c5cf2f7458f35a87b`.

## Riesgos residuales declarados

- Un zumbido fuerte o distorsionado que ya contenga 2f/3f con al menos ~8 % de la
  fundamental puede parecer una cuerda. Un zumbido puro necesita en las sondas finales
  amplitud 0,1 (RMS −23 dBFS) para saltarse el filtro; uno recortado/armónico puede entrar
  antes. Es el principal riesgo acústico pendiente de probar en el teléfono.
- Una nota real por debajo de 150 Hz, muy floja y extraordinariamente pobre en armónicos,
  puede rechazarse como ambiente. Las cuerdas sintéticas usadas tenían soporte 0,47–0,57,
  muy por encima del umbral 0,08, pero no sustituye instrumentos reales.
- Tras silencio digital prolongado, un ataque instantáneo puede mostrar 1–3 frames
  transitorios equivocados antes de estabilizarse; un ataque/fade real de 50 ms no lo
  reproduce en la sonda.
- El panel atenuado, el ajuste en paisaje y la ausencia de recortes deben comprobarse
  visualmente en el teléfono concreto.
