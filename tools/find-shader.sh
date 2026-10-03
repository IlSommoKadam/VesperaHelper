#!/system/bin/sh
APK=/data/app/~~vv1tFx6CpT4wE01q6o54FA==/com.vaonis.barnard-pdGLRyyr3GF2zvvMP6YxDQ==/base.apk
echo "=== unzip shaders ==="
unzip -l "$APK" | grep -iE 'glsl|shader|hypatie|frag|vert' | head -n 50
echo "=== counts ==="
grep -a -c 'version 320' "$APK" || true
grep -a -c '320 es' "$APK" || true
grep -a -c 'gl_FragColor' "$APK" || true
echo "=== snippets ==="
grep -a -o 'version 3[0-9][0-9] es' "$APK" | sort | uniq -c | head
