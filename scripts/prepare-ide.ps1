$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot)
$target='build/ides/idea-263'
if(Test-Path "$target/product-info.json"){return}
New-Item -Type Directory -Force $target,build/downloads | Out-Null
$url='https://download.jetbrains.com/idea/idea-2026.3.win.zip'
$file='build/downloads/idea-2026.3.zip'
if(!(Test-Path $file)){Invoke-WebRequest $url -OutFile $file}
if(!(Test-Path 'build/downloads/idea-2026.3.sha256')){Invoke-WebRequest "$url.sha256" -OutFile 'build/downloads/idea-2026.3.sha256'}
$expected=((Get-Content 'build/downloads/idea-2026.3.sha256' -Raw).Trim() -split '\s+')[0]
if((Get-FileHash $file).Hash -ne $expected){throw 'IntelliJ IDEA distribution checksum mismatch'}
& tar -xf $file -C $target
if($LASTEXITCODE){exit $LASTEXITCODE}
