function New-GlslClionLauncher([string]$Sdk,[string]$Profile,[string]$OptionsFile,[string[]]$TestOptions=@()) {
    $info=Get-Content -Raw -LiteralPath (Join-Path $Sdk 'product-info.json') | ConvertFrom-Json
    $launch=$info.launch | Where-Object {$_.os -eq 'Windows' -and $_.arch -in @('amd64','x86_64')} | Select-Object -First 1
    $settings=@('-Xmx1600m','--enable-native-access=ALL-UNNAMED')
    foreach($dir in @('config','system','plugins','log')){
        $path=Join-Path $Profile $dir
        New-Item -ItemType Directory -Force $path | Out-Null
        $settings+="-Didea.$dir.path=$path"
    }
    $settings+=$TestOptions
    $settings | Set-Content -Encoding utf8NoBOM -LiteralPath $OptionsFile
    $java=Join-Path $Sdk $launch.javaExecutablePath
    if(!$TestOptions.Count){$java=Join-Path (Split-Path $java) 'javaw.exe'}
    $start=[Diagnostics.ProcessStartInfo]::new($java)
    $start.UseShellExecute=$false
    foreach($arg in $launch.additionalJvmArguments){$start.ArgumentList.Add($arg.Replace('%IDE_HOME%',$Sdk).Replace('$IDE_HOME',$Sdk))}
    foreach($option in (Get-Content -LiteralPath $OptionsFile)){$start.ArgumentList.Add($option)}
    $start.ArgumentList.Add("-Didea.home.path=$Sdk")
    $start.ArgumentList.Add('-cp')
    $start.ArgumentList.Add((($launch.bootClassPathJarNames | ForEach-Object {Join-Path "$Sdk/lib" $_}) -join ';'))
    $start.ArgumentList.Add($launch.mainClass)
    return $start
}
