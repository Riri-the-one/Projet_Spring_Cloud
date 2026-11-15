param([ValidateRange(1,65535)][int]$Port = 5433)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$previousUrl = $env:CUSTOMER_DATABASE_URL
$previousSecret = $env:CUSTOMER_DATABASE_SECRET_FILE
try {
    $env:CUSTOMER_DATABASE_URL = "jdbc:postgresql://127.0.0.1:$Port/ticketflow_customer"
    $env:CUSTOMER_DATABASE_SECRET_FILE = Join-Path $projectRoot 'infra/postgresql/.local/customer.password'
    if (-not (Test-Path -LiteralPath $env:CUSTOMER_DATABASE_SECRET_FILE)) { throw 'Customer local secret missing; run Initialize-PostgresqlDev.ps1 first' }
    $wrapper = if ($env:OS -eq 'Windows_NT') { Join-Path $projectRoot 'mvnw.cmd' } else { Join-Path $projectRoot 'mvnw' }
    & $wrapper -B -ntp -f (Join-Path $projectRoot 'pom.xml') -pl services/customer-service -am -Ppostgresql-it verify
    if ($LASTEXITCODE -ne 0) { throw 'Customer PostgreSQL integration tests failed' }
} finally {
    $env:CUSTOMER_DATABASE_URL = $previousUrl
    $env:CUSTOMER_DATABASE_SECRET_FILE = $previousSecret
}
