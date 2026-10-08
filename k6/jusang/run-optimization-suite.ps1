param(
    [ValidateSet("smoke","load")][string]$Profile="load",
    [string]$BaseUrl="http://localhost:8080",
    [int]$Vus=10,[int]$Iterations=100,
    [string]$MovieId="14",[string]$TheaterId="1",[string]$ShowtimeId="115260",[string]$Date="2026-10-09",
    [string]$Lat="37.5665",[string]$Lon="126.9780",[string]$Address="",
    [switch]$ManageBackend,[switch]$OpenReport
)
$ErrorActionPreference="Stop"
if($Profile -eq "smoke"){$Vus=1;$Iterations=1}
if(-not $env:K6_LOGIN_ID -or -not $env:K6_PASSWORD){throw "K6_LOGIN_ID와 K6_PASSWORD를 먼저 설정하세요."}
$args1=@("-Profile",$Profile,"-BaseUrl",$BaseUrl,"-Vus","$Vus","-Iterations","$Iterations","-MovieId",$MovieId,"-TheaterId",$TheaterId,"-ShowtimeId",$ShowtimeId,"-Date",$Date)
$args5=@("-Profile",$Profile,"-BaseUrl",$BaseUrl,"-Vus","$Vus","-Iterations","$Iterations,"-Lat",$Lat,"-Lon",$Lon,"-Address",$Address)
if($ManageBackend){$args1+="-ManageBackend";$args5+="-ManageBackend"}
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$PSScriptRootun-cache-comparison.ps1" @args1
if($LASTEXITCODE -ne 0){throw "1/3 Redis 비교 실패"}
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$PSScriptRootun-nearby-benchmark.ps1" @args5
if($LASTEXITCODE -ne 0){throw "5 Nearby baseline 실패"}
& powershell.exe -NoProfile -ExecutionPolicy Bypass -File "$PSScriptRootgenerate-optimization-report.ps1" -Profile $Profile
if($LASTEXITCODE -ne 0){throw "통합 보고서 생성 실패"}
if($OpenReport){Start-Process "$PSScriptRootoptimization-test-report.html"}
