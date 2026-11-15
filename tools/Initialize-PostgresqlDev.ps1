param()
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$secretDirectory = Join-Path $projectRoot 'infra/postgresql/.local'
$environmentFile = Join-Path $projectRoot 'infra/postgresql/.env.local'
$names = @('admin', 'customer', 'event', 'booking')
$existing = @($names | Where-Object { Test-Path -LiteralPath (Join-Path $secretDirectory "$_.password") })
if ($existing.Count -gt 0 -and $existing.Count -ne $names.Count) {
    throw 'Jeu de secrets incomplet : restaurer les fichiers manquants. Aucune rotation automatique ni suppression.'
}
if ($existing.Count -eq 0) {
    $null = New-Item -ItemType Directory -Force -Path $secretDirectory
    $generator = [Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        foreach ($name in $names) {
            $bytes = New-Object byte[] 32
            $generator.GetBytes($bytes)
            $password = [BitConverter]::ToString($bytes).Replace('-', '').ToLowerInvariant()
            $path = Join-Path $secretDirectory "$name.password"
            # CreateNew refuses to overwrite even if another process created the file meanwhile.
            $stream = [IO.File]::Open($path, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
            try {
                $encoded = [Text.Encoding]::UTF8.GetBytes($password)
                $stream.Write($encoded, 0, $encoded.Length)
            } finally { $stream.Dispose() }
            $password = $null
        }
    } finally { $generator.Dispose() }
    Write-Host 'Quatre secrets locaux distincts generes, sans affichage des valeurs.'
} else {
    foreach ($name in $names) {
        if ((Get-Item -LiteralPath (Join-Path $secretDirectory "$name.password")).Length -eq 0) {
            throw 'Un secret existant est vide. Aucune modification automatique effectuee.'
        }
    }
    Write-Host 'Secrets existants conserves ; aucune rotation.'
}
if (-not (Test-Path -LiteralPath $environmentFile)) {
    $template = [IO.File]::ReadAllText((Join-Path $projectRoot 'infra/postgresql/.env.example'))
    [IO.File]::WriteAllText($environmentFile, $template, [Text.UTF8Encoding]::new($false))
}
Write-Host 'Configuration locale prete : infra/postgresql/.env.local et .local/*.password (ignores par Git et Docker build).'
