param([int]$PostgresPort=5433,[int]$KafkaPort=29092)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
& "$PSScriptRoot/Provision-NotificationPostgresql.ps1"
$k=@('compose','-f',"$root/infra/docker-compose.yml",'-f',"$root/infra/docker-compose.kafka.yml",'--profile','kafka')
& docker @k up -d --no-deps --wait --wait-timeout 240 kafka
if($LASTEXITCODE-ne 0){throw 'Kafka unavailable'}
& docker @k exec -T kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --create --if-not-exists --topic ticketflow.infra.notification.probe.v1 --partitions 1 --replication-factor 1 --config retention.ms=604800000
if($LASTEXITCODE-ne 0){throw 'Notification technical topic creation failed'}
$names=@('NOTIFICATION_DATABASE_URL','NOTIFICATION_SECRET_FILE','NOTIFICATION_TEST_SCHEMA','NOTIFICATION_TEST_MARKER','NOTIFICATION_KAFKA_BOOTSTRAP');$previous=@{}
foreach($name in $names){$previous[$name]=[Environment]::GetEnvironmentVariable($name)}
try{
    $env:NOTIFICATION_DATABASE_URL="jdbc:postgresql://127.0.0.1:$PostgresPort/ticketflow_notification"
    $env:NOTIFICATION_SECRET_FILE="$root/infra/postgresql/.local/notification.password"
    $env:NOTIFICATION_TEST_SCHEMA='notification_test_'+[Guid]::NewGuid().ToString('N')
    $null=New-Item -ItemType Directory -Force "$root/target/arij-validation"
    $env:NOTIFICATION_TEST_MARKER="$root/target/arij-validation/$env:NOTIFICATION_TEST_SCHEMA.properties"
    $env:NOTIFICATION_KAFKA_BOOTSTRAP="127.0.0.1:$KafkaPort"
    $wrapper=if($env:OS-eq 'Windows_NT'){"$root/mvnw.cmd"}else{"$root/mvnw"}
    & $wrapper -B -ntp -f "$root/services/notification-service/pom.xml" -Pnotification-it verify
    if($LASTEXITCODE-ne 0){throw 'Notification PostgreSQL/Kafka integration failed'}
    $p=@('compose','-f',"$root/infra/docker-compose.yml",'-f',"$root/infra/docker-compose.postgresql.yml",'-f',"$root/infra/docker-compose.notification.yml")
    if(Test-Path -LiteralPath "$root/infra/postgresql/.local/ticket.password"){$p+=@('-f',"$root/infra/docker-compose.ticket.yml",'-f',"$root/infra/docker-compose.ticket-postgresql.yml")}
    $p+=@('--profile','postgresql','--profile','notification')
    & docker @p restart --timeout 30 postgres
    if($LASTEXITCODE-ne 0){throw 'PostgreSQL restart failed'}
    & docker @p up -d --no-deps --wait --wait-timeout 180 postgres
    if($LASTEXITCODE-ne 0){throw 'PostgreSQL recovery failed'}
    & $wrapper -B -ntp -f "$root/services/notification-service/pom.xml" -Pnotification-it '-Dit.test=NotificationPersistenceIT' verify
    if($LASTEXITCODE-ne 0){throw 'Notification persistence/recovery failed'}
    Write-Host 'OK Notification Kafka/PostgreSQL: isolated technical fixtures, retries, quarantine and durable resumption.'
}finally{foreach($name in $names){[Environment]::SetEnvironmentVariable($name,$previous[$name])}}
