param(
    [string]$HistorySource='target/tp3-audit/external',
    [int]$ConfigPort=18890,
    [int]$ClientPort=18092
)
$ErrorActionPreference='Stop'
# Only a new disposable clone is advanced; never change the supplied source or GitHub.
if($ConfigPort -lt 10000 -or $ClientPort -lt 10000 -or $ConfigPort-eq $ClientPort){throw 'Use distinct isolated ports >= 10000'}
$root=(Resolve-Path "$PSScriptRoot/..").Path
Set-Location $root
$source=(Resolve-Path $HistorySource).Path
$v1='da70d329e25ae1816c324fdfe82c085937bbed8a'
$v2='1e91958792b8941687a73bf00ada35f12aab951d'
$work=Join-Path $root ('target/tp3-refresh-'+[Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory $work | Out-Null
$java=Join-Path $env:JAVA_HOME 'bin/java.exe'
$jar=Join-Path $env:JAVA_HOME 'bin/jar.exe'
$javac=Join-Path $env:JAVA_HOME 'bin/javac.exe'
$processes=@()
function CheckExit {if($LASTEXITCODE-ne 0){throw "Command failed: $LASTEXITCODE"}}
function WaitHttp([string]$url) {
    for($i=0;$i-lt 60;$i++) {
        try {return Invoke-RestMethod $url -TimeoutSec 2} catch {Start-Sleep -Seconds 1}
    }
    throw "Endpoint did not start: $url (logs: $work)"
}
function StartJava([string[]]$Arguments,[string]$Name) {
    Start-Process $java -ArgumentList $Arguments -WindowStyle Hidden -PassThru -RedirectStandardOutput "$work/$Name.log" -RedirectStandardError "$work/$Name.err"
}
try {
    foreach($port in @($ConfigPort,$ClientPort)) {
        $listener=[System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback,$port)
        try {$listener.Start()} finally {$listener.Stop()}
    }
    git clone --bare --no-hardlinks $source "$work/remote.git"; CheckExit
    git clone --no-hardlinks "$work/remote.git" "$work/history"; CheckExit
    git -C "$work/history" switch -c refresh-proof $v1; CheckExit
    git -C "$work/history" push -u origin refresh-proof; CheckExit
    git clone --no-hardlinks --branch refresh-proof "$work/remote.git" "$work/serving"; CheckExit
    Push-Location $work
    try {& $jar xf "$root/services/customer-service/target/customer-service-0.0.1-SNAPSHOT.jar" BOOT-INF/lib; CheckExit} finally {Pop-Location}
    # Retain Config Client crypto; omit domain persistence and HTTP security auto-configurations.
    $libs=Get-ChildItem "$work/BOOT-INF/lib/*.jar" | Where-Object {
        $_.Name -notmatch 'jdbc|hibernate-core|hibernate-models|jpa|spring-data|flyway' -and
        ($_.Name -notmatch 'security' -or $_.Name -match '^spring-security-crypto-')
    }
    $cp=$libs.FullName -join ';'
    & $javac -cp $cp -d "$work/classes" tools/tp3/RefreshProbe.java services/customer-service/src/main/java/com/ticketflow/customerservice/controllers/ConfigDemoController.java; CheckExit
    $uri=([Uri]"$work/serving").AbsoluteUri
    $processes+=StartJava @('-Xmx256m','-jar',"$root/services/config-server/target/config-server-0.0.1-SNAPSHOT.jar",'--server.address=127.0.0.1',"--server.port=$ConfigPort",'--spring.profiles.active=git',"--spring.cloud.config.server.git.uri=$uri",'--spring.cloud.config.server.git.default-label=refresh-proof',"--spring.cloud.config.server.git.basedir=$work/server") 'server'
    $config="http://127.0.0.1:$ConfigPort/customer-service/default/refresh-proof"
    $initial=WaitHttp $config
    if($initial.version-ne $v1){throw 'Initial Git SHA is not historical v1'}
    $processes+=StartJava @('-Xmx256m','-cp',('"'+"$work/classes;$cp"+'"'),'tp3.RefreshProbe',"--spring.config.location=optional:file:$work/empty.properties",'--spring.application.name=customer-service',"--spring.config.import=configserver:http://127.0.0.1:$ConfigPort",'--spring.cloud.config.label=refresh-proof','--server.address=127.0.0.1',"--server.port=$ClientPort",'--eureka.client.enabled=false','--spring.cloud.discovery.enabled=false') 'client'
    $client="http://127.0.0.1:$ClientPort"
    $before=(WaitHttp "$client/api/config/message").message
    if($before-ne 'Bienvenue dans TicketFlow - configuration v1'){throw 'Client did not load v1'}
    git -C "$work/history" merge --ff-only $v2; CheckExit
    git -C "$work/history" push origin refresh-proof; CheckExit
    $served=Invoke-RestMethod $config
    if($served.version-ne $v2){throw 'Server did not load historical v2 SHA'}
    $stale=(Invoke-RestMethod "$client/api/config/message").message
    if($stale-ne $before){throw 'Value changed before explicit refresh'}
    $changed=Invoke-RestMethod -Method Post -ContentType application/json -Body '{}' "$client/actuator/refresh"
    $after=(Invoke-RestMethod "$client/api/config/message").message
    if($after-ne 'Bienvenue dans TicketFlow - configuration v2' -or 'ticketflow.message' -notin $changed){throw 'Dynamic refresh not proven'}
    if($processes[-1].HasExited){throw 'Client exited'}
    [ordered]@{initialVersion=$v1;before=$before;gitVersion=$served.version;beforeRefresh=$stale;changedKeys=$changed;after=$after;clientPid=$processes[-1].Id} | ConvertTo-Json | Tee-Object "$work/proof.json"
    Write-Output "OK dynamic refresh, same client JVM; evidence: $work/proof.json"
} finally {
    foreach($process in $processes){if(-not $process.HasExited){Stop-Process -Id $process.Id}}
}
