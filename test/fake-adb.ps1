# Фейковый adb для тестов: имитирует телевизор, состояние хранится в JSON ($env:FAKE_ADB_STATE).
$argv = @($args)
$statePath = $env:FAKE_ADB_STATE
$state = Get-Content $statePath -Raw -Encoding UTF8 | ConvertFrom-Json
Add-Content -Path "$statePath.log" -Value ($argv -join ' ') -Encoding UTF8

function Save { $state | ConvertTo-Json -Depth 5 | Set-Content $statePath -Encoding UTF8 }

if ($argv[0] -eq '-s') { $argv = $argv[2..($argv.Count - 1)] }

switch ($argv[0]) {
  'start-server' { return }
  'connect' { "connected to $($argv[1])"; return }
  'disconnect' { "disconnected"; return }
  'get-state' { 'device'; return }
  'push' { "$($argv[1]): 1 file pushed, 0 skipped."; return }
  'shell' {
    $cmd = ($argv[1..($argv.Count - 1)]) -join ' '
    if ($cmd -match '^getprop (\S+)$') { $state.props.($Matches[1]); return }
    if ($cmd -eq 'getprop') { $state.props.PSObject.Properties | ForEach-Object { "[$($_.Name)]: [$($_.Value)]" }; return }
    if ($cmd -eq 'pm list packages') { $state.installed | ForEach-Object { "package:$_" }; return }
    if ($cmd -eq 'pm list packages -d') { $state.disabled | ForEach-Object { "package:$_" }; return }
    if ($cmd -match '^pm disable-user --user 0 (\S+)$') {
      $p = $Matches[1]
      if ($state.refuse) { "java.lang.SecurityException: Shell cannot change component state for $p"; return }
      if ($state.installed -notcontains $p) { "Error: Unknown package: $p"; return }
      $state.disabled = @($state.disabled) + $p | Select-Object -Unique
      Save
      "Package $p new state: disabled-user"; return
    }
    if ($cmd -match '^pm enable (\S+)$') {
      $p = $Matches[1]
      $state.disabled = @($state.disabled | Where-Object { $_ -ne $p })
      Save
      "Package $p new state: enabled"; return
    }
    if ($cmd -match '^pm install -r ') {
      $state.installed = @($state.installed) + 'com.installed.by.test' | Select-Object -Unique
      Save
      'Success'; return
    }
    if ($cmd -match '^am start ') { $state.started = @($state.started) + $cmd; Save; 'Starting: Intent { act=android.intent.action.VIEW }'; return }
    if ($cmd -match '^settings list') { 'a=1'; return }
    if ($cmd -match '^settings get global private_dns_mode') { $state.dns_mode; return }
    if ($cmd -match '^settings get global private_dns_specifier') { $state.dns_spec; return }
    if ($cmd -match '^settings put global private_dns_mode (\S+)') { $state.dns_mode = $Matches[1]; Save; return }
    if ($cmd -match '^settings put global private_dns_specifier (\S+)') { $state.dns_spec = $Matches[1]; Save; return }
    if ($cmd -match '^rm ') { return }
    "unknown shell command: $cmd"; return
  }
  default { "fake adb: unknown $($argv -join ' ')" }
}
