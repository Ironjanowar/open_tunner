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

La escala se subió entera tras el uso real: una guitarra eléctrica desenchufada y un
ukelele tocado flojo no se oían ni en el nivel más alto. Según el nivel, los umbrales de
activación bajaron entre 9,4 y 15 veces (19,4–23,5 dB) y las ganancias máximas crecieron
entre 1× y 3×.

| Nivel | `minimumNoteRms` | ≈ dBFS | Ganancia máxima | Salience mínima | SNR | Suelo mínimo |
| --- | --- | --- | --- | --- | --- | --- |
| Muy suave | 0,0032 | −49,9 | 3× | 0,50 | 5,0 | 0,0007 |
| Suave | 0,0016 | −55,9 | 6× | 0,42 | 3,5 | 0,00055 |
| Normal | 0,0008 | −61,9 | 12× | 0,35 | 2,0 | 0,0004 |
| Alta (de fábrica) | 0,0004 | −68,0 | 24× | 0,30 | 1,5 | 0,00025 |
| Muy alta | 0,0002 | −74,0 | 48× | 0,26 | 1,2 | 0,00012 |

Todos los valores son estrictamente monótonos: al subir de nivel baja el umbral de nota,
baja la exigencia de claridad y de relación señal/ruido, y sube la ganancia máxima. Cada
escalón multiplica el umbral por 2,0 y la ganancia por 2,0, así que ningún nivel queda
decorativo.

Umbrales comprobados con una cuerda sintética armónica de 82,41 Hz sobre un ambiente de
sala tranquila (RMS 0,000115): Muy suave 0,0032, Suave 0,0016, Normal 0,0008, Alta
0,0004 y Muy alta 0,0002. Es decir, cada nivel detecta exactamente lo que anuncia su propio
`minimumNoteRms`; el límite práctico del nivel más alto con ese ambiente lo pone la
relación señal/ruido física (unos 10 dB por encima del ruido de sala), no la puerta.

`INITIAL_NOISE_RMS` bajó de 0,010 (−40 dBFS) a 0,0008 (−61,9 dBFS), una sala tranquila.
Antes, al abrir la app el estimador arrancaba en 0,010 y tardaba decenas de segundos en
bajar (semivida de 5 s), así que la puerta efectiva al arrancar era ≈0,0025 incluso con el
nivel más alto seleccionado: de ahí que hubiera que subir la sensibilidad y esperar. El
valor nuevo es además el mayor que no tapa el umbral del nivel más alto, porque la puerta
relativa es un cuarto del estimador (0,25 × 0,0008 = 0,0002 = `minimumNoteRms` de Muy
alta). El estimador adaptativo no puede seguir ambientes muy por encima de su envolvente
de aprendizaje; en los niveles altos la protección principal frente a ruido sigue siendo
la ausencia de un pitch fiable y, por debajo de 150 Hz, la estructura armónica.

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
