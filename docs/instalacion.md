# Instalación manual y ficha de pruebas

El APK es de depuración (`debug`), firmado con la clave de depuración de Android. Sirve
para instalar en tu teléfono sin cable ni ADB, pero **no** para publicar en una tienda.

## Archivo

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Instalar sin ADB

El APK se instala directamente desde el propio teléfono:

1. Pasa el archivo al teléfono como prefieras (Telegram, correo, almacenamiento).
2. Ábrelo desde la app de archivos o desde la notificación de descarga.
3. Android pedirá permitir la instalación de apps de esa fuente
   («Instalar apps desconocidas»). Concédeselo solo a la app con la que abres el
   archivo, no a todo el sistema.
4. Instala y abre **Afinador**.
5. La primera vez pedirá acceso al micrófono. Sin ese permiso el afinador no puede
   escuchar nada.

Alternativa por línea de comandos, si algún día conectas el teléfono:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Qué hace y qué no

- Afinador cromático: muestra la nota más cercana y la desviación en cents.
- Rango 40–1500 Hz con A4 = 440 Hz. Notas escritas con sostenidos (C, C♯, D…).
- Offline: no usa Internet, no tiene cuentas, anuncios ni telemetría.
- Solo pide `RECORD_AUDIO`. Al salir a segundo plano libera el micrófono y deja de
  grabar.

## Ficha de pruebas manual

Marca cada punto y anota lo que falle. Es la parte que **no** se puede verificar desde
el servidor de compilación.

| # | Prueba | Qué se espera | Resultado |
| --- | --- | --- | --- |
| 1 | Instalación sin ADB | Se instala y abre sin cable | |
| 2 | Permiso del micrófono | Pide permiso; al concederlo aparece «Toca una nota sostenida» | |
| 3 | Permiso denegado | Muestra la explicación y el botón para volver a pedir | |
| 4 | Permiso denegado para siempre | Muestra «Abrir ajustes» y lleva a los ajustes de la app | |
| 5 | Silencio | No muestra ninguna nota ni aguja vieja | |
| 6 | Nota sostenida, cuerda al aire | Muestra la nota correcta y cents cercanos a 0 | |
| 7 | Afinación fina | Cambia a «Afinado» al acercarse y no parpadea | |
| 8 | Cuerda desafinada | Indica demasiado alto o demasiado bajo con el signo correcto | |
| 9 | Graves (cuerda grave) | Detecta la nota sin saltar a la octava | |
| 10 | Agudos (trastes altos) | Detecta la nota correcta | |
| 11 | Voz o silbido | Detecta el tono sin inventar notas | |
| 12 | Habitación ruidosa | No muestra notas falsas estables | |
| 13 | Hablar mientras se afina | La nota aparece y desaparece en menos de ~0,4 s | |
| 14 | Cambio de nota | Sigue el cambio rápidamente, sin quedarse pegada | |
| 15 | Salir a segundo plano | Al volver, sigue funcionando y no ha grabado en background | |
| 16 | Revocar el permiso en caliente | Muestra el estado de permiso, sin cuelgues | |
| 17 | Sesión de 10 minutos | No se calienta en exceso ni se degrada | |

## Interpretar resultados

- Si detecta la octava equivocada en un instrumento concreto, es el dato más útil que
  puedes darme: el diseño usa salience armónica como detector principal y YIN para
  corregir octava, así que un fallo ahí apunta a ese par de umbrales.
- Si no detecta nada con el micrófono de tu teléfono, mira si aparece «Error de audio»:
  el arranque de la captura prueba varias fuentes y formatos y, si todos fallan,
  muestra el motivo.
- La precisión real con instrumento **no** está medida. El servidor de compilación solo
  validó corpus sintético y el emulador, que no demuestra calidad acústica.
