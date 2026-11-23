$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot)
. "$PSScriptRoot/hosts.ps1"
$tool=$env:GLSL_VERIFIER_JAR
if(!$tool){
    $cache=Join-Path ([Environment]::GetFolderPath('UserProfile')) '.gradle/caches/modules-2/files-2.1/org.jetbrains.intellij.plugins/verifier-cli/1.409'
    $tool=Get-ChildItem -Recurse -Path $cache -Filter '*-all.jar' -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty FullName
}
if(!$tool){$tool='build/downloads/verifier-cli-1.409-all.jar';if(!(Test-Path $tool)){Invoke-WebRequest 'https://packages.jetbrains.team/maven/p/intellij-plugin-verifier/intellij-plugin-verifier/org/jetbrains/intellij/plugins/verifier-cli/1.409/verifier-cli-1.409-all.jar' -OutFile $tool}}
if((Get-FileHash $tool).Hash -ne 'B97EF2DAE064507564162CD7065FA532AAA1CFB0E30751520D30706E143B24BB'){throw 'Verifier checksum mismatch'}
$hosts=@(Get-GlslTestHosts)
$privateHome=[IO.Path]::GetFullPath('build/verifier-home')
$reports=[IO.Path]::GetFullPath('build/verification')
New-Item -Type Directory -Force $reports,$privateHome | Out-Null
$verdicts=Join-Path $reports ([Guid]::NewGuid().ToString('N'))
& java -Xmx2500m "-Duser.home=$privateHome" -jar $tool check-plugin build/distributions/GLSL-Support-1.1.1.zip @hosts -offline -verification-reports-dir $verdicts -team-city 2>&1 | Tee-Object -FilePath "$reports/verifier.log"
if($LASTEXITCODE){exit $LASTEXITCODE}
if(Select-String -Quiet -LiteralPath "$reports/verifier.log" -Pattern 'Plugin is invalid|Scheduled verifications \(0\)|##teamcity\[testFailed'){throw "Verifier reported a failure in $reports/verifier.log"}
$problems=Get-ChildItem $verdicts -Recurse -File -Filter 'compatibility-problems.txt'|Where-Object Length -gt 0
if($problems){throw ("Plugin compatibility problems: "+($problems.FullName -join ', '))}
$verifications=@(Get-ChildItem $verdicts -Recurse -File -Filter 'verification-verdict.txt')
if($verifications.Count -ne $hosts.Count){throw "Expected $($hosts.Count) IDE verdicts, received $($verifications.Count)"}
$summary=foreach($result in $verifications){
    $verdict=(Get-Content -Raw -LiteralPath $result.FullName).Trim()
    if(!$verdict.StartsWith('Compatible')){throw "$($result.FullName): $verdict"}
    "$($result.Directory.Parent.Parent.Parent.Name)  $verdict"
}
$summary | Set-Content -Encoding utf8NoBOM "$reports/summary.txt"
$summary
