param()
$ErrorActionPreference='Stop'
foreach($domain in @('ticket','notification')) {
    $name='ticketflow-arij-h2-'+$domain+'-'+[Guid]::NewGuid().ToString('N').Substring(0,8)
    $port=if($domain-eq 'ticket'){8084}else{8085}
    # Real Config Server, but no secret, published port or volume. Never registers in Eureka.
    & docker run --rm -d --name $name --network ticketflow-arij_default -e SPRING_PROFILES_ACTIVE=demo-h2 -e CONFIG_SERVER_URL=http://config-server:8888 -e EUREKA_CLIENT_ENABLED=false -e 'JAVA_TOOL_OPTIONS=-Xms64m -Xmx256m' "ticketflow-arij-$domain-service"
    if($LASTEXITCODE-ne 0){throw "Cannot start isolated H2 $domain verification"}
    try {
        $healthy=$false
        for($attempt=0;$attempt-lt 45;$attempt++) {
            $running=@(& docker ps -q --filter "name=^/$name`$")
            if($LASTEXITCODE-ne 0-or $running.Count-ne 1){throw "Isolated H2 $domain container exited during startup"}
            $health=@(& docker exec $name curl --fail --silent --max-time 2 "http://localhost:$port/actuator/health")
            if($LASTEXITCODE-eq 0-and (($health-join '')|ConvertFrom-Json).status-eq 'UP'){$healthy=$true;break}
            Start-Sleep -Seconds 2
        }
        if(-not $healthy){throw "Isolated $domain H2 health check failed"}
        $status=(& docker exec $name curl --silent --output /dev/null --write-out '%{http_code}' "http://localhost:$port/api/${domain}s/me")
        if($LASTEXITCODE-ne 0-or $status-ne '401'){throw "H2 $domain unexpectedly permits private access"}
        Write-Host "OK $domain H2: healthy without PostgreSQL credentials/volume; private endpoint returns 401"
    } finally {
        $running=@(& docker ps -q --filter "name=^/$name`$")
        if($running.Count-eq 1) {
            & docker stop --timeout 15 $name
            if($LASTEXITCODE-ne 0){throw "Could not stop isolated H2 test container $name"}
        }
    }
}
