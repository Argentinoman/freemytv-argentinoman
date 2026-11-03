#!/system/bin/sh
# Free My TV: the projector's ADB (root, no authorisation) only for the projector itself and, inside it, only for
# Free My TV (the mode switch and the projection panel use it) and the system. Other apps used it to get root.
# The quick panel's "ADB por red" opens it to the network until the next restart.
APP=$(stat -c %u /data/data/io.freemytv 2>/dev/null)
for t in iptables ip6tables; do
  $t -N fmtv_adb 2>/dev/null
  $t -F fmtv_adb
  $t -A fmtv_adb -i lo -j RETURN
  $t -A fmtv_adb -j DROP
  while $t -D INPUT -p tcp --dport 5555 -j fmtv_adb 2>/dev/null; do :; done
  $t -I INPUT -p tcp --dport 5555 -j fmtv_adb

  $t -N fmtv_adb_apps 2>/dev/null
  $t -F fmtv_adb_apps
  $t -A fmtv_adb_apps -m owner --uid-owner 0 -j RETURN
  $t -A fmtv_adb_apps -m owner --uid-owner 2000 -j RETURN
  [ -n "$APP" ] && $t -A fmtv_adb_apps -m owner --uid-owner $APP -j RETURN
  $t -A fmtv_adb_apps -j REJECT
  while $t -D OUTPUT -o lo -p tcp --dport 5555 -j fmtv_adb_apps 2>/dev/null; do :; done
  $t -I OUTPUT -o lo -p tcp --dport 5555 -j fmtv_adb_apps
done
