# Build xjasonlyu/tun2socks for Android arm64 and copy to jniLibs.
# Requires: Go 1.18+, tun2socks repo in GOPATH (go get github.com/xjasonlyu/tun2socks/v2/... or clone).
$ErrorActionPreference = "Stop"
$repo = "github.com/xjasonlyu/tun2socks"
$gopath = go env GOPATH
$src = Join-Path $gopath "src" ($repo -replace "/v2$","")
if (-not (Test-Path $src)) {
    Write-Host "Cloning $repo..."
    $dir = Split-Path $src
    New-Item -ItemType Directory -Force -Path $dir | Out-Null
    Push-Location $dir
    git clone --depth 1 "https://github.com/xjasonlyu/tun2socks.git"
    Pop-Location
}
$out = Join-Path $PSScriptRoot "..\app\src\main\jniLibs\arm64-v8a\libtun2socks.so"
$outDir = Split-Path $out
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
Push-Location $src
$env:CGO_ENABLED = "0"
$env:GOOS = "android"
$env:GOARCH = "arm64"
go build -o $out .
if ($LASTEXITCODE -ne 0) { Pop-Location; exit 1 }
Pop-Location
Write-Host "Built: $out"
