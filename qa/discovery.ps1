# Exercise the release's auto-discovery-to-manual handoff with no LAN routes available.
$ErrorActionPreference = 'Stop'
$src = Join-Path (Split-Path $PSScriptRoot) 'src\main.ps1'
$ast = [System.Management.Automation.Language.Parser]::ParseFile($src, [ref]$null, [ref]$null)
foreach ($node in $ast.FindAll({ $args[0] -is [System.Management.Automation.Language.FunctionDefinitionAst] }, $false)) {
  . ([scriptblock]::Create($node.Extent.Text))
}
$Script:Adb = Join-Path (Split-Path $PSScriptRoot) 'test\fake-adb.ps1'
$Script:Serial = $null
$Script:Problems = [Collections.Generic.List[string]]::new()
function Get-LocalPrefixes { return @() }
function Ask { return '127.0.0.1' }
if (-not (Select-Tv)) { throw 'No manual connection' }
if ($Script:Serial -ne '127.0.0.1:5555') { throw 'Unexpected ADB target' }
if ($Script:Problems.Count -ne 1) { throw 'Expected a discovery warning' }
'discovery and manual connection passed'
