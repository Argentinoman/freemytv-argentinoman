# Free My TV · Proyector: notas para el asistente

Rama `proyector`: el inicio de Free My TV recortado para proyectores Android chinos. El de referencia es un **HY300**:
Amlogic T950S, Android 13, 1 GB de RAM, pantalla de 1024×600, firmware `be311-13 TQ2A.20250807`. Es una app de
Android en Java, compilada sin Gradle por `build.sh`.

- [PROYECTOR.md](PROYECTOR.md): todo lo que se encontró y se hizo en el proyector.
- [SEGURIDAD-HY300.md](SEGURIDAD-HY300.md): los riesgos del software de fábrica.
- [PROMPT-PROYECTOR.md](PROMPT-PROYECTOR.md): la instalación paso a paso.

La persona a la que ayudás puede no ser técnica. Hablale en castellano, corto y amable. Antes de hacer algo en su
proyector, decile qué vas a hacer, y dejá que las teclas las toque ella.

## Instalarlo

Seguí [PROMPT-PROYECTOR.md](PROMPT-PROYECTOR.md) de punta a punta:

1. Preguntas.
2. Revisión del firmware.
3. Foto del estado de fábrica.
4. Instalación.
5. Apps, por grupos.
6. Cambio de modo.
7. Apps de streaming.
8. Firewall: siempre lo último, y avisándole antes a la persona (después el ADB por red queda cerrado).

Para compilar uno propio en vez de usar `apk/FreeMyTV-proyector.apk`:

1. **Herramientas:** JDK 17+, los command-line tools del SDK de Android (build-tools 34+, platform android-34) y adb.
   Revisá qué ya está instalado (`java -version`, `$ANDROID_HOME`, `adb version`) e instalá solo lo que falte. En
   Windows, Git Bash.
2. **Configuración:** `./build.sh` la primera vez crea `config.properties`. Completá `latitude`, `longitude` y `city`
   con su ciudad, y `tmdb_region` con su país.
3. **La clave de TMDB es de la persona y es secreta:** que la pegue ella en `config.properties`. No la pidas en el
   chat, no la imprimas y no la subas a git. Sin clave anda todo menos las recomendaciones de películas. Lo mismo vale
   para `watchmode_key`.
4. **Compilar:** `./build.sh` tiene que terminar en `Built FreeMyTV.apk`. Guardá `freemytv.keystore`: las
   actualizaciones necesitan la misma firma. Un APK con otra firma (por ejemplo, el de `apk/`) obliga a volver a
   *Original* y desinstalar antes.
5. **Instalar:** `adb install -r FreeMyTV.apk` y enseguida `adb shell cmd package compile -m speed -f io.freemytv`.
   Recién instalada corre sin compilar, y en 1 GB se nota. Avisá que instalar encima cierra el inicio un momento.
6. **APK genérico de `apk/`:** compilalo sin `config.properties` propio, sin `app/assets/local/` y sin
   `app/assets/profile.jpg`, y comprobá que no lleve claves ni fotos personales. Actualizá su SHA-256 en
   `apk/LEEME.md`.

## Adaptarlo a otro proyector

Primero leé, después cambiá. Comandos de solo lectura:

```bash
adb shell getprop ro.product.device; adb shell getprop ro.build.display.id; adb shell getprop ro.build.version.release
adb shell getprop ro.board.platform; adb shell wm size; adb shell id
adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME
adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.HOME.JM
adb shell pm list packages -s     # del sistema (-3 para las instaladas)
adb shell ls /system/etc/init     # scripts que corren al arrancar: buscá instaladores remotos
```

Después, en el código:

- **`RemoteKeys.HY300`** se fija en `ro.product.device` (`t950s_be311`). Otro proyector necesita su propia marca y
  sus propias condiciones en todo lo que hoy depende de `HY300`:
  - las pantallas de Proyección, HDMI e Inalámbrica;
  - el volumen, los botones del control y la ausencia de protector de pantalla.
- **`Projector`:** el trapecio son cuatro esquinas en `persist.vendor.hwc.keystone`, y el modo de proyección es
  `/sys/class/aml_lcd/lcd0/paneL_filp`. Otra placa puede usar otras: miralas en la app de trapecio del fabricante.
- **`HomeMode.DEBLOAT`:** launchers, tiendas y relleno del fabricante, un comentario por línea, y solo eso.
  **`HomeMode.PROTECTED`:** las piezas del sistema (imagen, sonido, entradas, control, actualizaciones). Ante la duda,
  protegé. `neededByTv` deja prendido lo que pide `TV_INPUT_HARDWARE`: apagado, el HDMI queda en "Sin señal".
- **`HomeApps.PROJECTION_SCREENS`:** la pantalla de proyección del fabricante, por si la nuestra no aplica.
- Compilá, instalá, que la persona haga el cambio, leé `adb logcat -s FreeMyTV`. Después, que vuelva a *Original* una
  vez y confirmá que todo regresó.

## El código

- Java 8, minSdk 26, targetSdk 33. Sin Gradle ni librerías: solo el SDK de Android.
- **La rama comparte el código con `master`** (la versión para la tele): lo que se arregla allá se trae con
  `git merge master`. Por eso quedan en el código listas y teclas de marcas de televisores (TCL, Sony, Vestel…). No
  molestan en el proyector, y sacarlas complicaría cada merge. Las carpetas de LG, Samsung y el sitio web no están en
  esta rama: si un merge las trae, se borran otra vez.
- **El inicio se dibuja a mano en un solo Canvas** (`HomeView`). Mantené los 60 fps: nada de crear objetos en `onDraw`
  ni trabajo pesado en el hilo de la interfaz.
  - La GPU es una Mali-450 (OpenGL ES 2.0), débil: probá cada cambio visual en el proyector.
  - La pantalla es de 1024×600 a 30 Hz.
  - Con 1 GB, cada MB cuenta: `Backdrop` arma las imágenes al tamaño de la pantalla.
- **Procesos:**
  - `HomeMode` hace cada cambio como un trabajo en segundo plano (`ModeJob`) en el proceso principal; la interfaz está
    en `:home`.
  - `Watchdog` vuelve al inicio del fabricante después de 3 cuelgues en 5 minutos.
  - `RemoteKeyService` y `NetflixNavService` viven en `:keys`. Lo que leen va en archivos (`remote-keys.txt`), nunca
    en SharedPreferences, y `onKeyEvent` no hace trabajo pesado.
- **El ADB local:** `Adb` se conecta a `127.0.0.1:5555`. En el HY300 es root, así que la Proyección, el explorador y
  el cambio de modo pasan por ahí. El firewall (`tools/proyector/agent.sh`) lo deja solo para Free My TV, root y
  shell. `NetworkAdb` lo abre a la red desde el panel rápido hasta el próximo reinicio.
- **Texto en pantalla:** `Ui.tr("English")`, traducido desde `app/assets/i18n/<lang>.json`. La interfaz nueva del
  proyector está escrita directo en castellano. Comentarios en inglés, cortos, solo donde el porqué no es obvio.
- **Configuración:** sale de `config.properties`, a través del `out/gen/io/freemytv/Config.java` generado (no lo
  edites). Lo que la persona cambia en el proyector (`Prefs`) gana sobre esos valores.
- Después de cualquier cambio, `./build.sh` tiene que terminar en `Built FreeMyTV.apk`.

## Siempre

- **Nunca apagar la app que guardó una red Wi-Fi** (en el HY300, `com.htc.htcsettings`): Android borra sus redes, y
  *Original* no puede devolverlas (`wifiOwners` en `HomeMode`). Primero, olvidar la red y reconectar desde los ajustes
  de Android.
- **Nunca desinstalar ni borrar datos de apps del sistema.** Solo `pm disable-user --user 0` o `pm hide`, para que todo
  pueda volver.
- **Ninguna app de streaming o IPTV se apaga sin preguntarle a la persona,** aunque parezca pirata.
- **Nunca `am force-stop io.freemytv`:** apaga sus servicios de accesibilidad.
- **No tocar `com.droidlogic.android.tv`** (tiene `TV_INPUT_HARDWARE`: sin ella no anda el HDMI).
- **El cambio de modo lo hace la persona en el proyector,** no vos desde la computadora.
- **Nunca subir** `config.properties`, `*.keystore`, claves ni fotos personales (`app/assets/local/`,
  `app/assets/profile.jpg`).
- **No cambiar** la imagen, el sonido, la red ni las cuentas del proyector sin que lo pidan.
- **Mantener la vuelta atrás:** el modo *Original* y el watchdog.
