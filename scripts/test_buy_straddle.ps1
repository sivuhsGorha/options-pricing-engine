# Verifies the Alpaca PAPER multi-leg order path with a one-contract buy straddle at the venue's touch.
#
# By default this is a DRY RUN: it prints the account, the quotes and the order it would send, and sends nothing.
# With -Place it sends ONE REAL ORDER to the paper account. That order bypasses the engine, so the engine's ledger never
# records the fill and its reconciliation will HALT TRADING until you flatten the position on Alpaca's paper dashboard.
# Stop the engine first (docker compose stop), and run it only with the market open.
#
#   .\scripts\test_buy_straddle.ps1                  dry run
#   .\scripts\test_buy_straddle.ps1 -Place           place the order (after docker compose stop)
#   .\scripts\test_buy_straddle.ps1 -Docker          dry run inside the running container

param(
    [switch]$Place = $false,
    [switch]$Docker = $false
)

$ErrorActionPreference = "Stop"
$extra = @()
if ($Place) { $extra += "--confirm" }

if ($Docker) {
    if ($Place) { throw "-Place with -Docker would trade from inside the running engine's container. Stop the engine and run it locally instead." }
    docker compose exec options-engine java --add-modules jdk.incubator.vector -jar /app/app.jar --test-buy-straddle @extra
} else {
    $JarPath = Join-Path $PSScriptRoot "..\target\options-pricing-engine-1.0.0-SNAPSHOT.jar"
    if (-not (Test-Path $JarPath)) {
        throw "No jar at $JarPath. Build it first: mvn -B -DskipTests package"
    }
    # run from the project folder so .env (the Alpaca keys) is found
    Push-Location (Join-Path $PSScriptRoot "..")
    try { java --add-modules jdk.incubator.vector -jar $JarPath --test-buy-straddle @extra } finally { Pop-Location }
}
