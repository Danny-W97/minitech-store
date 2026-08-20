[CmdletBinding()]
param(
    [string[]]$Services = @("api-gateway", "product-service", "order-service", "inventory-service", "payment-service", "notification-service", "customer-service", "delivery-service", "promotion-service", "wishlist-service", "review-service", "recommendation-service", "chat-service", "returns-service", "billing-service"),
    [ValidateSet("dev", "test")]
    [string]$Profile = "dev",
    [switch]$SkipInfrastructure,
    [switch]$NoExit
)

$ErrorActionPreference = "Stop"
$projectRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot "..")).Path
$runtimeDirectory = Join-Path $projectRoot "gitIgnoreOwn\runtime"
$runtimeFile = Join-Path $runtimeDirectory "dev-processes.json"
$knownServices = @("api-gateway", "product-service", "order-service", "inventory-service", "payment-service", "notification-service", "customer-service", "delivery-service", "promotion-service", "wishlist-service", "review-service", "recommendation-service", "chat-service", "returns-service", "billing-service")

foreach ($service in $Services) {
    if ($knownServices -notcontains $service) {
        throw "Nieznany serwis: $service. Dozwolone: $($knownServices -join ', ')"
    }
}

$activeProfileFile = Join-Path $runtimeDirectory "active-profile"
New-Item -ItemType Directory -Path $runtimeDirectory -Force | Out-Null
[System.IO.File]::WriteAllText(
    $activeProfileFile,
    "$Profile`n",
    [System.Text.UTF8Encoding]::new($false)
)

if (Test-Path -LiteralPath $runtimeFile -PathType Leaf) {
    throw "Rejestr procesów już istnieje: $runtimeFile. Uruchom scripts\stop-dev.ps1 przed ponownym startem."
}

if (-not $SkipInfrastructure) {
    if (-not (Get-Command docker.exe -ErrorAction SilentlyContinue)) {
        throw "Nie znaleziono docker.exe. Uruchom Docker Desktop albo użyj -SkipInfrastructure, jeśli bazy już działają."
    }
    & docker.exe compose --project-directory $projectRoot -f (Join-Path $projectRoot "compose.yaml") up -d
    if ($LASTEXITCODE -ne 0) {
        throw "Nie udało się uruchomić baz PostgreSQL przez Docker Compose."
    }
}

New-Item -ItemType Directory -Path $runtimeDirectory -Force | Out-Null
$processRecords = @()
$mavenCommand = Join-Path $projectRoot "mvnw.cmd"

foreach ($service in $Services) {
    $escapedRoot = $projectRoot.Replace("'", "''")
    $escapedMaven = $mavenCommand.Replace("'", "''")
    $command = "Set-Location -LiteralPath '$escapedRoot'; `$env:SPRING_PROFILES_ACTIVE='$Profile'; & '$escapedMaven' -pl $service spring-boot:run"
    $arguments = @("-NoProfile")
    if ($NoExit) {
        $arguments += "-NoExit"
    }
    $arguments += @("-Command", $command)

    $process = Start-Process powershell.exe -ArgumentList $arguments -WorkingDirectory $projectRoot -PassThru
    $processRecords += [pscustomobject]@{
        Service = $service
        ProcessId = $process.Id
        StartedAt = (Get-Date).ToUniversalTime().ToString("o")
    }
    Write-Host "Uruchomiono $service (PID okna: $($process.Id))."
}

$processRecords | ConvertTo-Json -Depth 3 | Set-Content -LiteralPath $runtimeFile -Encoding UTF8
Write-Host "Bazy: PostgreSQL przez compose.yaml."
Write-Host "Profil Spring: $Profile"
Write-Host "Mailpit SMTP: 127.0.0.1:11025, panel: http://127.0.0.1:18025"
Write-Host "Rejestr procesów: $runtimeFile"
Write-Host "Użyj scripts\stop-dev.ps1, aby zatrzymać dokładnie uruchomione okna aplikacji."
