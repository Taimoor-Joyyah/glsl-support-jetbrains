$ErrorActionPreference='Stop'
. "$PSScriptRoot/distribution.ps1"
& (Get-GlslPython) "$PSScriptRoot/check-distribution.py" (Get-GlslDistribution)
exit $LASTEXITCODE
