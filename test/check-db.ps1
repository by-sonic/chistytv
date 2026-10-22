# Проверка базы пакетов: структура, дубликаты, конфликт с защитными правилами src/main.ps1.
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
$root = Split-Path $PSScriptRoot
$db = [IO.File]::ReadAllText((Join-Path $root 'data\packages.json'), [Text.Encoding]::UTF8) | ConvertFrom-Json
$errors = @()

$platformIds = @('any') + @($db.platforms | ForEach-Object { $_.id })
foreach ($e in $db.packages) {
  if ($e.id -notmatch '^[A-Za-z][\w]*(\.[\w]+)+$') { $errors += "плохое имя пакета: $($e.id)" }
  if ($platformIds -notcontains $e.platform) { $errors += "$($e.id): неизвестная платформа $($e.platform)" }
  if (@('disable', 'ask', 'keep') -notcontains $e.action) { $errors += "$($e.id): неизвестное действие $($e.action)" }
  if (-not $e.title) { $errors += "$($e.id): нет названия" }
  if ($e.launcher -and $e.action -ne 'keep') { $errors += "$($e.id): лаунчер должен быть keep" }
}
foreach ($d in ($db.packages | Group-Object id | Where-Object Count -gt 1)) { $errors += "дубликат: $($d.Name)" }

# Защитные шаблоны берём из самого скрипта, чтобы проверка не разъезжалась с ним.
$script = [IO.File]::ReadAllText((Join-Path $root 'src\main.ps1'), [Text.Encoding]::UTF8)
$block = [regex]::Match($script, '\$Script:ProtectPatterns = @\((?<p>[\s\S]*?)\n\)').Groups['p'].Value
$patterns = [regex]::Matches($block, "'([^']+)'") | ForEach-Object { $_.Groups[1].Value }
if (-not $patterns) { $errors += 'не нашёл ProtectPatterns в src/main.ps1' }
foreach ($e in ($db.packages | Where-Object { $_.action -ne 'keep' })) {
  foreach ($p in $patterns) {
    if ($e.id -match $p) { $errors += "$($e.id) ($($e.action)) попадает под защиту «$p» — скрипт его не тронет" }
  }
}

$byAction = ($db.packages | Group-Object action | ForEach-Object { "$($_.Name)=$($_.Count)" }) -join ', '
Write-Host "Платформ: $($db.platforms.Count), пакетов: $($db.packages.Count) ($byAction)"
if ($errors.Count) { $errors | ForEach-Object { Write-Host "✖ $_" -ForegroundColor Red }; exit 1 }
Write-Host '✔ база пакетов в порядке' -ForegroundColor Green
