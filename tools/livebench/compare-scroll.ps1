param(
    [Parameter(Mandatory)][string]$BeforeApk,
    [Parameter(Mandatory)][string]$AfterApk,
    [Parameter(Mandatory)][string]$OutputRoot
)
$ErrorActionPreference = 'Stop'
if ($env:ANDROID_SERIAL -ne 'emulator-5666') { throw 'Select only the owned emulator-5666' }
$adb = Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'
$package = 'io.github.papajon123.meshcentral.debug'
$activity = "$package/ru.papajon.meshcentralmobile.MainActivity"
$variants = @{ before = (Resolve-Path -LiteralPath $BeforeApk).Path; after = (Resolve-Path -LiteralPath $AfterApk).Path }
$hashes = @{}
foreach ($variant in @('before','after')) { $hashes[$variant] = (Get-FileHash -LiteralPath $variants[$variant]).Hash }
foreach ($trial in 1..3) {
    # Counterbalance the middle pair; never select a run according to its performance.
    $order = if ($trial -eq 2) { @('after','before') } else { @('before','after') }
    foreach ($variant in $order) {
        $destination = Join-Path $OutputRoot "$variant-$trial"
        New-Item -ItemType Directory -Force -Path $destination | Out-Null
        & $adb -s emulator-5666 shell am force-stop $package
        & $adb -s emulator-5666 install -r -d $variants[$variant] | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Failed to install measurement APK' }
        & $adb -s emulator-5666 shell am start -n $activity | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Failed to start measurement activity' }
        Start-Sleep -Seconds 3
        $os = Get-CimInstance Win32_OperatingSystem
        [ordered]@{
            variant = $variant; trial = $trial; utc = [DateTime]::UtcNow.ToString('o')
            apk_sha256 = $hashes[$variant]; free_memory_mib = [math]::Round($os.FreePhysicalMemory / 1024)
        } | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $destination 'environment.json') -Encoding utf8
        & python (Join-Path $PSScriptRoot 'large-scroll.py') $destination --all
        if ($LASTEXITCODE -ne 0) { throw 'Invalid scrolling measurement; inspect this run before continuing' }
    }
}
