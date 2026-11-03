#!/system/bin/sh
# Free My TV: ADB over the network only from the projector itself (the mode switch uses 127.0.0.1).
# The quick panel's "ADB por red" opens it until the next restart.
for t in iptables ip6tables; do
  $t -N fmtv_adb 2>/dev/null
  $t -F fmtv_adb
  $t -A fmtv_adb -i lo -j RETURN
  $t -A fmtv_adb -j DROP
  while $t -D INPUT -p tcp --dport 5555 -j fmtv_adb 2>/dev/null; do :; done
  $t -I INPUT -p tcp --dport 5555 -j fmtv_adb
done
