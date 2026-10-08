param(
    [string]$ResultRoot = (Join-Path (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path 'benchmark-results\redis-query')
)

$ErrorActionPreference = 'Stop'
$offFiles = Get-ChildItem -LiteralPath $ResultRoot -Filter '*-off.json' -File
$onFiles = Get-ChildItem -LiteralPath $ResultRoot -Filter '*-on.json' -File
if ($offFiles.Count -eq 0 -or $onFiles.Count -eq 0) { throw "Both OFF and ON JSON results are required in $ResultRoot" }

function Get-Number($value) {
    if ($null -eq $value) { return 0.0 }
    return [double]$value
}

$rows = @()
foreach ($offFile in $offFiles) {
    $feature = $offFile.BaseName -replace '-off$',''
    $onFile = Join-Path $ResultRoot ($feature + '-on.json')
    if (-not (Test-Path $onFile)) { continue }

    $off = Get-Content -Raw -LiteralPath $offFile.FullName | ConvertFrom-Json
    $on = Get-Content -Raw -LiteralPath $onFile | ConvertFrom-Json
    $offP95 = Get-Number $off.metrics.httpReqDuration.'p(95)'
    $onP95 = Get-Number $on.metrics.httpReqDuration.'p(95)'
    $offP99 = Get-Number $off.metrics.httpReqDuration.'p(99)'
    $onP99 = Get-Number $on.metrics.httpReqDuration.'p(99)'
    $p95Improvement = if ($offP95 -gt 0) { (($offP95 - $onP95) / $offP95) * 100 } else { 0 }
    $p99Improvement = if ($offP99 -gt 0) { (($offP99 - $onP99) / $offP99) * 100 } else { 0 }

    $rows += [pscustomobject]@{
        feature = $feature
        offP95 = $offP95
        onP95 = $onP95
        offP99 = $offP99
        onP99 = $onP99
        p95Improvement = $p95Improvement
        p99Improvement = $p99Improvement
        offErrors = Get-Number $off.metrics.httpReqFailed.rate
        onErrors = Get-Number $on.metrics.httpReqFailed.rate
        offRequests = Get-Number $off.metrics.httpReqs.count
        onRequests = Get-Number $on.metrics.httpReqs.count
    }
}
$rows = $rows | Sort-Object feature
$generated = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'

function Charts([string]$percentile, [double]$max) {
    return ($rows | ForEach-Object {
        if ($percentile -eq 'p99') {
            $offValue = $_.offP99; $onValue = $_.onP99
        } else {
            $offValue = $_.offP95; $onValue = $_.onP95
        }
        $offWidth = if ($max -gt 0) { [Math]::Max(2, [Math]::Min(100, ($offValue / $max) * 100)) } else { 0 }
        $onWidth = if ($max -gt 0) { [Math]::Max(2, [Math]::Min(100, ($onValue / $max) * 100)) } else { 0 }
        "<section><h3>$($_.feature) · $percentile</h3><div class='bar-row'><span class='bar-label'>OFF</span><div class='bar-track'><div class='bar' style='width:$([Math]::Round($offWidth,1))%'></div></div><span class='bar-value'>$([Math]::Round($offValue,2)) ms</span></div><div class='bar-row'><span class='bar-label'>ON</span><div class='bar-track'><div class='bar' style='width:$([Math]::Round($onWidth,1))%'></div></div><span class='bar-value'>$([Math]::Round($onValue,2)) ms</span></div></section>"
    }) -join [Environment]::NewLine
}

$maxP95 = ($rows | ForEach-Object { $_.offP95; $_.onP95 } | Measure-Object -Maximum).Maximum
$maxP99 = ($rows | ForEach-Object { $_.offP99; $_.onP99 } | Measure-Object -Maximum).Maximum

$tableRows = ($rows | ForEach-Object {
    $status = if ($_.p95Improvement -gt 0) { 'improved' } elseif ($_.p95Improvement -lt 0) { 'regressed' } else { 'same' }
    "<tr><td>$($_.feature)</td><td>$([Math]::Round($_.offP95,2))</td><td>$([Math]::Round($_.onP95,2))</td><td>$([Math]::Round($_.p95Improvement,2))%</td><td>$([Math]::Round($_.offP99,2))</td><td>$([Math]::Round($_.onP99,2))</td><td>$([Math]::Round($_.p99Improvement,2))%</td><td>$([Math]::Round($_.offErrors * 100,3))%</td><td>$([Math]::Round($_.onErrors * 100,3))%</td><td class='$status'>$status</td></tr>"
}) -join [Environment]::NewLine

$p95Charts = Charts 'p95' $maxP95
$p99Charts = Charts 'p99' $maxP99

$html = @"
<!doctype html>
<html lang="ko">
<head>
<meta charset="utf-8">
<title>SmartTicketing Redis ON/OFF Performance Report</title>
<style>
body{font-family:Arial,"Malgun Gothic",sans-serif;margin:32px;background:#f6f7f9;color:#20242a}
h1{margin-bottom:6px}.meta{color:#666;margin-bottom:28px}
table{width:100%;border-collapse:collapse;background:#fff;margin:18px 0 34px}
th,td{border:1px solid #ddd;padding:10px 8px;text-align:center}
th{background:#eef1f5}td:first-child{text-align:left;font-weight:600}
.improved{font-weight:700}.regressed{font-weight:700}.same{color:#666}
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(360px,1fr));gap:18px}
section{background:#fff;border:1px solid #ddd;border-radius:10px;padding:16px}
.bar-row{display:flex;align-items:center;gap:10px;margin:12px 0}.bar-label{width:45px}
.bar-track{flex:1;height:24px;background:#e8ebef;border-radius:4px;overflow:hidden}.bar{height:100%;background:#5d6b7a}
.bar-value{width:90px;text-align:right;font-variant-numeric:tabular-nums}
.note{background:#fff;border-left:4px solid #5d6b7a;padding:14px 16px;margin-bottom:24px}
</style>
</head>
<body>
<h1>SmartTicketing Redis ON/OFF &#49457;&#45733; &#48708;&#44368;</h1>
<div class="meta">&#49373;&#49457; &#49884;&#44033;: $generated · &#45824;&#49345;: &#47196;&#52980; &#48177;&#50644;&#46300; · &#46041;&#51068; &#48512;&#54616; &#51312;&#44148;</div>
<div class="note">Redis OFF&#45716; &#44592;&#51316; MySQL &#51312;&#54924; &#44221;&#47196;, Redis ON&#51008; &#46041;&#51068; &#51312;&#54924; &#44221;&#47196;&#50640; 2&#52488; TTL Redis query cache&#47484; &#52628;&#44032;&#54620; &#44208;&#44284;&#51077;&#45768;&#45796;. &#50577;&#49688; &#44060;&#49440;&#50984;&#51008; ON&#51060; &#45908; &#48736;&#47476;&#45796;&#45716; &#46907;&#51077;&#45768;&#45796;.</div>
<h2>&#51333;&#54633; &#48708;&#44368;</h2>
<table>
<thead><tr><th>&#44592;&#45733;</th><th>OFF p95(ms)</th><th>ON p95(ms)</th><th>p95 &#44060;&#49440;&#50984;</th><th>OFF p99(ms)</th><th>ON p99(ms)</th><th>p99 &#44060;&#49440;&#50984;</th><th>OFF &#50724;&#47448;&#50984;</th><th>ON &#50724;&#47448;&#50984;</th><th>&#54032;&#51221;</th></tr></thead>
<tbody>$tableRows</tbody>
</table>
<h2>p95 &#44536;&#47000;&#54532;</h2><div class="grid">$p95Charts</div>
<h2>p99 &#44536;&#47000;&#54532;</h2><div class="grid">$p99Charts</div>
<h2>&#50896;&#48376; JSON</h2><p>각 &#44592;&#45733;별 OFF/ON 원본 결과는 같은 폴더에 보존됩니다.</p>
</body>
</html>
"@

$out = Join-Path $ResultRoot 'redis-cache-report.html'
[IO.File]::WriteAllText($out, $html, (New-Object Text.UTF8Encoding($false)))
$rows | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $ResultRoot 'redis-cache-report.json') -Encoding UTF8
Write-Host ('Report written: ' + $out)
