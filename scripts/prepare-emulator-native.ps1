param([switch]$TrustWindowsHttpsScanner)
$ErrorActionPreference = 'Stop'
$nativeRoot = Join-Path $PSScriptRoot '..\app\build\emulator-native'
$archive = Join-Path $nativeRoot 'Xray-android-amd64.zip'
$expected = 'F11F5BB71EBB2FE4127670404FBD0C21D93F62E453870FDA65AD6EFFE7A6131E'
New-Item -ItemType Directory -Force -Path $nativeRoot | Out-Null
if (-not (Test-Path -LiteralPath $archive)) {
    Invoke-WebRequest 'https://github.com/XTLS/Xray-core/releases/download/v26.2.6/Xray-android-amd64.zip' -OutFile $archive
}
if ((Get-FileHash -LiteralPath $archive -Algorithm SHA256).Hash -ne $expected) {
    throw 'Xray archive checksum mismatch'
}
Expand-Archive -LiteralPath $archive -DestinationPath (Join-Path $nativeRoot 'upstream') -Force
$jniDir = Join-Path $nativeRoot 'jniLibs\x86_64'
New-Item -ItemType Directory -Force -Path $jniDir | Out-Null
Copy-Item -LiteralPath (Join-Path $nativeRoot 'upstream\xray') -Destination (Join-Path $jniDir 'libxray.so')
if ($TrustWindowsHttpsScanner) {
    $certificate = Get-ChildItem Cert:\LocalMachine\Root | Where-Object {
        $_.Subject -like 'CN=Avast Web/Mail Shield Root,*'
    } | Select-Object -First 1
    if (-not $certificate) { throw 'Installed Avast HTTPS scanner root was not found' }
    foreach ($folder in @('res\raw', 'res\xml', 'assets')) {
        New-Item -ItemType Directory -Force -Path (Join-Path $nativeRoot $folder) | Out-Null
    }
    [IO.File]::WriteAllBytes((Join-Path $nativeRoot 'res\raw\qa_root.cer'), $certificate.RawData)
    $pem = "-----BEGIN CERTIFICATE-----`n" + [Convert]::ToBase64String($certificate.RawData, 'InsertLineBreaks') + "`n-----END CERTIFICATE-----`n"
    [IO.File]::WriteAllText((Join-Path $nativeRoot 'assets\emulator-root.pem'), $pem)
    @'
<network-security-config>
    <base-config cleartextTrafficPermitted="false">
        <trust-anchors>
            <certificates src="system" />
            <certificates src="@raw/qa_root" />
        </trust-anchors>
    </base-config>
</network-security-config>
'@ | Set-Content -LiteralPath (Join-Path $nativeRoot 'res\xml\emulator_network_security_config.xml')
    '<manifest xmlns:android="http://schemas.android.com/apk/res/android"><application android:networkSecurityConfig="@xml/emulator_network_security_config" /></manifest>' | Set-Content -LiteralPath (Join-Path $nativeRoot 'AndroidManifest.xml')
    Write-Output "Emulator-only trust: $($certificate.Subject) / $($certificate.Thumbprint)"
} elseif (Test-Path -LiteralPath (Join-Path $nativeRoot 'AndroidManifest.xml')) {
    Remove-Item -LiteralPath (Join-Path $nativeRoot 'AndroidManifest.xml')
}
Write-Output 'Verified official Xray 26.2.6 Android amd64. Build with -Pemulator=true.'
