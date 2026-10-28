# Тесты ЧистогоТВ на фейковом adb: powershell -ExecutionPolicy Bypass -File test\run-tests.ps1
$ErrorActionPreference = 'Stop'
# chistytv.ps1 пишет в UTF-8 — читаем вывод дочернего процесса в той же кодировке
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$root = Split-Path $PSScriptRoot
$script = Join-Path $root 'chistytv.ps1'
$fakeAdb = Join-Path $PSScriptRoot 'fake-adb.ps1'
$data = Join-Path $PSScriptRoot 'fixtures\packages.json'
$tmp = Join-Path ([IO.Path]::GetTempPath()) ("chistytv-test-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Path $tmp | Out-Null
$failed = 0

function New-Tv([string[]]$Installed, [hashtable]$Extra = @{}) {
  $state = @{
    props     = @{
      'ro.product.manufacturer' = 'Xiaomi'; 'ro.product.brand' = 'Xiaomi'; 'ro.product.model' = 'MiTV-TEST'
      'ro.build.version.release' = '11'; 'ro.build.version.sdk' = '30'; 'ro.product.cpu.abi' = 'armeabi-v7a'
      'ro.product.cpu.abilist' = 'armeabi-v7a,armeabi'
    }
    installed = $Installed; disabled = @(); started = @(); dns_mode = 'opportunistic'; dns_spec = 'null'; refuse = $false; home = ''; home_works = $false; wifi_owner = ''; tv_journal = ''
  }
  foreach ($k in $Extra.Keys) { $state[$k] = $Extra[$k] }
  $path = Join-Path $tmp ("tv-" + [guid]::NewGuid().ToString('N').Substring(0, 6) + '.json')
  $state | ConvertTo-Json -Depth 5 | Set-Content $path -Encoding UTF8
  $env:FAKE_ADB_STATE = $path
  return $path
}

function State([string]$Path) { Get-Content $Path -Raw -Encoding UTF8 | ConvertFrom-Json }

function Run([string[]]$ScriptArgs) {
  $work = Join-Path $tmp 'work'
  $out = & powershell -NoProfile -ExecutionPolicy Bypass -File $script -AdbPath $fakeAdb -DataPath $data -WorkDir $work -TvIp 10.0.0.5 @ScriptArgs *>&1 | Out-String
  return $out
}

function RunWithInput([string]$InputText, [string[]]$ScriptArgs) {
  $work = Join-Path $tmp 'work'
  return ($InputText | & powershell -NoProfile -ExecutionPolicy Bypass -File $script -AdbPath $fakeAdb -DataPath $data -WorkDir $work -TvIp 10.0.0.5 @ScriptArgs *>&1 | Out-String)
}

function Check([string]$Name, [bool]$Condition, [string]$Details = '') {
  if ($Condition) { Write-Host "✔ $Name" -ForegroundColor Green }
  else { Write-Host "✖ $Name $Details" -ForegroundColor Red; $script:failed++ }
}

$db = Get-Content $data -Raw -Encoding UTF8 | ConvertFrom-Json
$safe = @($db.packages | Where-Object { $_.action -eq 'disable' -and $_.platform -eq 'xiaomi' } | Select-Object -First 2 | ForEach-Object { $_.id })
$ask = @($db.packages | Where-Object { $_.action -eq 'ask' -and $_.platform -eq 'any' } | Select-Object -First 1 | ForEach-Object { $_.id })
$keep = @($db.packages | Where-Object { $_.action -eq 'keep' } | Select-Object -First 1 | ForEach-Object { $_.id })
$detect = @($db.platforms | Where-Object { $_.id -eq 'xiaomi' } | ForEach-Object { $_.detect_packages } | Select-Object -First 1)
Check 'в базе есть данные для теста' ($safe.Count -ge 1 -and $ask.Count -ge 1 -and $keep.Count -ge 1)

# 1. Чистка с -Yes отключает только рекомендованные пакеты платформы
$installed = @('com.android.settings', 'com.android.systemui') + $safe + $ask + $keep + $detect + @('com.unknown.app')
$tv = New-Tv $installed
$out = Run @('-Action', 'clean', '-Yes')
$s = State $tv
Check 'рекомендованные отключены' (@($safe | Where-Object { $s.disabled -notcontains $_ }).Count -eq 0) $out
Check 'необязательные не тронуты без вопроса' (@($ask | Where-Object { $s.disabled -contains $_ }).Count -eq 0)
Check 'подсказали про рекламные ряды штатного лаунчера' ($out -match 'рисует сам штатный лаунчер') $out
Check 'keep и неизвестные не тронуты' (($s.disabled -notcontains $keep[0]) -and ($s.disabled -notcontains 'com.unknown.app') -and ($s.disabled -notcontains 'com.android.settings'))
$log = Get-ChildItem (Join-Path $tmp 'work') -Recurse -Filter 'disabled-by-chistytv.txt' | Select-Object -First 1
Check 'журнал отката записан' ($log -and ((Get-Content $log.FullName) -contains $safe[0]))
Check 'бэкап сделан' ((Get-ChildItem (Join-Path $tmp 'work') -Recurse -Filter 'packages.txt').Count -ge 1)

Check 'журнал продублирован на телевизор' ((State $tv).tv_journal -match [regex]::Escape($safe[0]))

# 2. Откат включает всё обратно
$out = Run @('-Action', 'restore', '-Yes')
$s = State $tv
Check 'откат включил пакеты' (@($s.disabled).Count -eq 0) $out

# 2б. Откат по журналу на телевизоре: компьютер другой (или чистили с телефона), своего журнала нет
$tv = New-Tv $installed
Run @('-Action', 'clean', '-Yes') | Out-Null
Get-ChildItem (Join-Path $tmp 'work') -Recurse -Filter 'disabled-by-chistytv.txt' | Remove-Item -Force
$out = Run @('-Action', 'restore', '-Yes')
$s = State $tv
Check 'откат по журналу на телевизоре' (@($s.disabled).Count -eq 0) $out

# 2в. Прошивка не даёт включить пакет — он остаётся в журнале, «всё включено» не пишем
function Set-State([string]$Path, [string]$Key, $Value) {
  $s = State $Path
  $s | Add-Member -Force $Key $Value
  $s | ConvertTo-Json -Depth 5 | Set-Content $Path -Encoding UTF8
}
$tv = New-Tv $installed
Get-ChildItem (Join-Path $tmp 'work') -Recurse -Filter 'disabled-by-chistytv.txt' | Remove-Item -Force
Run @('-Action', 'clean', '-Yes') | Out-Null
Set-State $tv 'refuse_enable' @($safe[0])
$out = Run @('-Action', 'restore', '-Yes')
$log = Get-ChildItem (Join-Path $tmp 'work') -Recurse -Filter 'disabled-by-chistytv.txt' | Select-Object -First 1
Check 'невключённое осталось в журналах, без «всё включено»' (($out -match "Не включилось: $([regex]::Escape($safe[0]))") -and ($out -notmatch 'Все отключённые ЧистымТВ приложения включены') -and $log -and (@(Get-Content $log.FullName | Where-Object { $_ }) -join ',') -eq $safe[0] -and "$((State $tv).tv_journal)".Trim() -eq $safe[0]) $out
Set-State $tv 'refuse_enable' @()
$out = Run @('-Action', 'restore', '-Yes')
Check 'второй откат добил остаток' ((@((State $tv).disabled).Count -eq 0) -and ($out -match 'Все отключённые ЧистымТВ приложения включены')) $out

# 3. Прошивка, запрещающая отключение — понятное сообщение
$tv = New-Tv $installed @{ refuse = $true }
$out = Run @('-Action', 'clean', '-Yes')
Check 'запрет прошивки распознан' ($out -match 'Прошивка не даёт отключать') $out

# 4. Импорт подписки в Happ (приложения уже стоят, сеть не нужна)
$tv = New-Tv ($installed + @('com.happproxy', 'org.smarttube.stable'))
$out = Run @('-Action', 'youtube', '-Yes', '-Subscription', 'https://example.com/sub/abc123')
$s = State $tv
Check 'подписка отправлена через happ://add/' (@($s.started | Where-Object { $_ -match "happ://add/https://example\.com/sub/abc123" }).Count -eq 1) $out

$out = Run @('-Action', 'youtube', '-Yes', '-Subscription', 'https://example.com/sub?id=1&lang=ru')
$s = State $tv
Check 'ссылка с & в параметрах проходит' (@($s.started | Where-Object { $_ -match "-p com\.happproxy -d 'happ://add/https://example\.com/sub\?id=1&lang=ru'" }).Count -eq 1) $out

# 5. Подозрительная «ссылка» отвергается
$out = Run @('-Action', 'youtube', '-Yes', '-Subscription', "https://x.com/'; reboot")
Check 'инъекция в ссылке отвергнута' ($out -match 'не похоже на ссылку') $out

# 6. DNS включается и выключается
$tv = New-Tv $installed
Run @('-Action', 'dns', '-Yes') | Out-Null
$s = State $tv
Check 'AdGuard DNS включён' ($s.dns_mode -eq 'hostname' -and $s.dns_spec -eq 'dns.adguard-dns.com')

# 6б. Провайдер режет DNS-over-TLS: ТВ остался бы без интернета — скрипт сам возвращает как было
$tv = New-Tv $installed @{ dot_blocked = $true }
$out = Run @('-Action', 'dns', '-Yes')
$s = State $tv
Check 'DNS откатился, если интернет пропал' ($s.dns_mode -eq 'opportunistic' -and $s.dns_spec -eq 'null') $out
Check 'объяснили причину' ($out -match 'порт 853') $out

# 6в. Android 9: имя, которое ТВ уже искал, находится из кэша и при сломанном DNS — проверка не должна обмануться
$tv = New-Tv $installed @{ dot_blocked = $true; stale_validated = $true }
$s = State $tv; $s.props.'ro.build.version.sdk' = '28'; $s | ConvertTo-Json -Depth 5 | Set-Content $tv -Encoding UTF8
$out = Run @('-Action', 'dns', '-Yes')
$s = State $tv
Check 'Android 9: кэш DNS не обманул проверку' (($s.dns_mode -eq 'opportunistic') -and (@($s.pinged | Select-Object -Skip 1) -notcontains 'connectivitycheck.gstatic.com')) $out

# 6г. Свой частный DNS: откат возвращает его, а не opportunistic
$tv = New-Tv $installed @{ dns_mode = 'hostname'; dns_spec = 'dns.google'; dot_blocked_spec = 'dns.adguard-dns.com' }
$out = Run @('-Action', 'dns', '-Yes')
$s = State $tv
Check 'откат вернул свой частный DNS' ($s.dns_mode -eq 'hostname' -and $s.dns_spec -eq 'dns.google' -and $out -match 'частный DNS dns\.google') $out

# 6д. «Выключить» тоже возвращает свой DNS, а не opportunistic
$tv = New-Tv $installed @{ dns_mode = 'hostname'; dns_spec = 'dns.google' }
Run @('-Action', 'dns', '-Yes') | Out-Null
$out = RunWithInput "y`n" @('-Action', 'dns')
$s = State $tv
Check '«Выключить» вернул свой частный DNS' ($s.dns_mode -eq 'hostname' -and $s.dns_spec -eq 'dns.google' -and -not $s.dns_before) $out

# 7. Главный экран: прошивка слушается set-home-activity
$tv = New-Tv ($installed + @('com.leanbitlab.ltvL')) @{ home_works = $true }
$out = Run @('-Action', 'launcher', '-Yes')
$s = State $tv
Check 'лаунчер назначен главным' ($s.home -eq 'com.leanbitlab.ltvL/com.leanbitlab.ltvL.MainActivity' -and $out -match 'открывает LTvLauncher') $out

# 8. Главный экран: прошивка упрямится — без согласия штатный лаунчер не трогаем
$tv = New-Tv ($installed + @('com.leanbitlab.ltvL', 'com.test.launcher')) @{ home_works = $false }
$out = Run @('-Action', 'launcher', '-Yes')
$s = State $tv
Check 'предложен мягкий способ, штатный лаунчер цел' (($out -match 'Home Button Fix') -and (@($s.disabled).Count -eq 0)) $out

# 9. Жёсткий режим: парный пакет владеет Wi-Fi — отменяем всё, ничего не отключаем
$tv = New-Tv ($installed + @('com.leanbitlab.ltvL', 'com.test.stock', 'com.test.wraith')) @{ wifi_owner = 'cname=com.test.wraith' }
$out = RunWithInput "n`ny`n" @('-Action', 'launcher')
$s = State $tv
Check 'Wi-Fi владелец — отмена без отключений' (($out -match 'потеряет Wi-Fi') -and (@($s.disabled).Count -eq 0)) $out

# 10. Жёсткий режим: лаунчер отключается вместе с парным пакетом
$tv = New-Tv ($installed + @('com.leanbitlab.ltvL', 'com.test.stock', 'com.test.wraith'))
$out = RunWithInput "n`ny`n" @('-Action', 'launcher')
$s = State $tv
Check 'лаунчер и парный пакет отключены вместе' ((@($s.disabled) -contains 'com.test.stock') -and (@($s.disabled) -contains 'com.test.wraith') -and ($out -match 'Готово')) $out

# 11. ТВ ждёт подтверждения отладки — это не ошибка, ждём и подключаемся
$tv = New-Tv $installed @{ auth_pending = $true }
$out = Run @('-Action', 'info', '-Yes')
Check 'failed to authenticate — ждём подтверждения' (($out -match 'Подключено') -and ($out -notmatch 'Не подключился')) $out

# 12. Пустое пояснение — без висящего тире
$tv = New-Tv $installed
$out = Run @('-Action', 'clean', '-Yes')
Check 'нет висящих тире' ($out -notmatch ' — ?
') $out

# 12б. Мало памяти на ТВ: чистим кэш приложений и ставим; не помогло — понятная подсказка без стектрейса
$apk = Join-Path $tmp 'test.apk'
[IO.File]::WriteAllBytes($apk, (New-Object byte[] 1MB))   # нужно около 3×1 + 50 = 53 МБ
function RunInstall { & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'run-install.ps1') -Adb $fakeAdb -Apk $apk -Label 'SmartTube' *>&1 | Out-String }

$tv = New-Tv $installed @{ free_mb = 40; cache_mb = 500; need_mb = 100; trimmed = $false }
$out = RunInstall
Check 'мало памяти заранее — кэш очищен и установлено' ((State $tv).trimmed -and $out -match 'мало памяти' -and $out -match 'SmartTube установлен') $out

$tv = New-Tv $installed @{ free_mb = 60; cache_mb = 500; need_mb = 100; trimmed = $false }
$out = RunInstall
Check 'не хватило при установке — кэш очищен, вторая попытка удалась' ((State $tv).trimmed -and $out -match 'пробую ещё раз' -and $out -match 'SmartTube установлен') $out

$tv = New-Tv $installed @{ free_mb = 40; cache_mb = 5; need_mb = 100; trimmed = $false }
$out = RunInstall
Check 'места нет совсем — подсказка, без стектрейса' (($out -match 'not enough space') -and ($out -match 'Удалите ненужные приложения') -and ($out -match 'свободно 45 МБ') -and ($out -notmatch 'ExceptionUtils') -and ($out -notmatch 'SmartTube установлен')) $out

$tv = New-Tv $installed
$out = RunInstall
Check 'обычная установка не трогает кэш' ((-not (State $tv).trimmed) -and $out -match 'SmartTube установлен' -and $out -notmatch 'Кэш') $out

# 13. Запуск «irm | iex»: загрузчик без BOM и только ASCII, иначе PowerShell не поймёт блок param
$launcher = [IO.File]::ReadAllBytes($script)
Check 'chistytv.ps1 без BOM' (-not ($launcher[0] -eq 0xEF -and $launcher[1] -eq 0xBB -and $launcher[2] -eq 0xBF))
Check 'chistytv.ps1 только ASCII' (@($launcher | Where-Object { $_ -gt 127 }).Count -eq 0)
$errs = $null
[void][System.Management.Automation.Language.Parser]::ParseInput([Text.Encoding]::UTF8.GetString($launcher), [ref]$null, [ref]$errs)
Check 'загрузчик разбирается как текст для iex' ($errs.Count -eq 0) ($errs | Out-String)
$errs = $null
[void][System.Management.Automation.Language.Parser]::ParseInput([IO.File]::ReadAllText((Join-Path $root 'src\main.ps1'), [Text.Encoding]::UTF8), [ref]$null, [ref]$errs)
Check 'src/main.ps1 разбирается' ($errs.Count -eq 0) ($errs | Out-String)

Remove-Item $tmp -Recurse -Force
if ($failed) { Write-Host "`nПровалено: $failed" -ForegroundColor Red; exit 1 }
Write-Host "`nВсе тесты прошли" -ForegroundColor Green
