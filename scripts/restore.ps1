<#
.SYNOPSIS
  Restores a backup made by backup.ps1 into a Docker volume, and checks the ledger against the manifest.

.DESCRIPTION
  Creates the volume if it is missing and unpacks data.tar.gz into it, then makes the files writable for the
  engine's user. It REFUSES to restore into a volume that already holds files, so a restore can never overwrite a
  live ledger; pass a new -Volume name to try a restore safely. It does not touch config.yaml or .env: copy
  config.yaml from the backup folder yourself and put .env back from your password manager.

.EXAMPLE
  .\scripts\restore.ps1 -BackupDir D:\aura-backups\20261014-120000Z                          # a new machine
  .\scripts\restore.ps1 -BackupDir D:\aura-backups\20261014-120000Z -Volume scratch_restore   # a rehearsal
#>
param(
    [Parameter(Mandatory = $true)][string]$BackupDir,
    [string]$Volume = 'options-pricing-engine_engine-data',
    [string]$Image = 'options-pricing-engine:local'
)

$ErrorActionPreference = 'Stop'

# Windows PowerShell 5.1 turns a native program's stderr into a terminating error under 'Stop', and "no such volume"
# is an expected answer here, so existence checks run with errors merely continuing.
function Test-Docker([string[]]$DockerArgs) {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & docker @DockerArgs 2>$null | Out-Null; return ($LASTEXITCODE -eq 0) } finally { $ErrorActionPreference = $previous }
}

$archive = Join-Path $BackupDir 'data.tar.gz'
if (-not (Test-Path $archive)) { throw "$archive not found" }
if (-not (Test-Docker 'image', 'inspect', $Image)) { throw "No Docker image '$Image'. Build it first: docker compose build" }

if (-not (Test-Docker 'volume', 'inspect', $Volume)) { docker volume create $Volume | Out-Null }

$existing = docker run --rm -v "${Volume}:/dst:ro" --entrypoint sh $Image -c 'ls -A /dst | wc -l'
if ([int]$existing -ne 0) { throw "Volume '$Volume' already holds $existing entries. Refusing to restore over it; choose a new -Volume name." }

docker run --rm -u root -v "${Volume}:/dst" -v "${BackupDir}:/in:ro" --entrypoint sh $Image `
    -c 'tar xzf /in/data.tar.gz -C /dst && chown -R appuser:appgroup /dst'
if ($LASTEXITCODE -ne 0) { throw 'extracting the archive failed' }

# No nested quotes in this command: Windows PowerShell 5.1 strips them on the way to a native program.
$ledgerOut = @(docker run --rm -v "${Volume}:/src:ro" --entrypoint sh $Image -c 'if [ -f /src/fills.csv ]; then wc -l < /src/fills.csv; sha256sum /src/fills.csv; fi')
if ($LASTEXITCODE -ne 0) { throw 'could not read the restored ledger' }
if ($ledgerOut.Count -eq 0) {
    $lines = '0'; $hash = 'none'
} else {
    $lines = ([string]$ledgerOut[0]).Trim()
    $hash = (([string]$ledgerOut[1]).Trim() -split '\s+')[0]
}
Write-Host "Restored into volume '$Volume': ledger $lines lines (sha256 $hash)"

$manifest = Join-Path $BackupDir 'manifest.txt'
if (Test-Path $manifest) {
    $text = Get-Content $manifest -Raw
    $expectedHash = [regex]::Match($text, 'fills\.csv sha256: (\S+)').Groups[1].Value
    if ($expectedHash -and $expectedHash -eq $hash) {
        Write-Host 'The ledger matches the manifest.'
    } else {
        throw "The ledger does NOT match the manifest (expected $expectedHash, got $hash). Do not start the engine on this volume."
    }
} else {
    Write-Warning 'No manifest.txt next to the archive, so the ledger could not be checked.'
}
Write-Host 'Next: put .env and config.yaml back in the project folder, then docker compose up -d and look for [RECONCILE] OK.'
