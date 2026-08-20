[CmdletBinding(SupportsShouldProcess = $true)]
param()

$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$runtimeFile = Join-Path $projectRoot "gitIgnoreOwn\runtime\dev-processes.json"
$legacyRuntimeFile = Join-Path $projectRoot ".mini-order-platform\dev-processes.json"

if (-not (Test-Path -LiteralPath $runtimeFile -PathType Leaf) -and
    (Test-Path -LiteralPath $legacyRuntimeFile -PathType Leaf)) {
    $runtimeFile = $legacyRuntimeFile
}

if (-not (Test-Path -LiteralPath $runtimeFile -PathType Leaf)) {
    Write-Host "Brak rejestru uruchomionych procesów: $runtimeFile"
    Write-Host "Bazy PostgreSQL nie są zatrzymywane."
    exit 0
}

$records = @(Get-Content -LiteralPath $runtimeFile -Raw -Encoding UTF8 | ConvertFrom-Json)
$processTable = @{}
Get-CimInstance Win32_Process | ForEach-Object { $processTable[[int]$_.ProcessId] = $_ }

function Get-DescendantProcessIds {
    param([int]$ParentId)

    $children = @($processTable.Values | Where-Object { [int]$_.ParentProcessId -eq $ParentId })
    foreach ($child in $children) {
        Get-DescendantProcessIds -ParentId ([int]$child.ProcessId)
        Write-Output ([int]$child.ProcessId)
    }
}

$processIds = @()
foreach ($record in $records) {
    $rootPid = [int]$record.ProcessId
    if ($processTable.ContainsKey($rootPid)) {
        $processIds += Get-DescendantProcessIds -ParentId $rootPid
        $processIds += $rootPid
    }
}

foreach ($processId in ($processIds | Sort-Object -Descending -Unique)) {
    $process = Get-Process -Id $processId -ErrorAction SilentlyContinue
    if ($null -ne $process -and $PSCmdlet.ShouldProcess("PID $processId ($($process.ProcessName))", "Zatrzymaj proces")) {
        Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
        Write-Host "Zatrzymano PID $processId ($($process.ProcessName))."
    }
}

Remove-Item -LiteralPath $runtimeFile -Force
Write-Host "Aplikacje dev zostały zatrzymane. Kontenery baz PostgreSQL pozostają uruchomione."
