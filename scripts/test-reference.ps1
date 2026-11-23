param([string[]]$Page)
$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot)
$root=[IO.Path]::GetFullPath('build/reference-tests')
$run=Join-Path $root ([Guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($run)
[void][IO.Directory]::CreateDirectory([IO.Path]::GetFullPath('build/reports'))
[IO.Compression.ZipFile]::ExtractToDirectory([IO.Path]::GetFullPath('build/distributions/GLSL-Support-1.1.1.zip'),$run)
& node scripts/test-reference.mjs "$run/GLSLSupport/reference_pages" "$run/browser-profile" build/reports/offline-reference.json @Page
if($LASTEXITCODE){exit $LASTEXITCODE}
$resolved=[IO.Path]::GetFullPath($run)
if([IO.Path]::GetDirectoryName($resolved) -eq $root){
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
