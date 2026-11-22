param([string]$IdeHome=(Join-Path ([Environment]::GetFolderPath('UserProfile')) 'AppData/Local/Programs/CLion'))
$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot)
. "$PSScriptRoot/clion-launch.ps1"
$info=Get-Content -Raw -LiteralPath (Join-Path $IdeHome 'product-info.json') | ConvertFrom-Json
$profile=[IO.Path]::GetFullPath("build/ide-tests/CL-$($info.buildNumber)")
$start=New-GlslClionLauncher $IdeHome $profile (Join-Path $profile 'interactive.vmoptions')
$start.WindowStyle='Normal'
$proc=[Diagnostics.Process]::Start($start)
Write-Output "Opened CLion test profile (PID $($proc.Id)). Configuration: $profile/config"
