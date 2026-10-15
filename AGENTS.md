# Instrucciones del proyecto

- Leer README y `.hermes/plans/2026-10-15_232217-afinador-cromatico.md` antes de implementar.
- Alcance: afinador cromático Android offline, pantalla única. Prioridad detección y estabilidad, no funciones accesorias.
- Las decisiones principales ya están confirmadas y documentadas; la implementación está en curso mediante ciclos TDD verticales.
- Kotlin/JVM puro para DSP; no mezclar AudioRecord, Compose o contexto Android con algoritmos.
- Funciones cortas, módulos pequeños, dependencias explícitas. Tests antes del código y resultados reales antes de declarar terminado.
- No copiar código, binarios, pseudocódigo o recursos de GuitarTuna. No versionar sus APKs ni el análisis descompilado.
- No publicar ni crear remoto sin autorización. No decidir licencia de distribución sin el usuario.
- El teléfono no tiene conexión ADB disponible: entregar APK para instalación manual. Emulador no demuestra calidad acústica.
- Textos UI en español; notas musicales C/C♯/D…B. No confundir suavidad visual con precisión del detector.
- Documentar incertidumbre, pruebas omitidas y bloqueos. No fabricar salidas.
- Tras limpieza final solicitada por el usuario, borrar completamente `.hermes/plans/`; conservar las decisiones duraderas en docs antes de borrarlo.
