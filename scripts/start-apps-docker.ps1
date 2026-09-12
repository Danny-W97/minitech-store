[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Arguments = @()
)

$ErrorActionPreference = "Stop"
$controlCenter = Join-Path $PSScriptRoot "control-center\start.ps1"
& $controlCenter -CurrentWindow docker-apps-up @Arguments
exit $LASTEXITCODE
