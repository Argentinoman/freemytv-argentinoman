# Prompt: llevar las mejoras del fork a Samsung (Tizen) y LG (webOS)

Copiá todo lo de abajo en un chat nuevo de Claude Code, abierto en la carpeta del repo.

---

Trabajás en mi fork de Free My TV (repo `Argentinoman/freemytv-argentinoman`). Leé primero `CLAUDE.md` (en especial la
parte de LG y Samsung, `web/`) y `CAMBIOS.md`, que describe todo lo que agregué en la versión Android
(`app/src/io/freemytv/`). Quiero portar **una parte** de esas mejoras a la versión web (`web/`), que es la misma para
Samsung (Tizen, `tizen/`) y LG (webOS, `webos/`).

## Restricciones del código web (no negociables)

- JavaScript **ES5** plano y CSS sin variables ni grid (`backdrop-filter`, solo como mejora detectada: ver el punto 6): tiene que andar en el Chromium de 2015–2017
  (webOS 3–4, Tizen 3). Solo se animan `transform` y `opacity`. Sin frameworks ni librerías.
- Texto: `tr(pt, en, es)`; alemán y ruso en `web/strings.js` (clave = el inglés). La UI nueva va en castellano
  rioplatense, como en Android.
- `web/home.js` sigue el diseño y comportamiento de `HomeView.java`: si portás algo, mirá cómo lo hace Android y
  replicalo.
- Vista previa en la PC: `python3 webos/build.py --preview` y abrir `out/webos/index.html` (una maqueta lista apps de
  ejemplo). Build Samsung: `python3 tizen/build.py --sign <perfil>`; LG: `python3 webos/build.py`.
- Nunca commitear `config.properties`, claves ni certificados.
- ESPN y Wikimedia Commons responden con `Access-Control-Allow-Origin: *`, y `tizen/config.xml` ya tiene
  `<access origin="*">`: se pueden pedir directo con XHR.

## Qué portar

### 1. Fútbol (reemplaza a las criptomonedas)

Hoy la web muestra precios de cripto (Binance) en el cajón y en la fila de pestañas. Reemplazalos por fútbol, como en
Android (`Futbol.java`, `HomeView.drawFutbolRow`, `HomeView.drawFutbolRows`, `DreamView.drawGames`):

- **Fuente:** el scoreboard público de ESPN, sin clave:
  `https://site.api.espn.com/apis/site/v2/sports/soccer/<liga>/scoreboard` (hoy) y `?dates=YYYYMMDD` (otros días).
- **Ligas, en este orden de peso** (si dos coinciden, la primera va antes): `fifa.world`, `conmebol.america`,
  `fifa.worldq.conmebol`, `conmebol.libertadores`, `conmebol.sudamericana`, `conmebol.recopa`, `uefa.champions`,
  `uefa.europa`, `arg.supercopa.internacional`, `arg.trofeo_de_la_campeones`, `arg.supercopa`, `arg.copa_de_la_superliga`,
  `arg.copa`, `arg.1`.
- **Nombres cortos** de competición: Mundial, Copa América, Eliminatorias, Libertadores, Sudamericana, Recopa, Champions,
  Europa League, Supercopa Internacional, Trofeo de Campeones, Supercopa Argentina, Copa de la Liga, Copa Argentina, Liga.
- **Estados:** `pre`/`in`/`post` de ESPN. Además, un partido "pre" cuya hora de inicio ya pasó (y hace menos de 150 min)
  cuenta como **en juego** y muestra "Empezó HH:MM" hasta que llega el marcador (ESPN tarda en marcarlo).
- **Debajo del reloj:** si hay partidos en vivo, se muestran **solo los en vivo**: 1 → ese solo; 2 o 3 → juntos, uno
  debajo del otro, un poco más chicos; 4 o más → de a 3, turnándose cada 12 s. Si no hay ninguno en vivo, se turnan cada
  12 s los que terminaron hace menos de 1 h y los que empiezan en menos de 3 h. Sin puntitos de página.
- **Formato de cada línea:** escudo, `Local  1 – 0  Visitante` (o `vs` antes de empezar), escudo, estado: `EN VIVO 63'`
  (rojo `#FF453A`), `Entretiempo`, `Final`, `Hoy 21:30`, `Mañana 19:15`, `Dom 19:15`, `13/11 17:00`.
- **Cajón, tarjeta "Próximos partidos":** agrupados con un título gris por grupo ("EN VIVO", "FECHA 14 · LIGA
  PROFESIONAL", "SEMIFINAL · LIBERTADORES"…); solo el estado "EN VIVO" de cada partido va en rojo, nunca el título del
  grupo. Varias páginas con puntitos (visibles solo con la tarjeta seleccionada; OK y ← → para pasar de página).
- **Protector de pantalla:** la misma regla que debajo del reloj (se turnan cada minuto, que es lo que tarda en
  redibujarse).
- **Actualización:** cada 5 min; cada minuto si hay uno en vivo o "empezado"; la semana completa (2 días atrás, 8
  adelante) cada 3 h.
- **Ajustes:** un interruptor "Fútbol" (en lugar de cripto) en los ajustes de la app web.

### 2. Pronóstico extendido de 7 días

Hoy `web/data.js` pide `forecast_days=2` a Open-Meteo. Pedí 7 y agregale a la tarjeta del clima del cajón una segunda
página "<Ciudad> · 7 días": por día, el nombre corto (Hoy, Lun, Mar…), el ícono, la máxima y la mínima (la mínima más
tenue). Puntitos de página como en la de partidos.

### 3. Fondos de Wikimedia Commons (versión reducida)

En Android hay fondos para el inicio y el protector (`Gallery.java`). En la web, solo:

- **Categorías:** Paisajes, Arte y los **temas de temporada** (ver abajo). Nada de Arte abstracto, Animales ni favoritas.
- **Fuente:** API de Commons con `origin=*`. Paisajes: categorías `Featured pictures of landscapes` y
  `Featured pictures of mountains` (`generator=categorymembers`). Arte: `Featured pictures of paintings from <país>`
  (Francia, Países Bajos, Japón, Noruega, España, Italia, Estados Unidos, Rusia).
- **Filtro:** solo apaisadas (ancho/alto entre 1,3 y 2,4), originales de 1600 px o más, sin videos ni documentos
  (`.webm`, `.ogv`, `.mp4`, `.pdf`, `.djvu`), y sin títulos con escenas crudas (martyr, execution, massacre, beheading,
  holofernes, carcass, killed…).
- **Calidad sobre cantidad:** de cada categoría, las **20** con el original más grande; mostrarlas a 1920 de ancho
  (`iiurlwidth=1920`). La lista se renueva cada semana (guardala en `localStorage`).
- **Tiempos:** el fondo del inicio cambia cada 6 h; la foto del protector, cada 30 s.
- **Legibilidad:** sobre una foto, el texto va blanco con un velo oscuro detrás (como el "night look").

### 4. Temas de temporada

Una opción "Tema de temporada" en los ajustes (Desactivado, Halloween, Navidad, Año Nuevo, Carnaval, Pascuas,
Cumpleaños). Mientras uno está activo, el fondo del inicio y las fotos del protector salen de ese tema; al desactivarlo,
vuelve lo elegido antes. Fuente, `generator=search` de Commons (recorre subcategorías con `deepcat:`):

| Tema | Búsqueda |
|---|---|
| Halloween | `deepcat:"Jack-o'-lantern"` |
| Navidad | `deepcat:"Christmas lights" incategory:"Quality images"` y `deepcat:"Christmas trees" incategory:"Featured pictures on Wikimedia Commons"` |
| Año Nuevo | `deepcat:"New Year fireworks"` |
| Carnaval | `deepcat:"Carnival in Venice"` y `deepcat:"Carnival masks"` |
| Pascuas | `deepcat:"Easter decorations"` |
| Cumpleaños | `deepcat:"Birthday cakes"` |

Mismo filtro y las mismas 20 por tema. Pedí de a una página de 50 con 1,5 s entre pedidos (Wikimedia limita).

### 5. Botón "Navegador"

En los accesos directos del cajón, junto a Todas las apps y Ajustes: abre el navegador de la tele (Samsung:
`org.tizen.browser`; LG: `com.webos.app.browser`). Solo si está instalado.

### 6. Que se vea como la tele Android

La referencia visual es la versión Android en mi TCL (capturas en `screenshots/`). Tiene que parecerse lo más posible:

- **Logos del estante:** reusar los de Android, `app/assets/logos/` (netflix, youtube, prime, disney, max, spotify),
  copiados a `web/icons/` y asociados a los ids de esas apps en Samsung y LG. Hoy la web no tiene logo de Disney+.
  No usar los íconos que da Samsung (en Tizen 3 no cargan; en otras son genéricos).
- **Tipografía:** Inter, como en Android (ya está en la web; verificar que se use en todo lo nuevo).
- **Cajón:** misma disposición que Android (`HomeView.layoutDrawer`): fila de entradas y accesos directos con
  "Navegador" al final; abajo, tarjeta del clima (izquierda, más ancha, con las dos páginas) y tarjeta de partidos
  (derecha). Mismos radios, márgenes y tamaños de letra relativos.
- **Vidrio esmerilado:** como en Android (y como la propia interfaz de Samsung), con `backdrop-filter: blur(...)` en
  las tarjetas, el estante y el cajón, **solo donde la tele lo soporte** (Tizen 6+, teles de 2021 en adelante; detectarlo
  con `CSS.supports` y agregar una clase al `<body>`). En las teles viejas, el fondo translúcido liso de hoy. Medir que
  no trabe la animación del cajón; si traba, desenfocar solo cuando está quieto.
- **Con fondo de foto:** el aspecto de noche (texto blanco y un velo oscuro sobre la foto), como Android.

## Qué NO portar

"Continuar viendo", el reproductor de música, los botones del control fuera de la app, el panel rápido, la animación de
apagado, el Top 3 de Spotify, Arte abstracto, Animales y las favoritas. Samsung ya resuelve "Continuar viendo" y el
historial con su propio inicio.

## Cómo trabajar

1. Leé `web/home.js`, `web/data.js` y `web/tv.js` enteros antes de tocar nada, y los equivalentes de Android.
2. Implementá de a una función, en este orden: logos y cajón como Android, fútbol, 7 días, Navegador, fondos, temas. Después de cada una, probá en
   la vista previa (`python3 webos/build.py --preview`) y mostrame una captura.
3. Medí que no se trabe: la tele más vieja (Tizen 3) es lenta; nada de redibujar todo en cada segundo.
4. Para probar en una Samsung real: modo desarrollador (Apps → `1 2 3 4 5`), la IP de la PC en *Host PC IP*, y
   `python3 tizen/install.py <IP de la tele>`. Las de 2023 en adelante necesitan un certificado de Samsung (ver
   `CLAUDE.md`).
5. Commits chicos, uno por función, y actualizá `CAMBIOS.md` y el README.
