# Runs the same checks as CI and exits non-zero if any of them fails.
$ErrorActionPreference = 'Continue' # native stderr output must not abort; exit codes decide
Set-Location $PSScriptRoot

function Invoke-Step([string]$Name, [scriptblock]$Command) {
    Write-Host "`n=== $Name ===" -ForegroundColor Cyan
    & $Command
    if ($LASTEXITCODE -ne 0) {
        Write-Host "FAILED: $Name (exit code $LASTEXITCODE)" -ForegroundColor Red
        exit $LASTEXITCODE
    }
}

Invoke-Step 'Java build, tests and coverage gate' { mvn -B clean verify }
Invoke-Step 'Python script tests' { python -m unittest discover -s scripts -p "test_*.py" -v }
Push-Location web-react
try {
    Invoke-Step 'Frontend lint' { npm run lint }
    Invoke-Step 'Frontend build' { npm run build }
} finally {
    Pop-Location
}

Write-Host "`nAll checks passed." -ForegroundColor Green
