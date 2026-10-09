param(
  [Parameter(Mandatory = $true)]
  [string]$Branch
)

$ErrorActionPreference = "Stop"
# Native k6 writes its normal error summary to stderr; do not let PowerShell
# convert that stderr stream into a terminating NativeCommandError before we
# can print the useful k6 failure and inspect $LASTEXITCODE.
$PSNativeCommandUseErrorActionPreference = $false
$root = (Get-Location).Path
$fixtureDir = Join-Path $root "k6/outbox-branch-benchmark"
$fixture = Join-Path $fixtureDir "fixture.local.json"
$script = Join-Path $fixtureDir "waiting-rank.js"
$results = Join-Path $root "benchmark-results"
New-Item -ItemType Directory -Force $results | Out-Null

if (-not (Get-Command k6 -ErrorAction SilentlyContinue)) {
  throw "k6 is not installed or not on PATH. Install k6, then rerun this command."
}

function Set-LocalFixture {
  Write-Host "Enter an account that can log in to http://localhost:5173 (local app only)."
  $loginId = Read-Host "Local login ID"
  $securePassword = Read-Host "Local password" -AsSecureString
  $passwordPtr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
  try { $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPtr) }
  finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPtr) }
  $json = @{ users = @(@{ loginId = $loginId; password = $password }) } | ConvertTo-Json -Depth 5
  [System.IO.File]::WriteAllText($fixture, $json, (New-Object System.Text.UTF8Encoding($false)))
}

if (-not (Test-Path $fixture)) {
  Set-LocalFixture
} else {
  Write-Host "Using the local-only fixture. If login fails, this script will ask for valid local credentials."
}

$env:BASE_URL = "http://localhost:8080"
$env:FIXTURE = (Resolve-Path $fixture).Path
$env:VUS = "20"
$env:DURATION = "60s"
$env:BRANCH = $Branch
$env:SUMMARY = Join-Path $results (($Branch -replace '/', '-') + ".json")

Write-Host "Benchmarking $Branch with 20 VUs for 60s..."
$output = & k6 run $script 2>&1
$output | ForEach-Object { Write-Host $_ }
if ($LASTEXITCODE -ne 0) {
  $message = $output -join "`n"
  if ($message -match "Local test account login failed|아이디 또는 비밀 번호가 올바르지 않습니다") {
    Write-Host "The saved account did not log in locally. Enter a valid local account and retry once."
    Set-LocalFixture
    $env:FIXTURE = (Resolve-Path $fixture).Path
    $output = & k6 run $script 2>&1
    $output | ForEach-Object { Write-Host $_ }
  }
}
if ($LASTEXITCODE -ne 0) {
  throw "Benchmark failed. Do not compare this run's latency values; fix the reported setup/API error first."
}
Write-Host "Result saved: $env:SUMMARY"
