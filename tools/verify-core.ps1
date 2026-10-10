$ErrorActionPreference = 'Stop'
$g = "C:\Users\circle5\.gradle\caches\modules-2\files-2.1"

# Resolve each jar once, with a single pass, instead of one recursive scan per jar.
$want = @(
  'kotlin-compiler-embeddable-2.0.21.jar',
  'kotlin-stdlib-2.0.21.jar',
  'kotlin-reflect-2.0.21.jar',
  'kotlin-script-runtime-2.0.21.jar',
  'kotlin-daemon-embeddable-2.0.21.jar',
  'trove4j-1.0.20200330.jar',
  'annotations-23.0.0.jar',
  'kotlin-test-2.0.21.jar',
  'kotlin-test-junit-2.0.21.jar',
  'junit-4.13.2.jar',
  'hamcrest-core-1.3.jar',
  'kotlinx-coroutines-core-jvm-1.9.0.jar'
)
$all = Get-ChildItem -Path $g -Filter '*.jar' -Recurse -ErrorAction SilentlyContinue |
  Where-Object { $_.Name -notmatch 'sources|javadoc' }

function Jar($name) {
  $hit = $all | Where-Object { $_.Name -eq $name } | Select-Object -First 1
  if ($hit) { $hit.FullName } else { $null }
}

$compiler    = Jar 'kotlin-compiler-embeddable-2.0.21.jar'
$stdlib      = Jar 'kotlin-stdlib-2.0.21.jar'
$reflect     = Jar 'kotlin-reflect-2.0.21.jar'
$script      = Jar 'kotlin-script-runtime-2.0.21.jar'
$daemon      = Jar 'kotlin-daemon-embeddable-2.0.21.jar'
$trove       = Jar 'trove4j-1.0.20200330.jar'
$annotations = Jar 'annotations-23.0.0.jar'
$ktest       = Jar 'kotlin-test-2.0.21.jar'
$ktestjunit  = Jar 'kotlin-test-junit-2.0.21.jar'
$junit       = Jar 'junit-4.13.2.jar'
$hamcrest    = Jar 'hamcrest-core-1.3.jar'
$coroutines  = Jar 'kotlinx-coroutines-core-jvm-1.9.0.jar'

$missing = @()
foreach ($pair in @(
  @('compiler',$compiler), @('stdlib',$stdlib), @('reflect',$reflect),
  @('script',$script), @('daemon',$daemon), @('trove',$trove),
  @('annotations',$annotations), @('ktest',$ktest), @('ktestjunit',$ktestjunit),
  @('junit',$junit), @('hamcrest',$hamcrest), @('coroutines',$coroutines)
)) { if (-not $pair[1]) { $missing += $pair[0] } }
if ($missing) { Write-Output "MISSING: $($missing -join ', ')"; exit 1 }

$src  = "C:\Users\circle5\AppData\Local\Temp\opencode\bp\app\src"
$out  = "C:\Users\circle5\AppData\Local\Temp\opencode\bp\build-test"
$JAVA = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot\bin\java.exe'
if (-not (Test-Path $JAVA)) { Write-Output "JDK 17 not found"; exit 1 }
Remove-Item -Recurse -Force $out -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $out | Out-Null

$files = @(
  Get-ChildItem -Path "$src\main\java\com\nothingnesscore\bootpatcher\core" -Filter *.kt
  Get-ChildItem -Path "$src\test\java\com\nothingnesscore\bootpatcher\core" -Filter *.kt
) | ForEach-Object { $_.FullName }

$cp         = @($stdlib,$reflect,$script,$daemon,$trove,$annotations,$coroutines,$ktest,$ktestjunit,$junit,$hamcrest) -join ';'
$compilerCp = @($compiler,$stdlib,$reflect,$script,$trove,$annotations,$coroutines) -join ';'

Write-Output "--- compiling $($files.Count) files ---"
$compileOut = Join-Path $out 'compile.txt'
$argList = @('-cp', $compilerCp, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
  '-no-stdlib','-nowarn','-jvm-target','17','-classpath',$cp,'-d',$out) + $files
$p = Start-Process -FilePath $JAVA -ArgumentList $argList -NoNewWindow -Wait -PassThru `
  -RedirectStandardOutput $compileOut -RedirectStandardError "$compileOut.err"
Get-Content $compileOut -ErrorAction SilentlyContinue | ForEach-Object { Write-Output $_ }
Get-Content "$compileOut.err" -ErrorAction SilentlyContinue |
  Where-Object { $_ -notmatch 'WARNING' -and $_ -match '\S' } | Select-Object -First 25 | ForEach-Object { Write-Output $_ }
if ($p.ExitCode -ne 0) { Write-Output "COMPILE FAILED ($($p.ExitCode))"; exit 1 }

Write-Output "--- running tests ---"
$runCp  = @($out,$stdlib,$reflect,$ktest,$ktestjunit,$junit,$hamcrest) -join ';'
$testOut = Join-Path $out 'test.txt'
$t = Start-Process -FilePath $JAVA -PassThru -NoNewWindow -Wait `
  -ArgumentList @('-cp',$runCp,'org.junit.runner.JUnitCore',
    'com.nothingnesscore.bootpatcher.core.BootImageParserTest',
    'com.nothingnesscore.bootpatcher.core.CompatibilityCheckerTest') `
  -RedirectStandardOutput $testOut -RedirectStandardError "$testOut.err"
Get-Content $testOut -ErrorAction SilentlyContinue | ForEach-Object { Write-Output $_ }
Get-Content "$testOut.err" -ErrorAction SilentlyContinue |
  Where-Object { $_ -notmatch 'WARNING' -and $_ -match '\S' } | ForEach-Object { Write-Output $_ }
Write-Output "EXIT=$($t.ExitCode)"