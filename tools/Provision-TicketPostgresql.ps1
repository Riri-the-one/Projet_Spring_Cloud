param()
$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
& (Join-Path $PSScriptRoot 'Initialize-TicketPostgresqlDev.ps1')
$compose = @('compose','-f',"$root/infra/docker-compose.yml",'-f',"$root/infra/docker-compose.postgresql.yml",
    '-f',"$root/infra/docker-compose.ticket.yml",'-f',"$root/infra/docker-compose.ticket-postgresql.yml",'--profile','postgresql','--profile','ticket')
& docker @compose config --quiet
if ($LASTEXITCODE -ne 0) { throw 'Invalid Ticket PostgreSQL Compose configuration' }
# Adding the read-only Ticket secret can recreate only postgres, with its existing named volume.
& docker @compose up -d --no-deps --wait --wait-timeout 180 postgres
if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL unavailable; no provisioning attempted' }
& docker @compose exec -T postgres bash /opt/ticketflow-postgresql/provision-ticket.sh
if ($LASTEXITCODE -ne 0) { throw 'Ticket domain provisioning refused; inspect state without deleting or rotating anything' }
& docker @compose exec -T postgres bash /opt/ticketflow-postgresql/audit-ticket.sh
if ($LASTEXITCODE -ne 0) { throw 'Ticket domain isolation/authentication check failed' }
