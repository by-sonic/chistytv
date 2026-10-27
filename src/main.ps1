<#
  Основной код ЧистогоТВ. Запускается через chistytv.ps1 в корне: тот читает этот файл как UTF-8,
  поэтому работает и «irm | iex», и локальный запуск в Windows PowerShell 5.1.

.SYNOPSIS
  ЧистыйТВ — убирает рекламу и мусор с Android TV / Яндекс ТВ / Google TV по Wi-Fi,
  ставит YouTube без тормозов. Всё обратимо: пакеты отключаются, а не удаляются.

.EXAMPLE
  irm https://raw.githubusercontent.com/by-sonic/chistytv/main/chistytv.ps1 | iex

.EXAMPLE
  .\chistytv.ps1 -TvIp 192.168.1.50 -Action clean -Yes
#>
[CmdletBinding()]
param(
  [string]$TvIp,
  [ValidateSet('menu', 'clean', 'restore', 'youtube', 'launcher', 'dns', 'info')]
  [string]$Action = 'menu',
  [string]$Subscription,
  [switch]$Yes,
  [string]$AdbPath,
  [string]$DataPath,
  [string]$WorkDir
)

$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
try { [Console]::OutputEncoding = [Text.Encoding]::UTF8 } catch { }
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

$Script:Version = '0.2.1'
$Script:Repo = 'by-sonic/chistytv'
$Script:RawBase = "https://raw.githubusercontent.com/$($Script:Repo)/main"
$Script:Vpn = @{
  Name = 'RoseVPN'
  Url  = 'https://t.me/rosevpnru_bot'
}
$Script:Author = '@sonic_h1'
if (-not $WorkDir) { $WorkDir = Join-Path ([Environment]::GetFolderPath('MyDocuments')) 'ChistyTV' }
$Script:Serial = $null
$Script:Adb = $null
$Script:Db = $null
$Script:Info = $null
$Script:BackupDir = $null

# ─── Вывод ────────────────────────────────────────────────────────────────

function Say([string]$Text, [string]$Color = 'Gray') { Write-Host $Text -ForegroundColor $Color }
function Title([string]$Text) { Write-Host ''; Write-Host "━━ $Text" -ForegroundColor Cyan }
function Ok([string]$Text) { Write-Host "  ✔ $Text" -ForegroundColor Green }
function Warn([string]$Text) { Write-Host "  ! $Text" -ForegroundColor Yellow }
function Fail([string]$Text) { Write-Host "  ✖ $Text" -ForegroundColor Red }

function Ask([string]$Question, [string]$Default = '') {
  if ($Yes) { return $Default }
  $suffix = ''
  if ($Default) { $suffix = " [$Default]" }
  $answer = Read-Host "  $Question$suffix"
  if (-not $answer) { return $Default }
  return $answer.Trim()
}

function Confirm([string]$Question, [bool]$Default = $true) {
  if ($Yes) { return $Default }
  $hint = 'Д/н'
  if (-not $Default) { $hint = 'д/Н' }
  $answer = Read-Host "  $Question ($hint)"
  if (-not $answer) { return $Default }
  return $answer.Trim().ToLower() -in @('д', 'да', 'y', 'yes', 'l')
}

# ─── ADB ──────────────────────────────────────────────────────────────────

# У части провайдеров dl.google.com отдаёт на архив 404. Тогда берём закреплённую версию с запасных хостов
# и сверяем SHA-256: подменённый архив не пройдёт. Хэш — от архива, чья SHA-1 совпала с репозиторием Google.
$Script:AdbOfficial = 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip'
$Script:AdbPinned = @{
  File   = 'platform-tools_r37.0.1-win.zip'
  Sha256 = '45F4D63113E895EBDE0C90F194099A4676B6AC653BD28D54314A9E022BBC1A99'
  Hosts  = @('https://redirector.gvt1.com/edgedl/android/repository/', 'https://mirrors.cloud.tencent.com/AndroidSDK/')
}

function Save-PlatformTools([string]$Zip) {
  try {
    Invoke-WebRequest -UseBasicParsing -Uri $Script:AdbOfficial -OutFile $Zip
    return
  } catch {
    Warn "dl.google.com не отдал ADB ($("$($_.Exception.Message)".Split("`n")[0])), пробую запасные источники."
  }
  foreach ($base in $Script:AdbPinned.Hosts) {
    $url = $base + $Script:AdbPinned.File
    $hostName = ([uri]$url).Host
    try {
      Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $Zip
      if ((Get-FileHash $Zip -Algorithm SHA256).Hash -eq $Script:AdbPinned.Sha256) { Ok "ADB скачан с $hostName, контрольная сумма совпала"; return }
      Warn "$hostName`: контрольная сумма не совпала, файл отброшен."
    } catch {
      Warn "$hostName`: $("$($_.Exception.Message)".Split("`n")[0])"
    }
    Remove-Item $Zip -Force -ErrorAction SilentlyContinue
  }
  throw ("Не удалось скачать ADB. Скачайте platform-tools для Windows с developer.android.com/tools/releases/platform-tools, " +
    "распакуйте в $(Split-Path $Zip) (чтобы получилось ...\ChistyTV\platform-tools\adb.exe) и запустите ЧистыйТВ снова. " +
    'Или включите VPN на компьютере только на время скачивания, а перед подключением к ТВ выключите.')
}

function Get-AdbPath {
  if ($AdbPath) { return $AdbPath }
  $cmd = Get-Command adb -ErrorAction SilentlyContinue
  if ($cmd) { return $cmd.Source }
  $toolsDir = Join-Path $env:LOCALAPPDATA 'ChistyTV'
  $local = Join-Path $toolsDir 'platform-tools\adb.exe'
  if (Test-Path $local) { return $local }

  Say '  Скачиваю ADB (Google platform-tools, ~7 МБ)…'
  New-Item -ItemType Directory -Force -Path $toolsDir | Out-Null
  $zip = Join-Path $toolsDir 'platform-tools.zip'
  Save-PlatformTools $zip
  Expand-Archive -Path $zip -DestinationPath $toolsDir -Force
  Remove-Item $zip -Force
  if (-not (Test-Path $local)) { throw 'Не удалось распаковать ADB.' }
  Ok 'ADB готов'
  return $local
}

function Invoke-Adb {
  param([Parameter(ValueFromRemainingArguments = $true)][string[]]$AdbArgs)
  $all = @()
  if ($Script:Serial) { $all += @('-s', $Script:Serial) }
  $all += $AdbArgs
  $prev = $ErrorActionPreference
  $ErrorActionPreference = 'Continue'
  try {
    # Строки stderr приходят как ErrorRecord: пустая превращается в «System.Management.Automation.RemoteException»
    $out = & $Script:Adb @all 2>&1 | ForEach-Object { if ($_ -is [System.Management.Automation.ErrorRecord]) { $_.Exception.Message } else { "$_" } }
  } finally {
    $ErrorActionPreference = $prev
  }
  return (($out | Where-Object { $_ -ne $null }) -join "`n").Trim()
}

function Sh([string]$Command) { return Invoke-Adb shell $Command }

# ─── Поиск и подключение ─────────────────────────────────────────────────

# Адаптеры VPN и виртуальных машин: телевизора там нет, а скан по ним только тратит время.
$Script:VirtualNic = '(?i)vpn|amnezia|wireguard|wintun|tap-|tun|openvpn|outline|hiddify|happ|v2ray|clash|hyper-v|vethernet|wsl|virtualbox|vmware|docker|zerotier|tailscale|radmin|hamachi'

function Test-VpnRoute([string]$Ip) {
  # Если Windows ведёт трафик к ТВ через VPN-адаптер, телевизор будет недоступен.
  try {
    $route = Find-NetRoute -RemoteIPAddress $Ip -ErrorAction Stop | Where-Object { $_.InterfaceAlias } | Select-Object -First 1
    if ($route -and $route.InterfaceAlias -match $Script:VirtualNic) {
      Warn "Трафик к $Ip идёт через «$($route.InterfaceAlias)» — VPN на компьютере закрывает доступ к домашней сети."
      Say '    Выключите VPN на время настройки или разрешите в нём доступ к локальной сети.' 'DarkGray'
      return $true
    }
  } catch { }
  return $false
}

function Get-LocalPrefixes {
  $prefixes = @()
  foreach ($nic in [Net.NetworkInformation.NetworkInterface]::GetAllNetworkInterfaces()) {
    if ($nic.OperationalStatus -ne 'Up') { continue }
    if ($nic.NetworkInterfaceType -in @('Loopback', 'Tunnel', 'Ppp')) { continue }
    if ("$($nic.Name) $($nic.Description)" -match $Script:VirtualNic) { continue }
    foreach ($addr in $nic.GetIPProperties().UnicastAddresses) {
      if ($addr.Address.AddressFamily -ne 'InterNetwork') { continue }
      $ip = $addr.Address.ToString()
      if ($ip -like '169.254.*') { continue }
      if ($ip -notmatch '^(10|172\.(1[6-9]|2\d|3[01])|192\.168)\.') { continue }
      $prefixes += ($ip -replace '\.\d+$', '')
    }
  }
  return $prefixes | Select-Object -Unique
}

function Find-Tvs {
  $found = @()
  foreach ($prefix in Get-LocalPrefixes) {
    Say "  Ищу телевизор в сети $prefix.0/24 …" 'DarkGray'
    $probes = @()
    foreach ($i in 1..254) {
      $client = New-Object Net.Sockets.TcpClient
      $probes += [pscustomobject]@{ Ip = "$prefix.$i"; Client = $client; Task = $client.ConnectAsync("$prefix.$i", 5555) }
    }
    Start-Sleep -Milliseconds 2500
    foreach ($p in $probes) {
      if ($p.Task.Status -eq 'RanToCompletion' -and $p.Client.Connected) { $found += $p.Ip }
      $p.Client.Close()
    }
  }
  return $found
}

function Connect-Tv([string]$Ip) {
  $target = $Ip
  if ($target -notmatch ':\d+$') { $target = "$target`:5555" }
  $Script:Serial = $null
  $out = Invoke-Adb connect $target
  # «failed to authenticate» — телевизор показал окно «Разрешить отладку?» и ждёт ответа.
  if ($out -notmatch 'connected to|failed to authenticate|unauthorized') {
    Fail "Не подключился к $target`: $out"
    Test-VpnRoute ($target -replace ':\d+$', '') | Out-Null
    Say '    Проверьте: ТВ включён, в той же Wi-Fi сети, в «Для разработчиков» включена отладка (USB и по сети).' 'DarkGray'
    Say '    На Android 11+ без пункта «Отладка по сети» — выберите в меню «Подключение по коду» (беспроводная отладка).' 'DarkGray'
    return $false
  }
  $Script:Serial = $target
  Say '  Если на телевизоре появилось окно «Разрешить отладку?» — отметьте «Всегда» и нажмите «ОК».' 'Yellow'
  for ($i = 0; $i -lt 60; $i++) {
    $state = Invoke-Adb get-state
    if ($state -eq 'device') { Ok "Подключено: $target"; return $true }
    Start-Sleep -Seconds 1
  }
  Fail 'Телевизор не разрешил подключение за минуту. Запустите ещё раз и подтвердите запрос на экране ТВ.'
  return $false
}

function Pair-Tv {
  Say '  На ТВ: «Для разработчиков» → «Беспроводная отладка» → «Подключить по коду».' 'Yellow'
  $pairAddr = Ask 'IP-адрес и порт из окна с кодом (например 192.168.1.50:37123)'
  $code = Ask 'Шестизначный код'
  $Script:Serial = $null
  $out = Invoke-Adb pair $pairAddr $code
  if ($out -notmatch 'Successfully paired') { Fail "Не удалось: $out"; return $false }
  Ok 'Пара создана'
  $connAddr = Ask 'Теперь IP-адрес и порт с главного экрана «Беспроводной отладки» (там другой порт)'
  return (Connect-Tv $connAddr)
}

function Select-Tv {
  if ($TvIp) { return (Connect-Tv $TvIp) }
  $tvs = @(Find-Tvs)
  if ($tvs.Count -eq 1) {
    Ok "Нашёл устройство с отладкой по сети: $($tvs[0])"
    return (Connect-Tv $tvs[0])
  }
  if ($tvs.Count -gt 1) {
    Say '  Нашёл несколько устройств:'
    for ($i = 0; $i -lt $tvs.Count; $i++) { Say "    $($i + 1). $($tvs[$i])" }
    $n = [int](Ask 'Номер телевизора' '1')
    return (Connect-Tv $tvs[$n - 1])
  }
  Warn 'Автоматически не нашёл. IP телевизора: Настройки → Сеть → ваша сеть.'
  $ip = Ask 'IP телевизора (или «код» для беспроводной отладки Android 11+)'
  if ($ip -in @('код', 'kod', 'pair')) { return (Pair-Tv) }
  return (Connect-Tv $ip)
}

# ─── Сведения о ТВ и база пакетов ────────────────────────────────────────

function Get-TvInfo {
  $p = @{}
  foreach ($key in 'ro.product.manufacturer', 'ro.product.brand', 'ro.product.model', 'ro.build.version.release',
    'ro.build.version.sdk', 'ro.product.cpu.abi', 'ro.product.cpu.abilist') {
    $p[$key] = Sh "getprop $key"
  }
  $installed = (Sh 'pm list packages') -split "`n" | ForEach-Object { $_ -replace '^package:', '' } | Where-Object { $_ } | ForEach-Object { $_.Trim() }
  $disabled = (Sh 'pm list packages -d') -split "`n" | ForEach-Object { $_ -replace '^package:', '' } | Where-Object { $_ } | ForEach-Object { $_.Trim() }
  return [pscustomobject]@{
    Manufacturer = $p['ro.product.manufacturer']
    Brand        = $p['ro.product.brand']
    Model        = $p['ro.product.model']
    Android      = $p['ro.build.version.release']
    Sdk          = [int]("0" + $p['ro.build.version.sdk'])
    Abi          = $p['ro.product.cpu.abi']
    AbiList      = $p['ro.product.cpu.abilist']
    Installed    = @($installed)
    Disabled     = @($disabled)
  }
}

function Load-Db {
  $path = $DataPath
  if (-not $path -and $PSScriptRoot) {
    $local = Join-Path $PSScriptRoot 'data\packages.json'
    if (Test-Path $local) { $path = $local }
  }
  if ($path) {
    $json = [IO.File]::ReadAllText((Resolve-Path $path), [Text.Encoding]::UTF8)
  } else {
    $json = (Invoke-WebRequest -UseBasicParsing -Uri "$($Script:RawBase)/data/packages.json").Content
    if ($json -is [byte[]]) { $json = [Text.Encoding]::UTF8.GetString($json) }
  }
  return ($json | ConvertFrom-Json)
}

function Get-Platforms {
  $hits = @()
  foreach ($pl in $Script:Db.platforms) {
    $match = $false
    foreach ($pkg in @($pl.detect_packages)) { if ($Script:Info.Installed -contains $pkg) { $match = $true } }
    if ($pl.detect_manufacturer -and ($Script:Info.Manufacturer -match $pl.detect_manufacturer -or $Script:Info.Brand -match $pl.detect_manufacturer)) { $match = $true }
    if ($match) { $hits += $pl }
  }
  return $hits
}

# Защита от ошибок в базе: такие пакеты не трогаем никогда.
$Script:ProtectPatterns = @(
  '^android$', '^com\.android\.systemui$', '^com\.android\.settings$', '^com\.android\.tv\.settings$',
  'packageinstaller', 'inputmethod', 'keyboard', 'leanbackime', '\.ime$', 'tvinput', 'tvcenter', 'tv\.service',
  'bluetooth', 'remote', 'factory', 'fac$', '\.hkcfac', '^com\.android\.vending$', '^com\.google\.android\.gms$',
  '^com\.google\.android\.gsf$', 'setupwraith', 'setupwizard', 'provision', 'networkstack', 'wifi', 'webview'
)

function Test-Protected([string]$Pkg) {
  foreach ($pat in $Script:ProtectPatterns) { if ($Pkg -match $pat) { return $true } }
  return $false
}

function Get-Plan {
  $platformIds = @('any') + @(Get-Platforms | ForEach-Object { $_.id })
  $plan = @()
  foreach ($e in $Script:Db.packages) {
    if ($Script:Info.Installed -notcontains $e.id) { continue }
    if ($platformIds -notcontains $e.platform) { continue }
    if ($e.action -eq 'keep') { continue }
    if ($Script:Info.Disabled -contains $e.id) { continue }
    if ((Test-Protected $e.id) -and -not $e.override_protect) { continue }
    $plan += $e
  }
  return $plan
}

# ─── Бэкап и журнал отката ───────────────────────────────────────────────

function Get-TvKey {
  $name = "$($Script:Info.Manufacturer)-$($Script:Info.Model)" -replace '[^\w\-]+', '_'
  return $name.Trim('_')
}

function New-Backup {
  $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
  $dir = Join-Path $WorkDir "$(Get-TvKey)\$stamp"
  New-Item -ItemType Directory -Force -Path $dir | Out-Null
  [IO.File]::WriteAllText((Join-Path $dir 'packages.txt'), ($Script:Info.Installed -join "`n"))
  [IO.File]::WriteAllText((Join-Path $dir 'disabled-before.txt'), ($Script:Info.Disabled -join "`n"))
  [IO.File]::WriteAllText((Join-Path $dir 'getprop.txt'), (Sh 'getprop'))
  foreach ($ns in 'global', 'secure', 'system') {
    [IO.File]::WriteAllText((Join-Path $dir "settings-$ns.txt"), (Sh "settings list $ns"))
  }
  $Script:BackupDir = $dir
  Ok "Бэкап: $dir"
}

function Get-RestoreLog { return (Join-Path $WorkDir "$(Get-TvKey)\disabled-by-chistytv.txt") }

# Журнал дублируется на сам телевизор: откатить можно и с другого компьютера, и из приложения ЧистыйТВ на телефоне.
$Script:TvJournal = '/data/local/tmp/chistytv/disabled-by-chistytv.txt'
$Script:SafePkg = '^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)+$'

function Add-RestoreLog([string]$Pkg) {
  $log = Get-RestoreLog
  New-Item -ItemType Directory -Force -Path (Split-Path $log) | Out-Null
  Add-Content -Path $log -Value $Pkg -Encoding UTF8
  if ($Pkg -cmatch $Script:SafePkg) { Sh "mkdir -p /data/local/tmp/chistytv && echo $Pkg >> $($Script:TvJournal)" | Out-Null }
}

function Get-RestorePackages {
  $local = @()
  $log = Get-RestoreLog
  if (Test-Path $log) { $local = @(Get-Content $log -Encoding UTF8) }
  $remote = @((Sh "cat $($Script:TvJournal) 2>/dev/null") -split "`n")
  return @(($local + $remote) | ForEach-Object { "$_".Trim() } | Where-Object { $_ -cmatch $Script:SafePkg } | Select-Object -Unique)
}

function Disable-Packages($Entries) {
  $done = 0
  foreach ($e in $Entries) {
    # По одному пакету на вызов: в цепочке команд pm может молча остановиться после первой.
    $out = Sh "pm disable-user --user 0 $($e.id)"
    if ($out -match 'disabled-user|new state: disabled') {
      Add-RestoreLog $e.id
      Ok "$($e.title) ($($e.id))"
      $done++
    } else {
      Warn "$($e.id): $out"
    }
  }
  $now = (Sh 'pm list packages -d') -split "`n" | ForEach-Object { ($_ -replace '^package:', '').Trim() }
  $notApplied = @($Entries | Where-Object { $now -notcontains $_.id })
  if ($done -eq 0 -and $Entries.Count -gt 0) {
    Fail 'Прошивка не даёт отключать пакеты. Тогда остаётся блокировка рекламы через DNS (пункт меню «DNS»).'
  } elseif ($notApplied.Count) {
    Warn "Не применилось: $(($notApplied | ForEach-Object { $_.id }) -join ', ')"
  }
  return $done
}

# ─── Действия ────────────────────────────────────────────────────────────

function Show-Info {
  Title 'Телевизор'
  Say "  $($Script:Info.Manufacturer) $($Script:Info.Model) · Android $($Script:Info.Android) (SDK $($Script:Info.Sdk)) · $($Script:Info.Abi)"
  $pls = @(Get-Platforms)
  if ($pls.Count) { Say "  Платформа: $(($pls | ForEach-Object { $_.name }) -join ', ')" }
  else { Warn 'Платформа не распознана — применю только общие правила для Android TV.' }
  Say "  Приложений: $($Script:Info.Installed.Count), уже отключено: $($Script:Info.Disabled.Count)"
}

function Format-Entry($E) {
  if ($E.note) { return "$($E.title) — $($E.note)" }
  return $E.title
}

function Invoke-Clean {
  Title 'Чистка'
  $plan = @(Get-Plan)
  if (-not $plan.Count) {
    Ok 'Известного мусора не нашёл — или он уже отключён.'
    Say '  Для своей модели можно пройтись глубже с ИИ-ассистентом: PROMPT.md в репозитории.' 'DarkGray'
    return
  }
  New-Backup

  $recommended = @($plan | Where-Object { $_.action -eq 'disable' })
  $optional = @($plan | Where-Object { $_.action -eq 'ask' })

  if ($recommended.Count) {
    Say "  Реклама, слежка и фоновый мусор — отключить безопасно ($($recommended.Count)):" 'White'
    foreach ($e in $recommended) { Say "    • $(Format-Entry $e)" 'DarkGray' }
    if (Confirm 'Отключить их?') { Disable-Packages $recommended | Out-Null }
  }

  if ($optional.Count -and -not $Yes) {
    Say ''
    Say '  Приложения, которые вы могли использовать. Введите номера тех, что НЕ нужны (через запятую), или Enter — оставить всё:' 'White'
    for ($i = 0; $i -lt $optional.Count; $i++) {
      Say ("    {0,2}. {1}" -f ($i + 1), (Format-Entry $optional[$i])) 'DarkGray'
    }
    $answer = Ask 'Номера'
    if ($answer) {
      $picked = @()
      foreach ($n in ($answer -split '[,\s]+' | Where-Object { $_ -match '^\d+$' })) {
        $idx = [int]$n - 1
        if ($idx -ge 0 -and $idx -lt $optional.Count) { $picked += $optional[$idx] }
      }
      if ($picked.Count) { Disable-Packages $picked | Out-Null }
    }
  }
  Say ''
  Ok 'Готово. Перезагрузите телевизор и проверьте, что всё нужное работает.'
  Say '  Что-то сломалось — запустите ЧистыйТВ ещё раз и выберите «Вернуть как было».' 'DarkGray'
  Show-HomeHint
}

# Чистка убирает рекламные сервисы и слежку, но ряды «рекомендаций» на главном экране рисует сам штатный
# лаунчер. Его не отключаем — иначе ТВ останется без главного экрана. Без этой подсказки кажется, что ничего не произошло.
function Show-HomeHint {
  if ($Script:Info.Installed -contains $Script:LauncherPackage) { return }
  Say ''
  Say '  Ряды с рекламой на главном экране рисует сам штатный лаунчер — чистка его не трогает, иначе ТВ' 'Yellow'
  Say '  останется без главного экрана. Чтобы убрать и их, выберите «Новый главный экран без рекламных рядов».' 'Yellow'
}

function Invoke-Restore {
  Title 'Вернуть как было'
  $log = Get-RestoreLog
  $pkgs = @(Get-RestorePackages)
  if (-not $pkgs.Count) { Warn "Для этого телевизора нет журнала ЧистогоТВ — ни здесь ($log), ни на самом ТВ."; return }
  foreach ($pkg in $pkgs) {
    $out = Sh "pm enable $pkg"
    if ($out -notmatch 'enabled') { $out = Sh "cmd package install-existing $pkg" }
    if ($out -match 'enabled|installed') { Ok $pkg } else { Warn "$pkg`: $out" }
  }
  $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
  if (Test-Path $log) { Move-Item $log "$log.restored-$stamp" -Force }
  Sh "mv $($Script:TvJournal) $($Script:TvJournal).restored-$stamp 2>/dev/null" | Out-Null
  Ok 'Все отключённые ЧистымТВ приложения включены. Перезагрузите телевизор.'
}

function Get-GitHubAsset([string]$Repo, [string[]]$Patterns) {
  $rel = Invoke-RestMethod -UseBasicParsing -Uri "https://api.github.com/repos/$Repo/releases/latest" -Headers @{ 'User-Agent' = 'chistytv' }
  foreach ($pat in $Patterns) {
    $asset = $rel.assets | Where-Object { $_.name -match $pat } | Select-Object -First 1
    if ($asset) { return [pscustomobject]@{ Name = $asset.name; Url = $asset.browser_download_url; Version = $rel.tag_name } }
  }
  throw "В последнем релизе $Repo нет подходящего файла."
}

function Get-Apk([string]$Key, [scriptblock]$Resolve) {
  $cache = Join-Path $WorkDir 'apk'
  New-Item -ItemType Directory -Force -Path $cache | Out-Null
  $cached = Get-ChildItem $cache -Filter "$Key*.apk" -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1
  try {
    $src = & $Resolve
    $file = Join-Path $cache "$Key-$($src.Name -replace '[^\w\.\-]', '_')"
    if (-not $file.EndsWith('.apk')) { $file += '.apk' }
    if (-not (Test-Path $file)) {
      Say "  Скачиваю $($src.Name)…" 'DarkGray'
      Invoke-WebRequest -UseBasicParsing -Uri $src.Url -OutFile $file
    }
    return $file
  } catch {
    if ($cached) { Warn "Нет связи с GitHub, беру сохранённый $($cached.Name)"; return $cached.FullName }
    throw
  }
}

function Get-FreeMb {
  # Свободно во внутренней памяти (/data). stat -f есть в toybox с Android 6; нет ответа — $null
  $out = Sh "stat -f -c '%a %S' /data"
  if ($out -match '^(\d+) (\d+)$') { return [long]([double]$Matches[1] * [double]$Matches[2] / 1MB) }
  return $null
}

function Clear-TvCache {
  # pm trim-caches: система освобождает место за счёт кэша приложений. Данные и настройки не трогаются.
  $before = Get-FreeMb
  Sh 'pm trim-caches 999G' | Out-Null
  $after = Get-FreeMb
  if ($null -ne $before -and $null -ne $after) { Say "    Кэш приложений очищен: свободно $after МБ (было $before МБ)." 'DarkGray' }
}

function Get-ShortError([string]$Out) {
  # При исключении pm печатает весь стектрейс — оставляем суть: «Failure [...]» или текст исключения
  if ($Out -match 'Failure \[[^\]]+\]') { return $Matches[0] }
  $lines = @($Out -split "`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ -and $_ -notmatch '^(at |\.\.\. \d+ more|Caused by)' })
  $reason = $lines | ForEach-Object { if ($_ -match 'Exception: (?!.*Exception: )(.+)$') { $Matches[1] } } | Select-Object -First 1
  $text = if ($reason) { "$reason" } else { "$($lines | Select-Object -First 1)" }
  if ($text.Length -gt 300) { $text = $text.Substring(0, 300) + '…' }
  return $text
}

function Send-Apk([string]$File, [string]$Remote) {
  $push = Invoke-Adb push $File $Remote
  if ($push -notmatch 'pushed|file pushed|bytes in') { Sh "rm -f $Remote" | Out-Null; return "push: $push" }
  $out = Sh "pm install -r $Remote"
  Sh "rm -f $Remote" | Out-Null
  return $out
}

function Install-Apk([string]$File, [string]$Label) {
  # push + pm install: на Android 14 «adb install» может зависнуть, ожидая подтверждения на экране.
  $remote = '/data/local/tmp/chistytv.apk'
  $noSpace = 'INSUFFICIENT_STORAGE|not enough space|No space left'
  # Место с запасом: APK лежит во временной папке, при установке копируется и распаковывается
  $needMb = [long][math]::Ceiling((Get-Item $File).Length * 3 / 1MB) + 50
  $trimmed = $false
  $free = Get-FreeMb
  if ($null -ne $free -and $free -lt $needMb) {
    Warn "На телевизоре мало памяти: свободно $free МБ, для $Label нужно около $needMb МБ. Очищаю кэш приложений…"
    Clear-TvCache
    $trimmed = $true
  }
  $out = Send-Apk $File $remote
  if ($out -match $noSpace -and -not $trimmed) {
    Warn 'На телевизоре не хватило памяти — очищаю кэш приложений и пробую ещё раз…'
    Clear-TvCache
    $out = Send-Apk $File $remote
  }
  if ($out -match 'Success') { Ok "$Label установлен"; return $true }
  if ($out -like 'push: *') { Fail "Не удалось передать $Label`: $(Get-ShortError $out.Substring(6))" }
  else { Fail "$Label не установился: $(Get-ShortError $out)" }
  if ($out -match $noSpace) {
    $free = Get-FreeMb
    $now = if ($null -ne $free) { "свободно $free МБ, " } else { '' }
    Say "    Во внутренней памяти телевизора не хватает места даже после очистки кэша ($($now)нужно около $needMb МБ)." 'DarkGray'
    Say '    Удалите ненужные приложения: Настройки → Приложения (обычно больше всего весят игры и онлайн-кинотеатры), и запустите пункт снова.' 'DarkGray'
  }
  if ($out -match 'VERIFICATION_FAILURE') { Say '    Прошивка запрещает установку сторонних приложений по ADB.' 'DarkGray' }
  if ($out -match 'NO_MATCHING_ABIS') { Say "    У $Label нет сборки под процессор этого телевизора ($($Script:Info.AbiList))." 'DarkGray' }
  return $false
}

# Пакеты SmartTube: новые (после смены ключа в конце 2025) и старые.
$Script:SmartTubePackages = @('org.smarttube.stable', 'org.smarttube.beta', 'app.smarttube.fdroid',
  'com.teamsmart.videomanager.tv', 'com.liskovsoft.smarttubetv.beta')
$Script:HappPackage = 'com.happproxy'
$Script:LauncherPackage = 'com.leanbitlab.ltvL'

function Get-Abi {
  # Основная сборка SmartTube для ТВ — armeabi-v7a; arm64 берём, только если 32-битных библиотек нет.
  # На x86 «универсальные» APK не ставятся (в них нет x86-библиотек), поэтому для x86 — своя сборка.
  $list = "$($Script:Info.AbiList)"
  if ($list -match 'armeabi-v7a') { return 'armeabi-v7a' }
  if ($list -match 'arm64-v8a') { return 'arm64-v8a' }
  if ($list -match 'x86') { return 'x86' }
  return 'universal'
}

function Get-SmartTubeSource {
  # Релиз с тегом latest обновляется на месте и хранит файлы с постоянными именами.
  $abi = Get-Abi
  $name = 'smarttube_stable.apk'
  if ($abi -ne 'universal') { $name = "smarttube_stable_$abi.apk" }
  return [pscustomobject]@{ Name = $name; Url = "https://github.com/yuliskov/SmartTube/releases/download/latest/$name" }
}

function Test-SubscriptionUrl([string]$Url) {
  # Ссылка уходит в shell телевизора внутри одинарных кавычек: кавычки и пробелы недопустимы.
  return ($Url -match '^https?://[A-Za-z0-9\-\._~:/\?#\[\]@!\$\(\)\*\+,;=%&]+$')
}

function Send-HappSubscription([string]$Sub) {
  $out = Sh "am start -a android.intent.action.VIEW -p $($Script:HappPackage) -d 'happ://add/$Sub'"
  if ($out -notmatch 'Error|Exception|unable to resolve') {
    Ok 'Подписка отправлена в Happ. На телевизоре подтвердите добавление и разрешите VPN-подключение.'
    return $true
  }
  Warn "Happ не принял ссылку напрямую: $((($out -split "`n") | Where-Object { $_ -match 'Error|Exception' } | Select-Object -First 1))"
  if ($Yes) { return $false }
  Say '  Есть официальный способ Happ для ТВ: в Happ на телевизоре выберите «Web Import» — появится 5-значный код.' 'White'
  Say '  Ссылка подписки при этом пройдёт через сервер Happ (check.happ.su).' 'DarkGray'
  if (-not (Confirm 'Отправить подписку через сервер Happ?')) {
    Say '  Тогда добавьте вручную: Happ → «+» → вставьте ссылку подписки с пульта.' 'DarkGray'
    return $false
  }
  $code = Ask 'Код с экрана телевизора'
  if ($code -notmatch '^\d{5}$') { Fail 'Код — это 5 цифр.'; return $false }
  $body = @{ data = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($Sub)) } | ConvertTo-Json -Compress
  try {
    Invoke-RestMethod -UseBasicParsing -Method Post -Uri "https://check.happ.su/sendtv/$code" -ContentType 'application/json' -Body $body | Out-Null
    Ok 'Отправлено. Подтвердите добавление подписки в Happ на телевизоре.'
    return $true
  } catch {
    Fail "Сервер Happ не принял код: $($_.Exception.Message)"
    return $false
  }
}

function Invoke-YouTube {
  Title 'YouTube без тормозов'
  Say '  1) SmartTube — открытый клиент YouTube для ТВ: без рекламы, со SponsorBlock и 4K.' 'White'
  $installedTube = @($Script:SmartTubePackages | Where-Object { $Script:Info.Installed -contains $_ })
  if ($installedTube -contains 'org.smarttube.stable' -or $installedTube -contains 'org.smarttube.beta') {
    Ok 'SmartTube уже установлен'
  } else {
    if ($installedTube.Count) {
      Warn "Стоит старая сборка SmartTube ($($installedTube -join ', ')): после смены ключа разработчика она не обновляется. Поставлю новую рядом, старую потом можно удалить."
    }
    if (Confirm 'Установить SmartTube (с официального GitHub)?') {
      $apk = Get-Apk 'smarttube' { Get-SmartTubeSource }
      Install-Apk $apk 'SmartTube' | Out-Null
    }
  }

  Say ''
  Say '  2) VPN на телевизор — без него YouTube в России тормозит.' 'White'
  if ($Script:Info.Installed -contains $Script:HappPackage) { Ok 'Happ уже установлен' }
  elseif (Confirm 'Установить VPN-клиент Happ (с официального GitHub)?') {
    $apk = Get-Apk 'happ' { [pscustomobject]@{ Name = 'Happ.apk'; Url = 'https://github.com/Happ-proxy/happ-android/releases/latest/download/Happ.apk' } }
    Install-Apk $apk 'Happ' | Out-Null
  }

  $sub = $Subscription
  if (-not $sub -and -not $Yes) {
    Say ''
    Say "  Ссылка подписки — из вашего VPN-бота. Нет VPN? $($Script:Vpn.Name): бесплатный пробный период → $($Script:Vpn.Url)" 'Yellow'
    $sub = Ask 'Вставьте ссылку подписки (https://…) или Enter — пропустить'
  }
  if ($sub) {
    if (-not (Test-SubscriptionUrl $sub)) { Fail 'Это не похоже на ссылку подписки.'; return }
    if (Send-HappSubscription $sub) {
      Say '  В Happ выберите сервер (лучше «Автовыбор») и включите VPN, затем откройте SmartTube.' 'DarkGray'
    }
  }
}

# Работает ли на ТВ поиск адресов. Строку «PING host (ip)» ping печатает только после того, как имя нашлось,
# поэтому проверка не зависит от того, отвечает ли сервер на ping.
function Test-TvDns {
  $out = "$(Sh 'ping -c 1 -W 2 connectivitycheck.gstatic.com 2>&1')"
  return ($out -match 'PING \S+ \(\d')
}

function Invoke-Dns {
  Title 'Блокировка рекламы через DNS'
  if ($Script:Info.Sdk -lt 28) { Warn 'Нужен Android 9 и новее.'; return }
  $mode = "$(Sh 'settings get global private_dns_mode')".Trim()
  $spec = "$(Sh 'settings get global private_dns_specifier')".Trim()
  if ($mode -eq 'hostname') {
    Say "  Сейчас включён частный DNS: $spec"
    if (Confirm 'Выключить?' $false) {
      Sh 'settings put global private_dns_mode opportunistic' | Out-Null
      Ok 'Частный DNS выключен'
    }
    return
  }
  Say '  AdGuard DNS режет рекламу и трекеры в приложениях. Рекламу внутри YouTube не убирает — для неё SmartTube.' 'DarkGray'
  if (-not (Confirm 'Включить AdGuard DNS?')) { return }
  $online = Test-TvDns
  if (-not $online) { Warn 'У телевизора сейчас нет интернета — проверить DNS не получится.' }
  Sh 'settings put global private_dns_specifier dns.adguard-dns.com' | Out-Null
  Sh 'settings put global private_dns_mode hostname' | Out-Null
  if (-not $online) { Ok 'Включено. Если после появления интернета ТВ напишет «нет связи» — выключите здесь же.'; return }

  # Частный DNS ходит по порту 853. Если его режет провайдер или роутер, ТВ остаётся без интернета —
  # проверяем и при неудаче сразу возвращаем как было.
  Say '  Проверяю, что интернет на ТВ работает с новым DNS…' 'DarkGray'
  $ok = $false
  foreach ($i in 1..4) {
    Start-Sleep -Seconds 2
    if (Test-TvDns) { $ok = $true; break }
  }
  if ($ok) { Ok 'Включено, интернет работает. Отключить можно здесь же или в настройках сети ТВ.'; return }

  if ($mode -notin @('off', 'opportunistic')) { $mode = 'opportunistic' }
  Sh "settings put global private_dns_mode $mode" | Out-Null
  if ($spec -and $spec -ne 'null') { Sh "settings put global private_dns_specifier $spec" | Out-Null }
  else { Sh 'settings delete global private_dns_specifier' | Out-Null }
  Warn 'С AdGuard DNS телевизор остался без интернета: провайдер или роутер не пропускают DNS-over-TLS (порт 853).'
  Ok 'Вернул DNS как было — интернет на ТВ работает.'
  Say '  Можно прописать AdGuard DNS в роутере (94.140.14.14 и 94.140.15.15) — тогда реклама режется у всех устройств дома.' 'DarkGray'
}

function Get-FocusedActivity {
  $out = Sh 'dumpsys activity activities'
  $line = ($out -split "`n" | Where-Object { $_ -match 'topResumedActivity|mResumedActivity|mFocusedApp' } | Select-Object -First 1)
  return "$line"
}

function Test-HomeOpens([string]$Pkg) {
  Sh 'input keyevent KEYCODE_HOME' | Out-Null
  Start-Sleep -Seconds 3
  return ((Get-FocusedActivity) -match [regex]::Escape($Pkg))
}

function Invoke-Launcher {
  Title 'Новый главный экран без рекламы'
  Say '  LTvLauncher — открытый лаунчер (GPL-3.0) без рекламных рядов и рекомендаций.' 'White'
  Say '  Штатный главный экран не удаляется и не отключается, пока вы сами не попросите.' 'DarkGray'
  if ($Script:Info.Installed -notcontains $Script:LauncherPackage) {
    if (-not (Confirm 'Установить LTvLauncher (с официального GitHub)?')) { return }
    $abi = Get-Abi
    $apk = Get-Apk 'ltvlauncher' { Get-GitHubAsset 'leanbitlab-org/LtvLauncher' @("$abi-release\.apk$", 'universal-release\.apk$') }
    if (-not (Install-Apk $apk 'LTvLauncher')) { return }
  }

  $component = (Sh "cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME -p $($Script:LauncherPackage)") -split "`n" |
    ForEach-Object { $_.Trim() } | Where-Object { $_ -match '^[\w\.]+/[\w\.\$]+$' } | Select-Object -Last 1
  if (-not $component) { Fail 'Не нашёл экран лаунчера — откройте LTvLauncher вручную один раз и запустите пункт снова.'; return }
  $out = Sh "cmd package set-home-activity $component"
  if ($out -notmatch 'Success') { Warn "set-home-activity: $out" }

  if (Test-HomeOpens $Script:LauncherPackage) {
    Ok 'Кнопка «Домой» открывает LTvLauncher.'
    return
  }

  Warn 'Прошивка всё равно открывает штатный главный экран (так делает Google TV и часть Яндекс ТВ).'
  Say '  Мягкий способ: на ТВ откройте Настройки → Специальные возможности → «Home Button Fix» (LTvLauncher) и включите.' 'White'
  Say '  Затем нажмите «Домой» на пульте.' 'DarkGray'
  if ($Yes) { return }
  if (Confirm 'Включили? Проверить ещё раз?') {
    if (Test-HomeOpens $Script:LauncherPackage) { Ok 'Готово: «Домой» открывает LTvLauncher.'; return }
  }

  $stock = @()
  foreach ($e in @($Script:Db.packages | Where-Object { $_.launcher -and $Script:Info.Installed -contains $_.id -and $Script:Info.Disabled -notcontains $_.id })) {
    $stock += $e
    # Парные пакеты: на Google TV 14 без setupwraith система уходит в цикл перезагрузок.
    foreach ($req in @($e.requires)) {
      if (-not $req) { continue }
      if ($Script:Info.Installed -contains $req -and $Script:Info.Disabled -notcontains $req) {
        $reqEntry = $Script:Db.packages | Where-Object { $_.id -eq $req } | Select-Object -First 1
        $title = $req
        if ($reqEntry) { $title = $reqEntry.title }
        $stock += [pscustomobject]@{ id = $req; title = $title }
      }
    }
  }
  if (-not $stock.Count) { Warn 'Штатный лаунчер этой прошивки не знаю — оставляю как есть. Подробнее: PROMPT.md.'; return }
  Say ''
  Say '  Жёсткий способ — отключить штатный лаунчер. Обратимо через «Вернуть как было».' 'Yellow'
  foreach ($e in $stock) { Say "    • $($e.title) ($($e.id))" 'DarkGray' }
  if (-not (Confirm 'Отключить штатный лаунчер?' $false)) { return }

  # Пакет-владелец сохранённой сети Wi-Fi при отключении удаляет её — ТВ отвалится от Wi-Fi и ADB.
  # Лаунчер и его парные пакеты отключаются только все вместе, поэтому при любой помехе отменяем всё.
  $wifiOwners = Sh "dumpsys wifi | grep -o 'cname=[^ ]*'"
  $owners = @($stock | Where-Object { $wifiOwners -match [regex]::Escape($_.id) })
  if ($owners.Count) {
    Warn "$(($owners | ForEach-Object { $_.id }) -join ', ') владеет сохранённой сетью Wi-Fi: после отключения ТВ потеряет Wi-Fi."
    Say '    Настройки → Сеть → «Забыть сеть», подключитесь к ней заново и запустите этот пункт ещё раз.' 'DarkGray'
    return
  }
  $toDisable = $stock
  Disable-Packages $toDisable | Out-Null
  if (Test-HomeOpens $Script:LauncherPackage) { Ok 'Готово: «Домой» открывает LTvLauncher.' }
  else {
    Fail 'Не помогло. Возвращаю штатный лаунчер.'
    foreach ($e in $toDisable) { Sh "pm enable $($e.id)" | Out-Null }
  }
}

function Show-Finish {
  Title 'Напоследок'
  Say '  Когда закончите, выключите на ТВ «Отладку по сети» и «Отладку по USB»: порт 5555 открыт для всей домашней сети.' 'Yellow'
  Say "  Исходники и вопросы: https://github.com/$($Script:Repo) · автор $($Script:Author)" 'DarkGray'
  Invoke-Adb disconnect $Script:Serial | Out-Null
}

function Show-Menu {
  while ($true) {
    Title 'Что делаем?'
    Say '  1. Убрать рекламу и мусор (рекомендуется начать с этого)'
    Say '  2. YouTube без тормозов: SmartTube + VPN'
    Say '  3. Новый главный экран без рекламных рядов'
    Say '  4. Блокировка рекламы через DNS'
    Say '  5. Вернуть как было'
    Say '  0. Выход'
    switch (Ask 'Пункт' '1') {
      '1' { Invoke-Clean }
      '2' { Invoke-YouTube }
      '3' { Invoke-Launcher }
      '4' { Invoke-Dns }
      '5' { Invoke-Restore }
      '0' { return }
      default { Warn 'Нет такого пункта' }
    }
    $Script:Info = Get-TvInfo
  }
}

# ─── Запуск ──────────────────────────────────────────────────────────────

function Main {
  Say ''
  Say "  ЧистыйТВ $($Script:Version) — телевизор без рекламы и YouTube без тормозов" 'Cyan'
  Say '  Ничего не удаляется: приложения только отключаются, вернуть можно одной командой.' 'DarkGray'

  Title 'Подготовка'
  Say '  На ТВ: Настройки → Система → Об устройстве → 7 раз «Сборка» → «Для разработчиков» → «Отладка по USB»'
  Say '  и «Отладка по сети» (если есть). Компьютер и ТВ — в одной Wi-Fi сети.'
  Say "  Яндекс ТВ, Салют ТВ и другие платформы — пошагово: https://github.com/$($Script:Repo)/blob/main/docs/ADB.md" 'DarkGray'
  $Script:Adb = Get-AdbPath
  Invoke-Adb start-server | Out-Null
  if (-not (Select-Tv)) { return }

  $Script:Info = Get-TvInfo
  $Script:Db = Load-Db
  Show-Info

  switch ($Action) {
    'menu' { Show-Menu }
    'clean' { Invoke-Clean }
    'restore' { Invoke-Restore }
    'youtube' { Invoke-YouTube }
    'launcher' { Invoke-Launcher }
    'dns' { Invoke-Dns }
    'info' { }
  }
  Show-Finish
}

Main
