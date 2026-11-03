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

# The firmware's own remote control (ttyunos.rc): at every boot it downloaded scripts from the vendor and ran them as
# root (reporting the MAC, serial and country first). Its scripts in /data/ttyunos stay emptied and root's, and the
# vendor's servers stay unreachable (jm.v7ying.com and jm.v9ying.com, aliyun.v9ying.cn, txy.jiumaoos.com).
for f in custom ttyunos v9ying; do
  printf '#!/system/bin/sh\n# Free My TV: neutralizado\nexit 0\n' > /data/ttyunos/$f.sh
done
chown -R root:root /data/ttyunos; chmod 755 /data/ttyunos /data/ttyunos/*.sh
setprop persist.sys.jm.url http://127.0.0.1
iptables -N fmtv_vendor 2>/dev/null
iptables -F fmtv_vendor
for ip in 8.138.18.183 115.126.61.77 123.207.77.74; do iptables -A fmtv_vendor -d $ip -j REJECT; done
while iptables -D OUTPUT -j fmtv_vendor 2>/dev/null; do :; done
iptables -I OUTPUT -j fmtv_vendor
