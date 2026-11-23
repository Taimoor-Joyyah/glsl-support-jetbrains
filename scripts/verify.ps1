$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot)
. "$PSScriptRoot/distribution.ps1"
. "$PSScriptRoot/hosts.ps1"
$tool=$env:GLSL_VERIFIER_JAR
if(!$tool){
    $cache=Join-Path ([Environment]::GetFolderPath('UserProfile')) '.gradle/caches/modules-2/files-2.1/org.jetbrains.intellij.plugins/verifier-cli/1.411'
    $tool=Get-ChildItem -Recurse -Path $cache -Filter '*-all.jar' -ErrorAction SilentlyContinue | Select-Object -First 1 -ExpandProperty FullName
}
if(!$tool){$tool='build/downloads/verifier-cli-1.411-all.jar';if(!(Test-Path $tool)){Invoke-WebRequest 'https://packages.jetbrains.team/maven/p/intellij-plugin-verifier/intellij-plugin-verifier/org/jetbrains/intellij/plugins/verifier-cli/1.411/verifier-cli-1.411-all.jar' -OutFile $tool}}
if((Get-FileHash $tool).Hash -ne 'D6CC1C1F17761BB5EFFF187F98B63FB792C7EF2A2FB9202BB5DBEA05EBC02BFA'){throw 'Verifier checksum mismatch'}
$hosts=@(Get-GlslTestHosts)
foreach($hostPath in $hosts){
    $product=Get-Content -Raw -LiteralPath "$hostPath/product-info.json" | ConvertFrom-Json
    if(!($product.launch | Where-Object {$_.os -eq 'Windows' -and $_.arch -in 'amd64','x86_64'})){
        throw "This release requires a Windows x64 test host: $hostPath"
    }
}
# The offline verifier has no synthetic OS/CPU plugin descriptors. The host launch records,
# native PE header, package constraints and actual installed IDEs establish these two constraints.
$privateHome=[IO.Path]::GetFullPath('build/verifier-home')
$reports=[IO.Path]::GetFullPath('build/verification')
New-Item -Type Directory -Force $reports,$privateHome | Out-Null
$verdicts=Join-Path $reports ([Guid]::NewGuid().ToString('N'))
$inputDir=Join-Path $verdicts 'input'
$ideReports=Join-Path $verdicts 'ides'
[void][IO.Directory]::CreateDirectory($inputDir)
$built=Get-GlslDistribution
$filename=[IO.Path]::GetFileName($built)
$candidate=Join-Path $inputDir $filename
[IO.File]::Copy($built,$candidate)
$digest=(Get-FileHash -LiteralPath $candidate -Algorithm SHA256).Hash.ToLowerInvariant()
"$digest  $filename" | Set-Content -Encoding utf8NoBOM (Join-Path $inputDir 'SHA256SUMS')
$log=Join-Path $verdicts 'verifier.log'
& java -Xmx2500m "-Duser.home=$privateHome" -jar $tool check-plugin $candidate @hosts -offline -ignore-os-arch -verification-reports-dir $ideReports -team-city 2>&1 | Tee-Object -FilePath $log
if($LASTEXITCODE){exit $LASTEXITCODE}
if(Select-String -Quiet -LiteralPath $log -Pattern 'Plugin is invalid|Scheduled verifications \(0\)|##teamcity\[testFailed'){throw "Verifier reported a failure in $log"}
$problems=Get-ChildItem $ideReports -Recurse -File -Filter 'compatibility-problems.txt'|Where-Object Length -gt 0
if($problems){throw ("Plugin compatibility problems: "+($problems.FullName -join ', '))}
$verifications=@(Get-ChildItem $ideReports -Recurse -File -Filter 'verification-verdict.txt')
if($verifications.Count -ne $hosts.Count){throw "Expected $($hosts.Count) IDE verdicts, received $($verifications.Count)"}
$summary=foreach($result in $verifications){
    $verdict=(Get-Content -Raw -LiteralPath $result.FullName).Trim()
    if(!$verdict.StartsWith('Compatible')){throw "$($result.FullName): $verdict"}
    "$($result.Directory.Parent.Parent.Parent.Name)  $verdict"
}
$summary | Set-Content -Encoding utf8NoBOM "$verdicts/summary.txt"
foreach($name in 'verifier.log','summary.txt'){
    $staged=Join-Path $verdicts "$name.latest"
    [IO.File]::Copy((Join-Path $verdicts $name),$staged)
    [IO.File]::Move($staged,(Join-Path $reports $name),$true)
}
$summary
