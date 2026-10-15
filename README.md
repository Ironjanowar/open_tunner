# Afinador

Proyecto Android de afinación cromática offline.

## Estado

Implementación inicial en curso con TDD. El proyecto Gradle contiene los módulos `:dsp` y `:app`; el primer ciclo de `NoteMapper` está verde. El APK debug actual es todavía un artefacto de bootstrap y no constituye un afinador funcional.

## Requisitos de compilación

- JDK 17.
- Android SDK Platform 37 y Build Tools 36.0.0.
- El wrapper descarga Gradle 9.5.0 con checksum fijado.
- `ANDROID_HOME` debe apuntar al SDK.

Verificación actual:

```bash
./gradlew --no-daemon --no-configuration-cache :dsp:test :app:assembleDebug
```

## Empezar

Leer `AGENTS.md` y el plan completo:

[Plan de implementación](.hermes/plans/2026-10-15_232217-afinador-cromatico.md)

Arquitectura confirmada: Kotlin, Compose, AudioRecord y núcleo DSP Kotlin/JVM independiente. La salience armónica será el detector principal y YIN aportará contraste/confianza. Una pantalla, notas cromáticas con sostenidos, cents y estabilidad temporal. Sin red, anuncios ni cuentas.

Las decisiones técnicas y constantes del plan son propuestas que se deben validar, no resultados medidos. La investigación previa de GuitarTuna no autoriza copiar código o recursos propietarios.

El móvil del usuario no se conecta por ADB; el entregable final será un APK debug para instalación manual. Las pruebas acústicas reales permanecen pendientes hasta instalarlo en el teléfono.
