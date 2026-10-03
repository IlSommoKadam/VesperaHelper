#!/system/bin/sh
# Inject microG compatibility hooks into Singularity after its VM is up.
INJECT=/data/local/tmp/frida-inject
SCRIPT=/system/etc/gms-spoof.js
MARK=/data/local/tmp/gms-spoof.injected
log=/data/local/tmp/gms-spoof-inject.log

setprop wrap.com.vaonis.barnard "" 2>/dev/null

while true; do
  pid=$(pidof com.vaonis.barnard 2>/dev/null)
  [ -z "$pid" ] && pid=$(pidof .vaonis.barnard 2>/dev/null)
  if [ -n "$pid" ]; then
    last=$(cat "$MARK" 2>/dev/null)
    if [ "$last" != "$pid" ] && [ -x "$INJECT" ] && [ -f "$SCRIPT" ]; then
      echo "inject $pid $(date)" >> "$log"
      "$INJECT" -e -p "$pid" -s "$SCRIPT" >> "$log" 2>&1
      echo "$pid" > "$MARK"
    fi
  else
    rm -f "$MARK"
  fi
  sleep 2
done
