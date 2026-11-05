# Instalar Free My TV en el proyector HY300, a mano

`FreeMyTV-proyector.apk` es el inicio de Free My TV para el proyector **HY300** (Amlogic, Android 13), compilado de
la rama `proyector`.

> ⚠️ **Antes de usarlo así como viene, leé [SEGURIDAD-HY300.md](../SEGURIDAD-HY300.md):** el software de fábrica
> tiene un instalador remoto que corre como root, el ADB abierto a toda la red y apps con permisos de micrófono, cámara
> e instalación. Por eso este launcher reemplaza las apps del fabricante.

- **Viene configurado para:** Buenos Aires, en °C y sin clave de TMDB. Sin esa clave no hay recomendaciones de
  películas; todo lo demás anda.
- **La ciudad** se cambia en el proyector: Ajustes de inicio → Ciudad.
- **Con un asistente** (Claude Code u otro), usá mejor [PROMPT-PROYECTOR.md](../PROMPT-PROYECTOR.md): hace lo mismo,
  revisa tu firmware y te pregunta antes de apagar cada cosa.

| Archivo | Versión | SHA-256 |
|---|---|---|
| `FreeMyTV-proyector.apk` | 2026-11-05, 1,7 MB | `2e9c5e66796367a5c6d46b2875066be5129186c2ba77a8167ac0bd39a240f424` |

## Qué trae (reemplaza las apps del proyector)

Todo viene dentro del APK. Lo que se ve distinto:

- **Inicio:** apps, reloj, clima de 7 días, "Continuar viendo" (Netflix, Disney+, YouTube, Spotify, Prime, Flow),
  tus fotos de fondo y Mis favoritas, con el orden que quieras, en orden o al azar. Reemplaza al launcher chino, que
  hablaba con servidores del fabricante.
- **Proyección:** trapecio automático con el sensor, ángulos a mano, tamaño y modo de proyección (frontal, trasera,
  techo). Reemplaza a la app del fabricante, que mandaba datos afuera.
- **HDMI:** la entrada a pantalla completa, con "Sin señal" cuando no llega nada.
- **Inalámbrica:**
  - Android/Windows (Miracast) y Dispositivos Apple (AirPlay Server, que se instala aparte).
  - Cada uno se prende solo mientras lo usás.
- **Explorador de archivos y visor de fotos propios:**
  - el botón USB abre el pendrive;
  - ← → pasan las fotos;
  - mantener OK deja la foto de fondo.
- **Barra de volumen** fina, en vez del círculo gigante.
- **Netflix con las flechas:** la Netflix de celular, que es la única que anda acá, manejada con el control. Un aro
  blanco marca dónde estás.
- **Widget de música:** lo que suena en Spotify o YouTube.
- **Panel rápido** (botón de opciones del control): brillo, Proyección, notificaciones y ADB por red.
- **Ajustes:** fondos, parlante Bluetooth favorito, botones del control, ciudad e idioma.

## Antes de empezar

1. **Fijate el firmware:** Ajustes → Preferencias del dispositivo → Información → Compilación. Se probó con
   `be311-13 TQ2A.20250807`. Con otro número puede andar igual, pero algunas cosas pueden faltar o cambiar. Si no
   dice **be311**, no es el mismo equipo: las pantallas propias del proyector (Proyección, HDMI, Inalámbrica) no van
   a aparecer.
2. **No hace falta que lo actualices.** Las actualizaciones del fabricante pueden volver a prender lo que apagues.
3. **Qué cambia:**
   - el inicio;
   - se apagan (no se borran) el launcher chino y la capa de ajustes del fabricante;
   - el botón *Original* de la app devuelve todo como estaba.

## Camino A: sin computadora (solo el inicio)

1. Copiá `FreeMyTV-proyector.apk` a un pendrive y enchufalo al proyector.
   **Ojo:** que el pendrive no tenga una carpeta llamada `jm`. El firmware corre como root lo que haya ahí.
2. Abrí el explorador de archivos del proyector y elegí el APK.
3. Si dice que no puede instalar apps desconocidas: **Ajustes** → permitir esa fuente → volvé e instalá.
4. **El Wi-Fi primero:** en los ajustes **de Android**, olvidá tu red y volvé a conectarte. La red la guardó la capa
   de ajustes del fabricante, que se apaga con el cambio, y Android borraría la red. Si hace falta, Free My TV te
   frena y te lo avisa.
5. Abrí **Free My TV** (en Todas las apps) → **Pantalla de inicio** → **Free My TV**. Si pregunta por la depuración
   USB, aceptá y tildá *Permitir siempre*.

Con eso ya tenés el inicio nuevo. Para el widget de música: Ajustes de Android → Apps → Acceso especial → Acceso a
notificaciones → Free My TV. Si tu proyector no tiene esa pantalla, va por computadora (paso 4 del camino B).

**Lo que este camino no arregla:**

- El instalador remoto del fabricante sigue activo y puede volver a meter apps.
- El ADB sigue abierto como root.
- El audio Dolby de Disney+ sigue mudo.

Eso es el camino B.

## Camino B: con computadora (recomendado)

Así además se apagan el instalador remoto del fabricante y las apps sospechosas, y se cierra el ADB, que viene
abierto como root para cualquiera en tu red.

### 1. Las herramientas

Bajá **adb**: Android platform-tools, de <https://developer.android.com/tools/releases/platform-tools>. Descomprimilo
y abrí una terminal en esa carpeta (en Windows, `adb.exe`).

### 2. Conectate

Buscá la IP del proyector (Ajustes → Red) y conectate:

```bash
adb connect 192.168.1.50:5555
```

Probá que responda:

```bash
adb shell getprop ro.build.display.id
```

### 3. Guardá cómo está (para volver atrás)

```bash
adb shell pm list packages -d > apagadas-antes.txt
```

### 4. Instalá

```bash
adb install -r FreeMyTV-proyector.apk
```

```bash
adb shell cmd package compile -m speed -f io.freemytv
```

```bash
adb shell cmd notification allow_listener io.freemytv/io.freemytv.NowPlayingListener
```

```bash
adb shell appops set io.freemytv GET_USAGE_STATS allow
```

- El permiso de notificaciones es para el widget de música: solo lee qué se está reproduciendo.
- El último es para la fila de las apps que usaste hace poco.

### 5. Apagá lo peligroso

Una por una. Si alguna dice `Unknown package`, tu firmware no la trae: seguí con la siguiente.

```bash
adb shell pm disable-user --user 0 com.fb
```

```bash
adb shell pm disable-user --user 0 com.define.shellservice
```

```bash
adb shell pm disable-user --user 0 com.master.accessibility
```

```bash
adb shell pm disable-user --user 0 com.koushikdutta.superuser
```

```bash
adb shell pm disable-user --user 0 com.softwinner.tcorrection
```

```bash
adb shell pm hide com.softwinner.tcorrection
```

Qué es cada una:

- `com.fb`: instalaba apps como root desde servidores chinos.
- `com.define.shellservice`: una puerta trasera.
- `com.master.accessibility`: un "mouse" para Netflix que también instala en silencio. Free My TV trae el suyo.
- `com.koushikdutta.superuser`: el administrador de root.
- `com.softwinner.tcorrection`: el trapecio del fabricante, que manda datos afuera. Free My TV trae su pantalla de
  Proyección.

Tiendas y relleno: opcional, cada una con la misma línea `adb shell pm disable-user --user 0 <paquete>`:

- `com.gecen.store`
- `com.master.tv.market`
- `com.overseas.store.appstore`
- `com.android.camera2`
- `com.android.gallery3d`
- `com.android.printspooler`
- `com.google.android.tvrecommendations`
- `com.waxrain.airplayer2`
- `com.droidlogic.miracast`

Las dos últimas reciben pantalla del celular. Free My TV las prende solo mientras usás su pantalla *Inalámbrica*.

**Apps de IPTV, películas o fútbol que vinieron instaladas** (por ejemplo `com.android.mgstv`): **decidís vos**. Si
las usás, dejalas. Si no, se apagan igual que las de arriba. No toques nunca `com.droidlogic.android.tv`: sin esa,
el HDMI puede dejar de andar.

### 6. El cambio de modo

Igual que en los pasos 4 y 5 del camino A: primero el Wi-Fi desde los ajustes de Android, después Free My TV →
Pantalla de inicio → Free My TV.

Si vas a instalar apps de streaming (ver más abajo), hacelo ahora, antes del firewall.

### 7. El firewall del ADB: siempre lo último (opcional, recomendado)

> ⚠️ **Dejalo para el final**, cuando ya tengas todo instalado y probado. Después de reiniciar con el firewall, el ADB
> por red queda cerrado, y desde la computadora no vas a poder hacer nada hasta que lo abras desde el proyector.

El archivo es [`tools/proyector/agent.sh`](../tools/proyector/agent.sh) de este repo. El firmware lo corre como root
en cada arranque, y:

- cierra el ADB a la red;
- neutraliza el instalador remoto del fabricante;
- arregla el audio Dolby que sonaba mudo en Disney+.

Por qué hace falta:

- **El instalador remoto está en el firmware** (`ttyunos.rc`). En cada arranque baja scripts de servidores chinos y
  los corre como root: así instaló apps solo, apenas se conectó al Wi-Fi. El firewall vacía esos scripts y bloquea
  esos servidores.
- **El audio:** el proyector dice soportar Dolby pero no tiene la librería, y ese audio sale mudo. El firewall hace
  que las apps elijan la pista AAC.
- **Es reversible:** todo queda en memoria. Borrando el archivo y reiniciando, vuelve como estaba.

**Primero, mientras el ADB sigue abierto:**

1. Prendé la forma de volver a entrar: en el proyector, Free My TV → Ajustes de inicio → **ADB por red en el panel**.
   Fijate que en el panel rápido (botón de opciones del control) aparezca el botón **ADB por red**.
2. Sonido envolvente codificado en "Nunca" (también para el audio de Disney+):

   ```bash
   adb shell settings put global encoded_surround_output 1
   ```

3. Los restos de lo que bajó el instalador remoto se mueven a una carpeta aparte (no se borran):

   ```bash
   adb shell 'mkdir -p /data/local/fmtv/cuarentena && cd /data/local && for f in com.master.jmsu_*.apk rule install push uninstall shell; do [ -e $f ] && mv $f fmtv/cuarentena/; done'
   ```

   (Con comillas simples. Anda en PowerShell y en la Terminal de Mac o Linux; en el viejo "Símbolo del sistema" de
   Windows, no.)

**Después, el firewall.** En Windows, bajá `agent.sh` con "Download raw file": tiene que quedar con fin de línea LF.

```bash
adb push agent.sh /data/local/agent.sh
```

```bash
adb shell "chown root:root /data/local/agent.sh; chmod 755 /data/local/agent.sh"
```

```bash
adb reboot
```

**Listo: después del reinicio, el ADB por red queda cerrado.** Si alguna vez necesitás volver a entrar desde la
computadora, abrí el panel rápido y tocá **ADB por red**. Queda abierto hasta el próximo reinicio o hasta que lo
toques otra vez.

## Apps de streaming

Las que andan bien en este proyector, y de dónde se bajaron: la tabla del paso 8 de
[PROMPT-PROYECTOR.md](../PROMPT-PROYECTOR.md#8-apps-de-streaming-opcional-de-dónde-se-bajaron). Se instalan con
`adb install -r <archivo>.apk`, o desde el pendrive igual que Free My TV.

- **Netflix:** va la de celular. La de TV no anda en este proyector.
- **Calidad:** Netflix, Prime y Disney+ se ven en SD (Widevine L3).

## Volver todo atrás

Si pusiste el firewall, primero abrí **ADB por red** en el panel rápido y conectate (`adb connect <IP>:5555`).

1. Free My TV → Pantalla de inicio → **Original**.
2. Volvé a prender lo que apagaste: `adb shell pm enable <paquete>`, y además
   `adb shell pm unhide com.softwinner.tcorrection`.
3. Si querés, desinstalá Free My TV: `adb uninstall io.freemytv`.
4. **Último, el firewall:** `adb shell rm /data/local/agent.sh` y reiniciá.
