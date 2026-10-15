# Safe verification script for testing Alpaca Paper Multi-Leg Order Path (Buy Straddle)
# Complies with Alpaca Level 3 permissions (long debit straddles only; naked short straddles are strictly prohibited).

param(
    [switch]$Docker = $false
)

$ErrorActionPreference = "Stop"

Write-Host "=========================================================================" -ForegroundColor Cyan
Write-Host "          ALPACA PAPER MULTI-LEG ORDER PATH VERIFICATION                " -ForegroundColor Cyan
Write-Host "=========================================================================" -ForegroundColor Cyan

if ($Docker) {
    Write-Host "[MODE] Executing inside Docker container options-pricing-engine-options-engine-1..." -ForegroundColor Yellow
    docker compose exec options-engine java --enable-preview --add-modules jdk.incubator.vector -jar /app/options-pricing-engine-1.0.0-SNAPSHOT.jar --test-buy-straddle
} else {
    $JarPath = Join-Path $PSScriptRoot "..\target\options-pricing-engine-1.0.0-SNAPSHOT.jar"
    if (-not (Test-Path $JarPath)) {
        Write-Host "[BUILD] Packaging jar before test..." -ForegroundColor Yellow
        Push-Location (Join-Path $PSScriptRoot "..")
        mvn -B -DskipTests package
        Pop-Location
    }
    Write-Host "[MODE] Executing local jar $JarPath..." -ForegroundColor Yellow
    java --enable-preview --add-modules jdk.incubator.vector -jar $JarPath --test-buy-straddle
}
