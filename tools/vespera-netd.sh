#!/system/bin/sh
# Root helper for VesperaHelper on Ethernet+WiFi Pi images.
# The app cannot run `su` (SELinux). This daemon applies:
#   - cmd wifi connect-network  (system-wide SSID, OwnerUid 1000)
#   - ip rule for 10.0.0.0/24 via wlan0 (Singularity reaches API without VPN)
#   - start-singularity: launch only if not running (no force-stop); UI stays in background
#   - restart-singularity: force-stop + relaunch without taking the screen
#     (Singularity comes to front only if the user opens it by hand)
#   - keep VesperaHelper FGS alive after crash / force-stop / swipe-away (resume photo sync)
#   - list-disks / mount-disk / umount-disk / disk-status / ensure-bind  (USB HD)
#
# Start once after boot (adb root):
#   adb push tools/vespera-netd.sh /data/local/tmp/vespera-netd.sh
#   adb shell chmod 755 /data/local/tmp/vespera-netd.sh
#   adb shell "/data/local/tmp/vespera-netd.sh >/data/local/tmp/vespera-netd.log 2>&1 &"

SINGULARITY_PKG="com.vaonis.barnard"
# -p alone does not resolve MAIN/LAUNCHER on this image (am start result -91).
SINGULARITY_ACTIVITY="com.vaonis.barnard/.ui.mainactivity.MainActivity"
HELPER_PKG="com.vaonis.vesperahelper"
# Prefer the real emulated-storage inode. /sdcard is FUSE and can deadlock rm/cat from root.
if [ -d "/data/media/0/Android/data/$HELPER_PKG/files" ]; then
  REQ_DIR="/data/media/0/Android/data/$HELPER_PKG/files"
else
  REQ_DIR="/sdcard/Android/data/$HELPER_PKG/files"
fi
REQ_FILE="$REQ_DIR/net.req"
SINGULARITY_REQ="$REQ_DIR/singularity.req"
ACK_FILE="$REQ_DIR/net.ack"
SINGULARITY_ACK="$REQ_DIR/singularity.ack"
DISK_REQ="$REQ_DIR/disk.req"
DISKS_ACK="$REQ_DIR/disks.ack"
MOUNT_ACK="$REQ_DIR/mount.ack"
PROBE_REQ="$REQ_DIR/probe.req"
PROBE_ACK="$REQ_DIR/probe.ack"
HD_MOUNT="/mnt/vespera-hd"
HD_STATE="/data/local/tmp/vespera-hd.state"
HD_USB_STATE="/data/local/tmp/vespera-hd-usb"
HD_USB_PORTS="/data/local/tmp/vespera-hd-usb-ports"
HD_USB_ID="/data/local/tmp/vespera-hd-usb-id"
NTFS_DIR="/data/local/tmp/ntfs"
NTFS_BIN="$NTFS_DIR/mount.ntfs"
TABLE=94
PRIORITY=94

mkdir -p "$REQ_DIR" 2>/dev/null

# Cache once. Never stat/chown from the request loop: sdcardfs can deadlock
# (`stat` on the app files dir stuck the daemon, so the app reported it missing).
OWNER_UID=$(timeout 1 stat -c %u "$REQ_DIR" 2>/dev/null)
OWNER_GID=$(timeout 1 stat -c %g "$REQ_DIR" 2>/dev/null)

# Prefer truncating an app-owned inode in place. Fall back to cached chown/chmod.
publish_file() {
  path="$1"
  chmod 666 "$path" 2>/dev/null
  if [ -n "$OWNER_UID" ] && [ -n "$OWNER_GID" ]; then
    chown "$OWNER_UID:$OWNER_GID" "$path" 2>/dev/null
  fi
}

write_ack() {
  echo "$1" > "$ACK_FILE"
  publish_file "$ACK_FILE"
}

write_singularity_ack() {
  echo "$1" > "$SINGULARITY_ACK"
  publish_file "$SINGULARITY_ACK"
}

apply_route() {
  # Policy rule must beat Tailscale/eth0 defaults so 10.0.0.1 uses wlan0.
  ip route replace 10.0.0.0/24 dev wlan0 table "$TABLE" 2>/dev/null
  ip route replace 10.0.0.0/24 dev wlan0 2>/dev/null
  if ! ip rule 2>/dev/null | grep -q '10\.0\.0\.0/24'; then
    ip rule add to 10.0.0.0/24 lookup "$TABLE" priority "$PRIORITY" 2>/dev/null \
      || ip rule add to 10.0.0.0/24 lookup "$TABLE" priority 50 2>/dev/null
  fi
  write_ack "route-ok $(date)"
}

api_reachable() {
  # Prefer source IP on wlan0 so checks work even if policy rules briefly lag.
  API_PORT=""
  wlan_ip=$(ip -4 -o addr show wlan0 2>/dev/null | awk '{print $4}' | cut -d/ -f1 | head -n 1)
  for port in 8083 8082; do
    if [ -n "$wlan_ip" ] && nc -z -w2 -s "$wlan_ip" 10.0.0.1 "$port" >/dev/null 2>&1; then
      API_PORT="$port"
      return 0
    fi
    if nc -z -w2 10.0.0.1 "$port" >/dev/null 2>&1; then
      API_PORT="$port"
      return 0
    fi
  done
  return 1
}

write_probe_ack() {
  echo "$1" > "$PROBE_ACK"
  publish_file "$PROBE_ACK"
}

probe_api() {
  apply_route
  if api_reachable; then
    write_probe_ack "api-port-$API_PORT $(date)"
    write_ack "probe-api $API_PORT $(date)"
    return 0
  fi
  write_probe_ack "api-port-none $(date)"
  write_ack "probe-api none $(date)"
  return 1
}

promote() {
  ssid="$1"
  bssid="$2"
  if [ -z "$ssid" ] || [ -z "$bssid" ]; then
    write_ack "promote-bad-args"
    return 1
  fi
  cmd wifi connect-network "$ssid" open -b "$bssid"
  sleep 2
  apply_route
  write_ack "promote-ok $ssid $(date)"
}

check_singularity() {
  # Helper may hold the Vespera network without it being the "current" wifi status SSID.
  wifi_blob=$(cmd wifi status 2>/dev/null; dumpsys wifi 2>/dev/null | head -n 80)
  if ! echo "$wifi_blob" | grep -qiE 'vespera|VESPERAPRO'; then
    if ! ip route 2>/dev/null | grep -q '10\.0\.0\.0/24'; then
      write_singularity_ack "singularity-missing wifi-not-vespera $(date)"
      return 1
    fi
  fi
  # Ensure route only if API probe fails (avoid bouncing rules / dropping sockets).
  if ! api_reachable; then
    apply_route
    if ! api_reachable; then
      write_singularity_ack "singularity-missing vespera-api-down $(date)"
      return 1
    fi
  fi
  PID=$(singularity_pid)
  if [ -z "$PID" ]; then
    write_singularity_ack "singularity-disconnected not-running $(date)"
    return 1
  fi
  # ss on this image often omits uid= and uses IPv4-mapped IPv6 addresses.
  port_tag=""
  [ -n "$API_PORT" ] && port_tag=" port=$API_PORT"
  if ss -tnp state established 2>/dev/null | grep -E 'barnard|vaonis' | grep -qE '10\.0\.0\.1:808[23]|\[::ffff:10\.0\.0\.1\]:808[23]'; then
    write_singularity_ack "singularity-connected socket$port_tag $(date)"
    return 0
  fi
  UID=$(dumpsys package "$SINGULARITY_PKG" 2>/dev/null | sed -n 's/.*userId=//p' | head -1)
  if [ -n "$UID" ] && ss -tnp state established 2>/dev/null | grep "uid=$UID" | grep -qE '10\.0\.0\.1:808[23]|\[::ffff:10\.0\.0\.1\]:808[23]'; then
    write_singularity_ack "singularity-connected socket-uid$port_tag $(date)"
    return 0
  fi
  if grep -qE '0100000A:1F63|0100000A:1F62' "/proc/$PID/net/tcp" 2>/dev/null \
    || grep -qE '00000000000000000000FFFF0100000A:1F63|00000000000000000000FFFF0100000A:1F62' "/proc/$PID/net/tcp6" 2>/dev/null; then
    write_singularity_ack "singularity-connected proc-tcp$port_tag $(date)"
    return 0
  fi
  write_singularity_ack "singularity-disconnected no-socket$port_tag $(date)"
  return 1
}

write_disks_ack() {
  echo "$1" > "$DISKS_ACK"
  publish_file "$DISKS_ACK"
}

write_mount_ack() {
  echo "$1" > "$MOUNT_ACK"
  publish_file "$MOUNT_ACK"
}

photos_dir() {
  if [ -d "/data/media/0/Android/data/$HELPER_PKG/files" ]; then
    echo "/data/media/0/Android/data/$HELPER_PKG/files/vespera-photos"
  else
    echo "$REQ_DIR/vespera-photos"
  fi
}

# True if path is a mountpoint (exact /proc/mounts field 2, plus sdcard pass-through).
path_mounted() {
  p="$1"
  [ -n "$p" ] || return 1
  awk -v p="$p" '$2 == p { found=1 } END { exit found ? 0 : 1 }' /proc/mounts && return 0
  case "$p" in
    /data/media/0/*)
      alt="/mnt/pass_through/0/emulated/0/${p#/data/media/0/}"
      awk -v p="$alt" '$2 == p { found=1 } END { exit found ? 0 : 1 }' /proc/mounts
      ;;
    *) return 1 ;;
  esac
}

# Re-bind HD into the app-visible folder when /mnt/vespera-hd is up but the
# bind vanished (daemon restart used to umount it; do not recreate USER on the
# empty emulated dir — that looks mounted to the app).
ensure_photos_bind() {
  bind=$(photos_dir)
  if [ -f "$HD_STATE" ]; then
    state_bind=$(grep '^BIND=' "$HD_STATE" | cut -d= -f2)
    state_mnt=$(grep '^MOUNT=' "$HD_STATE" | cut -d= -f2)
    [ -n "$state_bind" ] && bind="$state_bind"
    HD_REAL="$HD_MOUNT"
    [ -n "$state_mnt" ] && HD_REAL="$state_mnt"
  else
    HD_REAL="$HD_MOUNT"
  fi
  mkdir -p "$bind" 2>/dev/null
  if path_mounted "$bind"; then
    mkdir -p "$bind/USER" 2>/dev/null
    touch "$bind/.nomedia" 2>/dev/null
    chmod 777 "$bind" "$bind/USER" 2>/dev/null
    return 0
  fi
  if ! path_mounted "$HD_REAL"; then
    return 1
  fi
  umount "$bind" 2>/dev/null
  if mount --bind "$HD_REAL" "$bind"; then
    chmod 777 "$bind" 2>/dev/null
    mkdir -p "$bind/USER" 2>/dev/null
    touch "$bind/.nomedia" 2>/dev/null
    chmod 777 "$bind/USER" 2>/dev/null
    echo "bind-restored $HD_REAL -> $bind $(date)" >&2
    return 0
  fi
  echo "bind-fail $HD_REAL -> $bind $(date)" >&2
  return 1
}

# /system/bin/blkid (e2fsprogs) hangs on NTFS. toybox blkid is enough.
# Do not wrap it in `timeout`: toybox timeout can kill this daemon's process group.
blkid_probe() {
  dev="$1"
  [ -z "$dev" ] && return 0
  toybox blkid "$dev" 2>/dev/null
}

blkid_field() {
  dev="$1"
  key="$2"
  blob=$(blkid_probe "$dev")
  echo "$blob" | sed -n "s/.*${key}=\"\([^\"]*\)\".*/\1/p" | head -n 1
}

block_node() {
  p="$1"
  [ -z "$p" ] && return 1
  if [ -b "/dev/block/$p" ]; then echo "/dev/block/$p"; return 0; fi
  if [ -b "/dev/$p" ]; then echo "/dev/$p"; return 0; fi
  return 1
}

is_system_disk() {
  echo "$1" | grep -qE '^(loop|ram|zram|dm-|mmcblk0)'
}

human_size() {
  sectors="$1"
  if [ -z "$sectors" ] || [ "$sectors" -lt 1 ] 2>/dev/null; then
    echo "?"
    return
  fi
  mb=$((sectors / 2048))
  if [ "$mb" -ge 1048576 ]; then
    echo "$((mb / 1048576))T"
  elif [ "$mb" -ge 1024 ]; then
    echo "$((mb / 1024))G"
  else
    echo "${mb}M"
  fi
}

is_mounted_dev() {
  dev="$1"
  base=$(basename "$dev")
  awk -v d="$dev" -v b="$base" '
    $1 == d { found=1 }
    $1 ~ ("/dev(/block)?/" b "$") { found=1 }
    END { exit found ? 0 : 1 }
  ' /proc/mounts 2>/dev/null
}

mounted_path() {
  dev="$1"
  base=$(basename "$dev")
  awk -v d="$dev" -v b="$base" '
    $1 == d { print $2; exit }
    $1 ~ ("/dev(/block)?/" b "$") { print $2; exit }
  ' /proc/mounts 2>/dev/null
}

resolve_block_dev() {
  spec="$1"
  [ -z "$spec" ] && return 1
  case "$spec" in
    /dev/*)
      [ -b "$spec" ] && { echo "$spec"; return 0; }
      ;;
  esac
  resolved=$(block_node "$spec") && { echo "$resolved"; return 0; }
  spec_lc=$(echo "$spec" | tr 'A-Z' 'a-z')
  for sys in /sys/block/sd* /sys/block/vd* /sys/block/nvme* /sys/block/usb* /sys/block/mmcblk[1-9]*; do
    [ -e "$sys" ] || continue
    name=$(basename "$sys")
    is_system_disk "$name" && continue
    for part in "$sys"/"$name"*; do
      [ -e "$part" ] || continue
      p=$(basename "$part")
      dev="/dev/block/$p"
      [ -b "$dev" ] || dev="/dev/$p"
      [ -b "$dev" ] || continue
      uuid=$(blkid_field "$dev" UUID)
      uuid_lc=$(echo "$uuid" | tr 'A-Z' 'a-z')
      [ -n "$uuid" ] && [ "$uuid_lc" = "$spec_lc" ] && { echo "$dev"; return 0; }
    done
    whole="/dev/block/$name"
    [ -b "$whole" ] || whole="/dev/$name"
    if [ -b "$whole" ]; then
      uuid=$(blkid_field "$whole" UUID)
      uuid_lc=$(echo "$uuid" | tr 'A-Z' 'a-z')
      [ -n "$uuid" ] && [ "$uuid_lc" = "$spec_lc" ] && { echo "$whole"; return 0; }
    fi
  done
  return 1
}

list_disks() {
  tmp=$(mktemp /data/local/tmp/disks.XXXXXX 2>/dev/null || echo /data/local/tmp/disks.tmp)
  echo "ok" > "$tmp"
  found=0
  for sys in /sys/block/sd* /sys/block/vd* /sys/block/nvme* /sys/block/usb* /sys/block/mmcblk[1-9]*; do
    [ -e "$sys" ] || continue
    name=$(basename "$sys")
    is_system_disk "$name" && continue
    parts=0
    for part in "$sys"/"$name"*; do
      [ -e "$part" ] || continue
      p=$(basename "$part")
      [ "$p" = "$name" ] && continue
      parts=1
      emit_disk_line "$p" >> "$tmp"
      found=1
    done
    if [ "$parts" -eq 0 ]; then
      emit_disk_line "$name" >> "$tmp"
      found=1
    fi
  done
  if [ "$found" -eq 0 ]; then
    # Fallback if the glob missed a USB/SCSI name (ueventd / sysfs layout).
    for name in $(awk 'NF==4 && $4 !~ /^(loop|ram|zram|dm-|mmcblk0)/ && $4 ~ /^(sd|vd|nvme|usb|mmcblk[1-9])/ {print $4}' /proc/partitions); do
      echo "$name" | grep -qE 'p?[0-9]+$' || continue
      emit_disk_line "$name" >> "$tmp"
      found=1
    done
  fi
  if [ "$found" -eq 0 ]; then
    echo "none" >> "$tmp"
  fi
  cat "$tmp" > "$DISKS_ACK"
  rm -f "$tmp"
  publish_file "$DISKS_ACK"
  write_ack "disks-ok $(date)"
}

emit_disk_line() {
  p="$1"
  dev=$(block_node "$p") || return 0
  blob=$(blkid_probe "$dev")
  uuid=$(echo "$blob" | sed -n 's/.*UUID="\([^"]*\)".*/\1/p' | head -n 1)
  label=$(echo "$blob" | sed -n 's/.*LABEL="\([^"]*\)".*/\1/p' | head -n 1)
  fstype=$(echo "$blob" | sed -n 's/.*TYPE="\([^"]*\)".*/\1/p' | head -n 1)
  sectors=$(cat "/sys/class/block/$p/size" 2>/dev/null)
  size=$(human_size "$sectors")
  mounted="no"
  mnt=""
  if is_mounted_dev "$dev"; then
    mounted="yes"
    mnt=$(mounted_path "$dev")
  fi
  [ -z "$uuid" ] && uuid="-"
  [ -z "$label" ] && label="-"
  [ -z "$fstype" ] && fstype="-"
  [ -z "$mnt" ] && mnt="-"
  uuid=$(printf '%s' "$uuid" | tr '|' '/' | sed 's/[[:space:]]*$//')
  label=$(printf '%s' "$label" | tr '|' '/' | sed 's/[[:space:]]*$//')
  fstype=$(printf '%s' "$fstype" | tr '|' '/' | sed 's/[[:space:]]*$//')
  echo "$p|$uuid|$label|$size|$fstype|$mounted|$mnt"
}

save_hd_state() {
  echo "DEV=$1" > "$HD_STATE"
  echo "UUID=$2" >> "$HD_STATE"
  echo "LABEL=$3" >> "$HD_STATE"
  echo "MOUNT=$4" >> "$HD_STATE"
  echo "BIND=$5" >> "$HD_STATE"
  echo "OWNED=$6" >> "$HD_STATE"
}

clear_hd_state() {
  rm -f "$HD_STATE"
}

mount_disk() {
  spec="$1"
  if [ -z "$spec" ]; then
    write_mount_ack "mount-bad-args"
    write_ack "mount-bad-args"
    return 1
  fi
  dev=$(resolve_block_dev "$spec")
  if [ -z "$dev" ]; then
    # After eject (SCSI delete) the block node is gone until USB is re-probed.
    wake_disk
    sleep 1
    dev=$(resolve_block_dev "$spec")
  fi
  if [ -z "$dev" ]; then
    write_mount_ack "mount-missing $spec"
    write_ack "mount-missing $spec"
    return 1
  fi
  uuid=$(blkid_field "$dev" UUID)
  label=$(blkid_field "$dev" LABEL)
  fstype=$(blkid_field "$dev" TYPE)
  bind=$(photos_dir)
  mkdir -p "$HD_MOUNT" "$bind" 2>/dev/null
  umount "$bind" 2>/dev/null
  umount "$HD_MOUNT" 2>/dev/null

  owned=0
  target="$HD_MOUNT"
  if is_mounted_dev "$dev"; then
    existing=$(mounted_path "$dev")
    if [ -n "$existing" ] && [ "$existing" != "$bind" ]; then
      target="$existing"
    fi
  else
    if ! try_mount_fs "$dev" "$fstype" "$HD_MOUNT"; then
      case "$fstype" in
        ntfs|fuseblk)
          write_mount_ack "mount-unsupported-ntfs $dev"
          write_ack "mount-unsupported-ntfs $dev"
          ;;
        *)
          write_mount_ack "mount-fail $dev $fstype"
          write_ack "mount-fail $dev $fstype"
          ;;
      esac
      return 1
    fi
    owned=1
    chmod 777 "$HD_MOUNT" 2>/dev/null
    target="$HD_MOUNT"
  fi

  if [ "$target" != "$bind" ]; then
    umount "$bind" 2>/dev/null
    if ! mount --bind "$target" "$bind"; then
      write_mount_ack "mount-bind-fail $dev $bind"
      write_ack "mount-bind-fail $dev $bind"
      return 1
    fi
    chmod 777 "$bind" 2>/dev/null
  fi
  mkdir -p "$bind/USER" 2>/dev/null
  touch "$bind/.nomedia" 2>/dev/null
  chmod 777 "$bind" "$bind/USER" 2>/dev/null
  [ -z "$uuid" ] && uuid="-"
  [ -z "$label" ] && label="-"
  save_hd_state "$dev" "$uuid" "$label" "$target" "$bind" "$owned"
  save_hd_usb "$(usb_parent_of_block "$dev")"
  write_mount_ack "mounted|$uuid|$label|$dev|$bind"
  write_ack "mount-ok $dev $bind"
}

try_mount_fs() {
  dev="$1"
  fstype="$2"
  dest="$3"
  app_uid=$(stat -c %u "$REQ_DIR" 2>/dev/null)
  app_gid=$(stat -c %g "$REQ_DIR" 2>/dev/null)
  [ -z "$app_uid" ] && app_uid=1023
  [ -z "$app_gid" ] && app_gid=1023
  fat_opts="rw,uid=$app_uid,gid=$app_gid,umask=000"
  case "$fstype" in
    vfat|fat|fat32|msdos|exfat)
      mount -t "$fstype" -o "$fat_opts" "$dev" "$dest" && return 0
      ;;
    ntfs|fuseblk)
      chmod 666 /dev/fuse 2>/dev/null
      ntfs_opts="rw,uid=$app_uid,gid=$app_gid,umask=000,allow_other,nonempty,big_writes"
      if [ -x "$NTFS_BIN" ]; then
        LD_LIBRARY_PATH="$NTFS_DIR${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}" \
          "$NTFS_BIN" -o "$ntfs_opts" "$dev" "$dest" && return 0
      fi
      if command -v ntfs-3g >/dev/null 2>&1; then
        ntfs-3g -o "$ntfs_opts" "$dev" "$dest" && return 0
      fi
      if command -v mount.ntfs >/dev/null 2>&1; then
        mount.ntfs -o "$ntfs_opts" "$dev" "$dest" && return 0
      fi
      mount -t ntfs -o "$ntfs_opts" "$dev" "$dest" 2>/dev/null && return 0
      mount -t fuseblk -o "$ntfs_opts" "$dev" "$dest" 2>/dev/null && return 0
      return 1
      ;;
    *)
      if [ -n "$fstype" ] && [ "$fstype" != "-" ]; then
        mount -t "$fstype" -o rw "$dev" "$dest" && return 0
      fi
      mount -o rw "$dev" "$dest" && return 0
      ;;
  esac
  return 1
}

scsi_parent() {
  name=$(basename "$1")
  if [ -e "/sys/class/block/$name/partition" ]; then
    basename "$(readlink -f "/sys/class/block/$name/..")"
  else
    echo "$name"
  fi
}

# Walk from a block device up to the USB device that has "authorized".
usb_parent_of_block() {
  name=$(basename "$1")
  parent=$(scsi_parent "$1")
  [ -z "$parent" ] && parent="$name"
  path=$(readlink -f "/sys/block/$parent/device" 2>/dev/null)
  [ -z "$path" ] && path=$(readlink -f "/sys/class/block/$parent/device" 2>/dev/null)
  while [ -n "$path" ] && [ "$path" != "/" ]; do
    if [ -f "$path/idVendor" ] && [ -f "$path/authorized" ]; then
      echo "$path"
      return 0
    fi
    path=$(dirname "$path")
  done
  return 1
}

save_hd_usb() {
  usb="$1"
  if [ -n "$usb" ] && [ -f "$usb/authorized" ]; then
    echo "$usb" > "$HD_USB_STATE"
    if [ -f "$usb/idVendor" ] && [ -f "$usb/idProduct" ]; then
      echo "$(cat "$usb/idVendor"):$(cat "$usb/idProduct")" > "$HD_USB_ID"
    fi
  fi
}

usb_hub_port() {
  [ -n "$1" ] && [ -e "$1/port" ] && readlink -f "$1/port"
}

# Pi 4: each USB-A connector is usb2-portN (SS) + 1-1-portN (HS). Cutting only
# SuperSpeed leaves VBUS on and the disk reappears as USB2 (1-1.N).
usb_twin_port() {
  port="$1"
  [ -n "$port" ] || return 1
  name=$(basename "$port")
  twin=""
  case "$name" in
    usb2-port[1-4])
      n=${name#usb2-port}
      twin="/sys/bus/usb/devices/1-1:1.0/1-1-port$n"
      ;;
    1-1-port[1-4])
      n=${name#1-1-port}
      twin="/sys/bus/usb/devices/usb2/2-0:1.0/usb2-port$n"
      ;;
    *) return 1 ;;
  esac
  [ -f "$twin/disable" ] || return 1
  echo "$twin"
}

usb_port_record() {
  port="$1"
  [ -n "$port" ] || return 0
  grep -qxF "$port" "$HD_USB_PORTS" 2>/dev/null && return 0
  echo "$port" >> "$HD_USB_PORTS"
}

usb_port_disable() {
  port="$1"
  [ -n "$port" ] && [ -f "$port/disable" ] || return 1
  echo 0 > "$port/power/pm_qos_no_power_off" 2>/dev/null
  echo 1 > "$port/disable"
  usb_port_record "$port"
  echo "usb-port-disable $port $(date)" >&2
}

usb_port_enable() {
  port="$1"
  [ -n "$port" ] && [ -f "$port/disable" ] || return 1
  echo 0 > "$port/disable"
  echo "usb-port-enable $port $(date)" >&2
}

# USB mass-storage device dirs still enumerated (even with authorized=0).
usb_storage_dirs() {
  for d in /sys/bus/usb/devices/*; do
    [ -f "$d/idVendor" ] || continue
    for iface in "$d":*; do
      [ -f "$iface/bInterfaceClass" ] || continue
      class=$(cat "$iface/bInterfaceClass" 2>/dev/null)
      if [ "$class" = "08" ]; then
        echo "$d"
        break
      fi
    done
  done
}

eject_dev() {
  [ -z "$1" ] && return 0
  parent=$(scsi_parent "$1")
  [ -z "$parent" ] && return 0
  sync
  if [ -e "/sys/block/$parent/device/delete" ]; then
    echo 1 > "/sys/block/$parent/device/delete" 2>/dev/null
  elif [ -e "/sys/class/block/$parent/device/delete" ]; then
    echo 1 > "/sys/class/block/$parent/device/delete" 2>/dev/null
  fi
}

# Re-enable hub ports then re-authorize so Monta can find the disk again.
wake_disk() {
  if [ -f "$HD_USB_PORTS" ]; then
    while IFS= read -r port; do
      usb_port_enable "$port"
    done < "$HD_USB_PORTS"
    sleep 3
  fi
  usb=""
  if [ -f "$HD_USB_STATE" ]; then
    usb=$(cat "$HD_USB_STATE" 2>/dev/null)
  fi
  if [ -z "$usb" ] || [ ! -f "$usb/authorized" ]; then
    usb=$(usb_storage_dirs | head -n 1)
  fi
  if [ -n "$usb" ] && [ -f "$usb/authorized" ]; then
    echo 0 > "$usb/authorized" 2>/dev/null
    sleep 1
    echo 1 > "$usb/authorized" 2>/dev/null
    sleep 3
  else
    for host in /sys/class/scsi_host/host*; do
      [ -e "$host/scan" ] || continue
      echo "- - -" > "$host/scan" 2>/dev/null
    done
    sleep 2
  fi
  write_ack "wake-disk-ok $(date)"
}

# Cut VBUS on the HD USB-A port (SS + HS twin). authorized=0 alone leaves 5V on.
usb_power_off() {
  usb=""
  if [ -f "$HD_USB_STATE" ]; then
    usb=$(cat "$HD_USB_STATE" 2>/dev/null)
  fi
  if [ -z "$usb" ] && [ -n "$1" ]; then
    usb=$(usb_parent_of_block "$1")
  fi
  if [ -z "$usb" ]; then
    usb=$(usb_storage_dirs | head -n 1)
  fi

  ports=""
  if [ -n "$usb" ] && [ -d "$usb" ]; then
    save_hd_usb "$usb"
    echo 0 > "$usb/authorized" 2>/dev/null
    p=$(usb_hub_port "$usb")
    [ -n "$p" ] && ports="$p"
  fi
  # Device already gone: recover SS/HS ports from the saved sysfs name (2-N / 1-1.N).
  if [ -z "$ports" ] && [ -n "$usb" ]; then
    case "$(basename "$usb")" in
      2-[1-4])
        n=$(basename "$usb" | cut -d- -f2)
        ports="/sys/bus/usb/devices/usb2/2-0:1.0/usb2-port$n /sys/bus/usb/devices/1-1:1.0/1-1-port$n"
        ;;
      1-1.[1-4])
        n=$(basename "$usb" | cut -d. -f2)
        ports="/sys/bus/usb/devices/1-1:1.0/1-1-port$n /sys/bus/usb/devices/usb2/2-0:1.0/usb2-port$n"
        ;;
    esac
  fi
  if [ -f "$HD_USB_PORTS" ]; then
    ports="$ports $(cat "$HD_USB_PORTS")"
  fi

  off=1
  for p in $ports; do
    usb_port_disable "$p"
    twin=$(usb_twin_port "$p")
    [ -n "$twin" ] && usb_port_disable "$twin"
    off=0
  done

  vid=""; pid=""
  if [ -f "$HD_USB_ID" ]; then
    vid=$(cut -d: -f1 "$HD_USB_ID")
    pid=$(cut -d: -f2 "$HD_USB_ID")
  fi
  sleep 1
  if [ -n "$vid" ] && [ -n "$pid" ]; then
    for d in /sys/bus/usb/devices/*; do
      [ -f "$d/idVendor" ] || continue
      [ "$(cat "$d/idVendor" 2>/dev/null)" = "$vid" ] || continue
      [ "$(cat "$d/idProduct" 2>/dev/null)" = "$pid" ] || continue
      p=$(usb_hub_port "$d")
      [ -n "$p" ] || continue
      echo 0 > "$d/authorized" 2>/dev/null
      usb_port_disable "$p"
      twin=$(usb_twin_port "$p")
      [ -n "$twin" ] && usb_port_disable "$twin"
      off=0
    done
  fi
  echo "usb-power-off usb=${usb:-none} rc=$off $(date)" >&2
  return "$off"
}

# Drop every leftover HD mount (incl. NTFS fuse ghosts after SCSI delete).
flush_hd_mounts() {
  bind=$(photos_dir)
  state_mnt=""
  if [ -f "$HD_STATE" ]; then
    state_bind=$(grep '^BIND=' "$HD_STATE" | cut -d= -f2)
    state_mnt=$(grep '^MOUNT=' "$HD_STATE" | cut -d= -f2)
    [ -n "$state_bind" ] && bind="$state_bind"
  fi
  sync
  umount "$bind" 2>/dev/null || umount -l "$bind" 2>/dev/null
  umount "$HD_MOUNT" 2>/dev/null || umount -l "$HD_MOUNT" 2>/dev/null
  [ -n "$state_mnt" ] && { umount "$state_mnt" 2>/dev/null || umount -l "$state_mnt" 2>/dev/null; }
  # Any remaining vespera-photos / vespera-hd mounts (pass_through, ghosts).
  awk '
    $2 ~ /vespera-photos$/ || $2 == "/mnt/vespera-hd" || $1 ~ /\/sda[0-9]*$/ { print $2 }
  ' /proc/mounts 2>/dev/null | while read -r mp; do
    [ -n "$mp" ] || continue
    umount "$mp" 2>/dev/null || umount -l "$mp" 2>/dev/null
  done
  sync
}

# Device currently backing the HD mount, even if HD_STATE was cleared.
mounted_hd_dev() {
  awk '
    $2 == "/mnt/vespera-hd" { print $1; exit }
    $2 ~ /vespera-photos$/ { print $1; exit }
  ' /proc/mounts 2>/dev/null
}

umount_all_of() {
  dev="$1"
  [ -z "$dev" ] && return 0
  base=$(basename "$dev")
  awk -v d="$dev" -v b="$base" '
    $1 == d { print $2 }
    $1 ~ ("/dev(/block)?/" b "$") { print $2 }
  ' /proc/mounts 2>/dev/null | while read -r mp; do
    [ -n "$mp" ] || continue
    umount "$mp" 2>/dev/null || umount -l "$mp" 2>/dev/null
  done
}

# Flush and unmount HD without clearing HD_STATE (so next boot can remount).
shutdown_pi() {
  sync
  write_mount_ack "shutdown-pi-ok"
  (
    sleep 2
    setprop sys.powerctl shutdown 2>/dev/null \
      || svc power shutdown 2>/dev/null \
      || reboot -p
  ) &
}

shutdown_umount_hd() {
  bind=$(photos_dir)
  state_mnt=""
  dev=""
  if [ -f "$HD_STATE" ]; then
    state_bind=$(grep '^BIND=' "$HD_STATE" | cut -d= -f2)
    state_mnt=$(grep '^MOUNT=' "$HD_STATE" | cut -d= -f2)
    dev=$(grep '^DEV=' "$HD_STATE" | cut -d= -f2)
    [ -n "$state_bind" ] && bind="$state_bind"
  fi
  sync
  umount "$bind" 2>/dev/null || umount -l "$bind" 2>/dev/null
  umount "$HD_MOUNT" 2>/dev/null || umount -l "$HD_MOUNT" 2>/dev/null
  [ -n "$state_mnt" ] && { umount "$state_mnt" 2>/dev/null || umount -l "$state_mnt" 2>/dev/null; }
  umount_all_of "$dev"
  sync
  write_mount_ack "unmounted"
  write_ack "shutdown-umount-ok $(date)"
}

umount_disk() {
  spec="$1"
  bind=$(photos_dir)
  state_mnt=""
  dev=""
  if [ -f "$HD_STATE" ]; then
    state_bind=$(grep '^BIND=' "$HD_STATE" | cut -d= -f2)
    state_mnt=$(grep '^MOUNT=' "$HD_STATE" | cut -d= -f2)
    dev=$(grep '^DEV=' "$HD_STATE" | cut -d= -f2)
    [ -n "$state_bind" ] && bind="$state_bind"
  fi
  if [ -z "$dev" ] && [ -n "$spec" ]; then
    dev=$(resolve_block_dev "$spec")
  fi
  sync
  umount "$bind" 2>/dev/null || umount -l "$bind" 2>/dev/null
  umount "$HD_MOUNT" 2>/dev/null || umount -l "$HD_MOUNT" 2>/dev/null
  [ -n "$state_mnt" ] && umount "$state_mnt" 2>/dev/null
  umount_all_of "$dev"
  sync
  clear_hd_state
  write_mount_ack "unmounted"
  write_ack "umount-ok $(date)"
}

eject_disk() {
  spec="$1"
  bind=$(photos_dir)
  state_mnt=""
  dev=""
  if [ -f "$HD_STATE" ]; then
    state_bind=$(grep '^BIND=' "$HD_STATE" | cut -d= -f2)
    state_mnt=$(grep '^MOUNT=' "$HD_STATE" | cut -d= -f2)
    dev=$(grep '^DEV=' "$HD_STATE" | cut -d= -f2)
    [ -n "$state_bind" ] && bind="$state_bind"
  fi
  if [ -z "$dev" ] && [ -n "$spec" ]; then
    dev=$(resolve_block_dev "$spec")
  fi
  if [ -z "$dev" ]; then
    dev=$(mounted_hd_dev)
  fi
  # Remember USB path before we tear the block device down.
  if [ -n "$dev" ]; then
    save_hd_usb "$(usb_parent_of_block "$dev")"
  elif [ -f "$HD_USB_STATE" ]; then
    :
  fi

  # 1) Always flush mounts first (NTFS fuse ghosts keep the disk "on" in the UI).
  flush_hd_mounts
  [ -n "$dev" ] && umount_all_of "$dev"
  [ -n "$state_mnt" ] && { umount "$state_mnt" 2>/dev/null || umount -l "$state_mnt" 2>/dev/null; }
  sync

  # 2) SCSI delete if the block node is still visible.
  if [ -n "$dev" ]; then
    eject_dev "$dev"
    parent=$(scsi_parent "$dev")
    still=$(ls -d /sys/block/"$parent" 2>/dev/null)
    if [ -n "$still" ]; then
      flush_hd_mounts
      umount_all_of "$dev"
      sync
      eject_dev "$dev"
      still=$(ls -d /sys/block/"$parent" 2>/dev/null)
    fi
  else
    still=""
  fi

  # 3) Cut USB authorization so the enclosure can spin down.
  usb_power_off "$dev"
  clear_hd_state

  if path_mounted "$bind" || path_mounted "$HD_MOUNT"; then
    write_mount_ack "eject-busy ${dev:-?}"
    write_ack "eject-busy ${dev:-?} mounts-remain"
    return 1
  fi
  if [ -n "$still" ]; then
    write_mount_ack "eject-busy $dev"
    write_ack "eject-busy $dev"
    return 1
  fi
  write_mount_ack "ejected|${dev:-usb}"
  write_ack "eject-ok ${dev:-usb} $(date)"
}

disk_status() {
  bind=$(photos_dir)
  if [ -f "$HD_STATE" ]; then
    dev=$(grep '^DEV=' "$HD_STATE" | cut -d= -f2)
    uuid=$(grep '^UUID=' "$HD_STATE" | cut -d= -f2)
    label=$(grep '^LABEL=' "$HD_STATE" | cut -d= -f2)
    mnt=$(grep '^MOUNT=' "$HD_STATE" | cut -d= -f2)
    bind_path=$(grep '^BIND=' "$HD_STATE" | cut -d= -f2)
    [ -n "$bind_path" ] && bind="$bind_path"
    if is_mounted_dev "$dev" || path_mounted "$mnt" || path_mounted "$HD_MOUNT"; then
      ensure_photos_bind || true
    fi
    if path_mounted "$bind"; then
      write_mount_ack "mounted|$uuid|$label|$dev|$bind"
      write_ack "disk-mounted $dev"
      return 0
    fi
  fi
  if path_mounted "$HD_MOUNT"; then
    ensure_photos_bind || true
  fi
  if path_mounted "$bind"; then
    write_mount_ack "mounted|-|-|-|$bind"
    write_ack "disk-mounted bind"
    return 0
  fi
  write_mount_ack "unmounted"
  write_ack "disk-unmounted"
}

auto_mount_from_state() {
  [ -f "$HD_STATE" ] || return 0
  uuid=$(grep '^UUID=' "$HD_STATE" | cut -d= -f2)
  dev=$(grep '^DEV=' "$HD_STATE" | cut -d= -f2)
  spec="$uuid"
  if [ -z "$spec" ] || [ "$spec" = "-" ]; then
    spec="$dev"
  fi
  [ -z "$spec" ] && return 0
  if [ -n "$dev" ] && is_mounted_dev "$dev"; then
    ensure_photos_bind || true
    disk_status
    return 0
  fi
  bind=$(photos_dir)
  if path_mounted "$bind" || path_mounted "$HD_MOUNT"; then
    ensure_photos_bind || true
    disk_status
    return 0
  fi
  mount_disk "$spec"
}

set_clock() {
  tz="$1"
  epoch="$2"
  stamp="$3"
  if [ -n "$tz" ]; then
    setprop persist.sys.timezone "$tz" 2>/dev/null
    service call alarm 3 s16 "$tz" >/dev/null 2>&1
  fi
  settings put global auto_time 0 >/dev/null 2>&1
  settings put global auto_time_zone 0 >/dev/null 2>&1
  if [ -n "$epoch" ]; then
    toybox date -u -s "@$epoch" >/dev/null 2>&1 \
      || date -u -s "@$epoch" >/dev/null 2>&1
  fi
  if [ -n "$stamp" ]; then
    date -u -s "$stamp" >/dev/null 2>&1 \
      || toybox date -u -s "$stamp" >/dev/null 2>&1
  fi
  hwclock -w -u >/dev/null 2>&1
  write_ack "clock-ok tz=$tz epoch=$epoch $(date)"
}

write_ack "vespera-netd started $(date)"
# Do not remount at daemon start: Helper powers the HD off while Vespera is
# offline and mounts again when the telescope comes online (or via Monta).
power_off_hd_at_boot() {
  # Always tear down leftover mounts + USB power, even without HD_STATE.
  spec=""
  if [ -f "$HD_STATE" ]; then
    spec=$(grep '^UUID=' "$HD_STATE" | cut -d= -f2)
    [ -z "$spec" ] || [ "$spec" = "-" ] && spec=$(grep '^DEV=' "$HD_STATE" | cut -d= -f2)
  fi
  eject_disk "$spec"
}
power_off_hd_at_boot

handle_cmd() {
  line="$1"
  cmd=$(echo "$line" | cut -d'|' -f1)
  a=$(echo "$line" | cut -d'|' -f2)
  b=$(echo "$line" | cut -d'|' -f3)
  c=$(echo "$line" | cut -d'|' -f4)
  case "$cmd" in
    promote) promote "$a" "$b" ;;
    route) apply_route ;;
    restart-singularity)
      front_tid=$(front_task_id)
      am force-stop "$SINGULARITY_PKG" 2>/dev/null
      sleep 1
      launch_singularity_process
      hold_front_over_singularity "$front_tid"
      write_ack "singularity-restart-ok $(date)"
      ;;
    start-singularity)
      start_singularity_background
      write_ack "singularity-start-ok $(date)"
      ;;
    check-singularity) check_singularity ;;
    probe-api) probe_api ;;
    list-disks) list_disks ;;
    mount-disk) mount_disk "$a" ;;
    umount-disk) umount_disk "$a" ;;
    eject-disk) eject_disk "$a" ;;
    wake-disk) wake_disk; list_disks ;;
    ensure-bind) ensure_photos_bind; disk_status ;;
    disk-status) disk_status ;;
    auto-mount) auto_mount_from_state ;;
    shutdown-umount) shutdown_umount_hd ;;
    shutdown-pi) shutdown_pi ;;
    set-clock) set_clock "$a" "$b" "$c" ;;
    *) write_ack "unknown:$line" ;;
  esac
}

# Android often truncates process names (com.vaonis.barnard → .vaonis.barnard).
singularity_pid() {
  PID=$(pidof "$SINGULARITY_PKG" 2>/dev/null)
  if [ -z "$PID" ]; then
    PID=$(pidof ".vaonis.barnard" 2>/dev/null)
  fi
  if [ -z "$PID" ]; then
    PID=$(pgrep -f "$SINGULARITY_PKG" 2>/dev/null | head -n 1)
  fi
  echo "$PID"
}

# Launch Singularity without taking the screen. It must stay in recents until
# the user opens it by hand. Never force-stops: only starts if the process is missing.
start_singularity_background() {
  front_tid=$(front_task_id)
  if [ -n "$(singularity_pid)" ]; then
    echo "singularity-already-running $(date)" >&2
    return 0
  fi
  launch_singularity_process
  hold_front_over_singularity "$front_tid"
}

# Do not use MAIN/LAUNCHER on Helper: that recreates MainActivity (looks like a restart).
# Do not use monkey: a single event can be KEYCODE_HOME.
launch_singularity_process() {
  echo "launch-singularity $(date)" >&2
  am start --activity-no-animation --activity-no-user-action -n "$SINGULARITY_ACTIVITY" >/dev/null 2>&1 \
    || am start --activity-no-animation --activity-no-user-action \
         -a android.intent.action.MAIN -c android.intent.category.LAUNCHER \
         -n "$SINGULARITY_ACTIVITY" >/dev/null 2>&1
}

task_id_from_line() {
  tid=$(printf '%s' "$1" | sed 's/.*taskId=\([0-9][0-9]*\).*/\1/')
  case "$tid" in
    ''|*[!0-9]*) echo "" ;;
    *) echo "$tid" ;;
  esac
}

# Currently visible task, excluding Singularity. Fallback: Helper if present.
front_task_id() {
  line=$(cmd activity stack list 2>/dev/null | grep 'visible=true' | grep -v "$SINGULARITY_PKG" | head -n 1)
  tid=$(task_id_from_line "$line")
  if [ -n "$tid" ]; then
    echo "$tid"
    return 0
  fi
  helper_task_id
}

helper_task_id() {
  line=$(cmd activity stack list 2>/dev/null | grep "$HELPER_PKG/$HELPER_PKG.MainActivity" | head -n 1)
  task_id_from_line "$line"
}

restore_front_task() {
  tid="$1"
  case "$tid" in
    ''|*[!0-9]*) tid=$(front_task_id) ;;
  esac
  if [ -n "$tid" ]; then
    cmd activity task focus "$tid" >/dev/null 2>&1
    return 0
  fi
  am start --activity-no-animation --activity-reorder-to-front --activity-single-top \
    -n "$HELPER_PKG/.MainActivity" >/dev/null 2>&1
}

# am start returns before the UI is shown. Singularity then steals focus (~0.5s)
# and can steal again during splash. Yank it back only while it is on top;
# if the user switches to another app (or later to Singularity), leave it.
hold_front_over_singularity() {
  tid="$1"
  restore_front_task "$tid"
  n=0
  while [ "$n" -lt 20 ]; do
    top=$(cmd activity stack list 2>/dev/null | grep 'visible=true' | head -n 1)
    echo "$top" | grep -q "$SINGULARITY_PKG"
    if [ $? -eq 0 ]; then
      echo "singularity-stole-focus restore=$tid $(date)" >&2
      restore_front_task "$tid"
    fi
    n=$((n + 1))
    sleep 0.25
  done
}

helper_pid() {
  PID=$(pidof "$HELPER_PKG" 2>/dev/null)
  if [ -z "$PID" ]; then
    PID=$(pidof ".vaonis.vesperahelper" 2>/dev/null)
  fi
  echo "$PID"
}

# Restart Helper services without bringing the UI over Singularity.
# Covers crash, swipe-away and force-stop; the app then checks sync state and resumes.
ensure_helper_services() {
  if [ -n "$(helper_pid)" ]; then
    return 0
  fi
    am start-foreground-service -n "$HELPER_PKG/.PhotoSyncService" \
    -a com.vaonis.vesperahelper.PHOTO_BOOTSTRAP >/dev/null 2>&1 \
    || am startservice -n "$HELPER_PKG/.PhotoSyncService" \
         -a com.vaonis.vesperahelper.PHOTO_BOOTSTRAP >/dev/null 2>&1
  am start-foreground-service -n "$HELPER_PKG/.VesperaConnectionService" \
    -a com.vaonis.vesperahelper.CONNECT >/dev/null 2>&1 \
    || am startservice -n "$HELPER_PKG/.VesperaConnectionService" \
         -a com.vaonis.vesperahelper.CONNECT >/dev/null 2>&1
}

consume_req() {
  path="$1"
  [ -f "$path" ] || return 1
  line=""
  IFS= read -r line < "$path"
  rm -f "$path"
  line=$(printf '%s' "$line" | tr -d '\r')
  [ -n "$line" ] || return 1
  handle_cmd "$line"
  return 0
}

# TERM is used when reloading the daemon. Never umount the HD here.
on_term() {
  echo "vespera-netd stopping $(date)" >&2
  exit 0
}
trap on_term TERM INT

WATCH=0
while true; do
  handled=0
  # Disk ops have their own file so route/promote/check-singularity cannot steal them.
  consume_req "$DISK_REQ" && handled=1
  consume_req "$PROBE_REQ" && handled=1
  consume_req "$SINGULARITY_REQ" && handled=1
  consume_req "$REQ_FILE" && handled=1
  WATCH=$((WATCH + 1))
  if [ "$WATCH" -ge 8 ]; then
    WATCH=0
    ensure_helper_services
  fi
  [ "$handled" -eq 0 ] && sleep 1
done
