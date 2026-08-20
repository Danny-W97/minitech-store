[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("gateway", "api-gateway", "product", "order", "inventory", "payment", "notification", "customer", "delivery", "promotion", "wishlist", "review", "recommendation", "chat", "returns", "billing")]
    [string]$Service,
    [ValidateSet("dev", "test")]
    [string]$Profile = "dev"
)

$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\..")).Path
$wslProjectRoot = (& wsl.exe wslpath -a -- $projectRoot).Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($wslProjectRoot)) {
    throw "Nie mozna przeliczyc sciezki projektu na sciezke WSL."
}

& wsl.exe --cd $wslProjectRoot bash scripts/control-center/ide-run.sh $Service $Profile
exit $LASTEXITCODE
