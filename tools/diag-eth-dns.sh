#!/system/bin/sh
echo "=== ping stale dns/gw ==="
ping -c 1 -W 2 192.168.1.1
ping -c 1 -W 2 192.168.1.252
echo "=== dhcp props ==="
getprop | grep -E 'dhcp\.eth0|net\.dns'
echo "=== ndc ==="
ls -la /system/bin/ndc /system/xbin/ndc 2>/dev/null
ndc 2>&1 | head -8
echo "=== cmd list net-ish ==="
cmd -l 2>/dev/null | grep -iE 'net|dns|resol|ether'
echo "=== try setnetdns variants ==="
su 0 ndc resolver setnetdns eth0 '' 8.8.8.8 8.8.4.4
echo "ndc_exit=$?"
su 0 ndc network default get
echo "=== link again ==="
dumpsys connectivity | grep -A2 'DnsAddresses'
