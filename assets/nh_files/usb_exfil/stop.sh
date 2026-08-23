#!/system/bin/sh
# stop-usb-exfil

APP_SCRIPTS_PATH=${1:-"/data/data/com.offsec.nethunter/files/scripts"}

# 1. Kill the Python listener
echo "[*] Stopping USB Exfiltration listener..."

# Try killing via pkill in chroot (where it was started)
MNT="/data/local/nhsystem/kali-armhf"
BUSYBOX="/system/bin/busybox"

if [ -d "$MNT" ]; then
    echo "[*] Sending kill signal to chroot..."
    $BUSYBOX chroot "$MNT" /bin/bash -c "pkill -f listen.py"
else
    echo "[-] Chroot not found at $MNT"
fi

# Fallback: try killing any python process running listen.py in Android space (unlikely but safe)
if [ -x "/system/bin/pkill" ]; then
    /system/bin/pkill -f "listen.py"
fi

# 2. Kill usbtethering (if running)
echo "[*] Stopping usbtethering..."
if [ -x "/system/bin/pkill" ]; then
    /system/bin/pkill -f "usbtethering"
else
    killall usbtethering 2>/dev/null
fi

# 3. Disable RNDIS (Reset USB)
echo "[*] Resetting USB via usbarsenal..."

if [ -f "$APP_SCRIPTS_PATH/usbarsenal" ]; then
    CMD="/system/bin/sh $APP_SCRIPTS_PATH/usbarsenal"
elif [ -f "/system/xbin/usbarsenal" ]; then
    CMD="/system/bin/sh /system/xbin/usbarsenal"
else
    CMD="usbarsenal"
fi

# Execute usbarsenal with reset parameters (enable adb only)
# Using dummy vendor/product IDs as they are required by the script but ignored for reset
$CMD -t lnx -f reset -v 0x18d1 -p 0x4ee7

if [ $? -ne 0 ]; then
    echo "[-] usbarsenal failed or not found, falling back to setprop..."
    setprop sys.usb.config mtp,adb
fi

echo "[+] USB Exfiltration Stopped."
