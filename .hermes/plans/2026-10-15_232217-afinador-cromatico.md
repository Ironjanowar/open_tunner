# Afinador cromático Android — Implementation Plan

**Goal:** Crear una app Android de afinación cromática offline, sencilla, estable y sensible, sin funciones ajenas a afinar.

**Architecture:** Núcleo DSP Kotlin/JVM independiente de Android y de la UI. Adaptador AudioRecord → procesamiento secuencial → estado observable → Compose. Separar frecuencia detectada, seguimiento temporal y presentación; una pantalla sin servicios en segundo plano.

**Tech Stack propuesto:** Kotlin, Gradle Kotlin DSL, Jetpack Compose/Material 3, coroutines/StateFlow, AudioRecord y JUnit. Dos módulos: `:dsp` (JVM puro) y `:app` (Android). Sin backend, red, cuentas, anuncios ni SDK de analítica.

**Estado de la entrega:** Implementación inicial en curso. El bootstrap Gradle compila y el primer ciclo TDD de `NoteMapper` está verde; todavía no existe un afinador funcional. No confundir criterios esperados con resultados ejecutados.

## 1. Contexto y decisiones propuestas

El usuario quiere una app cromática simple, no un clon de GuitarTuna. Prioridad: detección robusta del sonido. El móvil no puede conectarse al servidor ni por USB ni mediante depuración inalámbrica. Entregar un APK instalable manualmente; las pruebas de micrófono real se harán mediante instalación y feedback, no mediante ADB obligatorio.

Decisiones por defecto para evitar bloquear el inicio:

- Nombre provisional: Afinador. Directorio: `/workspace/repos/afinador`.
- Application ID provisional: `com.cuwano.afinador`; confirmar antes de publicar, no implica dominio propio.
- Android mínimo propuesto: API 26. Elegir compile/target SDK estable compatible al implementar; fijar todas las versiones y el wrapper.
- Referencia fija A4 = 440 Hz en MVP. Doce semitonos, notación C/C♯/D…B, octava científica; textos de ayuda en español.
- Rango inicial objetivo: 40–1500 Hz. Fuera de rango no inventar una nota.
- Pantalla única: nota/octava, cents, indicador centrado y frecuencia secundaria. Estado claro de escucha/silencio/permiso/error.
- Diseño cuidado: tarjeta principal, jerarquía visual, colores de estado y aguja/barra. Tema oscuro inicial con contraste y compatibilidad con escalado de fuente; no depender solo del color.
- Mostrar «Afinado» inicialmente dentro de ±5 cents, con entrada/salida diferenciadas (salida >±8 cents). Son umbrales de producto ajustables, no precisión garantizada.
- No implementar selección de instrumentos/cuerdas, metrónomo, acordes, reproducción de referencia, historial, presets o afinaciones alternativas.
- No abrir un repositorio remoto, publicar, subir APKs a tiendas ni elegir licencia sin autorización.

Antes de implementar, presentar estas decisiones resumidas y confirmar cambios esenciales con el usuario (prefiere grill-me y confirmación antes de desarrollar). No volver a preguntar detalles ya fijados; no implementar durante este turno de planificación.

## 2. Evidencia previa y sus límites

Investigación local disponible en `/workspace/tunning-il2cpp/ANALISIS.md`, `dump-v39/dump.cs`, `decompiled-native-core2.c` y `decompiled-native-internal.c`. Son referencias locales opcionales: este proyecto no debe depender de ellas para compilar.

El análisis estático de GuitarTuna sugiere salience armónica, estimación adaptativa del ruido, seguimiento temporal, confianza e histéresis. No demuestra qué rutas se ejecutan realmente en cada modo/dispositivo. La descompilación tiene advertencias y límites de funciones imperfectos; no tratar todos los valores del informe anterior como hechos verificados ni como una especificación a copiar.

En particular, el pseudocódigo de `ComputeSalience` contiene un peso con `(f + param_2) * 0.1`; el informe previo lo simplifica de otra manera. No trasladar esa fórmula. Tampoco asumir que todos los tiempos de note-on/pitch-lock o ventanas se han confirmado dinámicamente.

**Clean-room práctico:** escribir algoritmos propios desde conceptos DSP públicos, sin incluir APK, bibliotecas, recursos, pseudocódigo descompilado ni código propietario en el repositorio o distribución. No prometer idéntica calidad por reproducir constantes. Validar con señales y grabaciones propias/autorizadas.

## 3. Estrategia DSP: baseline medible antes de complejidad

Decisión confirmada: implementar desde el MVP una cadena armónica propia. La salience espectral será el detector principal y YIN actuará como contraste/confianza para reducir errores de armónicos y octavas. Mantener ambos detectores independientes y probarlos por separado antes de fusionarlos; Kotlin puro primero y migración nativa solo si un benchmark en dispositivo lo exige.

Configuración inicial experimental, centralizada y documentada en `AnalysisConfig`:

- Solicitar mono PCM16 a 48000 Hz; fallback 44100 Hz si no se puede inicializar. Usar SIEMPRE el sampleRate real comunicado por la captura.
- Ventana de análisis 4096 muestras; hop inicial 1024. No confundir longitud de ventana, frecuencia de actualización y latencia total. Afinar el hop tras medir CPU.
- Buffer circular: aceptar lecturas parciales, procesar todos los hops completos; sin concatenaciones/allocations de arrays por frame.
- Eliminar componente DC; calcular RMS y clipping. No normalizar amplificando silencio antes de evaluar calidad. No aplicar Hann automáticamente al YIN temporal; reservar ventana para eventual detector FFT.
- YIN: diferencia cuadrática, cumulative mean normalized difference, primer mínimo válido y refinamiento parabólico; limitar lags según rango. Configurar umbral inicial de dip 0.15 y validar, no presentarlo como constante universal.
- Resultado crudo: `PitchEstimate(frequencyHz, periodicity, rms, clipped, sampleIndex)`, o ausencia de estimación. Periodicity es puntuación algorítmica, no probabilidad calibrada.
- Gate de nivel relativo al ruido ambiental con límite absoluto conservador; no actualizar el ruido con una nota estable ni en clipping. Arranque sin calibración manual. Probar el caso de iniciar la app con una nota ya sonando.
- Seguimiento en espacio logarítmico (cents), mediana corta y EMA dependiente del tiempo; confirmar cambios persistentes y rechazar saltos aislados de octava. No bloquear una octava correcta que el usuario realmente cambia.
- Liberar nota obsoleta tras silencio sostenido (~200–350 ms como punto de partida), no conservarla varios segundos. Mostrar ausencia, nunca 0 Hz como nota.
- Reset del estado de captura/DSP al pausar, cambiar sampleRate o perder audio.

La cadena completa incluye FFT, salience armónica, suelo de ruido adaptativo, YIN de contraste, fusión de confianza y tracking/histéresis. Primero implementarla y medirla en Kotlin. Si no cumple el presupuesto de un hop en dispositivo: optimizar buffers y rangos antes de considerar NDK o Rust; cualquier migración exige benchmark reproducible.

## 4. Contratos y estructura objetivo

```text
settings.gradle.kts
build.gradle.kts
gradle/libs.versions.toml
gradle/wrapper/*
gradlew
gradlew.bat
dsp/build.gradle.kts
dsp/src/main/kotlin/com/cuwano/afinador/dsp/
  AnalysisConfig.kt
  PitchEstimate.kt
  NoteMapper.kt
  SampleRingBuffer.kt
  SignalMetrics.kt
  YinPitchDetector.kt
  NoiseGate.kt
  PitchTracker.kt
  TunerEngine.kt
dsp/src/test/kotlin/com/cuwano/afinador/dsp/
  SignalFixtures.kt
  NoteMapperTest.kt
  SampleRingBufferTest.kt
  SignalMetricsTest.kt
  YinPitchDetectorTest.kt
  NoiseGateTest.kt
  PitchTrackerTest.kt
  TunerEngineTest.kt
  DetectorBenchmarkTest.kt
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/kotlin/com/cuwano/afinador/
  MainActivity.kt
  audio/AudioSource.kt
  audio/AndroidAudioSource.kt
  audio/AudioCaptureConfig.kt
  tuner/TunerViewModel.kt
  tuner/TunerUiState.kt
  ui/TunerScreen.kt
  ui/PitchGauge.kt
  ui/theme/Theme.kt
app/src/test/kotlin/com/cuwano/afinador/tuner/TunerViewModelTest.kt
app/src/androidTest/kotlin/com/cuwano/afinador/ui/TunerScreenTest.kt
docs/validation.md
docs/dsp-decisions.md
```

Contratos orientativos (el implementador fija nombres finales sin ampliar responsabilidades):

- `NoteMapper.map(frequencyHz, referenceHz)` devuelve nota, octava, frecuencia objetivo y cents; rechaza valores inválidos.
- `YinPitchDetector.analyze(samples, sampleRate)` sin Android, reloj de pared o UI.
- `PitchTracker.update(estimate, elapsedAudioSeconds)` devuelve lectura estable o ausencia; usa tiempo de audio, no número de callbacks UI.
- `TunerEngine.accept(pcm, count, sampleRate)` integra framing, detección y tracking. Procesamiento en un único consumidor, nunca concurrente sobre buffers reutilizados.
- `AudioSource` permite fuente real y fuente falsa determinista. Definir explícitamente propiedad/lifetime de arrays; no encolar referencias a un buffer que será sobreescrito.
- Estado visible derivado de captura + resultado: permiso, escuchando sin nota, nota, error. No crear flags contradictorios.

## 5. Plan de implementación por entregas

En cada tarea de lógica: escribir test → ejecutarlo y registrar fallo pertinente → implementación mínima → tests verdes → refactor. No declarar TDD si el test no se ejecutó. Usar funciones cortas, módulos pequeños y dependencias explícitas. Commits locales pequeños después de validaciones, sin push automático.

### Tarea 1 — Bootstrap reproducible

Archivos: Gradle raíz, wrapper, catálogo, `dsp/build.gradle.kts`, `app/build.gradle.kts`, manifest, MainActivity y tema mínimo.

1. Inspeccionar entorno: Java, SDK Android, licencias aceptadas y herramientas disponibles; no asumir rutas ni instalar globalmente con sudo.
2. Consultar compatibilidad oficial AGP/Gradle/Kotlin/Compose/JDK, fijar versiones exactas en catálogo y wrapper, documentar JDK requerido en README.
3. Crear dos módulos; `:dsp` no puede importar Android. App depende del núcleo.
4. Crear test JVM mínimo y una Activity que arranque. Sin algoritmo aún.
5. Ejecutar `./gradlew :dsp:test :app:assembleDebug`. Verificar APK generado, no solo exit code de configuración.

### Tarea 2 — Conversión musical pura

Archivos: `NoteMapper.kt`, `NoteMapperTest.kt`.

1. Tests: 440 Hz → A4/0 cents; 220 → A3; 880 → A5; C4 aproximado; frecuencias con ±25 cents; semitono límite y octavas.
2. Tests: cero, negativo, NaN e infinito no producen lectura.
3. Implementar MIDI continuo `69 + 12*log2(f/440)`, nota más próxima y cents relativos; documentar redondeo en límites.
4. `./gradlew :dsp:test --tests '*NoteMapperTest'`.

### Tarea 3 — Framing y métricas

Archivos: `SampleRingBuffer.kt`, `SignalMetrics.kt` y tests homónimos.

1. Tests de lectura parcial, hop, wrap-around, orden de muestras y reset.
2. Comparar salida con la misma señal dividida en bloques distintos (1, 137, 1024, etc.).
3. Tests de RMS/DC/clipping con silencio, constante y seno.
4. Implementar buffers reutilizables; contar muestras válidas y no procesar un frame incompleto.
5. `./gradlew :dsp:test --tests '*SampleRingBufferTest' --tests '*SignalMetricsTest'`.

### Tarea 4 — Fixtures y detector YIN

Archivos: `SignalFixtures.kt`, `YinPitchDetector.kt`, `YinPitchDetectorTest.kt`, `AnalysisConfig.kt`, `PitchEstimate.kt`.

1. Generador determinista: seno, mezcla armónica, ruido con semilla, envolvente de ataque/decaimiento; frecuencias de verdad guardadas aparte del algoritmo.
2. Tests a 44100 y 48000 Hz, frecuencias que no coinciden con bins, diversas fases/amplitudes, extremos del rango y silencio.
3. Implementar YIN y refinamiento; salvaguardar denominadores y bordes; nunca devolver frecuencia no finita.
4. Tests con segundo armónico dominante y fundamental débil, además de ataques y mezcla inarmónica difícil. No ajustar las expectativas para ocultar fallos.
5. `./gradlew :dsp:test --tests '*YinPitchDetectorTest'`.

### Tarea 5 — Gate de ruido adaptativo

Archivos: `NoiseGate.kt`, `NoiseGateTest.kt`.

1. Tests: silencio, ruido constante, subida del ambiente, nota sostenida y nota presente desde el arranque.
2. Estimar ruido solo bajo condiciones documentadas; combinar RMS y periodicidad, sin absorber el sonido musical en el fondo.
3. Rechazar clipping fuerte o degradar lectura explícitamente; no dar falsa precisión.
4. Parametrizar constantes y explicar su unidad en `docs/dsp-decisions.md`.
5. `./gradlew :dsp:test --tests '*NoiseGateTest'`.

### Tarea 6 — Seguimiento estable sin pegajosidad

Archivos: `PitchTracker.kt`, `PitchTrackerTest.kt`.

1. Tests: nota estable con jitter, glissando, cambio real de semitono, salto aislado de octava, cambio real de octava y silencio.
2. Tests de independencia del hop: misma secuencia temporal con distintas cadencias produce comportamiento comparable.
3. Implementar confirmación corta, mediana/EMA temporal, reset de transición y expiración de lectura.
4. Mantener aparte frecuencia cruda, estimación estable y etiqueta visual.
5. `./gradlew :dsp:test --tests '*PitchTrackerTest'`.

### Tarea 7 — Integración offline y medición

Archivos: `TunerEngine.kt`, `TunerEngineTest.kt`, `DetectorBenchmarkTest.kt`, `docs/validation.md`.

1. Conectar framing → métricas → detector → gate → tracker.
2. Recorrer corpus sintético completo con mismos ajustes, semilla y duraciones.
3. Reportar: error cents mediano/p95 en segmentos sostenidos, tasa de lecturas válidas, fallos de octava, falsos positivos en ruido, tiempo a primera lectura y tiempo de cambio/liberación.
4. Medir coste por frame sin hacer un test CI frágil de tiempo absoluto; separar benchmark de assertions funcionales.
5. Si hay fallos, corregir con regresión mínima. Solo añadir un detector salience/FFT alternativo si la batería justifica la complejidad; registrar decisión antes de cambiar arquitectura.
6. `./gradlew :dsp:test`.

### Tarea 8 — Captura Android y ciclo de vida

Archivos: `audio/*`, manifest, `TunerViewModel.kt`, `TunerUiState.kt`, `TunerViewModelTest.kt`.

1. Permiso RECORD_AUDIO en runtime. Sin permiso INTERNET ni foreground service.
2. Probar fuente UNPROCESSED cuando esté soportada; fallback VOICE_RECOGNITION y luego MIC si la inicialización falla. No dar por hecho que todos los fabricantes deshabilitan procesamiento.
3. Buffer de captura >= mínimo de plataforma, separado del frame DSP; atender lecturas parciales, errores, ERROR_DEAD_OBJECT y rate real.
4. Lectura bloqueante y DSP fuera del hilo UI. Cola acotada o procesamiento directo; sin backlog ilimitado. Stop/cancel/release idempotentes y sin deadlock.
5. Capturar solo con pantalla en STARTED y permiso concedido; detener al ir al fondo, denegar permiso o cerrar Activity. Rotación sin dos grabadores simultáneos.
6. Tests con AudioSource falso: permiso denegado, start/stop repetidos, fuente fallida, pausa/reanudación y reset del motor.
7. `./gradlew :app:testDebugUnitTest :app:assembleDebug`.

### Tarea 9 — UI Compose

Archivos: `ui/*`, MainActivity y `TunerScreenTest.kt`.

1. Renderizar estados mediante datos falsos/previews antes de depender del micro.
2. Tarjeta de nota, octava, cents con signo, indicador de ±50 cents y frecuencia secundaria. Si el valor excede escala, limitar dibujo, no falsificar valor textual.
3. Indicador «Afinado» con histéresis; sin aguja vieja en silencio. Texto «Toca una nota sostenida» cuando falta señal.
4. Permiso denegado permanentemente: explicación y acceso a ajustes del sistema; no bucle de solicitudes.
5. Semántica accesible; no anunciar cada fluctuación a TalkBack. Layout usable con fuente grande y pantallas estrechas.
6. Tests UI de permiso, silencio, nota y error mediante inyección de estado, sin micrófono real.
7. `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`.

### Tarea 10 — Validación, APK y relevo

Archivos: README, `docs/validation.md`, `docs/dsp-decisions.md`; artefacto APK fuera de Git.

1. Ejecutar todos los tests/lint/build. Si hay emulador disponible, `./gradlew :app:connectedDebugAndroidTest`; si no, declarar pendiente. Emulador valida UI, no robustez acústica real.
2. APK debug para instalar manualmente; no confundir con release de tienda. Verificar con `apkanalyzer manifest application-id` y `apksigner verify --verbose`, usando rutas SDK descubiertas; calcular SHA-256.
3. Enviar APK como archivo, instrucciones de instalación y ficha breve de pruebas manuales. No requiere cable ni ADB del usuario.
4. Pedir prueba real con guitarra/voz u otro instrumento: silencio, nota suave/fuerte, graves, agudos, afinación cambiante, habitación ruidosa, reanudación y revocación de permiso. Capturar resultados, no audio sin consentimiento.
5. Si se usa una grabación aportada, confirmar permiso, no subirla externamente y no incluirla en Git por defecto.
6. Medir CPU/latencia en dispositivo cuando haya evidencia disponible; benchmark del servidor no certifica rendimiento móvil. Sin evidencia, marcar aceptación acústica/rendimiento como pendiente.
7. Revisión final de modularidad, seguridad, permisos, dependencias/licencias, precisión de documentación y ausencia de archivos propietarios.

## 6. Criterios de aceptación

### Automáticos

- Build reproducible con wrapper versionado y configuración documentada.
- Núcleo puro y tests JVM sin Android.
- Seno limpio sostenido: objetivo error absoluto <=3 cents en 80–1000 Hz y <=5 cents en extremos del rango; evaluar por frames posteriores al calentamiento y exigir cobertura >=95% de frames válidos (no aprobar absteniéndose siempre).
- Corpus armónico especificado: >=95% de frames sostenidos dentro de ±5 cents en casos nominales; reportar aparte casos adversos, errores de octava y abstenciones.
- Ruido/silencio: sin nota estable en silencio exacto; menos de 1% de frames con nota estable en corpus de ruido definido y reproducible. No generalizar a cualquier sonido ambiental.
- Lectura inicial y cambio de nota: objetivo <=300 ms en señal limpia; desaparición <=350 ms tras silencio. Medir desde muestra de entrada, no ignorar la ventana inicial.
- Estados UI probados sin captura real; stop/release probado con fuente falsa.

### Dispositivo, pendientes hasta ejecutar

- Instalación manual y permiso funcionales; afinación utilizable sin Internet.
- No grabación en background ni tras salir.
- No bloqueos, pérdidas crecientes de audio ni calentamiento evidente en sesión de 10 minutos.
- Objetivo DSP p95 < duración de hop en dispositivo representativo, sin cola creciente.
- Resultado acústico usable en instrumento real; no prometer ±1 cent ni equivalencia a GuitarTuna sin medición.

## 7. Comandos finales previstos

```bash
./gradlew --version
./gradlew :dsp:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
# Solo con un emulador/dispositivo autorizado disponible:
./gradlew :app:connectedDebugAndroidTest
# Verificar ruta generada antes de enviar:
sha256sum app/build/outputs/apk/debug/app-debug.apk
```

Los comandos son instrucciones futuras, NO resultados ya ejecutados. Registrar versiones, comandos, fallos y resultados reales en `docs/validation.md`.

## 8. Guía para el modelo implementador

1. Leer README, AGENTS.md y este plan completo; no necesitas recuperar la conversación.
2. Cargar skills pertinentes: grill-me para confirmar decisiones, test-driven-development, requesting-code-review y android-emulator-testing solo si se usa emulador.
3. Confirmar alcance antes de despachar agentes. No afirmar que existe una app compilable: solo hay documentos.
4. Trabajar por entregas y no reimplementar todo simultáneamente. Si se delega: test writer → implementer → reviewer; revisión final independiente de calidad. Los agentes reciben contratos y límites explícitos.
5. Las constantes son punto de partida experimental; justificar cambios con pruebas. No perseguir ajustes particulares a una sola señal.
6. No copiar bibliotecas/código de GuitarTuna ni incluir el directorio de análisis en Git.
7. Mantener el plan con progreso mientras se implementa. Cuando el usuario pida limpieza final, eliminar TODO `.hermes/plans/`, incluso archivos previamente versionados, después de conservar decisiones vigentes en README/docs. No hacerlo antes del relevo.
8. Si algo bloquea build/prueba, reportarlo con salida real. No reemplazar una verificación por resultados simulados.
