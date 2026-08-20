[CmdletBinding()]
param(
    [switch]$CurrentWindow,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$ControlCenterArguments = @()
)

$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..\..")).Path

if (-not $CurrentWindow) {
    if (-not (Get-Command wsl.exe -ErrorAction SilentlyContinue)) {
        throw "Nie znaleziono wsl.exe. Uruchom panel bezposrednio z WSL: bash ./scripts/start.sh"
    }

    $wslProjectRoot = (& wsl.exe wslpath -u -a $projectRoot).Trim()
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($wslProjectRoot)) {
        throw "Nie udalo sie przeliczyc sciezki projektu na format WSL."
    }

    if (Get-Command wt.exe -ErrorAction SilentlyContinue) {
        $wtArguments = @(
            "new-window",
            "--title",
            '"Mini Order Platform Control Center"',
            "wsl.exe",
            "--cd",
            ('"' + $wslProjectRoot + '"'),
            "bash",
            "./scripts/control-center/control-center.sh"
        ) + $ControlCenterArguments

        Start-Process wt.exe -ArgumentList $wtArguments | Out-Null
        Write-Host "Uruchomiono Control Center w Windows Terminal (WSL)."
        exit 0
    }

    $wslArguments = @(
        "--cd",
        ('"' + $wslProjectRoot + '"'),
        "bash",
        "./scripts/control-center/control-center.sh"
    ) + $ControlCenterArguments
    Start-Process wsl.exe -ArgumentList $wslArguments | Out-Null
    Write-Host "Windows Terminal nie jest dostepny; uruchomiono awaryjne okno terminala WSL."
    exit 0
}

if (-not (Get-Command wsl.exe -ErrorAction SilentlyContinue)) {
    throw "Nie znaleziono wsl.exe. Na Linux/macOS uruchom bezposrednio: bash scripts/control-center/control-center.sh"
}

$wslProjectRoot = (& wsl.exe wslpath -u -a $projectRoot).Trim()
if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($wslProjectRoot)) {
    throw "Nie udalo sie przeliczyc sciezki projektu na format WSL."
}

& wsl.exe --cd $wslProjectRoot bash ./scripts/control-center/control-center.sh @ControlCenterArguments
exit $LASTEXITCODE
