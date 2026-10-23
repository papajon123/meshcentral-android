'use strict';

const schema = require('./schema');
const { MAX_OUTPUT_BYTES } = require('./state');

const WINDOWS_POWERSHELL = 2; // MeshCore.js runcommands type 2
const LINUX_SHELL = 3;        // MeshCore.js runcommands type 3

const READ_TEMPLATES = new Set(['diagnostics', 'services', 'processes']);
const MUTATION_TEMPLATES = new Set(['service-start', 'service-stop', 'service-restart']);
const ALL_TEMPLATES = new Set([...READ_TEMPLATES, ...MUTATION_TEMPLATES]);

const WINDOWS_SERVICE_NAME = /^[A-Za-z0-9][A-Za-z0-9._ -]{0,127}$/;
const LINUX_SERVICE_NAME = /^[A-Za-z0-9][A-Za-z0-9_.@-]{0,127}$/;

function isReadTemplate(templateId) {
  return READ_TEMPLATES.has(templateId);
}

function isMutationTemplate(templateId) {
  return MUTATION_TEMPLATES.has(templateId);
}

function isValidTemplate(templateId) {
  return ALL_TEMPLATES.has(templateId);
}

function validateServiceName(name, platform) {
  if (typeof name !== 'string') return false;
  return platform === 'windows' ? WINDOWS_SERVICE_NAME.test(name) : LINUX_SERVICE_NAME.test(name);
}

function psQuote(value) {
  // Service names are validated before this is called; doubling quotes is
  // defense in depth and ensures the generated script is always a literal.
  return "'" + String(value).replace(/'/g, "''") + "'";
}

function psMarker(nonce) {
  return "[Console]::Out.WriteLine('__MC_DONE_" + nonce + "__' + $mcRc)";
}

function psPrelude() {
  return [
    // Mesh Agent embeds stdout/stderr in JSON WebSocket text frames. Windows
    // OEM code pages corrupt localized service names before the server parser.
    '[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)',
    '$OutputEncoding = [Console]::OutputEncoding',
    "$ErrorActionPreference = 'Stop'",
    "$ProgressPreference = 'SilentlyContinue'",
    '$mcRc = 0',
    'try {'
  ].join('\r\n');
}

function psFinish(nonce) {
  return [
    '} catch {',
    '  $mcRc = 1',
    "  [Console]::Error.WriteLine('mobilecompanion: ' + $_.Exception.Message)",
    '}',
    // MeshCore writes to powershell -Command - without closing stdin. A blank
    // line terminates this compound statement; otherwise even the appended
    // exit command is buffered and the agent never sends its final response.
    '',
    psMarker(nonce)
  ].join('\r\n');
}

function windowsDiagnostics(nonce) {
  const body = [
    psPrelude(),
    '  $os = Get-CimInstance Win32_OperatingSystem -ErrorAction Stop',
    '  $cpus = @(Get-CimInstance Win32_Processor -ErrorAction SilentlyContinue)',
    '  $hostname = $null; if ($env:COMPUTERNAME) { $hostname = [string]$env:COMPUTERNAME }',
    '  $osDescription = $null; if ($os.Caption) { $osDescription = [string]$os.Caption }',
    '  $uptime = $null',
    '  if ($os.LastBootUpTime -ne $null) { $uptime = [int64]((Get-Date) - $os.LastBootUpTime).TotalSeconds; if ($uptime -lt 0) { $uptime = 0 } }',
    '  $logical = $null',
    '  $load = $null',
    '  if ($cpus.Count -gt 0) {',
    '    $sum = 0; $sumOk = $true',
    '    foreach ($c in $cpus) { if ($c.NumberOfLogicalProcessors -ne $null) { $sum += [int]$c.NumberOfLogicalProcessors } else { $sumOk = $false } }',
    '    if ($sumOk -and ($sum -gt 0)) { $logical = $sum }',
    '    $loads = @($cpus | Where-Object { $_.LoadPercentage -ne $null } | ForEach-Object { [double]$_.LoadPercentage })',
    '    if ($loads.Count -gt 0) { $load = [math]::Round(($loads | Measure-Object -Average).Average, 2) }',
    '  }',
    '  $totalMem = $null; if ($os.TotalVisibleMemorySize -ne $null) { $totalMem = [int64]$os.TotalVisibleMemorySize * 1024 }',
    '  $freeMem = $null; if ($os.FreePhysicalMemory -ne $null) { $freeMem = [int64]$os.FreePhysicalMemory * 1024 }',
    '  $usedPercent = $null',
    '  if (($totalMem -ne $null) -and ($totalMem -gt 0) -and ($freeMem -ne $null)) { $usedPercent = [math]::Round((($totalMem - $freeMem) / $totalMem) * 100, 2) }',
    '  $truncated = $false',
    '  $diskRaw = @(Get-CimInstance Win32_LogicalDisk -Filter "DriveType=3" -ErrorAction SilentlyContinue)',
    '  if ($diskRaw.Count -gt 100) { $truncated = $true }',
    '  $disks = @()',
    '  foreach ($d in ($diskRaw | Select-Object -First 100)) {',
    '    $dt = $null; if ($d.Size -ne $null) { $dt = [int64]$d.Size }',
    '    $df = $null; if ($d.FreeSpace -ne $null) { $df = [int64]$d.FreeSpace }',
    '    $dp = $null; if (($dt -ne $null) -and ($dt -gt 0) -and ($df -ne $null)) { $dp = [math]::Round((($dt - $df) / $dt) * 100, 2) }',
    '    $disks += [ordered]@{ device = [string]$d.DeviceID; mount = [string]$d.DeviceID; totalBytes = $dt; availableBytes = $df; usedPercent = $dp }',
    '  }',
    '  $netRaw = @(Get-CimInstance Win32_NetworkAdapterConfiguration -Filter "IPEnabled=True" -ErrorAction SilentlyContinue)',
    '  if ($netRaw.Count -gt 100) { $truncated = $true }',
    '  $network = @()',
    '  foreach ($n in ($netRaw | Select-Object -First 100)) {',
    '    $addresses = @()',
    '    if ($n.IPAddress -ne $null) { foreach ($a in $n.IPAddress) { $addresses += [string]$a } }',
    '    $network += [ordered]@{ name = [string]$n.Description; mac = [string]$n.MACAddress; addresses = $addresses }',
    '  }',
    '  [ordered]@{',
    "    os = 'windows'",
    '    hostname = $hostname',
    '    osDescription = $osDescription',
    '    uptimeSeconds = $uptime',
    '    cpu = [ordered]@{ logicalCount = $logical; load1 = $null; loadPercent = $load }',
    '    memory = [ordered]@{ totalBytes = $totalMem; availableBytes = $freeMem; usedPercent = $usedPercent }',
    '    disks = $disks',
    '    network = $network',
    '    truncated = $truncated',
    '  } | ConvertTo-Json -Depth 8 -Compress',
    psFinish(nonce)
  ].join('\r\n');
  return { type: WINDOWS_POWERSHELL, script: body };
}

function windowsServices(nonce) {
  const body = [
    psPrelude(),
    '  $all = @(Get-Service -ErrorAction Stop)',
    '  $truncated = $all.Count -gt 200',
    '  $items = @()',
    '  foreach ($s in ($all | Select-Object -First 200)) {',
    '    $items += [ordered]@{ name = [string]$s.Name; displayName = [string]$s.DisplayName; state = [string]$s.Status; startMode = [string]$s.StartType }',
    '  }',
    "  [ordered]@{ os = 'windows'; items = $items; truncated = $truncated } | ConvertTo-Json -Depth 6 -Compress",
    psFinish(nonce)
  ].join('\r\n');
  return { type: WINDOWS_POWERSHELL, script: body };
}

function windowsProcesses(nonce) {
  const body = [
    psPrelude(),
    '  $raw = @(Get-CimInstance Win32_PerfFormattedData_PerfProc_Process -ErrorAction Stop | Where-Object { ($_.Name -ne \'_Total\') -and ($_.Name -ne \'Idle\') -and ($_.IDProcess -gt 0) })',
    '  $sorted = @($raw | Sort-Object -Property @{ Expression = { [double]$_.PercentProcessorTime } } -Descending)',
    '  $truncated = $sorted.Count -gt 200',
    '  $items = @()',
    '  foreach ($p in ($sorted | Select-Object -First 200)) {',
    '    $memory = $null; if ($p.WorkingSet -ne $null) { $memory = [int64]$p.WorkingSet }',
    '    $cpu = $null; if ($p.PercentProcessorTime -ne $null) { $cpu = [double]$p.PercentProcessorTime }',
    '    $items += [ordered]@{ pid = [int]$p.IDProcess; name = [string]$p.Name; cpu = $cpu; memory = $memory }',
    '  }',
    "  [ordered]@{ os = 'windows'; items = $items; truncated = $truncated } | ConvertTo-Json -Depth 6 -Compress",
    psFinish(nonce)
  ].join('\r\n');
  return { type: WINDOWS_POWERSHELL, script: body };
}

function windowsMutation(kind, name, nonce) {
  const nameLiteral = psQuote(name);
  const verb = kind === 'service-start' ? 'Start-Service' : (kind === 'service-stop' ? 'Stop-Service' : 'Restart-Service');
  const desired = kind === 'service-stop' ? 'Stopped' : 'Running';
  const body = [
    psPrelude(),
    '  $name = ' + nameLiteral,
    '  $svc = Get-Service -Name $name -ErrorAction Stop',
    '  $changed = $false',
    "  if ('" + kind + "' -eq 'service-start') {",
    "    if ($svc.Status.ToString() -ne 'Running') { " + verb + ' -Name $name -ErrorAction Stop; $changed = $true }',
    "  } elseif ('" + kind + "' -eq 'service-stop') {",
    "    if ($svc.Status.ToString() -ne 'Stopped') { " + verb + ' -Name $name -ErrorAction Stop; $changed = $true }',
    '  } else {',
    '    ' + verb + ' -Name $name -ErrorAction Stop; $changed = $true',
    '  }',
    '  $after = Get-Service -Name $name -ErrorAction Stop',
    "  if ($after.Status.ToString() -ne '" + desired + "') { throw 'service did not reach " + desired + "' }",
    "  [ordered]@{ os = 'windows'; operation = '" + kind + "'; name = $name; changed = $changed; state = [string]$after.Status } | ConvertTo-Json -Depth 4 -Compress",
    psFinish(nonce)
  ].join('\r\n');
  return { type: WINDOWS_POWERSHELL, script: body };
}

function shEscape(value) {
  return String(value).replace(/\\/g, '\\\\').replace(/\t/g, '\\t').replace(/\r/g, '\\r');
}

function shDiagnostics(nonce) {
  const lines = [
    'mc_rc=0',
    "mc_need() { if ! command -v \"$1\" >/dev/null 2>&1; then printf 'mobilecompanion: missing utility %s\\n' \"$1\" >&2; mc_rc=127; return 1; fi; return 0; }",
    "mc_esc() { printf '%s' \"$1\" | sed -e 's/\\\\/\\\\\\\\/g' -e 's/\\t/\\\\t/g' -e 's/\\r/\\\\r/g'; }",
    'mc_need sed',
    'mc_need head',
    'if ! command -v hostname >/dev/null 2>&1; then mc_need hostname; else mc_host=$(hostname 2>/dev/null); printf \'HOST\\t%s\\n\' "$(mc_esc "$mc_host")"; fi',
    'mc_osdesc=""',
    'if [ -r /etc/os-release ]; then mc_osdesc=$( . /etc/os-release 2>/dev/null; printf \'%s\' "${PRETTY_NAME:-}" ); fi',
    'printf \'OSDESC\\t%s\\n\' "$(mc_esc "$mc_osdesc")"',
    'if mc_need cut; then mc_uptime=$(cut -d. -f1 /proc/uptime 2>/dev/null); printf \'UPTIME\\t%s\\n\' "$mc_uptime"; fi',
    'mc_cpu=""',
    'if command -v getconf >/dev/null 2>&1; then mc_cpu=$(getconf _NPROCESSORS_ONLN 2>/dev/null); fi',
    'if [ -z "$mc_cpu" ] && [ -r /proc/cpuinfo ]; then mc_cpu=$(awk \'/^processor[ \\t]*:/ {c++} END {print c+0}\' /proc/cpuinfo 2>/dev/null); fi',
    'mc_load=$(cut -d\' \' -f1 /proc/loadavg 2>/dev/null)',
    'printf \'CPU\\t%s\\t%s\\n\' "$mc_cpu" "$mc_load"',
    'mc_mem_total=""',
    'mc_mem_avail=""',
    'if mc_need awk; then',
    '  mc_mem_total=$(awk \'/^MemTotal:/ {print $2*1024}\' /proc/meminfo 2>/dev/null)',
    '  mc_mem_avail=$(awk \'/^MemAvailable:/ {print $2*1024}\' /proc/meminfo 2>/dev/null)',
    'fi',
    'printf \'MEM\\t%s\\t%s\\n\' "$mc_mem_total" "$mc_mem_avail"',
    'if mc_need df; then',
    '  mc_df=$(df -P -k 2>/dev/null); mc_df_rc=$?',
    '  if [ $mc_df_rc -ne 0 ]; then mc_rc=$mc_df_rc; else',
    '    printf \'%s\\n\' "$mc_df" | awk \'NR>1 && NF>=6 && $2 ~ /^[0-9]+$/ { mount=$6; for (i=7; i<=NF; i++) mount=mount" "$i; print "DISK\\t"$1"\\t"mount"\\t"$2"\\t"$4 }\' | head -n 101',
    '  fi',
    'fi',
    'if mc_need ip; then',
    '  mc_ip=$(ip -o addr show 2>/dev/null); mc_ip_rc=$?',
    '  if [ $mc_ip_rc -ne 0 ]; then mc_rc=$mc_ip_rc; else',
    '    printf \'%s\\n\' "$mc_ip" | awk \'$3=="inet" || $3=="inet6" { print "NET\\t"$2"\\t"$4 }\' | head -n 101',
    '  fi',
    'fi',
    "printf '__MC_DONE_" + nonce + "__%s\\n' \"$mc_rc\""
  ];
  return { type: LINUX_SHELL, script: lines.join('\n') };
}

function shServices(nonce) {
  const lines = [
    'mc_rc=0',
    'if ! command -v systemctl >/dev/null 2>&1; then',
    "  printf 'mobilecompanion: systemctl not found\\n' >&2",
    '  mc_rc=127',
    'else',
    '  if ! command -v awk >/dev/null 2>&1; then printf \'mobilecompanion: awk not found\\n\' >&2; mc_rc=127; fi',
    '  if ! command -v head >/dev/null 2>&1; then printf \'mobilecompanion: head not found\\n\' >&2; mc_rc=127; fi',
    '  mc_units=$(systemctl list-units --type=service --all --no-legend --no-pager --plain 2>/dev/null); mc_units_rc=$?',
    '  if [ $mc_units_rc -ne 0 ]; then mc_rc=$mc_units_rc; fi',
    '  mc_count=$(printf \'%s\\n\' "$mc_units" | awk \'NF>0 {c++} END {print c+0}\')',
    '  mc_trunc=0; if [ "$mc_count" -gt 200 ]; then mc_trunc=1; fi',
    '  mc_names=$(printf \'%s\\n\' "$mc_units" | awk \'NF>0 {print $1}\' | head -n 200)',
    '  if [ -n "$mc_names" ]; then',
    '    mc_show=$(systemctl show -p Id -p Description -p ActiveState -p SubState -p UnitFileState $mc_names 2>/dev/null); mc_show_rc=$?',
    '    printf \'%s\\n\' "$mc_show"',
    '    if [ $mc_show_rc -ne 0 ]; then mc_rc=$mc_show_rc; fi',
    '  fi',
    '  printf \'\\nMC_TRUNCATED\\t%s\\n\' "$mc_trunc"',
    'fi',
    "printf '__MC_DONE_" + nonce + "__%s\\n' \"$mc_rc\""
  ];
  return { type: LINUX_SHELL, script: lines.join('\n') };
}

function shProcesses(nonce) {
  const lines = [
    'mc_rc=0',
    'if ! command -v ps >/dev/null 2>&1; then',
    "  printf 'mobilecompanion: ps not found\\n' >&2",
    '  mc_rc=127',
    'else',
    '  if ! command -v head >/dev/null 2>&1; then printf \'mobilecompanion: head not found\\n\' >&2; mc_rc=127; fi',
    '  mc_ps=$(ps -eo pid=,comm=,pcpu=,rss= 2>/dev/null); mc_ps_rc=$?',
    '  if [ $mc_ps_rc -ne 0 ]; then mc_rc=$mc_ps_rc; else',
    '    printf \'%s\\n\' "$mc_ps" | head -n 201',
    '  fi',
    'fi',
    "printf '__MC_DONE_" + nonce + "__%s\\n' \"$mc_rc\""
  ];
  return { type: LINUX_SHELL, script: lines.join('\n') };
}

function shMutation(kind, name, nonce) {
  const literal = "'" + String(name).replace(/'/g, "'\\''") + "'";
  const lines = [
    'mc_rc=0',
    'if ! command -v systemctl >/dev/null 2>&1; then',
    "  printf 'mobilecompanion: systemctl not found\\n' >&2",
    '  mc_rc=127',
    'else',
    '  mc_name=' + literal,
    '  mc_load=$(systemctl show -p LoadState --value "$mc_name" 2>/dev/null); mc_load_rc=$?',
    '  if [ $mc_load_rc -ne 0 ]; then mc_rc=1; fi',
    '  case "$mc_load" in',
    '    ""|not-found|LoadState=not-found|*not-found*)',
    "      printf 'mobilecompanion: service not found\\n' >&2",
    '      mc_rc=1',
    '      ;;',
    '  esac',
    '  mc_changed=false',
    '  mc_active=0',
    '  if [ $mc_rc -eq 0 ]; then',
    '    if systemctl is-active --quiet "$mc_name"; then mc_active=1; fi',
    "    if [ '" + kind + "' = 'service-start' ]; then",
    '      if [ $mc_active -eq 0 ]; then if systemctl start "$mc_name"; then mc_changed=true; else mc_rc=$?; fi; fi',
    '      if [ $mc_rc -eq 0 ]; then if ! systemctl is-active --quiet "$mc_name"; then mc_rc=1; fi; fi',
    "    elif [ '" + kind + "' = 'service-stop' ]; then",
    '      if [ $mc_active -eq 1 ]; then if systemctl stop "$mc_name"; then mc_changed=true; else mc_rc=$?; fi; fi',
    '      if [ $mc_rc -eq 0 ]; then if systemctl is-active --quiet "$mc_name"; then mc_rc=1; fi; fi',
    '    else',
    '      if systemctl restart "$mc_name"; then mc_changed=true; else mc_rc=$?; fi',
    '      if [ $mc_rc -eq 0 ]; then if ! systemctl is-active --quiet "$mc_name"; then mc_rc=1; fi; fi',
    '    fi',
    '  fi',
    '  if [ $mc_rc -eq 0 ]; then',
    '    mc_state=$(systemctl is-active "$mc_name" 2>/dev/null)',
    "    printf '{\"os\":\"linux\",\"operation\":\"" + kind + "\",\"name\":\"%s\",\"changed\":%s,\"state\":\"%s\"}\\n' \"$mc_name\" \"$mc_changed\" \"$mc_state\"",
    '  fi',
    'fi',
    "printf '__MC_DONE_" + nonce + "__%s\\n' \"$mc_rc\""
  ];
  return { type: LINUX_SHELL, script: lines.join('\n') };
}

function buildCommand(options) {
  const templateId = options.templateId;
  const platform = options.platform;
  const nonce = options.nonce;
  const parameters = options.parameters || {};
  if (typeof nonce !== 'string' || !/^[A-Za-z0-9]+$/.test(nonce)) throw new Error('invalid nonce');
  if (platform === 'windows') {
    if (templateId === 'diagnostics') return windowsDiagnostics(nonce);
    if (templateId === 'services') return windowsServices(nonce);
    if (templateId === 'processes') return windowsProcesses(nonce);
    if (MUTATION_TEMPLATES.has(templateId)) return windowsMutation(templateId, parameters.name, nonce);
  } else if (platform === 'linux') {
    if (templateId === 'diagnostics') return shDiagnostics(nonce);
    if (templateId === 'services') return shServices(nonce);
    if (templateId === 'processes') return shProcesses(nonce);
    if (MUTATION_TEMPLATES.has(templateId)) return shMutation(templateId, parameters.name, nonce);
  }
  throw new Error('unsupported template or platform');
}

function findMarker(output, nonce) {
  if (typeof output !== 'string') return { found: false };
  const token = '__MC_DONE_' + nonce + '__';
  const index = output.lastIndexOf(token);
  if (index < 0) return { found: false };
  const tail = output.slice(index + token.length);
  const match = /^([0-9]{1,3})\s*$/.exec(tail);
  if (match === null) return { found: false };
  return {
    found: true,
    exitCode: Number(match[1]),
    payload: output.slice(0, index)
  };
}

function parseResult(options) {
  const templateId = options.templateId;
  const platform = options.platform;
  const parameters = options.parameters || {};
  const payload = options.payload;
  if (templateId === 'diagnostics') {
    const parsed = platform === 'linux' ? schema.parseLinuxDiagnostics(payload) : schema.parseJson(payload);
    return schema.boundResultSize(schema.sanitizeDiagnostics(parsed, platform), MAX_OUTPUT_BYTES);
  }
  if (templateId === 'services') {
    const parsed = platform === 'linux' ? schema.parseLinuxServices(payload) : schema.parseJson(payload);
    return schema.boundResultSize(schema.sanitizeServices(parsed, platform), MAX_OUTPUT_BYTES);
  }
  if (templateId === 'processes') {
    const parsed = platform === 'linux' ? schema.parseLinuxProcesses(payload) : schema.parseJson(payload);
    return schema.boundResultSize(schema.sanitizeProcesses(parsed, platform), MAX_OUTPUT_BYTES);
  }
  if (MUTATION_TEMPLATES.has(templateId)) {
    return schema.sanitizeMutation(schema.parseJson(payload), platform, templateId, parameters.name);
  }
  throw new Error('unsupported template');
}

module.exports = {
  WINDOWS_POWERSHELL,
  LINUX_SHELL,
  READ_TEMPLATES,
  MUTATION_TEMPLATES,
  ALL_TEMPLATES,
  WINDOWS_SERVICE_NAME,
  LINUX_SERVICE_NAME,
  isReadTemplate,
  isMutationTemplate,
  isValidTemplate,
  validateServiceName,
  buildCommand,
  findMarker,
  parseResult,
  shEscape
};
