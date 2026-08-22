#!/system/bin/sh

# Configuration
DIR=$(cd "$(dirname "$0")"; pwd)
APP_SCRIPTS_PATH=${1:-"/data/data/com.offsec.nethunter/files/scripts"}
TARGET_OS=${2:-"lnx"}

# 1. Ensure clean slate by stopping any previous instances
if [ -x "$DIR/stop-usb-exfil" ]; then
    "$DIR/stop-usb-exfil" "$APP_SCRIPTS_PATH"
else
    # Fallback if execute permission is missing or script not found nearby
    /system/bin/sh "$DIR/stop-usb-exfil" "$APP_SCRIPTS_PATH"
fi

# Clear log file
> /sdcard/nh_files/usb_exfil/usb_exfil.log

# 2. Start Listener in Chroot (Background)
# We execute in background immediately to allow chroot to load while USB initializes
(
    # Prepare Kali Chroot environment
    # First, try to load environment variables without starting everything
    if [ -f "$APP_SCRIPTS_PATH/bootkali_env" ]; then
        . "$APP_SCRIPTS_PATH/bootkali_env"
    elif [ -f "$DIR/bootkali_env" ]; then
        . "$DIR/bootkali_env"
    else
        echo "Error: bootkali_env not found at $APP_SCRIPTS_PATH or $DIR"
        exit 1
    fi

    # Check if chroot is already active (checking /proc mount)
    if grep "$MNT/proc" /proc/mounts > /dev/null; then
        echo "[*] Chroot is already mounted at $MNT"
    else
        echo "[*] Chroot not mounted. Initializing..."
        if [ -f "$APP_SCRIPTS_PATH/bootkali_init" ]; then
            . "$APP_SCRIPTS_PATH/bootkali_init"
        elif [ -f "$DIR/bootkali_init" ]; then
            . "$DIR/bootkali_init"
        else
            echo "Error: bootkali_init not found at $APP_SCRIPTS_PATH or $DIR"
            exit 1
        fi
    fi

    # Execute listener in chroot
    echo "[*] Starting listener in chroot..."
    $BUSYBOX chroot "$MNT" /bin/bash -c "cd /sdcard/nh_files/usb_exfil && python3 -u listen.py"
) >> /sdcard/nh_files/usb_exfil/usb_exfil.log 2>&1 &

# 3. Enable RNDIS via usbarsenal
# stop-usb-exfil resets USB to adb-only, so we must re-enable RNDIS for usbtethering.
if [ -f "$APP_SCRIPTS_PATH/usbarsenal" ]; then
    /system/bin/sh "$APP_SCRIPTS_PATH/usbarsenal" -t $TARGET_OS -f rndis,hid -v 0x18d1 -p 0x4ee7
elif [ -f "/system/xbin/usbarsenal" ]; then
    /system/bin/sh /system/xbin/usbarsenal -t $TARGET_OS -f rndis,hid -v 0x18d1 -p 0x4ee7
else
    usbarsenal -t $TARGET_OS -f rndis,hid -v 0x18d1 -p 0x4ee7
fi

# 4. Wait for interface (Smart Polling)
echo "[*] Waiting for USB interface..." >> /sdcard/nh_files/usb_exfil/usb_exfil.log
USB_IFACE=""
MAX_RETRIES=50 # 10 seconds (50 * 0.2s)
COUNT=0

while [ $COUNT -lt $MAX_RETRIES ]; do
    if ip link show rndis0 >/dev/null 2>&1; then
        USB_IFACE="rndis0"
        break
    elif ip link show usb0 >/dev/null 2>&1; then
        USB_IFACE="usb0"
        break
    fi
    sleep 0.2
    COUNT=$((COUNT + 1))
done

if [ -z "$USB_IFACE" ]; then
    echo "[-] Timeout waiting for USB interface. Defaulting to rndis0." >> /sdcard/nh_files/usb_exfil/usb_exfil.log
    USB_IFACE="rndis0"
else
    echo "[+] Found interface: $USB_IFACE" >> /sdcard/nh_files/usb_exfil/usb_exfil.log
fi

# 5. Setup RNDIS Networking (IP & DHCP)
# Try standard path first
if [ -f /data/data/com.offsec.nethunter/files/scripts/usbtethering ]; then
    TETHER_SCRIPT="/data/data/com.offsec.nethunter/files/scripts/usbtethering"
elif [ -f /data/data/com.offsec.nethunter/scripts/usbtethering ]; then
    TETHER_SCRIPT="/data/data/com.offsec.nethunter/scripts/usbtethering"
else
    TETHER_SCRIPT="usbtethering"
fi

# Execute usbtethering as root in background (Forever mode for persistent DHCP)
# Redirect stdin from /dev/null to prevent EOF from killing the forever loop
nohup sh "$TETHER_SCRIPT" -F -o wlan0 -i "$USB_IFACE" -A 192.168.137.10 -B 192.168.137.10 -C 192.168.137.1 -D 255.255.255.0 </dev/null >> /sdcard/nh_files/usb_exfil/usb_exfil.log 2>&1 &
