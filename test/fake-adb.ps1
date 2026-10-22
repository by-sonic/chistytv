# Фейковый adb для тестов: имитирует телевизор, состояние хранится в JSON ($env:FAKE_ADB_STATE).
$argv = @($args)
$statePath = $env:FAKE_ADB_STATE
$state = Get-Content $statePath -Raw -Encoding UTF8 | ConvertFrom-Json
Add-Content -Path "$statePath.log" -Value ($argv -join ' ') -Encoding UTF8

function Save { $state | ConvertTo-Json -Depth 5 | Set-Content $statePath -Encoding UTF8 }

if ($argv[0] -eq '-s') { $argv = $argv[2..($argv.Count - 1)] }

switch ($argv[0]) {
  'start-server' { return }
  'connect' {
    if ($state.auth_pending) { "failed to authenticate to $($argv[1])"; return }
    "connected to $($argv[1])"; return
  }
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
    if ($cmd -match '^cmd package resolve-activity .*-p (\S+)') {
      $p = $Matches[1]
      if ($state.installed -contains $p) { "priority=0 preferredOrder=0`n$p/$p.MainActivity" } else { 'No activity found' }
      return
    }
    if ($cmd -match '^cmd package set-home-activity (\S+)') { $state.home = $Matches[1]; Save; 'Success'; return }
    if ($cmd -match '^input keyevent') { return }
    if ($cmd -eq 'dumpsys activity activities') {
      $top = 'com.google.android.apps.tv.launcherx/.StockActivity'
      if ($state.home_works -and $state.home) { $top = $state.home }
      if ($state.home -and @($state.disabled) -contains 'com.test.stock') { $top = $state.home }
      "  topResumedActivity=ActivityRecord{1a2b u0 $top t12}"
      return
    }
    if ($cmd -match '^dumpsys wifi') { $state.wifi_owner; return }
    if ($cmd -match '^am start ') { $state.started = @($state.started) + $cmd; Save; 'Starting: Intent { act=android.intent.action.VIEW }'; return }
    if ($cmd -match '^settings list') { 'a=1'; return }
    if ($cmd -match '^settings get global private_dns_mode') { $state.dns_mode; return }
    if ($cmd -match '^settings get global private_dns_specifier') { $state.dns_spec; return }
    if ($cmd -match '^settings put global private_dns_mode (\S+)') { $state.dns_mode = $Matches[1]; Save; return }
    if ($cmd -match '^settings put global private_dns_specifier (\S+)') { $state.dns_spec = $Matches[1]; Save; return }
    if ($cmd -match '^settings delete global private_dns_specifier') { $state.dns_spec = 'null'; Save; return }
    if ($cmd -match '^ping ') {
      # dot_blocked: провайдер режет порт 853 — с частным DNS имена перестают находиться
      if ($state.offline -or ($state.dot_blocked -and $state.dns_mode -eq 'hostname')) { 'ping: unknown host connectivitycheck.gstatic.com'; return }
      "PING connectivitycheck.gstatic.com (142.250.74.35) 56(84) bytes of data.`n64 bytes from 142.250.74.35: icmp_seq=1 ttl=117 time=12 ms"
      return
    }
    if ($cmd -match '^rm ') { return }
    "unknown shell command: $cmd"; return
  }
  default { "fake adb: unknown $($argv -join ' ')" }
}
