param([string]$Gateway = 'http://localhost:8080', [string]$Customer = 'http://localhost:8081')
$ErrorActionPreference = 'Stop'
# The default Gateway is now protected. Supply a real local ADMIN access token.
$demoAccessToken = $env:TICKETFLOW_DEMO_ACCESS_TOKEN
if ([string]::IsNullOrWhiteSpace($demoAccessToken)) {
    throw 'Gateway securisee : definir TICKETFLOW_DEMO_ACCESS_TOKEN (ADMIN), ou lancer node tools/Test-KeycloakIntegration.mjs.'
}
Add-Type -AssemblyName System.Net.Http
$handler = New-Object System.Net.Http.HttpClientHandler
$handler.AllowAutoRedirect = $false
$client = New-Object System.Net.Http.HttpClient($handler)
$client.Timeout = [TimeSpan]::FromSeconds(8)

function Request([string]$Method, [string]$Url, [int[]]$Expected, $Body = $null) {
    $request = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::new($Method), $Url)
    if (([uri]$Url).GetLeftPart([UriPartial]::Authority) -in @(([uri]$Gateway).GetLeftPart([UriPartial]::Authority), ([uri]$Customer).GetLeftPart([UriPartial]::Authority))) {
        $request.Headers.Authorization = New-Object System.Net.Http.Headers.AuthenticationHeaderValue('Bearer', $demoAccessToken)
    }
    if ($null -ne $Body) {
        $request.Content = New-Object System.Net.Http.StringContent(($Body | ConvertTo-Json -Compress), [Text.Encoding]::UTF8, 'application/json')
    }
    $response = $client.SendAsync($request).GetAwaiter().GetResult()
    try {
        $content = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if ($Expected -notcontains [int]$response.StatusCode) { throw "$Method $Url : $([int]$response.StatusCode), attendu $Expected. $content" }
        $served = if ($response.Headers.Contains('X-Served-By')) { $response.Headers.GetValues('X-Served-By') -join ',' } else { '' }
        Write-Host "OK $([int]$response.StatusCode) $Method $Url"
        return [pscustomobject]@{ StatusCode = [int]$response.StatusCode; Content = $content; Location = [string]$response.Headers.Location; ServedBy = $served }
    } finally { $response.Dispose(); $request.Dispose() }
}

try {
    # Health checks do not prove Eureka's eventually consistent client cache is populated.
    $ready = $false
    $deadline = [DateTime]::UtcNow.AddMinutes(3)
    $names = @('CUSTOMER-SERVICE','EVENT-SERVICE','BOOKING-SERVICE','API-GATEWAY')
    while ([DateTime]::UtcNow -lt $deadline) {
        try {
            foreach ($path in @('/api/customers','/api/events','/api/bookings')) {
                $null = Request GET "$Gateway$path" 200
            }
            $registry = Invoke-RestMethod 'http://localhost:8761/eureka/apps' -Headers @{Accept='application/json'} -TimeoutSec 8
            foreach ($name in $names) {
                $app = @($registry.applications.application) | Where-Object name -eq $name
                if (-not $app -or -not (@($app.instance) | Where-Object status -eq 'UP')) { throw "$name pas encore UP dans Eureka" }
            }
            $ready = $true
            break
        } catch { $lastError = $_.Exception.Message; Start-Sleep -Seconds 3 }
    }
    if (-not $ready) { throw "Gateway non prete apres attente du registre Eureka : $lastError" }

    foreach ($name in $names) {
        Write-Host "OK Eureka $name UP"
    }
    $config = Request GET 'http://localhost:8888/customer-service/default/main' 200
    if (@(($config.Content | ConvertFrom-Json).propertySources).Count -eq 0) { throw 'Config Server ne fournit aucune configuration' }
    $null = Request GET "$Customer/api/config/message" 200
    $null = Request POST "$Customer/actuator/refresh" 200
    $null = Request GET "$Customer/api/config/message" 200
    $null = Request GET "$Customer/api/customers" 200
    $customerResult = Request POST "$Gateway/api/customers" @(201,409) @{
        firstName='Arij'; lastName='Demo'; email=([guid]::NewGuid().ToString()+'@example.com'); phone='+212600000000'
    }
    if ($customerResult.StatusCode -eq 409) {
        if (($customerResult.Content | ConvertFrom-Json).code -ne 'CUSTOMER_IDENTITY_CONFLICT') { throw 'Conflit Customer inattendu' }
        $customerResult = Request GET "$Gateway/api/customers/me" 200
    }
    if ($customerResult.ServedBy -ne 'api-gateway') { throw 'En-tete X-Served-By incorrect' }
    $customerId = ($customerResult.Content | ConvertFrom-Json).id
    $location = if ($customerResult.StatusCode -eq 201) { [uri]$customerResult.Location } else { [uri]"$Gateway/api/customers/$customerId" }
    if ($location.Authority -ne ([uri]$Gateway).Authority -or $location.AbsolutePath -ne "/api/customers/$customerId") { throw "Location incorrecte: $location" }
    $null = Request GET $location.AbsoluteUri 200
    $null = Request GET "$Customer/api/customers/$customerId" 200
    $null = Request GET "$Gateway/api/customers/9223372036854775807" 404
    $null = Request POST "$Gateway/api/customers" 400 @{firstName='Arij'; lastName='Demo'; email='invalide'}

    $event = Request POST "$Gateway/api/events" 201 @{
        title='Demonstration TicketFlow'; venue='Universite'; startsAt=[DateTime]::UtcNow.AddDays(7).ToString('yyyy-MM-ddTHH:mm:ssZ'); price=100; capacity=10
    }
    $eventId = ($event.Content | ConvertFrom-Json).id
    $null = Request POST "$Gateway/api/events/$eventId/publish" 200
    $null = Request GET "$Gateway/api/events/$eventId" 200
    $booking = Request POST "$Gateway/api/bookings" 201 @{eventId=$eventId; customerId=$customerId; quantity=1}
    $bookingId = ($booking.Content | ConvertFrom-Json).id
    $null = Request GET "$Gateway/api/bookings/$bookingId" 200
    $null = Request POST "$Gateway/api/bookings/$bookingId/cancel" 200
    foreach ($path in @('/config-server/customer-service/default','/discovery-server/eureka/apps','/eureka/apps',
            '/api/config/message','/actuator/gateway/routes','/customer-service/actuator/health')) {
        $null = Request GET "$Gateway$path" 403
    }
    Write-Host 'Demonstration validee : Config Server, Eureka, Customer direct et Gateway JWT, Event, Booking, routes internes refusees (403).'
} finally { $client.Dispose(); $handler.Dispose() }
