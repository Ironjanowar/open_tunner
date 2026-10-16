# Decisiones DSP

## Alcance confirmado

- Salience armónica es el detector principal.
- YIN aporta contraste y confianza, en especial ante errores de octava.
- DSP en Kotlin puro; solo se migraría a nativo si un benchmark en dispositivo lo
  justificara. El benchmark en host deja margen amplio, así que no procede.
- Rango: 40–1500 Hz. A4 fijo a 440 Hz.
- El estado «Afinado» entra en ±5 cents y sale al superar ±8 cents.

## Cadena implementada

`SampleRingBuffer` (4096 muestras, hop 1024) → `SignalMetrics` (media DC, RMS,
clipping; la entrada original nunca se muta; se analiza la señal sin DC) →
`HannWindow` + `Radix2RealFft` → `HarmonicSalienceDetector` (propuesta primaria) →
`YinDetector` (contraste y octava) → `AdaptiveNoiseFloor` (gate) → `PitchTracker`
(estabilidad, corrección de octava, caducidad, histéresis) → `TunerEngine`
(`TunerReading` inmutable).

## Parámetros y unidades

Configuración centralizada en las constantes privadas de `TunerEngine`:

| Parámetro | Valor | Unidad / significado |
| --- | --- | --- |
| `frameSize` | 4096 | muestras |
| `hopSize` | 1024 | muestras (~21,3 ms a 48 kHz) |
| `YIN_CMND_THRESHOLD` | 0,15 | umbral de dip sobre CMND normalizado |
| `MINIMUM_PRIMARY_SALIENCE` | 0,35 | salience normalizada 0–1 |
| `MINIMUM_RELIABLE_YIN_CONFIDENCE` | 0,70 | 1 − CMND |
| `MINIMUM_YIN_CONFIDENCE` | 0,80 | confianza mínima para corregir octava |
| `MINIMUM_SIGNAL_TO_NOISE_RATIO` | 3,0 | RMS señal / RMS ruido |
| `INITIAL_NOISE_RMS` | 0,010 | RMS normalizado |
| `MINIMUM_NOISE_RMS` | 0,002 | suelo absoluto, nunca cero |
| `NOISE_HALF_LIFE_SECONDS` | 5,0 | s, vida media del suelo adaptativo |
| `SMOOTHING_HALF_LIFE_SECONDS` | 0,025 | s, suavizado exponencial en log-frecuencia |
| `CHANGE_CONFIRMATION_SECONDS` | 0,080 | s, confirmación de cambio persistente |
| `CLEAR_AFTER_SECONDS` | 0,200 | s de debilidad sostenida para liberar la nota |
| `TUNED_ENTER_CENTS` / `TUNED_EXIT_CENTS` | 5,0 / 8,0 | cents, histéresis visual |

La normalización de salience divide la suma cruda de magnitudes por
`frameSize × RMS × 0,25`, de modo que la puntuación es independiente de la amplitud
y del tamaño de frame y comparable con el umbral 0,35.

## Decisiones de robustez

- El suavizado y la caducidad dependen del tiempo transcurrido, no del número de
  callbacks: el comportamiento es equivalente con distinto hop.
- El suavizado se aplica en espacio logarítmico (cents), no en Hz lineales.
- Los cambios persistentes de nota se confirman en el tiempo, pero el movimiento
  coherente y continuo (glissando) progresa; los saltos aislados de octava se rechazan.
- El suelo de ruido nunca aprende de una nota fiable, de un frame con clipping ni de
  una señal más fuerte que el propio umbral del gate, así que arrancar con una nota
  ya sonando no la absorbe.
- El clipping cierra el gate y no adquiere ni refresca pitch: se rechaza o degrada,
  nunca se finge precisión.
- YIN devuelve `null` para tonos fuera de rango en lugar de recortarlos al extremo.
- La confianza solo promedia YIN cuando esa observación es relevante; una lectura
  fuerte sin YIN no queda penalizada a la mitad.
- Todos los componentes reutilizan buffers y están confinados a un único consumidor;
  `TunerSession` los usa desde un solo worker y `TunerEngine` no es reentrante.

Estos parámetros se validaron con corpus sintético determinista reproducible; no se
trasladó código, pseudocódigo ni constantes propietarias de GuitarTuna. Las constantes
del análisis previo de GuitarTuna se usaron solo como referencia de diseño y ninguna
se adoptó sin una prueba propia que la respaldara.
