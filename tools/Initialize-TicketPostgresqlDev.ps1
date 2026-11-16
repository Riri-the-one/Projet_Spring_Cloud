param()
$ErrorActionPreference = 'Stop'
& (Join-Path $PSScriptRoot 'Initialize-PostgresqlDev.ps1')
$directory = Join-Path (Resolve-Path (Join-Path $PSScriptRoot '..')).Path 'infra/postgresql/.local'
$path = Join-Path $directory 'ticket.password'
if (Test-Path -LiteralPath $path) {
    if ([IO.File]::ReadAllText($path).Trim() -notmatch '^[a-f0-9]{64}$') { throw 'Existing Ticket secret invalid; no automatic rotation' }
    Write-Host 'Secret Ticket existant conserve.'
} else {
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $bytes = New-Object byte[] 32
        $generator.GetBytes($bytes)
        $secret = [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
        $stream = [IO.File]::Open($path, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
        try { $encoded = [Text.Encoding]::UTF8.GetBytes($secret); $stream.Write($encoded, 0, $encoded.Length) }
        finally { $stream.Dispose() }
    } finally { $generator.Dispose(); $secret = $null }
    Write-Host 'Secret Ticket local genere, sans affichage de sa valeur.'
}
$ticketSecret = [IO.File]::ReadAllText($path).Trim()
try {
    foreach($domain in @('admin','customer','event','booking')) {
        if ($ticketSecret -eq [IO.File]::ReadAllText((Join-Path $directory "$domain.password")).Trim()) {
            throw 'Ticket credential must differ from every existing domain; no file or database password changed automatically'
        }
    }
} finally { $ticketSecret = $null }
