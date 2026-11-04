# Rama `proyector`

Una versión recortada de este fork para proyectores Android (los chinos), más liviana que la de la TCL. Se mantiene
aparte de `master`: lo que se arregle allá se trae con `git merge master` (o `cherry-pick`).

## Qué tiene

- Estante de apps, reloj y clima (con el pronóstico de 7 días en el cajón).
- Continuar viendo (Netflix, Disney+, YouTube, Spotify, Prime Video).
- Panel rápido, con el brillo del inicio.
- El cajón de abajo: a la izquierda (*Entradas*) un solo **HDMI** (las placas Amlogic dicen tener dos), **Explorador de archivos** (el primero instalado: el de la placa,
  X-plore, Files de Google…; sin ninguno, abre la Play Store para bajar uno), **USB** (abre el pendrive enchufado directo
  en el explorador; apagado sin pendrive) y **Parlante** (abre los dispositivos
  Bluetooth; se ve apagado mientras no hay un parlante conectado, y lleva el nombre del favorito, que se elige en
  *Ajustes de inicio → Parlante favorito*). A la derecha (*Accesos directos*) Todas las apps, Ajustes del TV y **Play
  Store**, que ya no aparece en Todas las apps. Si el proyector tiene pantalla de proyección conocida (trapezoide, zoom,
  modo de proyección), también **Proyección**, que reemplaza a "TV en vivo" en la esquina (un proyector no tiene antena).
- **Tus fondos**, en el inicio y en el protector de pantalla (con favoritas). Sin internet.
- El cambio completo a Free My TV / Original, igual que en la tele.

## Qué se sacó

- Fútbol: partidos bajo el reloj, la tarjeta del cajón, el partido en el protector y sus ajustes.
- El reproductor "Reproduciendo ahora" (en el inicio y en el protector). `NowPlayingListener` queda: alimenta los
  "Vistos recientemente" de YouTube y las notificaciones del panel rápido.
- Los fondos de Wikimedia Commons y los temas de temporada.

## Los fondos

Dos tipos, que se eligen en *Ajustes de inicio → Fondos* (para el inicio y para el protector por separado):

- **Fondos del sistema:** los de `app/assets/fondos/`, que están en el repositorio y van siempre.
- **Fondos personalizados:** los tuyos, en `app/assets/local/fondos/` (git no los sube, como el resto de
  `assets/local/`). Si la carpeta está vacía, muestra los del sistema.

Los dos entran en el APK al compilar:

- JPG, PNG o WebP, horizontales. Con 1920 px de ancho alcanza: se achican a eso igual, y cada MB suma al APK.
- El título que muestra el protector sale del nombre del archivo (`atardecer-en-el-delta.jpg` → "atardecer en el
  delta"), o de un `titulos.properties` en la misma carpeta, en UTF-8:

  ```properties
  delta.jpg=Atardecer en el Delta
  delta.jpg.autor=Mati
  ```

Cada cuánto cambian: *Cambiar fondo cada* (inicio: de 15 minutos a 1 semana) y *Cambiar foto cada* (protector: de 15
segundos a 5 minutos), en la misma pantalla. Las opciones del inicio son Negro, Fondos del sistema, Fondos
personalizados y Favoritas; las del protector, las mismas fotos o el Reloj.

## Pendiente: adaptarlo al proyector

El cambio de modo usa las listas de la TCL (`HomeMode.DEBLOAT` y `PROTECTED`, `HomeApps.SHELF`, `LIVE_TV_APPS` y
`HIDDEN`). Antes de hacer el cambio en el proyector, hay que leer qué trae (ver *Adapting it to another TV* en
`CLAUDE.md`) y completar esas listas con las apps de esa marca.

### HY300 (el de Eli)

Amlogic T950S, Android 13, 1 GB de RAM, Mali-450 (OpenGL ES 2.0), pantalla de 1024×600 a 30 Hz. El foco es la rueda
del costado; la pantalla de proyección es `com.softwinner.tcorrection/.projection.ProjectionSettingActivity`
(`HomeApps.PROJECTION_SCREENS`). El ADB por red viene abierto, sin autorización y como root.

Apps de streaming (2026-11-03, de APKMirror, firmas verificadas con `apksigner`; las de los `.apkm` con
`adb install-multiple` de base + `armeabi_v7a` + densidad + `es` + `en`): YouTube para Android TV 7.31.302, Prime
Video para Android TV 6.24.13 (reemplaza a la de celular, `com.amazon.avod.thirdpartyclient`, desinstalada), Disney+
para Android TV 26.20.0, Netflix de celular 9.90.1 (la de TV, `com.netflix.ninja`, pide un equipo certificado),
Spotify para Android TV 9.1.94 y Flow Android TV 4.112.1 (`ar.com.flow.androidtv`; no "Flow Box", que es para el deco).
Compiladas con `cmd package compile -m speed -f`. Widevine es L3: Netflix, Prime y Disney+ van en SD.

Desactivadas con `pm disable-user` (se vuelven con `pm enable`): `com.android.mgstv` (IPTV disfrazada),
`com.gecen.store`, `com.master.tv.market`, `com.overseas.store.appstore`, `com.android.camera2`,
`com.android.gallery3d`, `com.android.printspooler`, `com.google.android.syncadapters.calendar` y `.contacts`,
`com.google.android.tvrecommendations`, `com.google.android.backuptransport`, `com.google.android.partnersetup`,
`com.waxrain.airplayer2` y `com.droidlogic.miracast` (duplicación de pantalla).

También desactivadas, por sospechosas:

- `com.define.shellservice`: un servicio abierto a cualquier app que corre comandos como sistema (una puerta trasera).
- `com.fb` ("JMFactory"): menú de fábrica con `FixService`/`SuperService` siempre prendidos, un descargador y
  funciones para instalar apps en silencio desde `aliyun.v9ying.cn`.
- `com.master.accessibility` ("NetflixMouse"): arranca con el equipo y también puede instalar en silencio.
- `com.koushikdutta.superuser`: el administrador de root (el `su` sigue en `/system/xbin`).

Quedan para el cambio de modo (`HomeMode.DEBLOAT`, así *Original* los devuelve): el launcher
`com.htc.htclauncherhighenglishd08` (habla con `txy.jiumaoos.com` y manda errores a Umeng), `com.jm.jmlauncher`,
`com.master.apps` y la capa de ajustes `com.htc.htcsettings`. **Ojo:** la red Wi-Fi la guardó `com.htc.htcsettings`;
antes de apagarla hay que olvidar la red y volver a conectarla desde los ajustes de Android (*Ajustes → Redes e
Internet*), o Android borra la red.

### Seguridad del HY300

- `com.fb` era un **instalador remoto**: el 2026-11-03 a las 22:15, apenas se conectó al Wi-Fi, bajó de
  `apk.v8ying.com` e instaló como root `TvMarket`, una versión nueva de `com.htc.htcsettings` y Netflix de celular, y
  desinstaló otras (registro en `/data/local/install/script.log`; reglas en `/data/local/rule/`). También dejó un
  paquete cifrado, `/data/local/com.master.jmsu_*.apk`. Desactivado, nada de eso corre.
- `com.htc.htcsettings` desactivada a pedido (se lleva la red Wi-Fi: hay que volver a conectarse desde los ajustes
  de Android).
- Puertos abiertos: solo el 5555 (ADB, como root y sin autorización; `service.adb.tcp.port` está en
  `/vendor/build.prop`). SELinux apagado, compilación `userdebug/test-keys`, bootloader abierto: no se arreglan sin
  otro firmware.
- `/system/etc/init/preinstall.rc` corre como root al arrancar `/data/local/agent.sh` si existe (hoy no existe),
  e instala lo que haya en `/data/preinstall` que no esté anotado en `/data/local/tmp/preinstalled`.
- **Firewall del ADB:** `tools/proyector/agent.sh` va en `/data/local/agent.sh` (root, 755, fin de línea LF): el
  firmware lo corre como root al arrancar y deja el puerto 5555 solo para el propio proyector (el cambio de modo usa
  127.0.0.1), y adentro solo para Free My TV, root y shell: cualquier otra app podía conectarse a 127.0.0.1:5555 y
  tener root (así trabajaba el instalador remoto). Para trabajar desde una computadora: panel rápido → **ADB por red** (muestra la IP); se vuelve a cerrar
  con el mismo botón o al reiniciar. Se puso con:

  ```bash
  adb push tools/proyector/agent.sh /data/local/agent.sh
  adb shell "chown root:root /data/local/agent.sh; chmod 755 /data/local/agent.sh"
  ```
- Borrados los restos del instalador remoto (`/data/local/com.master.jmsu_*.apk`, `rule`, `install`, `push`,
  `uninstall`, `shell`).
- **El firmware bloquea otros launchers:** cuando el inicio predeterminado no es el suyo, el sistema pide la categoría
  `android.intent.category.HOME.JM` en vez de `HOME`, y solo le contestaba `FallbackHome` de los ajustes (una pantalla
  vacía: quedaba el logo del proyector). `HomeActivity` declara `HOME.JM` también.
- **Proyección propia** (`Projector`, `ProjectionActivity`): en el panel rápido. Trapecio automático con el
  acelerómetro (mide lo mismo que la app original: 26° → casi las mismas esquinas), ángulos manuales, tamaño y modo de
  proyección (escribe `/sys/class/aml_lcd/lcd0/paneL_filp`, 0 a 3). Se reaplica al volver al inicio.
  `com.softwinner.tcorrection` queda desactivada: mandaba datos a `txy.jiumaoos.com/netflix/dataSave` y su servicio
  disparaba los scripts root del firmware (`vendor.jm.usb.upgrade`: al enchufar un pendrive con carpeta `jm`, instala
  sus APK y corre `jm/jm.sh` como root; `vendor.jm.apk.install`).
- **El instalador remoto de verdad era el firmware** (`/system/etc/init/ttyunos.rc`): al arrancar copiaba
  `ttyunos.sh`, `v9ying.sh` y `custom.sh` a `/data/ttyunos` (carpeta 777: cualquier app podía cambiarlos) y los corría
  como root. Bajaban por HTTP scripts de `jm.v7ying.com` / `jm.v9ying.com` / `persist.sys.jm.url`, que mandaban MAC,
  serie, modelo y país a `123.207.77.74`, traían "reglas" y ejecutaban lo que dijeran (de ahí las instalaciones y el
  paquete cifrado). `agent.sh` los deja vacíos y de root en cada arranque, apunta `persist.sys.jm.url` a 127.0.0.1 y
  bloquea esos servidores.
- **HDMI propio** (`HdmiActivity`): el botón HDMI abre una vista a pantalla completa (un `TvView` con la entrada), con
  "Sin señal" en nuestro estilo; Atrás vuelve al inicio. La app de TV de Amlogic (`com.droidlogic.android.tv`) queda
  prendida porque tiene `TV_INPUT_HARDWARE`, pero `agent.sh` le corta internet.
- **Widget de música** (arriba a la derecha): necesita el acceso a notificaciones de `NowPlayingListener`, dado una
  vez con `adb shell cmd notification allow_listener io.freemytv/io.freemytv.NowPlayingListener` (solo mira las
  sesiones de reproducción).
- **Protector de pantalla:** el firmware trae "apagar pantalla: nunca" (`screen_off_timeout` al máximo), así que
  nunca arranca solo. Medido a mano: unos 30-35 % de CPU sumando procesos y 22 MB más mientras anima. Sacado del
  panel rápido en el HY300.
- **Volumen propio** (`VolumeBar`, dibujado por `RemoteKeyService`): una barra fina a la derecha. El sistema
  arrancaba en cada tecla de volumen el círculo gigante de `com.droidlogic/.tvvolume.VolumeService`
  (`PhoneWindowManager.dispatchDirectAudioEvent`); esa pieza sola quedó apagada con `pm disable` (vuelve con
  `pm enable com.droidlogic/.tvvolume.VolumeService`). El volumen va de 0 a 100.
- **Netflix y Flow:** no publican "continuar viendo"; el inicio anota lo que se reproduce ahí (como con Prime) y
  guarda las notificaciones de Netflix (solo las de Netflix) como tarjetas por una semana.
- **Inalámbrica** (`WirelessActivity`, en Entradas): dos tarjetas, Android / Windows (el Miracast de Amlogic,
  `com.droidlogic.miracast`, de 2014 y con uid de sistema) y Dispositivos Apple (AirPlay Server,
  `io.github.jqssun.airplay`, código abierto, de F-Droid). Los dos quedan desactivados: elegir uno lo prende y lo
  abre, y al volver a la pantalla se fuerza a cerrar y se desactiva otra vez. AirPlay Server 0.0.36 instalado de
  F-Droid (firma del autor, `OU=jqssun`), solo la parte armeabi-v7a (`adb install --abi armeabi-v7a`).
- **Navegador:** TV Bro 2.1.6 (`com.phlox.tvwebbrowser`, código abierto, de su GitHub; la versión sin Gecko, que usa
  el WebView del sistema, 6,8 MB), con su bloqueador de publicidad. Lo abre el botón "Navegador" del cajón. El
  WebView del sistema es el de AOSP 131 (de fines de 2024).
- **Play Store:** sin botón en el cajón; aparece en "Todas las apps" como una más.
- **ADB por red en el panel:** fila en Ajustes del inicio (`quick-adb.txt`, apagada por defecto); el firewall lo
  cierra en cada arranque igual.
- **Netflix con las flechas** (`NetflixNavService`, en `:keys`, prendido junto al de los botones por `HomeMode`): en
  la Netflix de celular, las flechas saltan entre lo que se puede tocar (como el buscador de foco de Android) y OK lo
  toca; un aro blanco marca dónde. Solo lee la pantalla de Netflix (`packageNames`); con el teclado abierto, las
  flechas son del teclado. Reemplaza al "NetflixMouse" del fabricante. Netflix marca ocultas las tarjetas de
  "Continuar viendo" (pero tienen posición y título reales: cuentan dentro de su fila); las barras flotantes se
  distinguen por no estar dentro de una lista; lo que asoma abajo se trae con `ACTION_SHOW_ON_SCREEN`; y escuchar
  los eventos de contenido y desplazamiento de Netflix es lo que mantiene al día la copia de su pantalla.
- **Spotify se cierra al apagar el proyector** (`RemoteKeyService`, con la pantalla apagada: `am force-stop`).
- **Nombre: "Irupé"** (2026-11-04): `settings global device_name` y `wifi_p2p_device_name` (Miracast; en vivo con
  `wpa_cli -p /data/vendor/wifi/wpa/sockets -i wlan0 set device_name`), Bluetooth (desde la pantalla de Android
  `android.settings.DEVICE_NAME`, que llama a `setName`: escribir `bt_config.conf` no alcanza) y AirPlay Server
  (`server_name` en su `shared_prefs/settings.xml`). El modelo (`ro.product.model` = HY300) es de fábrica.
- **Mis favoritas** (Ajustes → Fondos, `FavoritesActivity`): grilla de los fondos favoritos; OK oculta uno (sigue en favoritas
  pero el fondo y el protector lo saltean: `"paused"` en `gallery-favorites.json`) o lo vuelve a mostrar, y
  mantener OK abre un menú: **Cambiar de lugar** (las flechas la llevan; el orden se guarda en el archivo y lo siguen
  el fondo y el protector) o **Quitar de favoritas**. Con todas ocultas, el inicio usa los fondos del sistema.
- **Explorador propio** (`FilesActivity`): el botón USB del cajón. `com.droidlogic.FileBrower` desactivada.
- **Visor de fotos propio** (`ImageViewerActivity`): las fotos del explorador, y cualquier `content://` de imagen de otra
  app. ← → recorren la carpeta, OK muestra la barra, mantener OK la copia a `fondos-propios` y la deja de fondo. El
  de la placa (`com.droidlogic.imageplayer`) sigue instalado: si otra app lo elige, Android pregunta cuál usar.
- **Sin protector en Ajustes del inicio** (HY300): las filas del protector no se muestran (`NO_SCREENSAVER`).
- **`com.softwinner.tcorrection` oculta** (`pm hide`; vuelve con `pm unhide`): es persistente y Android la levantaba
  en cada arranque aunque estuviera desactivada.
- **Sin Dolby:** la placa anuncia AC3/E-AC3 (una salida "compress offload" y dos decodificadores
  `c2.amlogic.audio.decoder.ac3`/`.eac3`) pero le falta la librería de Dolby (`libms12: pls load lib first`): ese
  audio sonaba mudo (en Disney+, los episodios y las publicidades). `agent.sh` monta encima la variante del
  fabricante sin formatos crudos (`audio_policy_configuration_default.xml`) y una lista vacía sobre
  `media_codecs_amlogic_audio_ddp.xml`, y reinicia audioserver y mediaserver: las apps eligen la pista AAC. Solo
  montajes; también `encoded_surround_output=1` (Nunca).
- **Ojo al probar `agent.sh` a mano:** vuelve a cerrar el ADB por red (hay que reabrirlo desde el panel).
