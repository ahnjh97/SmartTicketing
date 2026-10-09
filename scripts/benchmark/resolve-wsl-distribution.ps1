function Resolve-BenchmarkWslDistribution {
    param([Parameter(Mandatory = $true)][string]$Distribution)

    # WSL's distribution list is UTF-16LE, including under Windows PowerShell 5.1.
    $previousEncoding = [Console]::OutputEncoding
    try {
        [Console]::OutputEncoding = [Text.Encoding]::Unicode
        $installed = @(& wsl.exe --list --quiet | ForEach-Object { $_.Trim() } | Where-Object { $_ })
        $listExitCode = $LASTEXITCODE
    } finally {
        [Console]::OutputEncoding = $previousEncoding
    }
    if ($listExitCode -ne 0) { throw 'Cannot list WSL distributions. Run wsl --list --verbose for details.' }

    $exact = @($installed | Where-Object { $_ -eq $Distribution })
    if ($exact.Count -gt 0) { return $exact[0] }

    if ($Distribution -in @('Ubuntu', 'Ubuntu-26.04')) {
        $alternate = if ($Distribution -eq 'Ubuntu') { 'Ubuntu-26.04' } else { 'Ubuntu' }
        $match = @($installed | Where-Object { $_ -eq $alternate })
        if ($match.Count -gt 0) {
            Write-Host "WSL: $Distribution is not registered; using $($match[0])."
            return $match[0]
        }
    }
    throw "WSL distribution '$Distribution' is not installed. Installed: $($installed -join ', '). Use -Distribution with an installed name."
}
