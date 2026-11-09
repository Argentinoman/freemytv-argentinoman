#!/system/bin/sh
# Free My TV, RK3326 "HY300" (Tianxing firmware): the firewall. Nothing in this firmware runs a script of ours at boot,
# so Free My TV runs this one through its own ADB as root (su) once the projector has started (HomeReceiver), and the
# quick panel's "ADB por red" opens ADB to the network until the next restart.
# Install: adb push agent-rk3326.sh /data/local/agent.sh; adb shell su 0 chmod 755 /data/local/agent.sh
# Remove: adb shell su 0 rm /data/local/agent.sh, and restart.

# ADB (no authorisation, and su for its shell): only for the projector itself and, inside it, only for Free My TV
# (the mode switch, the projection panel), root and the shell. Wireless debugging starts off at every boot.
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
settings put global adb_wifi_enabled 0

# The vendor's servers stay unreachable, should anything of theirs come back on: its settings layer and updater
# (shzhtxcz.com), the launcher's ads and recommendations (speed25.top, coocaa.asia, skysrt.com, openzapptv.com) and
# QuickShare's (120.26.86.122).
for t in iptables ip6tables; do
  $t -N fmtv_vendor 2>/dev/null
  $t -F fmtv_vendor
  while $t -D OUTPUT -j fmtv_vendor 2>/dev/null; do :; done
  $t -I OUTPUT -j fmtv_vendor
done
for ip in 47.113.90.237 120.26.86.122 47.253.204.65 43.172.180.24 183.62.168.221 43.135.209.31; do
  iptables -A fmtv_vendor -d $ip -j REJECT
done
ip6tables -A fmtv_vendor -d 240d:c000:f020:f600:8ed2:ffd0:3246:0 -j REJECT
# QuickShare (the Android card of "Inalámbrica", on only while in use) talks to the local network only.
QS=$(stat -c %u /data/data/com.bozee.usbdisplay 2>/dev/null)
if [ -n "$QS" ]; then
  for net in 10.0.0.0/8 172.16.0.0/12 192.168.0.0/16 224.0.0.0/4 127.0.0.0/8; do
    iptables -A fmtv_vendor -m owner --uid-owner $QS -d $net -j RETURN
  done
  iptables -A fmtv_vendor -m owner --uid-owner $QS -j REJECT
  ip6tables -A fmtv_vendor -m owner --uid-owner $QS ! -o lo -j REJECT
fi

# Done for this boot: Free My TV does not run it again until the next restart.
setprop sys.fmtv.fw 1
