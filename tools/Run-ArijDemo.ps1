param([switch]$Build,[switch]$Showcase,[switch]$TechnicalTests,[switch]$FailureTests)
$ErrorActionPreference='Stop'
$root=(Resolve-Path (Join-Path $PSScriptRoot '..')).Path
function Docker-Checked([string[]]$Arguments) {
    & docker @Arguments
    if($LASTEXITCODE-ne 0){throw "Docker failed (exit $LASTEXITCODE); no destructive repair attempted"}
}
function Script-Checked([string]$Name) {
    & (Join-Path $PSScriptRoot $Name)
    if(-not $?){throw "PowerShell verification failed: $Name"}
}
function Node-Checked([string[]]$Arguments) {
    & node @Arguments
    if($LASTEXITCODE-ne 0){throw 'OIDC/HTTP verification failed; tokens are never printed'}
}
Push-Location $root
try {
    Script-Checked 'Initialize-TicketPostgresqlDev.ps1'
    Script-Checked 'Initialize-NotificationPostgresqlDev.ps1'
    $compose=@('compose','-f',"$root/infra/docker-compose.yml")
    foreach($overlay in @('postgresql','customer-postgresql','kafka','ticket','ticket-postgresql','notification')) {
        $compose+=@('-f',"$root/infra/docker-compose.$overlay.yml")
    }
    foreach($profile in @('postgresql','kafka','ticket','notification')){$compose+=@('--profile',$profile)}
    Docker-Checked ($compose+@('config','--quiet'))
    # Same existing cluster and volume; bootstrap scripts do NOT rerun on existing data.
    Docker-Checked ($compose+@('up','-d','--no-deps','--wait','--wait-timeout','180','postgres'))
    foreach($domain in @('ticket','notification')) {
        Docker-Checked ($compose+@('exec','-T','postgres','bash',"/opt/ticketflow-postgresql/provision-$domain.sh"))
    }
    Docker-Checked ($compose+@('up','-d','--no-deps','--wait','--wait-timeout','240','kafka'))
    Docker-Checked ($compose+@('exec','-T','kafka','bash','/opt/ticketflow-kafka/init-topics.sh'))
    if($Build){
        # Keep memory usage bounded on a university laptop running the complete stack.
        foreach($service in @('customer-service','api-gateway','ticket-service','notification-service')) {
            Docker-Checked ($compose+@('build',$service))
        }
    }
    Docker-Checked ($compose+@('up','-d','--wait','--wait-timeout','300'))
    Script-Checked 'Test-ArijQuality.ps1'
    if($Showcase) {
        Write-Host 'Demonstration: real OIDC, Customer, catalogue, PENDING booking/cancellation. NO confirmed payment.'
        Node-Checked @('tools/Test-KeycloakIntegration.mjs')
        Node-Checked @('tools/Test-KeycloakIntegration.mjs','--ticket-gateway')
        Node-Checked @('tools/Test-NotificationHttp.mjs')
        Node-Checked @('tools/Test-NotificationHttp.mjs','--gateway')
    }
    if($FailureTests) {
        foreach($service in @('notification-service','kafka')) {
            Write-Host "Failure scenario: existing Customer/catalogue/PENDING booking while $service is stopped. No payment confirmation."
            try {
                Docker-Checked ($compose+@('stop','--timeout','30',$service))
                Node-Checked @('tools/Test-KeycloakIntegration.mjs')
            } finally {
                Docker-Checked ($compose+@('up','-d','--no-deps','--wait','--wait-timeout','300',$service))
            }
        }
        Script-Checked 'Test-ArijQuality.ps1'
    }
    if($TechnicalTests) {
        Write-Host 'Isolated technical fixtures only. PostgreSQL/Kafka may restart; do not run during business writes.'
        Script-Checked 'Test-ArijH2Profiles.ps1'
        Script-Checked 'Test-KafkaInfra.ps1'
        Script-Checked 'Test-CustomerPostgresql.ps1'
        Script-Checked 'Test-TicketPostgresql.ps1'
        Script-Checked 'Test-NotificationInfra.ps1'
        # Restore the complete set of mounts/overlays after component-specific verification.
        Docker-Checked ($compose+@('up','-d','--wait','--wait-timeout','300'))
        Script-Checked 'Test-ArijQuality.ps1'
    }
    Write-Host 'Arij demo ready. Volumes and existing credentials preserved. Business integration still requires accepted contracts.'
} finally { Pop-Location }
