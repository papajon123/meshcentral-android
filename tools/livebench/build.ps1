#Requires -Version 7.0
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$root = Join-Path $env:LOCALAPPDATA 'meshcentral-livebench'
if (!$env:JAVA_HOME -or !(Test-Path "$env:JAVA_HOME\bin\java.exe")) { throw 'Set JAVA_HOME to a JDK 17 installation.' }
if (!$env:ANDROID_HOME) { $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$version = (& "$env:JAVA_HOME\bin\java.exe" -version 2>&1) -join ' '
if ($version -notmatch 'version "17\.') { throw 'This baseline requires JDK 17.' }
foreach ($folder in @('logs','tmp','gradle','android-user')) { New-Item -ItemType Directory -Force (Join-Path $root $folder) | Out-Null }
$env:GRADLE_USER_HOME = Join-Path $root 'gradle'
$env:ANDROID_USER_HOME = Join-Path $root 'android-user'
$env:TEMP = Join-Path $root 'tmp'
$env:TMP = $env:TEMP
Push-Location (Join-Path $PSScriptRoot '..\..')
try {
    & .\gradlew.bat testDebugUnitTest lintDebug assembleDebug --no-daemon --max-workers=2 *> "$root\logs\gradle-check.log"
    if ($LASTEXITCODE) { throw 'Gradle checks failed; inspect the private logs/gradle-check.log.' }
    Write-Output 'testDebugUnitTest lintDebug assembleDebug: PASS'
} finally { Pop-Location }
