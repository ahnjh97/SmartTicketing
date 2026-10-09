param(
  [Parameter(Mandatory = $true)]
  [ValidateSet("feature/jusang", "feature/outbox-metrics")]
  [string]$Branch
)

$ErrorActionPreference = "Stop"
$root = (Get-Location).Path
$fixtureDir = Join-Path $root "k6/outbox-branch-benchmark"
$fixture = Join-Path $fixtureDir "fixture.local.json"
$script = Join-Path $fixtureDir "waiting-rank.js"
$results = Join-Path $root "benchmark-results"
New-Item -ItemType Directory -Force $results | Out-Null

if (-not (Get-Command k6 -ErrorAction SilentlyContinue)) {
  throw "k6 is not installed or not on PATH. Install k6, then rerun this command."
}

if (-not (Test-Path $fixture)) {
  Write-Host "First run only: enter an account that can log in to your LOCAL app."
  $loginId = Read-Host "Local login ID"
  $securePassword = Read-Host "Local password" -AsSecureString
  $passwordPtr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($securePassword)
  try { $password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($passwordPtr) }
  finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($passwordPtr) }
  $json = @{ users = @(@{ loginId = $loginId; password = $password }) } | ConvertTo-Json -Depth 5
  [System.IO.File]::WriteAllText($fixture, $json, (New-Object System.Text.UTF8Encoding($false)))
  Write-Host "Saved local-only credentials in fixture.local.json (ignored by Git)."
}

$env:BASE_URL = "http://localhost:8080"
$env:FIXTURE = (Resolve-Path $fixture).Path
$env:VUS = "20"
$env:DURATION = "60s"
$env:BRANCH = $Branch
$env:SUMMARY = Join-Path $results (($Branch -replace '/', '-') + ".json")

Write-Host "Benchmarking $Branch against $env:BASE_URL with 20 VUs for 60s..."
k6 run $script
if ($LASTEXITCODE -ne 0) {
  throw "Benchmark failed. Do not compare this run's latency values; fix the reported setup/API error first."
}
Write-Host "Result saved: $env:SUMMARY"
