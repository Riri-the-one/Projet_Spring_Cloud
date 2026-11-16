param([ValidateRange(1,65535)][int]$Port=5433)
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$names = @('TICKET_DATABASE_URL','TICKET_DATABASE_SECRET_FILE','TICKET_TEST_SCHEMA','TICKET_TEST_MARKER')
$previous = @{}
foreach($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name) }
try {
    $env:TICKET_DATABASE_URL = "jdbc:postgresql://127.0.0.1:$Port/ticketflow_ticket"
    $env:TICKET_DATABASE_SECRET_FILE = "$root/infra/postgresql/.local/ticket.password"
    $env:TICKET_TEST_SCHEMA = 'ticket_test_' + [Guid]::NewGuid().ToString('N')
    $directory = Join-Path $root 'target/arij-validation'
    $null = New-Item -ItemType Directory -Force $directory
    $env:TICKET_TEST_MARKER = Join-Path $directory ($env:TICKET_TEST_SCHEMA + '.properties')
    $wrapper = if ($env:OS -eq 'Windows_NT') { "$root/mvnw.cmd" } else { "$root/mvnw" }
    & $wrapper -B -ntp -f "$root/services/ticket-service/pom.xml" -Ppostgresql-it verify
    if ($LASTEXITCODE -ne 0) { throw 'Ticket PostgreSQL tests failed' }
    $compose = @('compose','-f',"$root/infra/docker-compose.yml",'-f',"$root/infra/docker-compose.postgresql.yml",
        '-f',"$root/infra/docker-compose.ticket.yml",'-f',"$root/infra/docker-compose.ticket-postgresql.yml",'--profile','postgresql','--profile','ticket')
    & docker @compose restart --timeout 30 postgres
    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL restart failed' }
    & docker @compose up -d --no-deps --wait --wait-timeout 180 postgres
    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL recovery failed' }
    & $wrapper -B -ntp -f "$root/services/ticket-service/pom.xml" -Ppostgresql-it '-Dit.test=TicketPostgresqlPersistenceIT' verify
    if ($LASTEXITCODE -ne 0) { throw 'Ticket persistence/replay after PostgreSQL and JVM restart failed' }
    Write-Host 'OK Ticket codes/IDs/idempotence persisted; only isolated test schema populated, runtime public schema untouched.'
} finally { foreach($name in $names) { [Environment]::SetEnvironmentVariable($name,$previous[$name]) } }
