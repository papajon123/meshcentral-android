#Requires -Version 7.0
[CmdletBinding(DefaultParameterSetName='Status')]
param(
    [Parameter(Mandatory, ParameterSetName='Setup')][switch]$Setup,
    [Parameter(Mandatory, ParameterSetName='Start')][switch]$Start,
    [Parameter(Mandatory, ParameterSetName='Stop')][switch]$Stop,
    [Parameter(ParameterSetName='Status')][switch]$Status,
    [Parameter(Mandatory, ParameterSetName='Reset')][switch]$Reset,
    [Parameter(ParameterSetName='Start')][switch]$ServerOnly,
    [string]$AvdHome = (Join-Path $env:USERPROFILE 'orca\workspaces\other\Meshcentral-Android\state\public-avd-home')
)
$ErrorActionPreference = 'Stop'
$root = [IO.Path]::GetFullPath((Join-Path $env:LOCALAPPDATA 'meshcentral-livebench'))
$server = Join-Path $root 'server'
$data = Join-Path $root 'meshcentral-data'
$helper = Join-Path $PSScriptRoot 'bench.cjs'
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$adb = Join-Path $sdk 'platform-tools\adb.exe'
$emulator = Join-Path $sdk 'emulator\emulator.exe'
$node = (Get-Command node.exe).Source
$env:npm_config_registry = 'https://registry.npmjs.org/'
$env:npm_config_cache = Join-Path $root 'npm-cache'

function Initialize-Root {
    New-Item -ItemType Directory -Force $root | Out-Null
    if ((Get-Item -LiteralPath $root).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Data root must not be a junction or symlink.' }
    $sid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    & icacls $root /inheritance:r /grant:r "*${sid}:(OI)(CI)F" '*S-1-5-18:(OI)(CI)F' | Out-Null
    if ($LASTEXITCODE) { throw 'Unable to protect the private data directory.' }
    foreach ($name in @('logs','downloads','tmp','server','emulators')) { New-Item -ItemType Directory -Force (Join-Path $root $name) | Out-Null }
    $env:TEMP = Join-Path $root 'tmp'
    $env:TMP = $env:TEMP
}
function Invoke-Bench([string]$Command) {
    & $node $helper $Command
    if ($LASTEXITCODE) { throw "Bench command failed: $Command" }
}
function Get-OwnedServer {
    $file = Join-Path $root 'server-process.json'
    if (!(Test-Path -LiteralPath $file)) { return $null }
    $record = Get-Content -LiteralPath $file -Raw | ConvertFrom-Json
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$($record.pid)"
    if (!$process) { return $null }
    if ($process.Name -ne 'node.exe' -or !$process.CommandLine.Contains($data) -or $process.CreationDate.ToUniversalTime() -ne ([datetime]$record.created).ToUniversalTime()) {
        throw 'Recorded PID is not the owned bench server; refusing to touch it.'
    }
    return $process
}
function Get-Listener { @(Get-NetTCPConnection -State Listen -LocalPort 4443 -ErrorAction SilentlyContinue) }
function Start-Server {
    if (!(Test-Path "$server\node_modules\meshcentral\package.json") -or (Get-Content "$server\node_modules\meshcentral\package.json" -Raw | ConvertFrom-Json).version -ne '1.3.1') { throw 'Expected MeshCentral 1.3.1; run -Setup.' }
    if (Get-OwnedServer) { Write-Output 'Bench server already running.'; return }
    if ((Get-Listener).Count) { throw 'Port 4443 belongs to another process.' }
    if (!(Test-Path "$data\config.json")) { throw 'Run -Setup first.' }
    $entry = Join-Path $server 'node_modules\meshcentral\meshcentral.js'
    $process = Start-Process -FilePath $node -ArgumentList @(('"' + $entry + '"'), ('--datapath="' + $data + '"'), '--launch') -WorkingDirectory $server -WindowStyle Hidden -PassThru -RedirectStandardOutput "$root\logs\server.stdout.log" -RedirectStandardError "$root\logs\server.stderr.log"
    $cim = Get-CimInstance Win32_Process -Filter "ProcessId=$($process.Id)"
    @{pid=$process.Id;created=$cim.CreationDate.ToUniversalTime().ToString('o')} | ConvertTo-Json | Set-Content "$root\server-process.json"
    for ($i=0; $i -lt 90; $i++) {
        $process.Refresh()
        if ($process.HasExited) { throw 'Bench server exited. Inspect private logs locally.' }
        try {
            $response = Invoke-WebRequest 'https://localhost:4443/' -SkipCertificateCheck -TimeoutSec 2
            if ($response.StatusCode -eq 200) { Write-Output 'MeshCentral 1.3.1 listening at https://localhost:4443'; return }
        } catch { }
        Start-Sleep -Seconds 2
    }
    throw 'Bench server did not become ready in 180 seconds.'
}
function Invoke-Adb([string]$Serial, [string[]]$Arguments) {
    if ($Serial -notin @('emulator-5568','emulator-5570')) { throw 'Foreign emulator is forbidden.' }
    $env:ANDROID_SERIAL = $Serial
    & $adb -s $Serial @Arguments
    if ($LASTEXITCODE) { throw "ADB operation failed on $Serial" }
}
function Start-BenchEmulator([string]$Name, [int]$Port) {
    $serial = "emulator-$Port"
    $recordPath = Join-Path $root "emulators\$Port.json"
    $listener = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
    if ($listener) {
        if (!(Test-Path $recordPath)) { throw "Port $Port is occupied by an unowned emulator." }
        $record = Get-Content $recordPath -Raw | ConvertFrom-Json
        $owner = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener[0].OwningProcess)"
        if (!$owner -or !$owner.CommandLine.Contains($Name) -or !$owner.CommandLine.Contains('-read-only') -or $owner.ProcessId -ne $record.pid -or $owner.CreationDate.ToUniversalTime() -ne ([datetime]$record.created).ToUniversalTime()) { throw "Emulator ownership mismatch on $Port" }
        return
    }
    $freeGB = (Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory / 1MB
    if ($freeGB -lt 6) { throw "Less than 6 GB free RAM ($([math]::Round($freeGB,2))). Wait, then retry -Start." }
    if (!(Test-Path (Join-Path $AvdHome "$Name.ini"))) { throw "Missing AVD $Name" }
    $env:ANDROID_AVD_HOME = $AvdHome
    $env:ANDROID_SERIAL = $serial
    Start-Process -FilePath $emulator -ArgumentList @('-avd',$Name,'-port',"$Port",'-read-only','-no-snapshot','-no-boot-anim','-no-window','-memory','1536','-no-audio') -WindowStyle Hidden -RedirectStandardOutput "$root\logs\emulator-$Port.stdout.log" -RedirectStandardError "$root\logs\emulator-$Port.stderr.log" | Out-Null
    for ($i=0; $i -lt 120; $i++) {
        Start-Sleep -Seconds 2
        $listener = Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue
        if ($listener) {
            $owner = Get-CimInstance Win32_Process -Filter "ProcessId=$($listener[0].OwningProcess)"
            if ($owner -and $owner.CommandLine.Contains($Name) -and $owner.CommandLine.Contains('-read-only')) {
                @{pid=$owner.ProcessId;created=$owner.CreationDate.ToUniversalTime().ToString('o');avd=$Name} | ConvertTo-Json | Set-Content $recordPath
            }
        }
        $boot = & $adb -s $serial shell getprop sys.boot_completed 2>$null
        if ($boot -match '1') {
            Invoke-Adb $serial @('reverse','tcp:4443','tcp:4443')
            Invoke-Adb $serial @('shell','settings','put','system','screen_off_timeout','2147483647')
            foreach ($scale in @('window_animation_scale','transition_animation_scale','animator_duration_scale')) { Invoke-Adb $serial @('shell','settings','put','global',$scale,'0') }
            Invoke-Adb $serial @('shell','input','keyevent','KEYCODE_WAKEUP')
            Invoke-Adb $serial @('shell','wm','dismiss-keyguard')
            Write-Output "$serial ($Name) ready."
            return
        }
    }
    throw "Emulator $serial did not boot."
}
function Stop-Bench {
    foreach ($port in @(5568,5570)) {
        $recordPath = Join-Path $root "emulators\$port.json"
        if (!(Test-Path $recordPath)) { continue }
        $record = Get-Content $recordPath -Raw | ConvertFrom-Json
        $owner = Get-CimInstance Win32_Process -Filter "ProcessId=$($record.pid)"
        if ($owner) {
            if (!$owner.CommandLine.Contains($record.avd) -or !$owner.CommandLine.Contains('-read-only') -or $owner.CreationDate.ToUniversalTime() -ne ([datetime]$record.created).ToUniversalTime()) { throw "Refusing to stop unowned PID for $port" }
            Invoke-Adb "emulator-$port" @('emu','kill')
        }
        Remove-Item -LiteralPath $recordPath
    }
    & wsl.exe -d Ubuntu-24.04 -u root --exec sh -c 'if test -f /opt/meshcentral-livebench/owned-by-livebench; then systemctl stop meshcentral-livebench.service; fi'
    if ($LASTEXITCODE) { throw 'Unable to stop bench WSL service.' }
    $leaseFile = Join-Path $root 'wsl-process.json'
    if (Test-Path $leaseFile) {
        $lease = Get-Content $leaseFile -Raw | ConvertFrom-Json
        $owner = Get-CimInstance Win32_Process -Filter "ProcessId=$($lease.pid)"
        if ($owner) {
            if ($owner.Name -ne 'wsl.exe' -or !$owner.CommandLine.Contains('linux-keepalive.sh') -or $owner.CreationDate.ToUniversalTime() -ne ([datetime]$lease.created).ToUniversalTime()) { throw 'WSL lease ownership mismatch.' }
            Stop-Process -Id $owner.ProcessId
        }
        Remove-Item -LiteralPath $leaseFile
    }
    $process = Get-OwnedServer
    if ($process) { Stop-Process -Id $process.ProcessId; Wait-Process -Id $process.ProcessId -Timeout 20 -ErrorAction SilentlyContinue }
    Write-Output 'Owned bench processes stopped.'
}
function Install-Assets {
    $cache = Join-Path $data 'agentbuilds\legacy-1.2.6'
    New-Item -ItemType Directory -Force $cache | Out-Null
    $manifest = Get-Content "$server\node_modules\meshcentral\agents\agent-defaults.json" -Raw | ConvertFrom-Json
    $linux = ($manifest.releases | Where-Object repository -eq 'Ylianst/MeshAgent').files | Where-Object filename -eq 'meshagent_x86-64'
    $target = Join-Path $cache 'meshagent_x86-64'
    if (!(Test-Path $target)) { Invoke-WebRequest 'https://github.com/Ylianst/MeshAgent/releases/download/legacy-1.2.6/meshagent_x86-64' -OutFile $target }
    if ((Get-FileHash $target -Algorithm SHA384).Hash.ToLowerInvariant() -ne $linux.sha384) { throw 'Linux agent SHA384 mismatch.' }
    $apk = Join-Path $root 'downloads\MeshCentral-Agent-1.0.23.apk'
    if (!(Test-Path $apk)) { Invoke-WebRequest 'https://github.com/Ylianst/MeshCentralAndroidAgent/releases/download/1.0.23/MeshCentral-Agent-1.0.23.apk' -OutFile $apk }
    if ((Get-FileHash $apk -Algorithm SHA256).Hash.ToLowerInvariant() -ne '473c930b3bef5c1bb73c286ee99cd2f34c7f9755e3e58da89414aabcc4b54e65') { throw 'Android agent SHA256 mismatch.' }
    Write-Output 'Pinned Linux and Android agents downloaded and verified.'
}
function Install-Linux {
    # A systemd service alone does not keep a WSL distribution alive.
    $leaseFile = Join-Path $root 'wsl-process.json'
    $owner = $null
    if (Test-Path $leaseFile) {
        $lease = Get-Content $leaseFile -Raw | ConvertFrom-Json
        $owner = Get-CimInstance Win32_Process -Filter "ProcessId=$($lease.pid)"
        if ($owner -and ($owner.Name -ne 'wsl.exe' -or !$owner.CommandLine.Contains('linux-keepalive.sh') -or $owner.CreationDate.ToUniversalTime() -ne ([datetime]$lease.created).ToUniversalTime())) { throw 'WSL lease ownership mismatch.' }
    }
    if (!$owner) {
        $keepalive = (& wsl.exe -d Ubuntu-24.04 --exec wslpath -a (Join-Path $PSScriptRoot 'linux-keepalive.sh')).Trim()
        $leaseProcess = Start-Process wsl.exe -ArgumentList @('-d','Ubuntu-24.04','--exec','bash',('"' + $keepalive + '"')) -WindowStyle Hidden -PassThru -RedirectStandardOutput "$root\logs\wsl.stdout.log" -RedirectStandardError "$root\logs\wsl.stderr.log"
        $owner = Get-CimInstance Win32_Process -Filter "ProcessId=$($leaseProcess.Id)"
        @{pid=$owner.ProcessId;created=$owner.CreationDate.ToUniversalTime().ToString('o')} | ConvertTo-Json | Set-Content $leaseFile
    }
    $gateway = (& wsl.exe -d Ubuntu-24.04 --exec sh -c 'ip route show default').Split(' ', [StringSplitOptions]::RemoveEmptyEntries)[2]
    $env:BENCH_WSL_GATEWAY = $gateway
    Invoke-Bench 'enrollment'
    $wslRoot = (& wsl.exe -d Ubuntu-24.04 --exec wslpath -a $root).Trim()
    $wslScript = (& wsl.exe -d Ubuntu-24.04 --exec wslpath -a (Join-Path $PSScriptRoot 'linux-agent.sh')).Trim()
    & wsl.exe -d Ubuntu-24.04 -u root --exec bash $wslScript $wslRoot
    if ($LASTEXITCODE) { throw 'WSL bench agent installation failed.' }
}

if ($Setup -or $Start -or $Reset) { Initialize-Root }
if ($Reset) {
    Stop-Bench
    # Archive only explicitly owned state, never recursively delete a calculated path.
    $archive = Join-Path $root ('reset-' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
    New-Item -ItemType Directory $archive | Out-Null
    foreach ($name in @('meshcentral-data','credentials.json','fixture.json','android-enrollment.txt','linux-agent.msh')) {
        $source = [IO.Path]::GetFullPath((Join-Path $root $name))
        if (!$source.StartsWith($root + [IO.Path]::DirectorySeparatorChar)) { throw 'Archive path escaped bench root.' }
        if (Test-Path -LiteralPath $source) { Move-Item -LiteralPath $source -Destination $archive }
    }
    & wsl.exe -d Ubuntu-24.04 -u root --exec sh -c 'if test -f /opt/meshcentral-livebench/owned-by-livebench; then cd /opt/meshcentral-livebench; archive=previous-$(date +%s); mkdir "$archive"; test ! -f meshagent.db || mv meshagent.db "$archive/meshagent.db"; fi'
    if ($LASTEXITCODE) { throw 'WSL identity reset failed.' }
    $Setup = $true
}
if ($Setup) {
    if (Get-OwnedServer) { throw 'Stop the bench before -Setup.' }
    if ((Get-Listener).Count) { throw 'Port 4443 is in use; refusing offline fixture updates.' }
    if (!(Test-Path "$server\package.json")) { '{"name":"meshcentral-livebench","version":"1.0.0","private":true,"dependencies":{"meshcentral":"1.3.1"}}' | Set-Content "$server\package.json" }
    Push-Location $server
    try {
        & npm.cmd install --registry https://registry.npmjs.org/ --cache "$root\npm-cache" --no-audit --no-fund *> "$root\logs\npm-install.log"
        if ($LASTEXITCODE) { throw 'npm install failed; inspect private npm-install.log.' }
    } finally { Pop-Location }
    $version = (Get-Content "$server\node_modules\meshcentral\package.json" -Raw | ConvertFrom-Json).version
    if ($version -ne '1.3.1') { throw 'Unexpected MeshCentral version.' }
    Invoke-Bench 'seed'
    Install-Assets
    Write-Output 'Setup complete. Use -Start to launch the bench.'
} elseif ($Start) {
    Start-Server
    Install-Linux
    if (!$ServerOnly) {
        Start-BenchEmulator 'PublicCompatibility35' 5568
        Invoke-Adb 'emulator-5568' @('install','-r',"$root\downloads\MeshCentral-Agent-1.0.23.apk")
        $env:ANDROID_SERIAL = 'emulator-5568'
        & python (Join-Path $PSScriptRoot 'ui.py') pair-agent
        if ($LASTEXITCODE) { throw 'Android pairing failed.' }
        Start-BenchEmulator 'PublicReadiness' 5570
        $apk = Join-Path $PSScriptRoot '..\..\app\build\outputs\apk\debug\app-debug.apk'
        if (Test-Path $apk) { Invoke-Adb 'emulator-5570' @('install','-r',([IO.Path]::GetFullPath($apk))) }
        else { Write-Warning 'Client APK missing; run assembleDebug, then -Start again.' }
    }
    if ($ServerOnly) { & $node $helper ready linux-only } else { & $node $helper ready }
    if ($LASTEXITCODE) { throw 'Required bench agents are not online.' }
} elseif ($Stop) { Stop-Bench }
else {
    $process = Get-OwnedServer
    if (!$process) { Write-Output 'Bench server stopped.'; exit 0 }
    Write-Output "Owned server PID: $($process.ProcessId)"
    Invoke-Bench 'status'
}
