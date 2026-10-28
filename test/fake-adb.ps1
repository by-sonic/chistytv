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
      if ($state.installed -notcontains $p) { "Error: Unknown package: $p"; return }
      if (@($state.refuse_enable) -contains $p) { "java.lang.SecurityException: Shell cannot change component state for $p"; return }
      $state.disabled = @($state.disabled | Where-Object { $_ -ne $p })
      Save
      "Package $p new state: enabled"; return
    }
    if ($cmd -match '^cmd package install-existing (\S+)$') {
      $p = $Matches[1]
      if ($state.installed -notcontains $p) { "Failure [Package $p doesn't exist]"; return }
      # Как на настоящем ТВ: «установлен» отвечает, даже если пакет так и остался отключённым
      "Package $p installed for user: 0"; return
    }
    # Память: free_mb свободно, cache_mb освобождает pm trim-caches, need_mb нужно для установки
    if ($cmd -eq "stat -f -c '%a %S' /data") { if ($null -ne $state.free_mb) { "$([long]$state.free_mb * 256) 4096" }; return }
    if ($cmd -match '^pm trim-caches ') {
      $state | Add-Member -Force free_mb ([long]$state.free_mb + [long]$state.cache_mb)
      $state | Add-Member -Force cache_mb 0
      $state | Add-Member -Force trimmed $true
      Save; return
    }
    if ($cmd -match '^pm install -r ' -and $null -ne $state.need_mb -and [long]$state.free_mb -lt [long]$state.need_mb) {
      "`nException occurred while executing:`nandroid.os.ParcelableException: java.io.IOException: Requested internal only, but not enough space`n`tat android.util.ExceptionUtils.wrap(ExceptionUtils.java:34)`n`tat com.android.server.pm.PackageInstallerService.createSession(PackageInstallerService.java:536)"
      return
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
    if ($cmd -match '^ping -c 1 -W 2 (\S+) 2>&1$') {
      $name = $Matches[1]
      $state | Add-Member -Force pinged (@($state.pinged) + $name)
      # dot_blocked: провайдер режет порт 853 — с частным DNS имена перестают находиться.
      # Но то, что ТВ уже искал, приходит из кэша — так ведёт себя настоящий Android 9
      $broken = $state.dns_mode -eq 'hostname' -and ($state.dot_blocked -or ($state.dot_blocked_spec -and $state.dns_spec -eq $state.dot_blocked_spec))
      if ($state.offline -or ($broken -and @($state.dns_cache) -notcontains $name)) { Save; "ping: unknown host $name"; return }
      $state | Add-Member -Force dns_cache (@($state.dns_cache) + $name | Select-Object -Unique)
      Save
      "PING $name (142.250.74.35) 56(84) bytes of data.`n64 bytes from 142.250.74.35: icmp_seq=1 ttl=117 time=12 ms"
      return
    }
    if ($cmd -eq 'dumpsys connectivity') {
      # Android 10+: при сломанном частном DNS сеть теряет VALIDATED (Android 9 не успевает — stale_validated)
      $broken = $state.dns_mode -eq 'hostname' -and ($state.dot_blocked -or ($state.dot_blocked_spec -and $state.dns_spec -eq $state.dot_blocked_spec))
      $caps = if ($state.offline -or ($broken -and -not $state.stale_validated)) { 'INTERNET&NOT_RESTRICTED' } else { 'INTERNET&NOT_RESTRICTED&VALIDATED' }
      "Active default network: 100`n  NetworkAgentInfo{network{100}  nc{[ Transports: WIFI Capabilities: $caps Unwanted: ]} }"
      return
    }
    if ($cmd -match "^mkdir -p \S+ && echo '([^']*)' > \S+dns-before\.txt$") { $state | Add-Member -Force dns_before $Matches[1]; Save; return }
    if ($cmd -match '^cat \S+dns-before\.txt 2>/dev/null$') { "$($state.dns_before)"; return }
    if ($cmd -match '^rm -f \S+dns-before\.txt$') { $state | Add-Member -Force dns_before ''; Save; return }
    if ($cmd -match '^settings delete global private_dns_mode') { $state.dns_mode = 'null'; Save; return }
    if ($cmd -match '^rm ') { return }
    # Журнал ЧистогоТВ на самом телевизоре
    if ($cmd -match '^mkdir -p \S+ && echo (\S+) >> \S+$') { $state.tv_journal = "$($state.tv_journal)$($Matches[1])`n"; Save; return }
    if ($cmd -match '^cat \S+disabled-by-chistytv\.txt 2>/dev/null$') { "$($state.tv_journal)".Trim(); return }
    if ($cmd -match '^mv \S+disabled-by-chistytv\.txt \S+ 2>/dev/null$') { $state.tv_journal = ''; Save; return }
    "unknown shell command: $cmd"; return
  }
  default { "fake adb: unknown $($argv -join ' ')" }
}
