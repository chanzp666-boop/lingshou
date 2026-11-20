#!/system/bin/sh
TAGS="BYDAutoYunDevice BYDAutoSensorDevice BYDAutoPM2p5Device BYDAutoStatisticDevice BYDAutoBodyworkDevice BYDAutoSettingDevice BYDAutoChargingDevice BMMCamProp CHIMEMCPY"
LOG=/data/local/tmp/byd-log-toggle.log

log() {
  echo "$(date '+%m-%d %H:%M:%S') $*" >> "$LOG"
  [ -f "$LOG" ] && [ "$(wc -c < "$LOG" | tr -d ' ')" -gt 65536 ] && \
    tail -n 200 "$LOG" > "$LOG.tmp" && mv "$LOG.tmp" "$LOG"
}

off() {
  for t in $TAGS; do setprop log.tag.$t S; setprop persist.log.tag.$t S; done
  log "off"; echo state=off
}

on() {
  for t in $TAGS; do setprop log.tag.$t ""; setprop persist.log.tag.$t ""; done
  log "on"; echo state=on
}

status() {
  if [ "$(getprop log.tag.BYDAutoYunDevice)" = "S" ]; then echo state=off; else echo state=on; fi
}

case "$1" in
  off|on|status) $1 ;;
  toggle) if [ "$(status | cut -d= -f2)" = "off" ]; then on; else off; fi ;;
  *) echo "usage: $0 status|off|on|toggle" ;;
esac
