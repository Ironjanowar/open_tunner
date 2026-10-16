# Decisiones DSP

## Alcance confirmado

- Salience armónica es el detector principal.
- YIN aporta contraste y confianza, en especial ante errores de octava.
- DSP en Kotlin puro; solo se migraría a Rust/NDK si un benchmark lo justificara, y el
  benchmark actual no lo justifica.
- Rango 40–1500 Hz, A4 = 440 Hz fijo, notas con sostenidos.

## Cadena de análisis

1. `SampleRingBuffer` acumula muestras y entrega frames solapados de 4096 con hop de 1024.
2. `SignalMetrics` quita la continua y mide RMS y clipping **sobre la señal original**.
3. `InputNormalizer` ajusta el nivel del frame de análisis (ganancia de solo amplificar,
   acotada por perfil y con la salida limitada a [-1, 1]). Es una ayuda para el análisis
   espectral, nunca una puerta de decisión.
4. `HannWindow` + `Radix2RealFft` dan el espectro.
5. `HarmonicSalienceDetector` propone la fundamental recorriendo hasta 8 armónicos con
   interpolación parabólica, y expone el soporte armónico (energía en 2f/3f frente a la
   fundamental) que se usa para distinguir una cuerda de un zumbido.
6. `YinDetector` da una segunda opinión y confianza; corrige errores de octava.
7. `AdaptiveNoiseFloor` estima el ruido de fondo de la sala solo con frames en los que no
   se ha aceptado ninguna nota, de modo que una nota sostenida nunca arrastra el suelo.
8. `PitchTracker` decide estabilidad, suaviza en cents y aplica la histéresis de afinado.
9. `TunerEngine` ordena todo lo anterior y emite `TunerReading` con `isLive`.

## Todas las puertas se miden en el dominio real de la señal

Este es el punto que más costó acertar. Si la decisión de "¿esto es una nota?" se toma
sobre la señal ya normalizada, el amplificador puede empujar un zumbido flojo por encima
del umbral y el afinador inventa notas. Por eso:

- El nivel y el ruido de fondo se miden **antes** de normalizar.
- La normalización solo condiciona el análisis espectral (salience y YIN).
- El clipping se juzga en el frame original: una señal que solo se pasa de rango al
  amplificarla no es una señal recortada.

## Niveles de sensibilidad

El deslizador elige uno de cinco perfiles. `minimumNoteRms` es la palanca real de "qué
flojo puede ser una nota", y se aplica con la misma regla a todas las frecuencias.

| Nivel | `minimumNoteRms` | Ganancia máxima | Salience mínima | SNR | Suelo mínimo |
| --- | --- | --- | --- | --- | --- |
| Muy suave | 0,030 | 3× | 0,50 | 5,0 | 0,004 |
| Suave | 0,020 | 5× | 0,42 | 3,5 | 0,003 |
| Normal | 0,012 | 8× | 0,35 | 2,0 | 0,002 |
| Alta (de fábrica) | 0,006 | 12× | 0,30 | 1,5 | 0,0012 |
| Muy alta | 0,003 | 16× | 0,26 | 1,2 | 0,0008 |

Todos los valores son estrictamente monótonos: al subir de nivel baja el umbral de nota,
baja la exigencia de claridad y de relación señal/ruido, y sube la ganancia máxima.

## Zumbido de red frente a cuerda

Un zumbido de red es casi una onda pura: no tiene 2f ni 3f. Una cuerda siempre los trae.
Por eso, para candidatos **por debajo de 150 Hz** y que no sean claramente fuertes, se
exige soporte armónico (`MINIMUM_HARMONIC_SUPPORT = 0,08`; medido: 0,0002–0,004 en un
zumbido puro y 0,47–0,57 en una cuerda simulada). Un candidato claramente fuerte
(`CLEARLY_LOUD_RMS = 0,05`, unos −26 dBFS) se acepta sin exigir esa estructura, para no
penalizar señales puras de laboratorio ni notas graves tocadas con fuerza.

Compromiso declarado: un tono puro y flojo por debajo de 150 Hz que no venga de una
cuerda se trata como ambiente y no se muestra. Antes esto se resolvía con un umbral de
nivel fijo que dejaba mudas las cuerdas graves tocadas flojo; se descartó por eso.

## Seguimiento, estabilidad y «última nota»

- Suavizado exponencial en cents con semivida de 25 ms.
- Confirmación de cambio de nota en 80 ms; los saltos de octava aislados se rechazan.
- Histéresis: entra en «Afinado» a ±5 cents y no sale hasta superar ±8.
- `STALE_AFTER_SECONDS = 0,600`: pasados 0,6 s sin evidencia, la nota se mantiene con
  `isLive = false` e `isTuned = false`. El motor no vuelve a `null` una vez que ha
  detectado algo: la última nota solo la sustituye otra nota, un error de audio o un
  reinicio del proceso.
- La frescura se decide fuera del motor: el coordinador marca la lectura como viva solo
  si el motor ha publicado una lectura viva en la ejecución actual, así al volver del
  segundo plano la nota anterior nunca se presenta como activa.

## Compromisos aceptados

- El suelo de ruido aprende solo de frames sin nota aceptada: una nota muy sostenida
  nunca engaña al detector subiéndole el suelo.
- La normalización no mejora la relación señal/ruido (es la misma ganancia para señal y
  ruido); lo que elimina es la dependencia del nivel absoluto de entrada.
- La medición en dispositivo real sigue pendiente y no se presenta como hecha.
