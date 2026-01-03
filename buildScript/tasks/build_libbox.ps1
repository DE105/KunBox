# Final Fix Build Script
# 1. Downloads/Uses Go 1.23 (Fixed path to avoid re-download)
# 2. Builds with correct package name (Fixes crash)
# 3. Builds with aggressive strip (Fixes size)
# 4. Auto-fetches latest stable version from GitHub if not specified

param(
    [string]$Version = "",  # Empty = auto-fetch latest
    [string]$OutputDir = "$PSScriptRoot\..\..\app\libs",
    [switch]$UseLatest = $true  # Default to using latest version
)

$ErrorActionPreference = "Stop"
$CacheDir = Join-Path $env:TEMP "SingBoxBuildCache_Fixed"
$GoZipPath = Join-Path $CacheDir "go1.23.4.zip"
$GoExtractPath = Join-Path $CacheDir "go_extract"
$GoRoot = Join-Path $GoExtractPath "go"
$GoBin = Join-Path $GoRoot "bin"

Write-Host "[1/7] Setting up workspace..." -ForegroundColor Yellow
if (-not (Test-Path $CacheDir)) { New-Item -ItemType Directory -Force -Path $CacheDir | Out-Null }

# Auto-fetch latest version if not specified
if ([string]::IsNullOrEmpty($Version) -or $UseLatest) {
    Write-Host "Fetching latest stable version from GitHub..." -ForegroundColor Yellow
    try {
        $releaseInfo = Invoke-RestMethod -Uri "https://api.github.com/repos/SagerNet/sing-box/releases/latest" -Headers @{ "User-Agent" = "PowerShell" }
        $Version = $releaseInfo.tag_name -replace '^v', ''
        Write-Host "Latest stable version: $Version" -ForegroundColor Green
    }
    catch {
        Write-Host "Failed to fetch latest version, using fallback: 1.10.7" -ForegroundColor Yellow
        $Version = "1.10.7"
    }
}

# 2. Check/Download Go 1.24
if (-not (Test-Path "$GoBin\go.exe")) {
    if (-not (Test-Path $GoZipPath)) {
        Write-Host "[2/7] Downloading Go 1.24.0 (Required for gomobile compatibility)..." -ForegroundColor Yellow
        try {
            Invoke-WebRequest -Uri "https://go.dev/dl/go1.24.0.windows-amd64.zip" -OutFile $GoZipPath
        }
        catch {
            Write-Host "Download failed." -ForegroundColor Red
            exit 1
        }
    }
    else {
        Write-Host "[2/7] Found cached Go zip..." -ForegroundColor Green
    }
    
    Write-Host "Extracting Go..." -ForegroundColor Yellow
    if (Test-Path $GoExtractPath) { Remove-Item -Recurse -Force $GoExtractPath }
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    [System.IO.Compression.ZipFile]::ExtractToDirectory($GoZipPath, $GoExtractPath)
}
else {
    Write-Host "[2/7] Using cached Go environment..." -ForegroundColor Green
}

# 3. Setup Env
Write-Host "[3/7] Configuring Environment..." -ForegroundColor Yellow
$env:GOROOT = $GoRoot
$env:PATH = "$GoBin;$env:PATH"
$env:GOPATH = Join-Path $CacheDir "gopath"
$env:PATH = "$env:PATH;$env:GOPATH\bin"

# Fix NDK Path - Auto detect or use explicit valid version
$SdkRoot = $env:ANDROID_SDK_ROOT
if (-not $SdkRoot) { $SdkRoot = $env:ANDROID_HOME }
if (-not $SdkRoot) { $SdkRoot = "C:\Users\$env:USERNAME\AppData\Local\Android\Sdk" }

$NdkRoot = Join-Path $SdkRoot "ndk"
if (Test-Path $NdkRoot) {
    $LatestNdk = Get-ChildItem -Path $NdkRoot -Directory | Sort-Object Name -Descending | Select-Object -First 1
    if ($LatestNdk) {
        Write-Host "Setting ANDROID_NDK_HOME to $($LatestNdk.FullName)" -ForegroundColor Cyan
        $env:ANDROID_NDK_HOME = $LatestNdk.FullName
    }
}
if (-not $env:ANDROID_NDK_HOME) {
    Write-Warning "NDK not found. Please install Android NDK."
}

# 4. Install Tools
Write-Host "[4/7] Installing build tools..." -ForegroundColor Yellow
# Ensure bind source is present
go get golang.org/x/mobile/bind
go install golang.org/x/mobile/cmd/gomobile@latest
go install golang.org/x/mobile/cmd/gobind@latest
gomobile init

# 5. Clone/Update Source - Always fetch the target version
Write-Host "[5/7] Preparing Source (v$Version)..." -ForegroundColor Yellow
$LocalSource = Join-Path $PSScriptRoot "..\singbox-build"
if (Test-Path $LocalSource) {
    Write-Host "Using local source code from $LocalSource" -ForegroundColor Cyan
    $BuildDir = $LocalSource
}
else {
    $BuildDir = Join-Path $CacheDir "singbox-source-v$Version"
    
    # Clean up old source directories if building a different version
    $OldSources = Get-ChildItem -Path $CacheDir -Directory -Filter "singbox-source-*" | Where-Object { $_.Name -ne "singbox-source-v$Version" }
    foreach ($old in $OldSources) {
        Write-Host "Removing old source: $($old.Name)" -ForegroundColor Gray
        Remove-Item -Recurse -Force $old.FullName
    }
    
    if (-not (Test-Path $BuildDir)) {
        Write-Host "Cloning sing-box v$Version from GitHub..." -ForegroundColor Yellow
        git clone --depth 1 --branch "v$Version" https://github.com/SagerNet/sing-box.git $BuildDir
    }
    else {
        Write-Host "Using cached source for v$Version" -ForegroundColor Green
    }
}
Push-Location $BuildDir

# Fix deps in source
# Create a dummy file to force retention of mobile/bind dependency
$DummyFile = Join-Path $BuildDir "tools_build.go"
# Must match existing package name in root (box)
Set-Content -Path $DummyFile -Value 'package box; import _ "golang.org/x/mobile/bind"'

go get golang.org/x/mobile/bind
go mod tidy

# 6. Build
Write-Host "[6/7] Building kernel..." -ForegroundColor Yellow
$BUILD_TAGS = "with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api,with_conntrack"
Write-Host "Building optimized kernel (Package: io.nekohasekai.libbox) with version $Version..." -ForegroundColor Yellow

# IMPORTANT: -javapkg should be the prefix. Gomobile appends the go package name 'libbox'.
# So io.nekohasekai -> io.nekohasekai.libbox
# Include -X flag to inject version number into the binary
gomobile bind -v -androidapi 21 -target "android/arm64" -tags "$BUILD_TAGS" -javapkg io.nekohasekai -trimpath -ldflags "-s -w -buildid= -X github.com/sagernet/sing-box/constant.Version=$Version -extldflags '-Wl,-s'" -o "libbox.aar" ./experimental/libbox

if ($LASTEXITCODE -eq 0) {
    Write-Host "[7/7] Build Success! Updating project..." -ForegroundColor Green
    $Dest = $OutputDir
    if (-not (Test-Path $Dest)) { New-Item -ItemType Directory -Force -Path $Dest | Out-Null }
    Copy-Item "libbox.aar" (Join-Path $Dest "libbox.aar") -Force
    Write-Host "Updated libbox.aar at $Dest" -ForegroundColor Cyan
    Write-Host ""
    Write-Host "================================================" -ForegroundColor Green
    Write-Host "  sing-box v$Version built successfully!" -ForegroundColor Green
    Write-Host "  Output: $Dest\libbox.aar" -ForegroundColor Green
    Write-Host "================================================" -ForegroundColor Green
}
else {
    Write-Host "Build failed." -ForegroundColor Red
}

Pop-Location