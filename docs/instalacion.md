# Instalación manual y ficha de pruebas

El APK es de depuración (`debug`), firmado con la clave de depuración de Android. Sirve
para instalar en tu teléfono sin cable ni ADB, pero **no** para publicar en tiendas.

## Instalar sin cable

1. Copia el archivo `Afinador-<versión>-debug.apk` al teléfono (por Telegram, correo,
   nube o cable, lo que te resulte más cómodo).
2. Ábrelo desde el gestor de archivos o desde la notificación de descarga.
3. Android pedirá permitir la instalación de apps de origen desconocido para esa app
   (archivos, Telegram, Chrome…). Concédelo solo para esa instalación.
4. Toca `Instalar` y luego `Abrir`.
5. Si ya tenías una versión anterior, se actualiza encima y conserva la sensibilidad
   elegida.

Para desinstalarlo: mantén pulsado el icono `Afinador` → `Desinstalar`.

## Al abrirlo la primera vez

Pide permiso de micrófono. Si lo deniegas, la pantalla explica cómo activarlo en los
ajustes. El afinador **no** manda audio a ningún sitio, no usa red y no guarda grabaciones.

## Deslizador de sensibilidad

Debajo de la tarjeta hay un deslizador con cinco posiciones y el nombre de cada una. Los
cinco niveles son bastante más sensibles que en la primera versión, y además no hacen
falta los primeros segundos de "calentamiento": antes había que subir el deslizador y
esperar a que la app dejara de creer que la habitación era ruidosa.

- **Muy suave**: el nivel menos sensible, equivalente al máximo de la primera versión. Úsalo
  en sitios ruidosos o si ves notas que no tocas.
- **Suave**
- **Normal**: suficiente para un ukelele o una guitarra acústica tocados con normalidad.
- **Alta**: viene de fábrica. Con el móvil cerca, detecta una guitarra eléctrica
  desenchufada.
- **Muy alta**: el nivel más sensible; apura hasta donde llega la relación señal/ruido real
  de la sala. Es el que más riesgo tiene de confundir un ruido tonal de fondo con una nota,
  así que en silencio comprueba que no aparece nada.

El nivel se guarda y se recupera al volver a abrir la app. Se aplica al instante: no hay
que reiniciar nada.

## La última nota se queda

Cuando tocas una nota y dejas de sonar, el afinador **no borra** lo que estaba midiendo:

- Pasados unos 0,6 s sin señal, la nota y la aguja se quedan quietas donde estaban y se
  atenúan, y el aviso cambia a `Última nota`.
- Durante los primeros ~0,6 s sin señal se conserva la lectura viva para evitar
  parpadeos. Después el aviso verde se apaga y cambia a `Última nota`; la aguja queda
  atenuada, aunque conserva el color de su última posición.
- La nota anterior se sustituye en cuanto tocas otra, y sobrevive si sales al Inicio y
  vuelves mientras Android mantiene viva la app.
- En un arranque limpio, sin haber tocado nada todavía, no se muestra ninguna nota.

## Ficha de pruebas

| # | Prueba | Qué debe pasar |
| --- | --- | --- |
| 1 | Abrir la app y conceder el micrófono | Empieza a escuchar y muestra `Toca una nota sostenida` |
| 2 | Cuerda al aire de guitarra | La nota coincide (E2, A2, D3, G3, B3, E4) y la aguja se centra |
| 3 | Aflojar la cuerda | La nota cambia y la aguja se va a un lado |
| 4 | Dejar la nota afinada | Aparece `Afinado` a ±5 cents y no parpadea |
| 5 | Tocar flojo, sin darle fuerte | Con el nivel de fábrica (`Alta`) la nota se detecta igual |
| 5b | Guitarra eléctrica desenchufada, móvil junto a la cuerda | Con `Alta` debe dar la nota de la cuerda; prueba también `Normal` y `Muy alta` para ver dónde deja de oírla |
| 6 | Callar dejando de tocar | La nota no desaparece: se atenúa y pasa a `Última nota` |
| 7 | Tocar otra nota distinta | Sustituye a la anterior y vuelve a estar viva |
| 8 | Sala en silencio, sin tocar nada | No muestra ninguna nota |
| 9 | Ventilador, nevera o aire acondicionado cerca | En condiciones normales no aparece ninguna nota; anota el resultado si hay un zumbido fuerte o distorsionado |
| 10 | Hablar o tararear | Comprueba que la voz no se queda fijada como si fuera una cuerda; esta prueba depende del entorno real |
| 11 | Bajar el deslizador a `Suave` | Solo detecta si tocas con más fuerza y claridad |
| 12 | Subirlo a `Muy alta` | Detecta notas muy flojas; en silencio sigue sin inventar nada |
| 13 | Cerrar y reabrir la app | Conserva el nivel de sensibilidad elegido. La nota anterior vuelve a aparecer como `Última nota` si la app sigue viva en segundo plano; si el sistema la cerró del todo, arranca sin nota |
| 14 | Salir y volver (Inicio → volver a la app) | Vuelve a escuchar y la nota anterior aparece como `Última nota` |
| 15 | Girar el móvil | Comprueba que no se recortan la aguja ni el deslizador; esta disposición depende del tamaño de pantalla |
| 16 | Nota aguda de la cuerda | No salta a la octava equivocada |
| 17 | Modo avión | Funciona igual |

Si algo falla, lo más útil es: qué prueba, qué esperabas, qué viste y la marca del
teléfono. No hace falta adjuntar audio.
