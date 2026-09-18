# Compile CourseTime and run its self-test.
# ASCII-only content: safe for Windows PowerShell 5.1 without a BOM.

$ErrorActionPreference = 'Stop'

# The program writes UTF-8 to stdout regardless of the JVM default charset,
# so decode native output as UTF-8 here as well.
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding $false

$root = $PSScriptRoot
$out = Join-Path $root 'build/classes'

if (Test-Path $out) {
    Remove-Item -Recurse -Force $out
}
New-Item -ItemType Directory -Force -Path $out | Out-Null

$sources = Get-ChildItem -Path (Join-Path $root 'src') -Recurse -Filter *.java |
    ForEach-Object { $_.FullName }

& javac -encoding UTF-8 -Xlint:all -d $out $sources
if ($LASTEXITCODE -ne 0) {
    Write-Output "javac failed with exit code $LASTEXITCODE"
    exit $LASTEXITCODE
}

Write-Output "javac ok -> $out"
Write-Output ""

& java -cp $out courseclock.coursetime.CourseTimeTableTest
exit $LASTEXITCODE
