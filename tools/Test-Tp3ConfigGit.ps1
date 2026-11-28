param([string]$RepositoryUri=$env:CONFIG_GIT_URI,[string]$Label='main',[string]$ConfigServer='http://localhost:8888',[switch]$RefreshCustomer,[string]$Customer='http://localhost:8081')
$ErrorActionPreference='Stop'
if([string]::IsNullOrWhiteSpace($RepositoryUri)) {
    Write-Output 'BLOCKED: CONFIG_GIT_URI absent. Aucun depot invente, clone ou cree. Le TP3 Git ne peut pas etre valide.'
    exit 2
}
$remote=[Uri]$RepositoryUri
if($remote.Scheme-ne 'https'-or $remote.UserInfo-or $remote.Query-or $remote.Fragment){throw 'Use a real HTTPS Git URI without embedded credentials'}
if($Label-notmatch '^[A-Za-z0-9_./-]+$'-or $Label.Contains('..')){throw 'Invalid Git label'}
foreach($address in @($ConfigServer,$Customer)) {
    $u=[Uri]$address
    if($u.Scheme-notin @('http','https')-or $u.Host-notin @('localhost','127.0.0.1')){throw 'Verification endpoints must be loopback'}
}
$refs=@(git ls-remote --exit-code --heads $RepositoryUri "refs/heads/$Label")
if($LASTEXITCODE-ne 0-or $refs.Count-ne 1){throw 'Real repository/ref inaccessible; no local substitute is accepted'}
$sha=($refs[0]-split '\s+')[0]
foreach($path in @('customer-service/default','api-gateway/default','customer-service/dev')) {
$environment=Invoke-RestMethod -Uri "$ConfigServer/$path/$Label" -TimeoutSec 15
if($environment.version-ne $sha -or $environment.label-ne $Label){throw 'Config Server does not serve the verified Git commit/label (native mode or stale/different repository)'}
$app=($path-split '/')[0]
foreach($file in @('application.yml',"$app.yml")) {
    if(-not @($environment.propertySources.name | Where-Object {$_ -eq "$($RepositoryUri.TrimEnd('/'))/$file"}).Count){throw "Missing expected Git source: $file"}
}
$effective=@{}
foreach($source in @($environment.propertySources)[($environment.propertySources.Count-1)..0]) {
    foreach($property in $source.source.PSObject.Properties){$effective[$property.Name]=$property.Value}
}
if($path-eq 'customer-service/default' -and $effective['ticketflow.message']-ne 'Bienvenue dans TicketFlow - configuration v2'){throw 'Unexpected default message'}
if($path-eq 'customer-service/dev') {
    if($effective['ticketflow.message']-ne 'TicketFlow - profil dev'){throw 'Dev override missing'}
    if(-not @($environment.propertySources.name | Where-Object {$_-eq "$($RepositoryUri.TrimEnd('/'))/customer-service-dev.yml"}).Count){throw 'Dev source missing'}
}
if($path-eq 'api-gateway/default' -and ($effective['spring.cloud.gateway.server.webflux.routes[0].id']-ne 'customer-service' -or $effective['server.port']-ne 8080)){throw 'Unexpected TP3 Gateway properties'}
foreach($source in $environment.propertySources){foreach($property in $source.source.PSObject.Properties){
    if($property.Name-match '(?i)password|secret|private.?key|access.?token'){
        $value=[string]$property.Value
        if($value-and -not $value.StartsWith('${')){throw 'Non-placeholder sensitive property served; inspect privately, value not displayed'}
    }
}}
Write-Output "OK real Git label/commit served: $sha. Sensitive served properties checked without printing values."
Write-Output "OK $path sources and expected properties."
}
if($RefreshCustomer){
    if(-not $env:TICKETFLOW_DEMO_ACCESS_TOKEN){throw 'A real admin JWT is required via TICKETFLOW_DEMO_ACCESS_TOKEN'}
    $null=Invoke-RestMethod -Method Post -ContentType application/json -Body '{}' -Uri "$Customer/actuator/refresh" -Headers @{Authorization='Bearer '+$env:TICKETFLOW_DEMO_ACCESS_TOKEN} -TimeoutSec 15
    Write-Output 'OK authorized Customer refresh HTTP call ONLY; this does not prove a dynamic v1-to-v2 transition. No external Git file was changed.'
}
