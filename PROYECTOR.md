# Rama `proyector`

Una versión recortada de este fork para proyectores Android (los chinos), más liviana que la de la TCL. Se mantiene
aparte de `master`: lo que se arregle allá se trae con `git merge master` (o `cherry-pick`).

## Qué tiene

- Estante de apps, reloj y clima (con el pronóstico de 7 días en el cajón).
- Continuar viendo (Netflix, Disney+, YouTube, Spotify, Prime Video).
- Panel rápido, con el brillo del inicio.
- El cajón de abajo: a la izquierda (*Entradas*) HDMI, **Explorador de archivos** (el primero instalado: el de la placa,
  X-plore, Files de Google…; sin ninguno, abre la Play Store para bajar uno) y **Parlante** (abre los dispositivos
  Bluetooth; se ve apagado mientras no hay un parlante conectado, y lleva el nombre del favorito, que se elige en
  *Ajustes de inicio → Parlante favorito*). A la derecha (*Accesos directos*) Todas las apps, Ajustes del TV y **Play
  Store**, que ya no aparece en Todas las apps.
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
