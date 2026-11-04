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
for t in iptables ip6tables; do
  $t -N fmtv_vendor 2>/dev/null
  $t -F fmtv_vendor
  while $t -D OUTPUT -j fmtv_vendor 2>/dev/null; do :; done
  $t -I OUTPUT -j fmtv_vendor
done
for ip in 8.138.18.183 115.126.61.77 123.207.77.74; do iptables -A fmtv_vendor -d $ip -j REJECT; done
# The board's live TV app stays (it holds TV_INPUT_HARDWARE: off, HDMI may stop for good), without internet: it only
# carried a British Freeview client. Free My TV's own HDMI view is what the HDMI button opens.
LIVETV=$(stat -c %u /data/data/com.droidlogic.android.tv 2>/dev/null)
[ -n "$LIVETV" ] && for t in iptables ip6tables; do $t -A fmtv_vendor -m owner --uid-owner $LIVETV ! -o lo -j REJECT; done

# Dolby: the board claims it (an audio output that takes AC3/E-AC3, decoders for both) but lacks Dolby's library, so
# that audio plays as silence (Disney+ episodes and ads). The vendor's own config without the raw formats goes over the
# one in use, and an empty list over the Dolby decoders: apps then pick the AAC track. Only mounted: a restart without
# this script brings it all back. The audio and media services restart to read them.
POLICY=/vendor/etc/audio_policy_configuration.xml DDP=/vendor/etc/media_codecs_amlogic_audio_ddp.xml
if [ -f /vendor/etc/audio_policy_configuration_default.xml ] && ! grep -q "Free My TV" $DDP 2>/dev/null; then
  mkdir -p /data/local/fmtv
  printf '<?xml version="1.0" encoding="utf-8" ?>\n<!-- Free My TV: no Dolby decoders (no Dolby library: they play silence) -->\n<Included>\n    <Decoders>\n    </Decoders>\n</Included>\n' > /data/local/fmtv/media_codecs_amlogic_audio_ddp.xml
  chmod 644 /data/local/fmtv/media_codecs_amlogic_audio_ddp.xml
  mount --bind /vendor/etc/audio_policy_configuration_default.xml $POLICY
  mount --bind /data/local/fmtv/media_codecs_amlogic_audio_ddp.xml $DDP
  killall audioserver mediaserver 2>/dev/null
fi
