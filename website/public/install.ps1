# Nox Installer Script for Windows (PowerShell)
# Installs nox, noxc, nox-lsp, and noxfmt into $HOME\.nox\bin

$ErrorActionPreference = "Stop"

Write-Host "Detected platform: Windows (x64)"
Write-Host "Targeting binary package: windows-x64"

# 1. Fetch the latest release tag from GitHub
Write-Host "Fetching latest release version from deepsarda/Nox..."

$LatestTag = $null

try {
    # Try GitHub API first
    $Releases = Invoke-RestMethod -Uri "https://api.github.com/repos/deepsarda/Nox/releases" -UseBasicParsing
    # Filter out vscode and intellij releases
    foreach ($Release in $Releases) {
        if ($Release.tag_name -like "v*" -and -not ($Release.tag_name -like "vscode-*") -and -not ($Release.tag_name -like "intellij-*")) {
            $LatestTag = $Release.tag_name
            break
        }
    }
} catch {
    # API failed (e.g. rate limits), try redirect header
    try {
        $Request = [System.Net.WebRequest]::Create("https://github.com/deepsarda/Nox/releases/latest")
        $Request.AllowAutoRedirect = $false
        $Response = $Request.GetResponse()
        $RedirectUrl = $Response.GetResponseHeader("Location")
        $Response.Close()
        if ($RedirectUrl -and $RedirectUrl -match "/tag/([^/]+)") {
            $LatestTag = $Matches[1]
        }
    } catch {
        # Fallback if both fail
    }
}

if (-not $LatestTag) {
    Write-Warning "Could not fetch latest release tag dynamically. Defaulting to v0.0.0."
    $LatestTag = "v0.0.0"
}

$Version = $LatestTag.TrimStart('v')
Write-Host "Latest version: $LatestTag (v$Version)"

# 2. Setup directories
$InstallDir = Join-Path $HOME ".nox"
$BinDir = Join-Path $InstallDir "bin"

if (-not (Test-Path $BinDir)) {
    New-Item -ItemType Directory -Path $BinDir -Force | Out-Null
}

# 3. Download Zip Archive
$ZipFile = [System.IO.Path]::GetTempFileName() + ".zip"
$DownloadUrl = "https://github.com/deepsarda/Nox/releases/download/$LatestTag/nox-$Version-windows-x64.zip"

Write-Host "Downloading $DownloadUrl..."
try {
    Invoke-WebRequest -Uri $DownloadUrl -OutFile $ZipFile -UseBasicParsing
} catch {
    Write-Error "Failed to download release asset from $DownloadUrl"
    if (Test-Path $ZipFile) { Remove-Item $ZipFile }
    exit 1
}

# 4. Extract and Install
Write-Host "Extracting archive..."
$TempExtract = Join-Path $InstallDir "temp_extract"
if (Test-Path $TempExtract) { Remove-Item -Recurse -Force $TempExtract }

Expand-Archive -Path $ZipFile -DestinationPath $TempExtract -Force

$ExtractedDir = Join-Path $TempExtract "nox-$Version-windows-x64"

if (Test-Path (Join-Path $ExtractedDir "bin")) {
    Write-Host "Installing binaries to $BinDir..."
    Copy-Item -Path (Join-Path $ExtractedDir "bin\*") -Destination $BinDir -Force -Recurse
} else {
    Write-Error "Extracted archive did not contain a bin directory at $ExtractedDir\bin"
    Remove-Item $ZipFile
    Remove-Item -Recurse -Force $TempExtract
    exit 1
}

# 5. Cleanup
Remove-Item $ZipFile
Remove-Item -Recurse -Force $TempExtract

# Write version file
Set-Content -Path (Join-Path $InstallDir "version") -Value $LatestTag

Write-Host "`n=========================================" -ForegroundColor Green
Write-Host " Nox has been successfully installed!" -ForegroundColor Green
Write-Host "=========================================" -ForegroundColor Green
Write-Host "Version: $LatestTag"
Write-Host "Location: $BinDir`n"

Write-Host "Available binaries:"
Get-ChildItem -Path $BinDir | Format-Table Name, Length

# 6. Add to PATH automatically
$UserPath = [Environment]::GetEnvironmentVariable("Path", "User")
if ($UserPath -split ";" -notcontains $BinDir) {
    Write-Host "Adding $BinDir to your User PATH environment variable..."
    $NewUserPath = $UserPath
    if (-not $NewUserPath.EndsWith(";")) { $NewUserPath += ";" }
    $NewUserPath += $BinDir
    [Environment]::SetEnvironmentVariable("Path", $NewUserPath, "User")
    Write-Host "Successfully added Nox to PATH. Please restart your shell/terminal to apply the changes." -ForegroundColor Cyan
} else {
    Write-Host "Nox is already in your PATH." -ForegroundColor Green
}
