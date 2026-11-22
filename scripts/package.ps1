param([string]$IdeHome=$env:GLSL_IDE_HOME,[switch]$WithSmoke)
$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot)
if(!$IdeHome){$IdeHome=Join-Path ([Environment]::GetFolderPath('UserProfile')) 'AppData/Local/Programs/IntelliJ IDEA Ultimate'}
if(!(Test-Path -LiteralPath "$IdeHome/product-info.json")){throw 'Set GLSL_IDE_HOME to an IntelliJ Platform 2026.1 SDK directory'}
$sdk=[IO.Path]::GetFullPath($IdeHome)
$classes="build/classes/$([Guid]::NewGuid().ToString('N'))"
$lib='build/staging/GLSLSupport/lib'
New-Item -Type Directory -Force $classes,$lib,"$lib/native/win-x64",build/distributions | Out-Null
$files=foreach($module in @('language-core','native-bridge','intellij-plugin')){Get-ChildItem "$module/src/main/java" -Recurse -Filter '*.java'|ForEach-Object FullName}
$argFile="$classes/sources.args"
$files | ForEach-Object {'"'+$_.Replace('\','/')+'"'} | Set-Content -Encoding utf8NoBOM $argFile
& javac --release 21 -proc:none -encoding UTF-8 -classpath "$sdk/lib/*;$sdk/lib/modules/*" -d $classes "@$argFile"
if($LASTEXITCODE){exit $LASTEXITCODE}
$pluginResources='intellij-plugin/src/main/resources'
$jarResources=foreach($item in Get-ChildItem -LiteralPath $pluginResources){
    if($item.Name -ne 'reference_pages'){'-C';$pluginResources;$item.Name}
}
& jar --create --file "$lib/glsl-support.jar" -C $classes com @jarResources -C language-core/src/main/resources .
if($LASTEXITCODE){exit $LASTEXITCODE}
Copy-Item -LiteralPath 'build/compiler/glsl_support.dll' -Destination "$lib/native/win-x64/" -Force
New-Item -Type Directory -Force build/staging/GLSLSupport/licenses | Out-Null
Copy-Item -Path 'licenses/*' -Destination build/staging/GLSLSupport/licenses -Force
$zip=[IO.Path]::GetFullPath('build/distributions/GLSL-Support-1.1.0.zip')
$tempZip="$zip.$([Guid]::NewGuid().ToString('N')).tmp"
[IO.Compression.ZipFile]::CreateFromDirectory([IO.Path]::GetFullPath('build/staging'),$tempZip)
$pages=[IO.Path]::GetFullPath("$pluginResources/reference_pages")
$archive=[IO.Compression.ZipFile]::Open($tempZip,[IO.Compression.ZipArchiveMode]::Update)
try {
    foreach($path in [IO.Directory]::EnumerateFiles($pages,'*',[IO.SearchOption]::AllDirectories)){
        $entry='GLSLSupport/reference_pages/'+[IO.Path]::GetRelativePath($pages,$path).Replace('\','/')
        [void][IO.Compression.ZipFileExtensions]::CreateEntryFromFile($archive,$path,$entry)
    }
} finally { $archive.Dispose() }
[IO.File]::Move($tempZip,$zip,$true)
if($WithSmoke){
    $testClasses="build/smoke/classes";$testLib="build/test-plugin/GLSLSupportTests/lib"
    New-Item -Type Directory -Force $testClasses,$testLib | Out-Null
    $tests=@(Get-ChildItem 'intellij-plugin/src/ideTest/java' -Filter '*.java' -Recurse | ForEach-Object FullName)
    & javac --release 21 -proc:none -encoding UTF-8 -cp "$sdk/lib/*;$sdk/lib/modules/*;$lib/glsl-support.jar" -d $testClasses @tests
    if($LASTEXITCODE){exit $LASTEXITCODE}
    & jar --create --file "$testLib/smoke.jar" -C $testClasses . -C intellij-plugin/src/ideTest/resources .
    if($LASTEXITCODE){exit $LASTEXITCODE}
}
Write-Output $zip
