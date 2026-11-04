# Instalar Free My TV en el proyector HY300, a mano

`FreeMyTV-proyector.apk` es el inicio de Free My TV para el proyector **HY300** (Amlogic, Android 13), compilado de
la rama `proyector`.

- **Viene configurado para:** Buenos Aires, en °C y sin clave de TMDB. Sin esa clave no hay recomendaciones de
  películas; todo lo demás anda.
- **La ciudad** se cambia en el proyector: Ajustes de inicio → Ciudad.
- **Con un asistente** (Claude Code u otro), usá mejor [PROMPT-PROYECTOR.md](../PROMPT-PROYECTOR.md): hace lo mismo,
  revisa tu firmware y te pregunta antes de apagar cada cosa.

| Archivo | Versión | SHA-256 |
|---|---|---|
| `FreeMyTV-proyector.apk` | 2026-11-04, 1,7 MB | `348bccb8b48d15851364b3d8c90e2964413ee4c7efdb1f97942c84c4060cb172` |

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

Con eso ya tenés el inicio nuevo. Lo de seguridad (camino B) necesita una computadora.

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

La última es para el widget de música: solo lee qué se está reproduciendo.

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

### 6. El firewall del ADB (opcional, recomendado)

El archivo es [`tools/proyector/agent.sh`](../tools/proyector/agent.sh) de este repo. El firmware lo corre como root
en cada arranque, y:

- cierra el ADB a la red;
- neutraliza el instalador remoto del fabricante;
- arregla el audio Dolby que sonaba mudo en Disney+.

En Windows, bajalo con "Download raw file": tiene que quedar con fin de línea LF. Después:

```bash
adb push agent.sh /data/local/agent.sh
```

```bash
adb shell "chown root:root /data/local/agent.sh; chmod 755 /data/local/agent.sh"
```

```bash
adb reboot
```

**Después del reinicio, el ADB por red queda cerrado.** Para volver a entrar desde la computadora:

1. Free My TV → Ajustes de inicio → **ADB por red en el panel**.
2. En el panel rápido, el botón **ADB por red** lo abre hasta el próximo reinicio.

### 7. El cambio de modo

Igual que en los pasos 4 y 5 del camino A: primero el Wi-Fi desde los ajustes de Android, después Free My TV →
Pantalla de inicio → Free My TV.

## Apps de streaming

Las que andan bien en este proyector, y de dónde se bajaron: la tabla del paso 8 de
[PROMPT-PROYECTOR.md](../PROMPT-PROYECTOR.md#8-apps-de-streaming-opcional-de-dónde-se-bajaron). Se instalan con
`adb install -r <archivo>.apk`, o desde el pendrive igual que Free My TV.

- **Netflix:** va la de celular. La de TV no anda en este proyector.
- **Calidad:** Netflix, Prime y Disney+ se ven en SD (Widevine L3).

## Volver todo atrás

1. Free My TV → Pantalla de inicio → **Original**.
2. Volvé a prender lo que apagaste: `adb shell pm enable <paquete>`, y además
   `adb shell pm unhide com.softwinner.tcorrection`.
3. Sacá el firewall: `adb shell rm /data/local/agent.sh` y reiniciá.
4. Si querés, desinstalá Free My TV: `adb uninstall io.freemytv`.
