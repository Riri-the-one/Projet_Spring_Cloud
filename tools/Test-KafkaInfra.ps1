param([switch]$Recreate)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$compose = @('compose', '-f', (Join-Path $projectRoot 'infra/docker-compose.yml'),
    '-f', (Join-Path $projectRoot 'infra/docker-compose.kafka.yml'), '--profile', 'kafka')
function Docker-Checked([string[]]$Arguments) {
    & docker @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Commande Docker en echec (code $LASTEXITCODE)" }
}
Docker-Checked ($compose + @('config', '--quiet'))
Docker-Checked ($compose + @('up', '-d', '--no-deps', '--wait', '--wait-timeout', '240', 'kafka'))
Docker-Checked ($compose + @('exec', '-T', 'kafka', 'bash', '/opt/ticketflow-kafka/init-topics.sh'))
$run = [Guid]::NewGuid().ToString('N')
$output = @(& docker @compose run --rm --no-deps kafka-client write $run)
if ($LASTEXITCODE -ne 0) { throw 'Echec du client Kafka sur le reseau Docker' }
$output | ForEach-Object { Write-Host $_ }
$marker = @($output | Where-Object { $_ -match "^PROBE $run OFFSET ([0-9]+)$" })
if ($marker.Count -ne 1) { throw 'Offset du temoin Kafka introuvable' }
$offset = ($marker[0] -split ' ')[3]
$container = (& docker @compose ps -q kafka).Trim()
if ($LASTEXITCODE -ne 0 -or -not $container) { throw 'Conteneur Kafka introuvable' }
$details = (& docker inspect $container | ConvertFrom-Json)[0]
$mount = @($details.Mounts | Where-Object { $_.Type -eq 'volume' -and $_.Destination -eq '/var/lib/kafka/data' })
if ($mount.Count -ne 1) { throw 'Volume Kafka persistant introuvable' }
$volumeName = $mount[0].Name
$bindings = @($details.NetworkSettings.Ports.'29092/tcp')
if ($bindings.Count -ne 1 -or $bindings[0].HostIp -ne '127.0.0.1') { throw 'Listener hote non limite a loopback' }
foreach ($brokerPort in @('9092/tcp','9093/tcp')) {
    if ($details.NetworkSettings.Ports.$brokerPort) { throw 'Port interne Kafka publie' }
}
$hostPort = [int]$bindings[0].HostPort
if ($Recreate) {
    Docker-Checked ($compose + @('up', '-d', '--no-deps', '--force-recreate', '--wait', '--wait-timeout', '240', 'kafka'))
    $replacement = (& docker @compose ps -q kafka).Trim()
    if ($replacement -eq $container) { throw 'Un nouveau conteneur etait attendu' }
    $newDetails = (& docker inspect $replacement | ConvertFrom-Json)[0]
    if (-not @($newDetails.Mounts | Where-Object { $_.Name -eq $volumeName -and $_.Destination -eq '/var/lib/kafka/data' })) {
        throw 'Le volume Kafka a change'
    }
} else {
    Docker-Checked ($compose + @('restart', '--timeout', '40', 'kafka'))
    Docker-Checked ($compose + @('up', '-d', '--no-deps', '--wait', '--wait-timeout', '240', 'kafka'))
}
Docker-Checked ($compose + @('run', '--rm', '--no-deps', 'kafka-client', 'verify', $run, $offset))
Docker-Checked ($compose + @('run', '--rm', '--no-deps', 'kafka-client', 'write', [Guid]::NewGuid().ToString('N')))
& (Join-Path $PSScriptRoot 'Test-KafkaHost.ps1') -Port $hostPort
Write-Host "OK Kafka : topics, messages, volume $volumeName et listener hote verifies ; aucun volume supprime."
