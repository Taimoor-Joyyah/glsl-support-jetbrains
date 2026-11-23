param([switch]$Live)
$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot)
. "$PSScriptRoot/distribution.ps1"
if(!$Live){
    & (Get-GlslPython) scripts/sync-reference-pages.py --verify-upstream
    if($LASTEXITCODE){exit $LASTEXITCODE}
}
$testRoot=[IO.Path]::GetFullPath('build/reference-tests')
$run=Join-Path $testRoot ([Guid]::NewGuid().ToString('N'))
[IO.Compression.ZipFile]::ExtractToDirectory((Get-GlslDistribution),$run)
$mode=if($Live){'--live-all'}else{'--fixtures'}
& java --class-path "$run/GLSLSupport/lib/glsl-support.jar" scripts/ReferenceCacheCheck.java "$run/cache" $mode
if($LASTEXITCODE){exit $LASTEXITCODE}
Write-Output 'All 499 direct Khronos pages reproduced byte for byte and all 544 pages reopened offline.'
if([IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($run)) -eq $testRoot){Remove-Item -LiteralPath $run -Recurse -Force}
