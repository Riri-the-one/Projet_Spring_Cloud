param()
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
& "$PSScriptRoot/Initialize-NotificationPostgresqlDev.ps1"
$compose=@('compose','-f',"$root/infra/docker-compose.yml",'-f',"$root/infra/docker-compose.postgresql.yml",'-f',"$root/infra/docker-compose.notification.yml")
if(Test-Path -LiteralPath "$root/infra/postgresql/.local/ticket.password") {
    $compose+=@('-f',"$root/infra/docker-compose.notification-ticket-secret.yml")
}
$compose+=@('--profile','postgresql','--profile','notification')
& docker @compose config --quiet
if($LASTEXITCODE-ne 0){throw 'Invalid Notification Compose'}
& docker @compose up -d --no-deps --wait --wait-timeout 180 postgres
if($LASTEXITCODE-ne 0){throw 'PostgreSQL unavailable'}
& docker @compose exec -T postgres bash /opt/ticketflow-postgresql/provision-notification.sh
if($LASTEXITCODE-ne 0){throw 'Notification provisioning refused; no destructive repair attempted'}
& docker @compose exec -T postgres bash /opt/ticketflow-postgresql/audit-notification.sh
if($LASTEXITCODE-ne 0){throw 'Notification isolation check failed'}
