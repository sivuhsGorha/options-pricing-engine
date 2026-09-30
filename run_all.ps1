# Compile the entire project
Write-Host "Compiling the Options Pricing Engine..." -ForegroundColor Cyan
New-Item -ItemType Directory -Force -Path "target/classes" | Out-Null

$javaFiles = Get-ChildItem -Path "src/main/java" -Filter "*.java" -Recurse
$filePaths = $javaFiles.FullName -join " "

# We use cmd /c to handle the long command line compilation properly
cmd /c "javac -d target/classes $filePaths"

Write-Host "`n======================================================="
Write-Host " 1. Running Phase 1: Core Mathematical Pricing Models"
Write-Host "=======================================================" -ForegroundColor Yellow
java -cp target/classes com.sbk.optionspricer.Main

Write-Host "`n======================================================="
Write-Host " 2. Running Phase 2: Lock-Free LMAX Ring Buffer"
Write-Host "=======================================================" -ForegroundColor Yellow
java -cp target/classes com.sbk.optionspricer.core.RingBufferTest

Write-Host "`n======================================================="
Write-Host " 3. Running Phase 2: SABR & SVI Volatility Calibration"
Write-Host "=======================================================" -ForegroundColor Yellow
java -cp target/classes com.sbk.optionspricer.volatility.VolatilitySurfaceTest

Write-Host "`n======================================================="
Write-Host " 4. Running Phase 3: Simulated Exchange Gateway Replay"
Write-Host "=======================================================" -ForegroundColor Yellow
java -cp target/classes com.sbk.optionspricer.gateways.GatewayTest

Write-Host "`n======================================================="
Write-Host " 5. Running Phase 4: Enterprise Risk Engine & SPAN Margin"
Write-Host "=======================================================" -ForegroundColor Yellow
java -cp target/classes com.sbk.optionspricer.risk.RiskEngineTest

Write-Host "`n======================================================="
Write-Host " 6. Running Phase 5: HFT Latency Benchmark"
Write-Host "=======================================================" -ForegroundColor Yellow
java -cp target/classes com.sbk.optionspricer.benchmark.LatencyBenchmarkTest

Write-Host "`nALL TESTS COMPLETED SUCCESSFULLY!" -ForegroundColor Green
