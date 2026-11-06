param(
    [switch]$IsolatedQa,
    [string]$PrivacyPolicyUrl
)
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$opsProperties = Join-Path (Split-Path $root -Parent) 'VikifyOps/local-config/keystore.properties'
$useOps = !$env:VIKIFY_RELEASE_STORE_FILE -and !$env:VIKIFY_KEYSTORE_PROPERTIES -and
    !(Test-Path -LiteralPath (Join-Path $root 'keystore.properties'))
Push-Location $root
try {
    if ($useOps) {
        if (!(Test-Path -LiteralPath $opsProperties -PathType Leaf)) {
            throw 'Provide VIKIFY_KEYSTORE_PROPERTIES for the existing Vikify release identity. See docs/RELEASE_SIGNING.md'
        }
        $env:VIKIFY_KEYSTORE_PROPERTIES = $opsProperties
    }
    $opts = @('--max-workers=2','--console=plain','-Djavax.net.ssl.trustStoreType=Windows-ROOT','-Djavax.net.ssl.trustStore=NONE')
    if ($IsolatedQa) { $opts += '-PisolatedReleaseQa=true' }
    if ($PrivacyPolicyUrl) { $opts += "-PprivacyPolicyUrl=$PrivacyPolicyUrl" }
    & .\gradlew.bat @opts :app:assembleRelease :app:bundleRelease
    if ($LASTEXITCODE) { throw 'Release build failed.' }
} finally {
    if ($useOps) { Remove-Item Env:VIKIFY_KEYSTORE_PROPERTIES -ErrorAction SilentlyContinue }
    Pop-Location
}
