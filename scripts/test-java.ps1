param([ValidateSet('core','bridge')][string]$Stage='core')
$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot)
. "$PSScriptRoot/distribution.ps1"
$runner = 'build/downloads/junit.jar'
if (!(Test-Path $runner)) {
    New-Item -ItemType Directory -Force build/downloads | Out-Null
    Invoke-WebRequest 'https://repo.maven.apache.org/maven2/org/junit/platform/junit-platform-console-standalone/1.11.4/junit-platform-console-standalone-1.11.4.jar' -OutFile $runner
}
if ((Get-FileHash $runner).Hash -ne 'B016EF6B1C3454D6D7C2C88CE081DABF289699686AF6622D6E4E2E1B54B4A2FC') {throw 'JUnit archive checksum mismatch'}
$modules=@('language-core')
if($Stage -eq 'bridge'){$modules+='native-bridge'}
$dest="build/tests/$Stage"
New-Item -ItemType Directory -Force $dest | Out-Null
$files=foreach($m in $modules){Get-ChildItem "$m/src" -Recurse -Filter '*.java' | ForEach-Object FullName}
& javac --release 21 -encoding UTF-8 -classpath $runner -d $dest @files
if($LASTEXITCODE){exit $LASTEXITCODE}
Copy-Item -Path 'language-core/src/main/resources/*' -Destination $dest -Recurse -Force
& (Get-GlslPython) scripts/reference-pack.py --catalog $dest
if($LASTEXITCODE){exit $LASTEXITCODE}
$native=(Join-Path (Get-Location) 'build/compiler/glsl_support.dll')
& java --enable-native-access=ALL-UNNAMED "-Dglsl.native=$native" -jar $runner execute --class-path $dest --scan-class-path --reports-dir "build/reports/$Stage" --details=tree --disable-ansi-colors
exit $LASTEXITCODE
