# Prompt: instalar Free My TV en un proyector HY300

Para quien tenga un proyector **HY300** (y parecidos: Amlogic, Android 13, 1 GB de RAM) y quiera sacarle el inicio
chino, las apps basura y el instalador remoto del fabricante, con ayuda de un asistente (Claude Code, Codex, Gemini
CLI…). Abrí un chat nuevo en la carpeta de este repo (rama `proyector`) y pegá todo lo que está debajo de la línea.

¿Sin asistente? Seguí [apk/LEEME.md](apk/LEEME.md): ahí están el APK listo y los pasos a mano.

---

Vas a instalar Free My TV (rama `proyector` de este repo) en mi proyector HY300 y a dejarlo limpio y seguro. Antes de
empezar leé `CLAUDE.md` (cómo instalar, reglas de siempre) y `PROYECTOR.md` (todo lo que se encontró y se hizo en el
proyector de referencia, "Irupé"). Hablame en castellano, simple: quizás no soy técnico. Antes de cada cosa que cambie
el proyector, decime qué vas a hacer y esperá mi OK. Las teclas en el proyector las toco yo.

## Reglas que no se negocian

- **Nunca desinstalar ni borrar datos de apps del sistema.** Solo `pm disable-user --user 0` (vuelve con `pm enable`)
  o `pm hide` (vuelve con `pm unhide`). Anotá en un archivo de esta carpeta (`proyector-<serie>.txt`, fuera de git)
  cada paquete que toques y cómo volverlo atrás.
- **Ninguna app de streaming o IPTV se apaga sin preguntarme**, aunque sea pirata o sospechosa. Mucha gente usa este
  proyector justamente con esas apps. Explicame qué encontraste y qué riesgo ves, y la decisión es mía (ver el paso 5).
- **Nunca apagar la app que guardó la red Wi-Fi** (en el HY300 es `com.htc.htcsettings`): Android borra la red. Primero
  hay que olvidar la red y conectarse otra vez desde los ajustes de Android (paso 7).
- **Nunca `am force-stop io.freemytv`**: apaga sus servicios de accesibilidad (los botones del control y Netflix con
  las flechas). Si pasa, se vuelven a prender (paso 4).
- El cambio de modo (Free My TV / Original) lo hago yo en el proyector, no vos desde la computadora.
- No completes CAPTCHAs ni esquives verificaciones de sitios de descarga: si una página lo pide, bajo el archivo yo.
- Nunca subas a git `config.properties`, claves, keystores, mis fotos ni los APK de terceros.

## 1. Preguntame primero

1. La IP del proyector (Ajustes → Red, o Acerca de) y mi ciudad (para el clima).
2. Si quiero usar el APK que ya viene en `apk/FreeMyTV-proyector.apk` (sin clave de TMDB: sin las recomendaciones de
   películas, la ciudad se cambia en el proyector) o compilar el mío con `./build.sh` (mi ciudad y mi clave de TMDB,
   que pego yo en `config.properties`; nunca en el chat).
3. **Qué apps uso** para ver cosas: Netflix, YouTube, Disney+, Prime, Spotify, Flow, y también si uso alguna app de
   IPTV, películas o fútbol "gratis" que vino instalada o que instalé. Antes de apagar nada, que quede claro cuáles
   quiero conservar.
4. Si quiero el firewall del ADB (`tools/proyector/agent.sh`, paso 6). Recomendado, pero cierra el ADB por red: para
   volver a entrar desde una computadora hay que abrirlo desde el panel rápido del proyector.

## 2. Revisá el firmware antes de tocar nada

Todo lo de este repo se probó en **un** firmware. Otro firmware puede tener otros paquetes, otros scripts y otras
rutas. Leé (solo lectura):

```bash
adb connect <IP>:5555
adb shell getprop ro.product.device
adb shell getprop ro.build.display.id
adb shell getprop ro.build.fingerprint
adb shell getprop ro.build.version.release
adb shell getprop ro.board.platform
adb shell wm size
adb shell id
```

El de referencia:

| Dato | Valor en el HY300 de referencia |
|---|---|
| `ro.product.device` | `t950s_be311` |
| `ro.build.display.id` | `be311-13 TQ2A.20250807 16:27:32.E1` |
| Android | 13 |
| Placa | Amlogic T950S, Mali-450, 1 GB de RAM |
| Pantalla | 1024×600 |
| `adb shell id` | `uid=0(root)` (ADB por red abierto, sin autorización y como root) |

- **`ro.product.device` distinto de `t950s_be311`:** la app no lo reconoce como HY300 (`RemoteKeys.HY300`), y las
  pantallas propias del proyector (Proyección, HDMI, Inalámbrica, volumen, sin protector) no aparecen. Avisame y
  pará: hay que adaptar el código primero (*Adapting it to another TV* en `CLAUDE.md`).
- **Mismo equipo pero otro `display.id`:** seguí, pero comprobá cada cosa antes de usarla, y contame lo que difiera:
  - `/system/etc/init/ttyunos.rc` y la carpeta `/data/ttyunos`: el instalador remoto del firmware.
  - `/system/etc/init/preinstall.rc` y si corre `/data/local/agent.sh`. Sin esto, el firewall no arranca solo.
  - `/vendor/etc/audio_policy_configuration_default.xml` y `/vendor/etc/media_codecs_amlogic_audio_ddp.xml`: el
    arreglo del audio Dolby mudo.
  - `/sys/class/aml_lcd/lcd0/paneL_filp`: el modo de proyección.
  - Que el sistema pida `android.intent.category.HOME.JM` para el inicio:
    `adb shell cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.HOME.JM`.
- Anotá los datos del firmware al principio de `proyector-<serie>.txt`.

## 3. Foto del estado de fábrica

Antes de cambiar nada, guardá en `proyector-<serie>.txt`:

- `adb shell pm list packages -s` (sistema), `-3` (instaladas) y `-d` (ya apagadas).
- `adb shell settings get secure enabled_accessibility_services`.
- El inicio actual: `adb shell cmd package resolve-activity -a android.intent.action.MAIN -c android.intent.category.HOME`.

Con esto todo se puede volver atrás.

## 4. Instalar Free My TV

```bash
adb install -r apk/FreeMyTV-proyector.apk      # o FreeMyTV.apk si compilaste el tuyo
adb shell cmd package compile -m speed -f io.freemytv
adb shell cmd notification allow_listener io.freemytv/io.freemytv.NowPlayingListener
```

- Avisame que instalar sobre el inicio lo cierra un momento, y no compiles mientras abro una app: usa el procesador
  unos segundos.
- El acceso a notificaciones es para el widget de música y el "continuar viendo" de Netflix: solo lee las sesiones
  de reproducción.
- El APK de `apk/` está firmado con la clave del repo. Uno compilado por vos tiene otra firma: para pasar de uno al
  otro hay que volver a *Original* y desinstalar primero.

## 5. Las apps que trae (preguntar antes de cada grupo)

Listá todo lo instalado con su nombre visible (`adb shell pm list packages -f` y `dumpsys package <p>` para el nombre y
quién lo instaló), y presentámelo por grupos. Lo que se hizo en el de referencia:

**a) Seguridad: recomendado apagar.** Contame qué hace cada una:

- `com.fb` ("JMFactory"): menú de fábrica con servicios siempre prendidos. Bajaba e instalaba apps como root desde
  servidores del fabricante; lo hizo apenas se conectó al Wi-Fi.
- `com.define.shellservice`: corre comandos como sistema para cualquier app (una puerta trasera).
- `com.master.accessibility` ("NetflixMouse"): arranca solo y puede instalar en silencio. Free My TV lo reemplaza
  (`NetflixNavService`).
- `com.koushikdutta.superuser`: el administrador de root.
- `com.softwinner.tcorrection`: el trapecio del fabricante. Mandaba datos a `txy.jiumaoos.com` y dispara scripts root
  al enchufar un pendrive con una carpeta `jm`. Free My TV trae su propia pantalla de Proyección. Va con
  `pm disable-user` **y** `pm hide`: es persistente y Android la levanta igual.

**b) Tiendas y relleno del fabricante:** `com.gecen.store`, `com.master.tv.market`, `com.overseas.store.appstore`,
`com.android.camera2`, `com.android.gallery3d`, `com.android.printspooler`,
`com.google.android.syncadapters.calendar`, `com.google.android.syncadapters.contacts`,
`com.google.android.tvrecommendations`, `com.google.android.backuptransport`, `com.google.android.partnersetup`.

**c) Recepción inalámbrica:** `com.waxrain.airplayer2` (AirPlay del fabricante) y `com.droidlogic.miracast`. Free My
TV tiene su pantalla *Inalámbrica*, que prende Miracast o AirPlay Server solo mientras se usa. El AirPlay del
fabricante se reemplaza con AirPlay Server (código abierto, paso 8).

**d) Streaming e IPTV no oficiales: SIEMPRE PREGUNTAR, una por una.**

- Cómo detectarlas: buscá entre las apps del sistema **y** las instaladas las que reproducen TV, películas o fútbol y
  no son de los servicios oficiales. En el de referencia estaba `com.android.mgstv`: una app de IPTV disfrazada con
  nombre de sistema. Otras pistas:
  - nombres como IPTV, Live, TV, Play, Magis, Flujo, Xuper, Cine o Fútbol;
  - paquetes con nombre de sistema (`com.android.*`) que no son de Android;
  - apps que no vienen de la Play Store.
- Para cada una, decime el nombre, el paquete y lo que veas: si tiene nombre falso, si arranca sola o si habla con
  servidores raros (`dumpsys package <p>`: permisos y receptores de arranque). Sé honesto con el riesgo, sin sermones:
  la decisión es mía.
- Si la conservo, queda como está, y el firewall del paso 6 no la afecta. Si la apago, va con `pm disable-user` y
  queda anotada para volverla.

**e) Se apagan solas con el cambio de modo** (`HomeMode.DEBLOAT`; *Original* las devuelve), no las toques a mano:

- el launcher `com.htc.htclauncherhighenglishd08`;
- `com.jm.jmlauncher` y `com.master.apps`;
- `com.htc.htcsettings`, la que guarda el Wi-Fi: ver el paso 7;
- `com.droidlogic.FileBrower`: lo reemplaza el explorador propio.

**f) No tocar nunca:**

- `com.droidlogic.android.tv`: tiene `TV_INPUT_HARDWARE`. Si se apaga, el HDMI puede dejar de andar.
- El resto de `com.droidlogic*`, salvo los de arriba.
- Los ajustes de Android, la Play Store, los Google Play Services y el WebView.

Extra, con mi OK: el círculo gigante de volumen del sistema se apaga como componente, solo esa pieza (Free My TV
dibuja su propia barra):
`adb shell pm disable com.droidlogic/.tvvolume.VolumeService`.

## 6. Firewall del ADB (`agent.sh`)

Qué hace:

- **El ADB por red:** el firmware lo deja abierto, como root y sin pedir permiso. Cualquiera en la red, y cualquier
  app del proyector vía 127.0.0.1, tenía root. `agent.sh` lo deja solo para el propio proyector, y adentro solo para
  Free My TV, root y shell.
- **El instalador remoto** (`ttyunos.rc`): en cada arranque bajaba scripts del fabricante y los corría como root,
  después de mandar MAC, serie y país. `agent.sh` vacía esos scripts, apunta `persist.sys.jm.url` a 127.0.0.1 y
  bloquea los servidores del fabricante.
- **La app de TV de Amlogic** se queda sin internet.
- **Audio Dolby mudo:** si existen los archivos del paso 2, monta la configuración de audio sin Dolby, y las apps
  eligen la pista AAC.

Es solo montajes y reglas en memoria: borrando el archivo y reiniciando, vuelve todo.

Comprobá primero las rutas del paso 2. Si algo no existe en este firmware, contámelo antes de seguir. Después:

```bash
adb push tools/proyector/agent.sh /data/local/agent.sh
adb shell "chown root:root /data/local/agent.sh; chmod 755 /data/local/agent.sh"
```

- El archivo tiene que quedar con fin de línea LF. En Windows con Git Bash, revisá que no tenga CRLF. Además, Git Bash
  cambia las rutas que empiezan con `/`: usá `MSYS_NO_PATHCONV=1` delante de `adb push`.
- Avisame antes de reiniciar o de correrlo a mano: después de eso, el ADB por red queda cerrado. Para volver a entrar:
  Ajustes de inicio → **ADB por red en el panel**. Después, en el panel rápido, el botón
  **ADB por red** lo abre hasta el próximo reinicio.
- Para sacarlo: `adb shell rm /data/local/agent.sh` y reiniciar.

## 7. El cambio de modo (lo hago yo)

1. **Antes, el Wi-Fi:** la red la guardó `com.htc.htcsettings`, que el cambio apaga. Hay que olvidar la red y volver
   a conectarse desde los ajustes **de Android**, no los del fabricante. Free My TV frena el cambio y lo avisa si
   hace falta. Las redes guardadas fuera de alcance se ven con `adb shell dumpsys wifi`; con mi OK se borran con
   `adb shell cmd wifi forget-network <id>`.
2. Explicame qué cambia: el inicio, se apagan las apps del grupo (e) y se prenden dos servicios de accesibilidad de
   Free My TV (los botones del control y Netflix con las flechas). *Original* devuelve todo.
3. Lo hago yo: abro **Free My TV** → **Pantalla de inicio** → **Free My TV**. El proyector puede pedir permiso de
   depuración: acepto.
4. Revisá:
   - `adb logcat -s FreeMyTV`;
   - `adb shell settings get secure enabled_accessibility_services`, que tiene que incluir
     `io.freemytv/io.freemytv.RemoteKeyService:io.freemytv/io.freemytv.NetflixNavService`.

   Si faltan, ponelos con
   `adb shell settings put secure enabled_accessibility_services io.freemytv/io.freemytv.RemoteKeyService:io.freemytv/io.freemytv.NetflixNavService`
   y `adb shell settings put secure accessibility_enabled 1`.

## 8. Apps de streaming (opcional; de dónde se bajaron)

En el de referencia se instalaron estas versiones (2026-11-03).

**De APKMirror:** antes de instalar, verificá la firma con `apksigner verify --print-certs` y que sea la del
desarrollador.

| App | Versión | Paquete | Dónde |
|---|---|---|---|
| YouTube para Android TV | 7.31.302 | `com.google.android.youtube.tv` | <https://www.apkmirror.com/apk/google-inc/youtube-for-android-tv-android-tv/youtube-for-android-tv-7-31-302-release/> |
| Prime Video para Android TV | 6.24.13 | `com.amazon.amazonvideo.livingroom` | <https://www.apkmirror.com/apk/amazon-mobile-llc/prime-video-android-tv-android-tv/prime-video-android-tv-6-24-13v16-0-1-93-allabis-release/> |
| Disney+ para Android TV | 26.20.0 | `com.disney.disneyplus` | <https://www.apkmirror.com/apk/disney/disney-android-tv/disney-android-tv-26-20-0rc1-2026-10-22-release/> |
| Netflix (la de celular) | 9.90.1 | `com.netflix.mediaclient` | <https://www.apkmirror.com/apk/netflix-inc/netflix/netflix-9-90-1-build-12-74736-release/> |
| Spotify para Android TV | 9.1.94 | `com.spotify.tv.android` | <https://www.apkmirror.com/apk/spotify-ltd/spotify-music-for-android-tv-android-tv/> |
| Flow Android TV | 4.112.1 | `ar.com.flow.androidtv` | <https://www.apkmirror.com/apk/cablevision-fibertel/flow-android-tv-android-tv/flow-android-tv-4-112-1-release/> |

**De código abierto, con firma del autor:**

| App | Versión | Paquete | Dónde |
|---|---|---|---|
| AirPlay Server (recibir de iPhone/Mac) | 0.0.36 | `io.github.jqssun.airplay` | <https://f-droid.org/packages/io.github.jqssun.airplay/> (código: <https://github.com/jqssun/android-airplay-server>) |
| TV Bro (navegador, sin Gecko) | 2.1.6 | `com.phlox.tvwebbrowser` | <https://github.com/truefedex/tv-bro/releases/download/v2.1.6/tvbro-2.1.6-generic-geckoExcluded.apk> |

- **Versión más nueva:** si hay una, usá esa, de la misma página de la app.
- **Variante:** la placa es de 32 bits (`armeabi-v7a`); bajá esa o la `universal`/`noarch`.
- **Archivos `.apkm` (paquetes divididos):** se descomprimen y se instalan base + `armeabi_v7a` + densidad + idiomas
  `es` y `en`, con `adb install-multiple base.apk split_config.armeabi_v7a.apk split_config.<dpi>.apk split_config.es.apk split_config.en.apk`.
- **AirPlay Server:** `adb install --abi armeabi-v7a <apk>`.
- **Netflix:** va la de celular. La de TV (`com.netflix.ninja`) pide un equipo certificado, y este no lo es. Free My
  TV la maneja con las flechas.
- **Calidad:** Widevine es L3, así que Netflix, Prime y Disney+ se ven en SD. No tiene arreglo.
- **Después de instalarlas:** `adb shell cmd package compile -m speed -f <paquete>`.

**Herramientas para la computadora:**

- adb (Android platform-tools): <https://developer.android.com/tools/releases/platform-tools>.
- Para compilar: JDK 17 (<https://adoptium.net/>) y los command-line tools del SDK de Android
  (<https://developer.android.com/studio#command-line-tools-only>). Ver `CLAUDE.md`.

## 9. Lo que no tiene arreglo (contámelo al final)

- SELinux apagado, firmware `userdebug/test-keys` y bootloader abierto. Solo se arreglan con otro firmware, y no hay
  uno oficial confiable.
- **Actualizaciones del fabricante:** no las instales. Pueden traer de vuelta todo lo que se apagó. Si alguna vez se
  actualiza, repetí los pasos 2, 5 y 6.
- El protector de pantalla no arranca solo: el firmware trae "apagar pantalla: nunca". Free My TV lo saca de sus
  ajustes en el HY300.

## 10. Cómo volver todo atrás

1. En el proyector: Free My TV → Pantalla de inicio → **Original**.
2. Volvé a prender lo que se apagó a mano, desde `proyector-<serie>.txt`:
   - `adb shell pm enable <paquete>`;
   - `adb shell pm unhide com.softwinner.tcorrection`;
   - `adb shell pm enable com.droidlogic/.tvvolume.VolumeService`.
3. Sacá el firewall: `adb shell rm /data/local/agent.sh` y reiniciar.
4. Recién ahí, si quiero, `adb uninstall io.freemytv`.

Al terminar, dame un resumen corto: qué quedó instalado, qué se apagó (y qué apps decidí conservar), dónde está
`proyector-<serie>.txt` y cómo abrir el ADB por red la próxima vez.
