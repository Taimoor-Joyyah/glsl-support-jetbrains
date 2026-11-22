$ErrorActionPreference='Stop'
Set-Location (Split-Path $PSScriptRoot)
$dest='build/tests/benchmark'
New-Item -ItemType Directory -Force $dest,build/reports | Out-Null
& javac --release 21 -cp build/tests/bridge -d $dest benchmarks/ShaderBenchmark.java
if($LASTEXITCODE){exit $LASTEXITCODE}
$java=(Get-Command java -CommandType Application | Select-Object -First 1).Source
$start=[Diagnostics.ProcessStartInfo]::new($java)
$start.UseShellExecute=$false
$start.RedirectStandardOutput=$true
foreach($argument in @('-Xmx512m','--enable-native-access=ALL-UNNAMED','-cp',"$dest;build/tests/bridge",'ShaderBenchmark','build/compiler/glsl_support.dll')){
    $start.ArgumentList.Add($argument)
}
$child=[Diagnostics.Process]::Start($start)
$stdout=$child.StandardOutput.ReadToEndAsync()
$peak=0L
do {
    try{$child.Refresh();$peak=[Math]::Max($peak,$child.PeakWorkingSet64)}catch [InvalidOperationException]{}
} until ($child.WaitForExit(20))
$child.WaitForExit()
if($child.ExitCode){throw "Java benchmark exited with $($child.ExitCode)"}
$metrics=$stdout.Result | ConvertFrom-Json -AsHashtable
$metrics.javaProcessPeakWorkingSetBytes=$peak
$metrics.nativeProcessPeakWorkingSetBytes=[long](& ./build/compiler/compiler_tests.exe profile)
if($LASTEXITCODE){throw 'Native benchmark failed'}
$metrics.nativeCompilerRequests=100
$metrics.measuredUtc=[DateTime]::UtcNow.ToString('o')
$metrics | ConvertTo-Json | Set-Content -Encoding utf8NoBOM build/reports/performance.json
Get-Content build/reports/performance.json
