# ⚠️ Lo que encontramos en el software del proyector HY300

Revisamos el HY300 de referencia (firmware `be311-13 TQ2A.20250807`, Amlogic T950S, Android 13) en noviembre de 2026.
Todo lo de abajo lo vimos en ese equipo: en los permisos de cada app, en las direcciones que tienen adentro, en los
scripts del firmware y en el registro de lo que instaló. Otro firmware puede ser distinto, pero si tu proyector dice
**HY300** y trae el launcher `com.htc.htclauncherhighenglishd08`, lo más probable es que tenga lo mismo.

## En corto

El proyector viene con **un sistema de control remoto del fabricante**. En cada arranque, el firmware baja
instrucciones de servidores en China y las ejecuta con permisos totales (root), sin preguntar y sin que se vea nada en
pantalla. Antes de eso manda la MAC, el número de serie, el modelo y el país.

No es una posibilidad teórica. **El 3 de noviembre de 2026 a las 22:15, apenas lo conectamos al Wi-Fi, instaló y
desinstaló apps por su cuenta, y dejó un paquete cifrado.** Encima de eso:

- El ADB (el acceso de depuración) viene abierto a toda la red, como root y sin pedir permiso.
- Varias apps del fabricante tienen, de fábrica y sin que nadie los acepte, permisos de micrófono, cámara,
  ubicación, instalar y borrar apps, leer los registros del sistema y cambiar ajustes protegidos.

Con root y esos permisos, quien maneje esos servidores, o cualquiera en tu red Wi-Fi, puede hacer en el proyector lo
que quiera:

- sacar **capturas de la pantalla** o grabarla;
- leer lo que se muestra;
- instalar una app espía;
- usar el proyector como puerta de entrada a tu red.

**No encontramos pruebas de que lo hayan hecho.** Pero todo estaba preparado para hacerlo, y ya instalaron cosas sin
avisar. Por eso no alcanzaba con esconder esas apps: había que reemplazarlas y cortarles la salida.

## El detalle

### El instalador remoto del firmware

- `/system/etc/init/ttyunos.rc` es parte del sistema: no se puede borrar sin cambiar el firmware.
- **En cada arranque:**
  - copia `ttyunos.sh`, `v9ying.sh` y `custom.sh` a `/data/ttyunos`, una carpeta con permisos 777: cualquier app podía
    cambiarlos;
  - después los corre como root.
- **Qué hacen esos scripts:**
  - bajan por HTTP, sin cifrar, más scripts de `jm.v7ying.com`, `jm.v9ying.com` o de la dirección guardada en
    `persist.sys.jm.url`;
  - mandan MAC, número de serie, modelo y país a `123.207.77.74`;
  - traen "reglas" (`/data/local/rule/`) y ejecutan lo que digan.

  Al ir por HTTP, alguien en la misma red también podía meter sus propias órdenes.
- **Lo que hizo apenas se conectó** (quedó en `/data/local/install/script.log`):
  - bajó de `apk.v8ying.com` e instaló como root `TvMarket`, otra versión de `com.htc.htcsettings` y Netflix de
    celular;
  - desinstaló otras apps;
  - dejó un paquete cifrado, `/data/local/com.master.jmsu_*.apk`, que no sabemos qué hace.
- **Otras puertas al arrancar:** `/system/etc/init/preinstall.rc` instala como root lo que aparezca en
  `/data/preinstall`, y corre `/data/local/agent.sh` si existe. Nosotros usamos ese mismo lugar para el firewall.
- **Por pendrive:** un pendrive con una carpeta `jm` hace que el firmware instale sus APK y corra `jm/jm.sh` como root
  (`vendor.jm.usb.upgrade`, disparado por la app del trapecio). Cualquiera que enchufe un pendrive preparado se
  adueña del equipo.

### Las apps del fabricante

| App | Qué es | Qué encontramos |
|---|---|---|
| `com.htc.htclauncherhighenglishd08` | El inicio | Habla con `txy.jiumaoos.com:8089` (`/api/desk/getData`: el contenido del inicio lo decide el servidor) y con `otyfastapi.dangsfly.com`. Manda sus reportes de errores a Umeng (`errlog.umeng.com`, analítica de Alibaba). Tiene enlaces a `tvapk.net`, un sitio de APKs. |
| `com.htc.htcsettings` | La capa de ajustes | Permisos de fábrica: micrófono, cámara, ubicación precisa, identidad del equipo, instalar y borrar apps, leer los registros del sistema, cambiar ajustes protegidos y dibujar sobre otras apps. El instalador remoto la reemplazó por otra versión sin avisar. |
| `com.fb` ("JMFactory") | Menú de fábrica | Los mismos permisos que la anterior. Tiene servicios siempre prendidos (`FixService`, `SuperService`), un descargador y funciones para instalar apps en silencio desde `aliyun.v9ying.cn`. |
| `com.define.shellservice` | Sin nombre visible | Los mismos permisos. Corre comandos como sistema para **cualquier** app que se lo pida: una puerta trasera. Con eso, cualquier app instalada podía, por ejemplo, sacar capturas de pantalla o instalar otras apps. |
| `com.master.accessibility` ("NetflixMouse") | Un "mouse" para Netflix | Es un servicio de accesibilidad: ese tipo de servicio **puede leer todo lo que aparece en pantalla**. Arranca solo y también puede instalar en silencio. |
| `com.softwinner.tcorrection` | El trapecio | Manda datos a `txy.jiumaoos.com/netflix/dataSave`. Su servicio tiene las llaves de los scripts root del firmware (el pendrive con `jm` y `vendor.jm.apk.install`). Es persistente: Android la levanta aunque esté desactivada. |
| `com.koushikdutta.superuser` | Administrador de root | El `su` está en `/system/xbin`, al alcance de cualquier app con permiso de shell. |
| `com.android.mgstv` | Disfrazada de app del sistema | Es una app de IPTV brasileña (`com.interactive.brasiliptv`) con un nombre que la hace pasar por parte de Android. |
| `com.master.tv.market`, `com.gecen.store`, `com.overseas.store.appstore` | Tiendas de apps chinas | Ubicación, identidad del equipo, arrancan solas y dibujan sobre otras apps. Por ahí entra cualquier cosa. |
| `com.waxrain.airplayer2` | Receptor AirPlay | Arranca solo y queda escuchando en la red todo el tiempo. |

### El sistema en sí

- **ADB:** abierto en el puerto 5555, como root y sin pedir autorización (`service.adb.tcp.port` en
  `/vendor/build.prop`). Desde cualquier computadora o celular de tu red, un solo comando
  (`adb exec-out screencap`) saca una captura de lo que está proyectando. Otro instala lo que quiera. Y cualquier app
  del propio proyector podía conectarse a 127.0.0.1:5555 y ser root: así trabajaba el instalador.
- **SELinux apagado:** la protección de Android que separa las apps entre sí.
- **Firmware `userdebug/test-keys`:** firmado con claves de prueba públicas.
- **Bootloader abierto.**

Estos últimos tres no se arreglan sin otro firmware, y no hay uno oficial confiable.

## Qué hicimos

- **Reemplazamos cada app del fabricante por una nuestra**, con el código en este repo:
  - el inicio;
  - el trapecio y el modo de proyección;
  - la entrada HDMI;
  - Miracast y AirPlay: se prenden solo mientras se usan;
  - el explorador de archivos y el visor de fotos;
  - la barra de volumen;
  - Netflix con las flechas, que solo puede leer la pantalla de Netflix y no se conecta a nada;
  - el panel rápido y los ajustes.

  Así no hace falta ninguna de las originales. Ver [PROYECTOR.md](PROYECTOR.md).
- **Apagamos las del fabricante** con `pm disable-user`, sin desinstalar nada: con `pm enable` vuelven.
- **Firewall** ([`tools/proyector/agent.sh`](tools/proyector/agent.sh)), que corre como root en cada arranque:
  - cierra el ADB a la red, y adentro del proyector lo deja solo para Free My TV y el sistema;
  - vacía los scripts del instalador remoto y apunta su dirección a 127.0.0.1;
  - bloquea los servidores del fabricante;
  - deja sin internet la app de TV de Amlogic, que tiene que quedar prendida para el HDMI.

  Para trabajar desde una computadora, el ADB se abre a mano desde el panel rápido hasta el próximo reinicio.
- **Movimos a cuarentena** lo que dejó el instalador: el paquete cifrado, las reglas y los registros.
- **Las apps de streaming** las bajamos de fuentes con firma verificada (ver
  [PROMPT-PROYECTOR.md](PROMPT-PROYECTOR.md)), no de las tiendas del fabricante.

## Si tenés este proyector

- **Lo más importante:** no lo dejes en la misma red que tus computadoras y celulares con datos sensibles. Si tu
  router tiene **red de invitados**, conectalo ahí.
- **Si no vas a instalar nada de esto**, al menos no enchufes pendrives de otros, y no instales las actualizaciones
  del fabricante.
- **Para dejarlo limpio:** [PROMPT-PROYECTOR.md](PROMPT-PROYECTOR.md) con un asistente, o [apk/LEEME.md](apk/LEEME.md)
  a mano. Las dos guías preguntan antes de apagar cualquier app de streaming o IPTV que uses.
