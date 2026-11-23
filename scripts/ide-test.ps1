param([Parameter(Mandatory)][string]$IdeHome,[switch]$DocsWindow)
$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot)
$sdk=(Resolve-Path -LiteralPath $IdeHome).Path
$info=Get-Content -LiteralPath "$sdk/product-info.json" -Raw | ConvertFrom-Json
$launch=$info.launch | Where-Object { $_.os -eq 'Windows' -and $_.arch -in @('amd64','x86_64') } | Select-Object -First 1
if(!$launch){$launch=$info.launch[0]}
$base=[IO.Path]::GetFullPath("build/ide-tests/$($info.productCode)-$($info.buildNumber)")
$run=Join-Path $base ([Guid]::NewGuid().ToString('N'))
$project="$run/project"
New-Item -Type Directory -Force "$project/.idea","$project/first","$project/second","$base/config","$base/system","$base/plugins","$base/log" | Out-Null
[IO.Compression.ZipFile]::ExtractToDirectory([IO.Path]::GetFullPath('build/distributions/GLSL-Support-1.1.1.zip'),"$base/plugins",$true)
Copy-Item -LiteralPath 'build/test-plugin/GLSLSupportTests' -Destination "$base/plugins" -Recurse -Force
'<project version="4"><component name="ProjectModuleManager"><modules><module fileurl="file://$PROJECT_DIR$/shader.iml" filepath="$PROJECT_DIR$/shader.iml"/></modules></component></project>' | Set-Content -Encoding utf8NoBOM "$project/.idea/modules.xml"
'<module type="EMPTY_MODULE" version="4"><component name="NewModuleRootManager"><content url="file://$MODULE_DIR$"/><orderEntry type="sourceFolder" forTests="false"/></component></module>' | Set-Content -Encoding utf8NoBOM "$project/shader.iml"
'<project version="4"><component name="GlslAnalyzerSettings"><option name="shaderStageMacros" value="TESS_EVALUATION=OLD_TESS"/><option name="includeSearchPaths" value="legacy-headers"/></component></project>' | Set-Content -Encoding utf8NoBOM "$project/.idea/glslAnalyzer.xml"
'<project version="4"><component name="GlslVariantSettings"><option name="files"><list><FileState><option name="url" value="file://$PROJECT_DIR$/legacy.glsl"/><option name="defaultVariantId" value="imported"/><option name="variants"><list><VariantState><option name="id" value="imported"/><option name="name" value="Imported"/><option name="definitions"><list><MacroState><option name="name" value="ZED"/><option name="value" value="2"/></MacroState><MacroState><option name="name" value="ALPHA"/><option name="value" value="1"/></MacroState></list></option></VariantState></list></option></FileState></list></option></component></project>' | Set-Content -Encoding utf8NoBOM "$project/.idea/glslVariants.xml"
'const float value = 1.0;' | Set-Content -Encoding utf8NoBOM "$project/common.glslh"
Copy-Item -LiteralPath 'test-data/editor/glslSupport.xml' -Destination "$project/settings-example.xml"
@'
#version 450
layout(location=0) out vec2 uv;
void main(){
    vec4 currentClip = vec4(1);
    uv = currentClip.xyl / currentClip.w;
    gl_Position = currentClip;
}
'@ | Set-Content -Encoding utf8NoBOM "$project/swizzle.vert"
'#ifndef ROOT_SHADER
#error Compile through the owning shader
#endif
float readValue(vec2 v){return v.z;}' | Set-Content -Encoding utf8NoBOM "$project/swizzle.glslh"
'#version 450
#define ROOT_SHADER 1
#include "swizzle.glslh"
void main(){}' | Set-Content -Encoding utf8NoBOM "$project/header-owner.frag"
'glad_glDrawArrays(GL_TRIANGLES, 0, 3);' | Set-Content -Encoding utf8NoBOM "$project/calls.cpp"
'GL.glBindTexture(target, texture)' | Set-Content -Encoding utf8NoBOM "$project/calls.py"
'void main(){float x=1.;}' | Set-Content -Encoding utf8NoBOM "$project/format.frag"
@'
#version 450
#ifdef BROKEN
#error intentional test variant
#endif
struct Light { vec3 color; };
layout(std140,binding=0) uniform Scene { float amount; vec3 tint; } scene;
layout(location=0) out vec3 col;
void main() {
    col = normalize(scene.tint) + vec3(0.1,0.2,0.3);
    gl_Position = vec4(col,1.0);
}
'@ | Set-Content -Encoding utf8NoBOM "$project/tools.vert"
@'
#version 450
layout(location=0) in vec3 col;
layout(location=0) out vec4 pixel;
void main(){pixel=vec4(col,1);}
'@ | Set-Content -Encoding utf8NoBOM "$project/tools.frag"
"#version 450`n#include `"common.glslh`"`nvoid main(){gl_Position=vec4(value);}" | Set-Content -Encoding utf8NoBOM "$project/scene.vert"
$jvm=@($launch.additionalJvmArguments | ForEach-Object { $_.Replace('%IDE_HOME%',$sdk).Replace('$IDE_HOME',$sdk) })
$classpath=($launch.bootClassPathJarNames | ForEach-Object {Join-Path "$sdk/lib" $_}) -join ';'
$result="$run/result.txt"
$ideaLog=Join-Path $base 'log/idea.log'
$priorLength=if(Test-Path -LiteralPath $ideaLog){(Get-Item -LiteralPath $ideaLog).Length}else{0}
# Desktop IDEs process external-project file changes in the background. Use the same dispatch
# in the headless fixture so PyCharm does not collect project settings inside a VFS write action.
$asyncImport='-Dexternal.system.auto.import.headless.async=true'
$headless="-Djava.awt.headless=$((!$DocsWindow).ToString().ToLowerInvariant())"
$docs="-Dglsl.test.docsWindow=$(([bool]$DocsWindow).ToString().ToLowerInvariant())"
$jvm+=@('-Xmx1300m',$headless,$docs,$asyncImport,'-Didea.trust.all.projects=true','-Didea.auto.reload.plugins=false',
    "-Didea.home.path=$sdk","-Didea.config.path=$base/config","-Didea.system.path=$base/system","-Didea.plugins.path=$base/plugins","-Didea.log.path=$base/log",
    '-Didea.required.plugins.id=com.glslsupport.tests',"-Dglsl.test.result=$result","-Dglsl.test.project=$project",'--enable-native-access=ALL-UNNAMED','-cp',$classpath,$launch.mainClass,'glsl-support-test')
if($info.productCode -eq 'CL'){
    . "$PSScriptRoot/clion-launch.ps1"
    $settings=@($headless,$docs,$asyncImport,'-Didea.trust.all.projects=true','-Didea.auto.reload.plugins=false',
        '-Didea.required.plugins.id=com.glslsupport.tests',"-Dglsl.test.result=$result","-Dglsl.test.project=$project")
    $start=New-GlslClionLauncher $sdk $base (Join-Path $run 'test.vmoptions') $settings
    $start.ArgumentList.Add('glsl-support-test')
}else{
    $start=[Diagnostics.ProcessStartInfo]::new((Join-Path $sdk $launch.javaExecutablePath))
    foreach($arg in $jvm){$start.ArgumentList.Add($arg)}
}
# Some IDEs ship a boot classpath longer than the Windows process command line.
$argsFile=Join-Path $run 'java.args'
$start.ArgumentList | ForEach-Object {'"'+$_.Replace('\','\\').Replace('"','\"')+'"'} | Set-Content -LiteralPath $argsFile -Encoding utf8NoBOM
$start.ArgumentList.Clear()
$start.ArgumentList.Add("@$argsFile")
$start.UseShellExecute=$false
$start.CreateNoWindow=$true
$start.WindowStyle='Hidden'
$start.RedirectStandardOutput=$true
$start.RedirectStandardError=$true
$proc=[Diagnostics.Process]::Start($start)
$stdout=$proc.StandardOutput.ReadToEndAsync()
$stderr=$proc.StandardError.ReadToEndAsync()
if(!$proc.WaitForExit(240000)){$proc.Kill($true);throw "IDE test exceeded four minutes. See $base/log"}
[IO.File]::WriteAllText("$run/console.log",$stdout.Result+$stderr.Result)
$code=$proc.ExitCode
if(Test-Path -LiteralPath $result){Get-Content -LiteralPath $result}
else{Get-Content -LiteralPath "$run/console.log" -Tail 35;throw "IDE test did not produce a result. See $run"}
if($code){exit $code}
if((Get-Content -Raw -LiteralPath $result) -notmatch '^PASS'){exit 1}
if(Test-Path -LiteralPath $ideaLog){
    $stream=[IO.File]::Open($ideaLog,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::ReadWrite)
    try{
        if($stream.Length -ge $priorLength){$null=$stream.Seek($priorLength,[IO.SeekOrigin]::Begin)}
        $reader=[IO.StreamReader]::new($stream)
        $log=$reader.ReadToEnd()
        if($log -match '(?m)^.*SEVERE - .*?(Non-StubBasedPsiElement.*GLSL|Unknown bundled file template: GLSL|Plugin to blame: GLSL Support version:).*$'){
            $finding=$Matches[0]
            Add-Content -LiteralPath $result -Value "FAIL $finding"
            throw "The IDE logged a GLSL plugin error: $finding"
        }
    }finally{$stream.Dispose()}
}
