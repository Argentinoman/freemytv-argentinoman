# MiracastReceiver para el RK3326 (el proyector de Cosquín)

La tarjeta Android/Windows de **Inalámbrica** abre [MiracastReceiver](https://github.com/weekdayjast/MiracastReceiver)
(MIT), compilado con estos cambios en vez del QuickShare de fábrica (Bozee, que corría como el usuario del sistema y
hablaba con un servidor en China).

Cambios (`fmtv.patch`, sobre el commit `3976a98`):

- **Sin Huawei Cast+:** se borran `app/libs/` (un `.jar` cerrado sin licencia) y `app/src/main/jniLibs/` (11
  librerías `.so` cerradas). El paquete `castplus` queda como una clase vacía con la misma forma.
- **Sin código nativo** (`app/src/main/cpp/`, que pide el NDK): AirPlay (FairPlay, ALAC) y Google Cast no arrancan. El
  proyector ya tiene AirPlay Server. Quedan Miracast y DLNA.
- **Sin los espejos de Aliyun** en `settings.gradle.kts`: todo baja de Google y Maven Central.
- **Solo 720p** en `wfd_video_formats`: la pantalla es de 720p y 1080p60 se trababa.
- Solo `armeabi-v7a`.

El anuncio Miracast (que una app común no puede hacer, porque pide un permiso de firma) lo hace Free My TV por el ADB
del proyector con `su`: `wpa_cli ... SET wifi_display 1` y `WFD_SUBELEM_SET 0 000600111c440032` (`WirelessActivity`).

Compilar:

```bash
git clone https://github.com/weekdayjast/MiracastReceiver.git && cd MiracastReceiver && git checkout 3976a98
rm -rf MiracastReceiver/app/libs MiracastReceiver/app/src/main/jniLibs MiracastReceiver/app/src/main/cpp
git apply <ruta>/fmtv.patch
cd MiracastReceiver && printf 'sdk.dir=C\\:/Users/<vos>/AppData/Local/Android/Sdk\n' > local.properties
./gradlew assembleRelease -x lintVitalReportRelease -x lintVitalAnalyzeRelease -x lintVitalRelease
```

Firmarlo (zipalign + apksigner) con una clave propia, siempre la misma, para poder actualizarlo.
