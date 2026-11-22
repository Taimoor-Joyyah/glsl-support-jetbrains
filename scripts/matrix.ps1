$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot)
. "$PSScriptRoot/hosts.ps1"
$failures=@()
foreach($ide in (Get-GlslTestHosts)){Write-Host "Testing $ide";try{& "$PSScriptRoot/ide-test.ps1" -IdeHome $ide;if($LASTEXITCODE){$failures+=$ide}}catch{Write-Host $_;$failures+=$ide}}
if($failures.Count){throw ("IDE tests failed for "+($failures -join ', '))}
