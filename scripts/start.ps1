[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$ControlCenterArguments = @()
)

$ErrorActionPreference = "Stop"
$controlCenter = Join-Path $PSScriptRoot "control-center\start.ps1"
& $controlCenter @ControlCenterArguments
exit $LASTEXITCODE
