function Get-GlslTestHosts {
    $programs=Join-Path ([Environment]::GetFolderPath('UserProfile')) 'AppData/Local/Programs'
    $baseline=if($env:GLSL_IDE_HOME){$env:GLSL_IDE_HOME}else{Join-Path $programs 'IntelliJ IDEA Ultimate'}
    $target=if($env:GLSL_IDEA_263){$env:GLSL_IDEA_263}else{'build/ides/idea-263'}
    if(!(Test-Path "$target/product-info.json")){& "$PSScriptRoot/prepare-ide.ps1";if($LASTEXITCODE){throw 'Could not prepare IntelliJ IDEA 2026.3'}}
    $hosts=@($baseline,$target,(Join-Path $programs 'PyCharm Professional'),(Join-Path $programs 'CLion'))
    if($env:GLSL_TEST_IDES){$hosts+=($env:GLSL_TEST_IDES -split ';')}
    $supported=@()
    foreach($item in ($hosts|Select-Object -Unique)){if(Test-Path "$item/product-info.json"){$info=Get-Content "$item/product-info.json" -Raw|ConvertFrom-Json;if([int](($info.buildNumber -split '\.')[0]) -ge 261){$supported+=(Resolve-Path $item).Path}}}
    if($supported.Count -lt 2){throw 'Provide the baseline SDK with GLSL_IDE_HOME and IntelliJ IDEA 2026.3 with GLSL_IDEA_263'}
    return $supported
}
