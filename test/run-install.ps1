# Install-Apk из src/main.ps1 на фейковом adb: сценарии установки без скачивания с GitHub
param([string]$Adb, [string]$Apk, [string]$Label)
$ErrorActionPreference = 'Stop'
$main = Join-Path (Split-Path $PSScriptRoot) 'src\main.ps1'
$ast = [System.Management.Automation.Language.Parser]::ParseFile($main, [ref]$null, [ref]$null)
foreach ($f in $ast.FindAll({ $args[0] -is [System.Management.Automation.Language.FunctionDefinitionAst] }, $false)) {
  . ([scriptblock]::Create($f.Extent.Text))
}
$Script:Adb = $Adb
$Script:Serial = $null
$Script:Info = [pscustomobject]@{ AbiList = 'armeabi-v7a,armeabi' }
Install-Apk $Apk $Label | Out-Null
