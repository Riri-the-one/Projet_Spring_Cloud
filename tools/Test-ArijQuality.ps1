param()
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$ids=@(docker ps -q --filter 'label=com.docker.compose.project=ticketflow-arij')
if($LASTEXITCODE-ne 0-or $ids.Count-eq 0){throw 'TicketFlow Docker project unavailable'}
$containers=ConvertFrom-Json -InputObject ((docker inspect @ids) -join "`n")
if($LASTEXITCODE-ne 0){throw 'Docker inspection failed'}
$required=@('config-server','discovery-server','api-gateway','keycloak','customer-service','event-service','booking-service','ticket-service','notification-service','postgres','kafka')
foreach($service in $required){
    $matches=@($containers | Where-Object {$_.Config.Labels.'com.docker.compose.service'-eq $service})
    if($matches.Count-ne 1-or $matches[0].State.Health.Status-ne 'healthy'){throw "Service not healthy: $service"}
    Write-Host "OK healthy $service"
}
foreach($container in $containers){foreach($property in $container.NetworkSettings.Ports.PSObject.Properties){foreach($binding in $property.Value){
    if($binding.HostIp-notin @('127.0.0.1','::1')){throw 'A TicketFlow port is exposed beyond loopback'}
}}}
Write-Host 'OK all published ports are loopback only'
$volumes=@($containers | ForEach-Object {$_.Mounts} | Where-Object {$_.Type-eq 'volume'} | Select-Object -ExpandProperty Name -Unique)
$volumes | ForEach-Object {Write-Host "OK retained named volume $_"}
foreach($port in @(8080,8081,8084,8085,8888,8761)){
    $health=Invoke-RestMethod -Uri "http://localhost:$port/actuator/health" -TimeoutSec 10
    if($health.status-ne 'UP'){throw "Unhealthy HTTP endpoint $port"}
}
& docker exec ticketflow-arij-postgres-1 bash /opt/ticketflow-postgresql/audit-arij-readonly.sh
if($LASTEXITCODE-ne 0){throw 'Read-only five-domain audit failed; ensure the corresponding secret mounts are present'}
$topics=@(docker exec -e 'KAFKA_HEAP_OPTS=-Xms32m -Xmx128m' ticketflow-arij-kafka-1 /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --list)
if($LASTEXITCODE-ne 0){throw 'Kafka topic listing failed'}
foreach($topic in @('ticketflow.event.events.v1','ticketflow.booking.events.v1','ticketflow.payment.events.v1','ticketflow.ticket.events.v1')){
    if($topics-notcontains $topic){throw "Missing business topic: $topic"}
}
Write-Host 'OK four business topics exist; no message published by this audit'
& node (Join-Path $root 'tools/Test-KeycloakIntegration.mjs') --auth-only
if($LASTEXITCODE-ne 0){throw 'Real OIDC authentication failed'}
Write-Host 'OK read-only Arij quality smoke: Docker, ports, volumes, health, domain isolation, Kafka and OIDC'
