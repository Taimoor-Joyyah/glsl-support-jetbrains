param([string[]]$Page)
$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot)
. "$PSScriptRoot/distribution.ps1"
& (Get-GlslPython) scripts/sync-reference-pages.py --verify-upstream
if($LASTEXITCODE){exit $LASTEXITCODE}
$root=[IO.Path]::GetFullPath('build/reference-tests')
$run=Join-Path $root ([Guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($run)
[void][IO.Directory]::CreateDirectory([IO.Path]::GetFullPath('build/reports'))
[IO.Compression.ZipFile]::ExtractToDirectory((Get-GlslDistribution),$run)
$library=& java --class-path "$run/GLSLSupport/lib/glsl-support.jar" scripts/ReferenceCacheCheck.java "$run/cache"
if($LASTEXITCODE){exit $LASTEXITCODE}
& node scripts/test-reference.mjs $library "$run/browser-profile" build/reports/offline-reference.json @Page
if($LASTEXITCODE){exit $LASTEXITCODE}
$resolved=[IO.Path]::GetFullPath($run)
if([IO.Path]::GetDirectoryName($resolved) -eq $root){
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
