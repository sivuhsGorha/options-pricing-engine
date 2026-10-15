<#
.SYNOPSIS
  Backs up the engine's state (the Docker volume holding data/) and config.yaml to a dated folder.

.DESCRIPTION
  Writes <Destination>\<UTC timestamp>\ containing
    data.tar.gz     the whole data volume: fills.csv (the ledger the book is rebuilt from), pnl_history.csv,
                    surface_history.csv and chain_snapshots/
    config.yaml     a copy of the local settings, if the file exists
    manifest.txt    when, which commit, and the ledger's row count and SHA-256, so a restore can be checked
  It reads the volume read-only, so the engine can keep running. It does NOT copy .env: the keys belong in a
  password manager, not in an archive beside the laptop. Nothing is ever deleted or overwritten.

.EXAMPLE
  .\scripts\backup.ps1 -Destination D:\blackthorn-backups
#>
param(
    [string]$Destination = (Join-Path $env:USERPROFILE 'blackthorn-sterling-backups'),
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

if (-not (Test-Docker 'volume', 'inspect', $Volume)) { throw "No Docker volume named '$Volume'. Is Docker Desktop running, and has the engine been started with docker compose?" }
if (-not (Test-Docker 'image', 'inspect', $Image)) { throw "No Docker image '$Image' (used only for tar and sha256sum). Build it with: docker compose build" }

$stamp = (Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmss') + 'Z'
$target = Join-Path $Destination $stamp
if (Test-Path $target) { throw "$target already exists; refusing to write into it." }
New-Item -ItemType Directory -Path $target | Out-Null

# The volume is mounted read-only; the archive is written to the host folder through a second mount.
docker run --rm -v "${Volume}:/src:ro" -v "${target}:/out" --entrypoint sh $Image -c 'tar czf /out/data.tar.gz -C /src .'
if ($LASTEXITCODE -ne 0) { throw 'tar failed inside the container' }

# No nested quotes in this command: Windows PowerShell 5.1 strips them on the way to a native program.
$ledgerOut = @(docker run --rm -v "${Volume}:/src:ro" --entrypoint sh $Image -c 'if [ -f /src/fills.csv ]; then wc -l < /src/fills.csv; sha256sum /src/fills.csv; fi')
if ($LASTEXITCODE -ne 0) { throw 'could not read the ledger inside the container' }
if ($ledgerOut.Count -eq 0) {
    Write-Warning 'There is no fills.csv in this volume: the backup holds no ledger.'
    $ledgerLines = '0'; $ledgerHash = 'none'
} else {
    $ledgerLines = ([string]$ledgerOut[0]).Trim()
    $ledgerHash = (([string]$ledgerOut[1]).Trim() -split '\s+')[0]
    if ($ledgerLines -notmatch '^\d+$' -or $ledgerHash -notmatch '^[0-9a-f]{64}$') { throw "unexpected ledger output: $($ledgerOut -join ' | ')" }
}

$configCopied = $false
$config = Join-Path (Split-Path $PSScriptRoot -Parent) 'config.yaml'
if (Test-Path $config) { Copy-Item $config (Join-Path $target 'config.yaml'); $configCopied = $true }

$commit = (git -C (Split-Path $PSScriptRoot -Parent) rev-parse --short HEAD 2>$null)
$archive = Get-Item (Join-Path $target 'data.tar.gz')
@(
    "backup of volume $Volume"
    "taken (UTC): $stamp"
    "git commit: $commit"
    "data.tar.gz bytes: $($archive.Length)"
    "fills.csv lines (header included): $ledgerLines"
    "fills.csv sha256: $ledgerHash"
    "config.yaml copied: $configCopied"
    "NOT included: .env (keep the keys in a password manager)"
) | Set-Content -Path (Join-Path $target 'manifest.txt') -Encoding ascii

Write-Host "Backed up to $target"
Write-Host "  data.tar.gz $([math]::Round($archive.Length / 1KB, 1)) KB, ledger $ledgerLines lines (sha256 $ledgerHash)"
Write-Host 'Remember: .env is not in the backup. Copy it to your password manager.'
