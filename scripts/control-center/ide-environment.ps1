[CmdletBinding()]
param(
    [ValidateSet("dev", "test")]
    [string]$Profile = "dev"
)

$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\..")).Path
$wslProjectRoot = (& wsl.exe wslpath -a -- $projectRoot).Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($wslProjectRoot)) {
    throw "Nie mozna przeliczyc sciezki projektu na sciezke WSL."
}

& wsl.exe --cd $wslProjectRoot bash scripts/control-center/ide-environment.sh $Profile
exit $LASTEXITCODE
