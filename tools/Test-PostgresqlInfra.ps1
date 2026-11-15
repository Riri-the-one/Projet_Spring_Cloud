param([switch]$Recreate)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$environmentFile = Join-Path $projectRoot 'infra/postgresql/.env.local'
if (-not (Test-Path -LiteralPath $environmentFile)) {
    throw 'Executer tools/Initialize-PostgresqlDev.ps1 avant ce test.'
}
$compose = @('compose', '--env-file', $environmentFile, '-f', (Join-Path $projectRoot 'infra/docker-compose.yml'),
    '-f', (Join-Path $projectRoot 'infra/docker-compose.postgresql.yml'), '--profile', 'postgresql')

function Docker-Checked([string[]]$Arguments) {
    & docker @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Commande Docker en echec (code $LASTEXITCODE)" }
}

function Audit {
    $output = @(& docker @compose exec -T postgres bash /opt/ticketflow-postgresql/audit.sh)
    if ($LASTEXITCODE -ne 0) { throw 'Echec authentification/isolation PostgreSQL' }
    $output | ForEach-Object { Write-Host $_ }
    return @($output | Where-Object { $_ -match '^MARKER (customer|event|booking) [a-f0-9]{32}$' } | Sort-Object)
}

Docker-Checked ($compose + @('config', '--quiet'))
Docker-Checked ($compose + @('up', '-d', '--no-deps', '--wait', '--wait-timeout', '180', 'postgres'))
$before = @(Audit)
if ($before.Count -ne 3) { throw 'Trois marqueurs de persistance attendus' }
$container = (& docker @compose ps -q postgres).Trim()
if ($LASTEXITCODE -ne 0 -or -not $container) { throw 'Conteneur PostgreSQL introuvable' }
$details = (& docker inspect $container | ConvertFrom-Json)[0]
$mount = @($details.Mounts | Where-Object { $_.Type -eq 'volume' -and $_.Destination -eq '/var/lib/postgresql' })
if ($mount.Count -ne 1) { throw 'Volume PostgreSQL 18 persistant introuvable au bon point de montage' }
$volumeName = $mount[0].Name
Write-Host "OK volume dedie : $volumeName"
foreach ($binding in $details.NetworkSettings.Ports.'5432/tcp') {
    if ($binding.HostIp -ne '127.0.0.1') { throw 'Port PostgreSQL publie hors loopback' }
}
if ($Recreate) {
    Docker-Checked ($compose + @('up', '-d', '--no-deps', '--force-recreate', '--wait', '--wait-timeout', '180', 'postgres'))
    $replacement = (& docker @compose ps -q postgres).Trim()
    if ($replacement -eq $container) { throw 'Le test demandait un nouveau conteneur' }
    $newDetails = (& docker inspect $replacement | ConvertFrom-Json)[0]
    if (-not @($newDetails.Mounts | Where-Object { $_.Name -eq $volumeName -and $_.Destination -eq '/var/lib/postgresql' })) {
        throw 'Le nouveau conteneur ne conserve pas le meme volume'
    }
} else {
    Docker-Checked ($compose + @('restart', '--timeout', '30', 'postgres'))
    Docker-Checked ($compose + @('up', '-d', '--no-deps', '--wait', '--wait-timeout', '180', 'postgres'))
}
$after = @(Audit)
if (@(Compare-Object -ReferenceObject $before -DifferenceObject $after).Count -ne 0) {
    throw 'Les marqueurs ont change : persistance non validee'
}
Write-Host 'OK donnees conservees ; trois domaines isoles ; aucun volume supprime.'
