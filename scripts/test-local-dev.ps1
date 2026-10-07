# Run with Windows PowerShell; no actual WSL, Redis, Java, or user .env required.
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'local-dev.ps1') -Action status
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('smartticketing-local-' + [guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($testRoot) | Out-Null
$projectRoot = $testRoot
$modePath = Join-Path $testRoot '.local/cache-mode.properties'
function Assert-True($Condition, $Message) { if (-not $Condition) { throw $Message } }
function Get-NetTCPConnection { if ($script:portBusy) { [pscustomobject]@{ LocalPort=8080 } } }
function Get-RedisPing { return $true }
$script:portBusy = $false
try {
    Set-CacheMode $true
    Assert-True ((Get-CacheMode) -eq 'true') 'ON mode was not saved'
    Set-CacheMode $false
    Assert-True ((Get-CacheMode) -eq 'false') 'OFF mode was not saved'
    [IO.File]::WriteAllText($modePath, 'app.cache.enabled=invalid')
    $rejected = $false
    try { Get-CacheMode } catch { $rejected = $true }
    Assert-True $rejected 'Invalid mode was accepted'
    Set-CacheMode $false
    $script:portBusy = $true
    $rejected = $false
    try { Start-LocalBackend $true } catch { $rejected = $_.Exception.Message.Contains('8080') }
    Assert-True $rejected 'An existing backend was not protected'
    Assert-True ((Get-CacheMode) -eq 'false') 'Mode changed despite a port conflict'
    $script:portBusy = $false
    [IO.File]::WriteAllText((Join-Path $testRoot '.env'), "SMARTTICKETING_TEST_VALUE=temporary`n")
    [IO.File]::WriteAllText((Join-Path $testRoot 'gradlew.bat'), "@exit /b 42`r`n")
    $env:SMARTTICKETING_TEST_VALUE = 'original'
    $rejected = $false
    try { Start-LocalBackend $true } catch { $rejected = $_.Exception.Message.Contains('42') }
    Assert-True $rejected 'Backend failure was not reported'
    Assert-True ($env:SMARTTICKETING_TEST_VALUE -eq 'original') 'Process environment was not restored'
    Write-Host 'PASS: mode persistence, invalid mode rejection, existing backend protection, environment restoration.'
} finally {
    Remove-Item Env:SMARTTICKETING_TEST_VALUE -ErrorAction SilentlyContinue
    foreach ($testFile in @($modePath, (Join-Path $testRoot '.env'), (Join-Path $testRoot 'gradlew.bat'))) {
        if (Test-Path -LiteralPath $testFile) { Remove-Item -LiteralPath $testFile }
    }
    if (Test-Path -LiteralPath (Join-Path $testRoot '.local')) { Remove-Item -LiteralPath (Join-Path $testRoot '.local') }
    Remove-Item -LiteralPath $testRoot
}
