<#
.SYNOPSIS
  ChistyTV launcher. The program itself is src/main.ps1.

.DESCRIPTION
  This file is intentionally ASCII-only and saved without BOM, so it runs both ways:
    irm https://raw.githubusercontent.com/by-sonic/chistytv/main/chistytv.ps1 | iex
    .\chistytv.ps1 -TvIp 192.168.1.50 -Action clean -Yes
  Windows PowerShell 5.1 reads a BOM-less file as ANSI and breaks Cyrillic text, while `irm | iex`
  chokes on a BOM before the param block. So the launcher stays ASCII and loads src/main.ps1
  explicitly as UTF-8: from disk next to it, or from GitHub when started with `irm | iex`.
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

$chistytvArgs = @{}
if ($PSBoundParameters) { foreach ($k in $PSBoundParameters.Keys) { $chistytvArgs[$k] = $PSBoundParameters[$k] } }

& {
  param([hashtable]$Params, [string]$Root)
  $local = if ($Root) { Join-Path $Root 'src\main.ps1' }
  if ($local -and (Test-Path $local)) {
    $code = [IO.File]::ReadAllText($local, [Text.Encoding]::UTF8)
    $db = Join-Path $Root 'data\packages.json'
    if (-not $Params.ContainsKey('DataPath') -and (Test-Path $db)) { $Params['DataPath'] = $db }
  } else {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $url = 'https://raw.githubusercontent.com/by-sonic/chistytv/main/src/main.ps1'
    $bytes = (Invoke-WebRequest -UseBasicParsing -Uri $url -ErrorAction Stop).RawContentStream.ToArray()
    $code = [Text.Encoding]::UTF8.GetString($bytes)
  }
  & ([scriptblock]::Create($code.TrimStart([char]0xFEFF))) @Params
} -Params $chistytvArgs -Root $PSScriptRoot
