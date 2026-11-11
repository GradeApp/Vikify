param(
    [Parameter(Mandatory = $true)][string]$Adb,
    [Parameter(Mandatory = $true)][string]$Serial,
    [ValidateSet('vikify.vpn', 'vikify.vpn.uiqa')][string]$Package = 'vikify.vpn',
    [string]$OutputDirectory = 'app/build/reports/device-regression'
)

$ErrorActionPreference = 'Stop'
# These tests replace saved access. Use a fresh test installation only.
# Separate instrumentation processes prevent the application repository cache
# from retaining another test class's synthetic subscription.
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$classes = @(
    'vikify.vpn.profile.ProfileManagerTest',
    'vikify.vpn.subscription.SubscriptionIntegrationTest',
    'vikify.vpn.subscription.SubscriptionUiTest'
)
foreach ($class in $classes) {
    $name = $class.Split('.')[-1]
    & $Adb -s $Serial shell am force-stop $Package
    if ($LASTEXITCODE -ne 0) { throw 'Could not stop the previous test process.' }
    $output = & $Adb -s $Serial shell am instrument -w -r -e class $class "${Package}.test/androidx.test.runner.AndroidJUnitRunner" 2>&1
    $exitCode = $LASTEXITCODE
    $output | Set-Content -LiteralPath (Join-Path $OutputDirectory "$name.txt") -Encoding UTF8
    if ($exitCode -ne 0 -or ($output -join "`n") -notmatch 'OK \(\d+ tests?\)') {
        throw "$name failed. See $OutputDirectory/$name.txt."
    }
    Write-Output "$name passed"
}
