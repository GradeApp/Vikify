param(
    [Parameter(Mandatory=$true)][string]$KitDirectory,
    [switch]$CreateDraft
)
$ErrorActionPreference = 'Stop'
$kit = (Resolve-Path -LiteralPath $KitDirectory).Path
$manifest = Get-Content -LiteralPath (Join-Path $kit 'release-manifest.json') -Raw | ConvertFrom-Json
if ($manifest.repository -ne 'GradeApp/Vikify' -or $manifest.package -ne 'vikify.vpn') {
    throw 'Unexpected repository or package.'
}
if ($manifest.version -notmatch '^\d+\.\d+\.\d+$' -or $manifest.versionCode -lt 1) { throw 'Invalid version.' }
$tag = "vikify-v$($manifest.version)-build$($manifest.versionCode)"
$branch = "codex/vikify-$($manifest.version)-build$($manifest.versionCode)"
$files = @($manifest.assets)
foreach ($asset in $files) {
    if ($asset.name -ne [IO.Path]::GetFileName($asset.name)) { throw 'Unsafe asset name.' }
    $path = Join-Path $kit $asset.name
    if (!(Test-Path -LiteralPath $path -PathType Leaf)) { throw "Missing asset: $($asset.name)" }
    if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -ne $asset.sha256) {
        throw "Checksum mismatch: $($asset.name)"
    }
}
$source = $files | Where-Object { $_.kind -eq 'source' }
if (@($source).Count -ne 1) { throw 'Exactly one source snapshot is required.' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead((Join-Path $kit $source.name))
try {
    foreach ($entry in $archive.Entries) {
        $name = $entry.FullName.Replace('\','/')
        if ($name.StartsWith('/') -or $name.Contains(':') -or $name.Split('/') -contains '..' -or
            $name -match '(^|/)(\.git|\.gradle|build|backups|release-artifacts|local.properties|keystore.properties)(/|$)' -or
            $name -match '\.(jks|keystore|p12|pfx|pem)$') { throw 'Unsafe source archive entry.' }
    }
} finally { $archive.Dispose() }
Write-Output "Validated $($files.Count) assets for $tag in GradeApp/Vikify."
if (!$CreateDraft) {
    Write-Output 'Validation only. Use -CreateDraft after gh auth login to create a new source branch and a draft release.'
    exit 0
}
function Invoke-Checked([string]$program, [string[]]$arguments) {
    & $program @arguments
    if ($LASTEXITCODE -ne 0) { throw "$program failed (exit $LASTEXITCODE)." }
}
Get-Command gh,git -ErrorAction Stop | Out-Null
Invoke-Checked 'gh' @('auth','status','--hostname','github.com')
$remote = 'https://github.com/GradeApp/Vikify.git'
$existing = & git ls-remote $remote "refs/heads/$branch" "refs/tags/$tag" "refs/tags/$tag^{}"
if ($LASTEXITCODE) { throw 'Could not check remote references.' }
if ($existing) { throw 'The target branch or tag already exists. Inspect it manually; nothing was overwritten.' }
# An independent source branch avoids replacing the old Xray fork on master.
# A unique staging directory is retained for recovery if any network step fails.
$stage = Join-Path (Split-Path $kit -Parent) ("github-source-" + $tag + '-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $stage | Out-Null
[IO.Compression.ZipFile]::ExtractToDirectory((Join-Path $kit $source.name), $stage)
Push-Location $stage
try {
    Invoke-Checked 'git' @('init','--initial-branch',$branch)
    Invoke-Checked 'git' @('var','GIT_AUTHOR_IDENT')
    Invoke-Checked 'git' @('remote','add','origin',$remote)
    # Every file comes from the validated, explicitly allowlisted source archive.
    Invoke-Checked 'git' @('add','--force','--all','--','.')
    Invoke-Checked 'git' @('commit','-m',"Vikify $($manifest.version) build $($manifest.versionCode): source snapshot")
    Invoke-Checked 'git' @('push','origin',"HEAD:refs/heads/$branch")
    $releaseArguments = @('release','create',$tag,'--repo','GradeApp/Vikify','--target',$branch,'--draft',
        '--title',"Vikify $($manifest.version) (build $($manifest.versionCode))",'--notes-file',(Join-Path $kit 'RELEASE_NOTES.md'))
    $releaseArguments += @($files | ForEach-Object { Join-Path $kit $_.name })
    $releaseArguments += Join-Path $kit 'release-manifest.json'
    Invoke-Checked 'gh' $releaseArguments
    Write-Output 'Draft created. Review it on GitHub before publishing. The default branch was not changed.'
} finally { Pop-Location }
