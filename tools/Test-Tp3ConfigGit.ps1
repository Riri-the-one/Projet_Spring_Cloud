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
$environment=Invoke-RestMethod -Uri "$ConfigServer/customer-service/default/$Label" -TimeoutSec 15
if($environment.version-ne $sha){throw 'Config Server does not serve the verified Git commit (native mode or stale/different repository)'}
foreach($source in $environment.propertySources){foreach($property in $source.source.PSObject.Properties){
    if($property.Name-match '(?i)password|secret|private.?key|access.?token'){
        $value=[string]$property.Value
        if($value-and -not $value.StartsWith('${')){throw 'Non-placeholder sensitive property served; inspect privately, value not displayed'}
    }
}}
Write-Output "OK real Git label/commit served: $sha. Sensitive served properties checked without printing values."
if($RefreshCustomer){
    if(-not $env:TICKETFLOW_DEMO_ACCESS_TOKEN){throw 'A real admin JWT is required via TICKETFLOW_DEMO_ACCESS_TOKEN'}
    $null=Invoke-RestMethod -Method Post -Uri "$Customer/actuator/refresh" -Headers @{Authorization='Bearer '+$env:TICKETFLOW_DEMO_ACCESS_TOKEN} -TimeoutSec 15
    Write-Output 'OK authorized Customer refresh. No external Git file was changed.'
}
