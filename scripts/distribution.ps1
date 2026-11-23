function Get-GlslDescriptor {
    [xml](Get-Content -Raw -LiteralPath (Join-Path $PSScriptRoot '../intellij-plugin/src/main/resources/META-INF/plugin.xml'))
}
function Get-GlslDistribution {
    $plugin=(Get-GlslDescriptor).'idea-plugin'
    $name=($plugin.name -replace '[^A-Za-z0-9.-]+','-').Trim('-')
    [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "../build/distributions/$name-$($plugin.version)-windows-x86_64.zip"))
}
function Get-GlslPython {
    if ($env:GLSL_PYTHON) { return $env:GLSL_PYTHON }
    $profileDir=[Environment]::GetFolderPath('UserProfile')
    $local=Get-ChildItem "$profileDir/AppData/Local/Python/pythoncore-*-64/python.exe" -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($local) { return $local.FullName }
    return 'python'
}
