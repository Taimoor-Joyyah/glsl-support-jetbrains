param([string]$IdeHome=$env:GLSL_IDE_HOME,[switch]$WithSmoke)
$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot)
. "$PSScriptRoot/distribution.ps1"
if(!$IdeHome){$IdeHome=Join-Path ([Environment]::GetFolderPath('UserProfile')) 'AppData/Local/Programs/IntelliJ IDEA Ultimate'}
if(!(Test-Path -LiteralPath "$IdeHome/product-info.json")){throw 'Set GLSL_IDE_HOME to an IntelliJ Platform 2026.1 SDK directory'}
$sdk=[IO.Path]::GetFullPath($IdeHome)
$classes="build/classes/$([Guid]::NewGuid().ToString('N'))"
$stage="$classes/distribution"
$pluginDir="$stage/GLSLSupport"
$lib="$pluginDir/lib"
New-Item -Type Directory -Force $classes,$lib,"$lib/native/win-x64",build/distributions | Out-Null
$files=foreach($module in @('language-core','native-bridge','intellij-plugin')){Get-ChildItem "$module/src/main/java" -Recurse -Filter '*.java'|ForEach-Object FullName}
$argFile="$classes/sources.args"
$files | ForEach-Object {'"'+$_.Replace('\','/')+'"'} | Set-Content -Encoding utf8NoBOM $argFile
& javac --release 21 -proc:none -encoding UTF-8 -classpath "$sdk/lib/*;$sdk/lib/modules/*" -d $classes "@$argFile"
if($LASTEXITCODE){exit $LASTEXITCODE}
$pluginResources='intellij-plugin/src/main/resources'
& (Get-GlslPython) scripts/reference-pack.py --catalog "$classes/generated"
if($LASTEXITCODE){exit $LASTEXITCODE}
$jarResources=foreach($item in Get-ChildItem -LiteralPath $pluginResources){
    if($item.Name -ne 'reference_pages'){'-C';$pluginResources;$item.Name}
}
& jar --create --file "$lib/glsl-support.jar" -C $classes com @jarResources -C language-core/src/main/resources . -C "$classes/generated" reference
if($LASTEXITCODE){exit $LASTEXITCODE}
Copy-Item -LiteralPath 'build/compiler/glsl_support.dll' -Destination "$lib/native/win-x64/" -Force
New-Item -Type Directory -Force "$pluginDir/licenses" | Out-Null
Copy-Item -Path 'licenses/*' -Destination "$pluginDir/licenses" -Force
$license=Get-ChildItem -File -LiteralPath . | Where-Object {$_.Name -in 'LICENSE','LICENSE.txt','LICENSE.md'} | Select-Object -First 1
if($license){Copy-Item -LiteralPath $license.FullName -Destination "$pluginDir/LICENSE"}
$zip=Get-GlslDistribution
$tempZip="$zip.$([Guid]::NewGuid().ToString('N')).tmp"
[IO.Compression.ZipFile]::CreateFromDirectory([IO.Path]::GetFullPath($stage),$tempZip)
[IO.File]::Move($tempZip,$zip,$true)
if($WithSmoke){
    $testClasses="$classes/smoke";$testLib="build/test-plugin/GLSLSupportTests/lib"
    New-Item -Type Directory -Force $testClasses,$testLib | Out-Null
    $tests=@(Get-ChildItem 'intellij-plugin/src/ideTest/java' -Filter '*.java' -Recurse | ForEach-Object FullName)
    & javac --release 21 -proc:none -encoding UTF-8 -cp "$sdk/lib/*;$sdk/lib/modules/*;$lib/glsl-support.jar" -d $testClasses @tests
    if($LASTEXITCODE){exit $LASTEXITCODE}
    & jar --create --file "$testLib/smoke.jar" -C $testClasses . -C intellij-plugin/src/ideTest/resources .
    if($LASTEXITCODE){exit $LASTEXITCODE}
}
Write-Output $zip
