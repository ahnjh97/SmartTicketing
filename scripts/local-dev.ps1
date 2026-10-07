param(
    [ValidateSet('menu', 'setup', 'on', 'off', 'set-on', 'set-off', 'status', 'redis-start')]
    [string]$Action = 'menu'
)

$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$modePath = Join-Path $projectRoot '.local/cache-mode.properties'
$redisScript = Join-Path $PSScriptRoot 'local-redis.sh'

function Get-CacheMode {
    if (Test-Path -LiteralPath $modePath) {
        $content = [IO.File]::ReadAllText($modePath).Trim()
        if ($content -notmatch '^app\.cache\.enabled=(true|false)$') {
            throw 'Invalid .local/cache-mode.properties; expected app.cache.enabled=true or false.'
        }
        return $matches[1]
    }
    return 'false'
}

function Set-CacheMode([bool]$Enabled) {
    $value = $Enabled.ToString().ToLowerInvariant()
    [IO.Directory]::CreateDirectory((Split-Path $modePath)) | Out-Null
    [IO.File]::WriteAllText($modePath, "app.cache.enabled=$value`n", (New-Object Text.UTF8Encoding($false)))
    Write-Host "Saved local cache mode: $value" -ForegroundColor Cyan
    Write-Host 'Configuration only: application cache is not implemented yet. QR Redis stays ON.'
    Write-Host 'IDE users: restart the backend with the repository root as its working directory.'
}

function Get-RedisPing {
    $client = New-Object Net.Sockets.TcpClient
    try {
        $pending = $client.BeginConnect('127.0.0.1', 6379, $null, $null)
        if (-not $pending.AsyncWaitHandle.WaitOne(1500)) { return $false }
        $client.EndConnect($pending)
        $stream = $client.GetStream()
        $stream.ReadTimeout = 1500
        $stream.WriteTimeout = 1500
        $bytes = [Text.Encoding]::ASCII.GetBytes("*1`r`n`$4`r`nPING`r`n")
        $stream.Write($bytes, 0, $bytes.Length)
        $reader = New-Object IO.StreamReader($stream, [Text.Encoding]::ASCII)
        return ($reader.ReadLine() -eq '+PONG')
    } catch { return $false } finally { $client.Dispose() }
}

function Assert-Ubuntu {
    $null = & wsl.exe -d Ubuntu -u root --exec /bin/true 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw 'Ubuntu is not ready. Finish WSL installation/reboot, launch Ubuntu once to create its user, then run local.cmd setup.'
    }
}

function Invoke-Redis([ValidateSet('setup', 'start')][string]$Command) {
    Assert-Ubuntu
    # Pass only the checked-in script to WSL; no .env or credentials enter Linux.
    $scriptText = [IO.File]::ReadAllText($redisScript).Replace("`r`n", "`n")
    # Windows PowerShell adds CRLF when piping to native processes, even after
    # Replace above. Normalize inside WSL before Bash reads the script.
    $scriptText | & wsl.exe -d Ubuntu -u root --exec bash -c "tr -d '\r' | bash -s -- $Command"
    if ($LASTEXITCODE -ne 0) { throw "Redis $Command failed. Check the WSL output above." }
    if (-not (Get-RedisPing)) { throw 'Redis started in WSL, but Windows localhost:6379 did not return PONG.' }
    Write-Host 'Redis: 127.0.0.1:6379 -> PONG' -ForegroundColor Green
}

function Start-LocalBackend([bool]$Enabled) {
    # Do not stop another application or an IDE-managed Java process.
    $listener = Get-NetTCPConnection -State Listen -LocalPort 8080 -ErrorAction SilentlyContinue
    if ($listener) { throw 'Port 8080 is already in use. Stop your IDE backend first, or use set-on/set-off and restart it in the IDE.' }
    if (-not (Get-RedisPing)) { Invoke-Redis 'start' }
    $envPath = Join-Path $projectRoot '.env'
    if (-not (Test-Path -LiteralPath $envPath)) { throw 'Create the project .env with your existing DB and application settings first.' }
    $previous = @{}
    try {
        foreach ($line in [IO.File]::ReadAllLines($envPath)) {
            if ($line -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
                $key = $matches[1]
                $value = $matches[2].Trim()
                if ($value.Length -ge 2 -and (($value.StartsWith('"') -and $value.EndsWith('"')) -or ($value.StartsWith("'") -and $value.EndsWith("'")))) {
                    $value = $value.Substring(1, $value.Length - 2)
                }
                if (-not $previous.ContainsKey($key)) { $previous[$key] = [Environment]::GetEnvironmentVariable($key, 'Process') }
                [Environment]::SetEnvironmentVariable($key, $value, 'Process')
            }
        }
        Set-CacheMode $Enabled
        $mode = Get-CacheMode
        Write-Host "Starting backend with cache mode=$mode. Ctrl+C stops this run."
        # Command-line properties also protect this local runner against stale IDE/shell Redis settings.
        & (Join-Path $projectRoot 'gradlew.bat') bootRun "--args=--app.cache.enabled=$mode --spring.data.redis.host=127.0.0.1 --spring.data.redis.port=6379 --server.port=8080"
        if ($LASTEXITCODE -ne 0) { throw "Backend exited with code $LASTEXITCODE" }
    } finally {
        foreach ($key in $previous.Keys) { [Environment]::SetEnvironmentVariable($key, $previous[$key], 'Process') }
    }
}

function Invoke-Action([string]$Choice) {
    switch ($Choice) {
        'setup' { Invoke-Redis 'setup' }
        'redis-start' { Invoke-Redis 'start' }
        'on' { Start-LocalBackend $true }
        'off' { Start-LocalBackend $false }
        'set-on' { Set-CacheMode $true }
        'set-off' { Set-CacheMode $false }
        'status' {
            Write-Host "Saved local cache mode: $(Get-CacheMode) (takes effect after backend restart)"
            Write-Host "Redis localhost:6379 PONG: $(Get-RedisPing)"
            Write-Host 'Application cache: not implemented yet.'
        }
    }
}

Push-Location -LiteralPath $projectRoot
try {
    if ($Action -eq 'menu') {
        while ($true) {
            Write-Host "`nSmartTicketing LOCAL" -ForegroundColor Cyan
            Write-Host '1. Install/start Redis (Ubuntu must be ready)'
            Write-Host '2. Cache ON  + run backend'
            Write-Host '3. Cache OFF + run backend'
            Write-Host '4. IDE: save ON  (restart backend in IDE)'
            Write-Host '5. IDE: save OFF (restart backend in IDE)'
            Write-Host '6. Status'
            Write-Host '0. Exit'
            $choice = Read-Host 'Select'
            if ($choice -eq '0') { break }
            $options = @{ '1'='setup'; '2'='on'; '3'='off'; '4'='set-on'; '5'='set-off'; '6'='status' }
            if ($options.ContainsKey($choice)) {
                try { Invoke-Action $options[$choice] } catch { Write-Host $_.Exception.Message -ForegroundColor Red }
            }
        }
    } else { Invoke-Action $Action }
} finally { Pop-Location }
