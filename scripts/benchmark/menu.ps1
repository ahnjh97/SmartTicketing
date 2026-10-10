function Read-BenchmarkChoice([string]$Title, [string[]]$Options) {
    Write-Host "`n$Title" -ForegroundColor Cyan
    for ($i = 0; $i -lt $Options.Count; $i++) { Write-Host "$($i + 1). $($Options[$i])" }
    Write-Host '0. 취소'
    while ($true) {
        $answer = Read-Host '번호'
        if ($answer -eq '0' -or $null -eq $answer) { throw [OperationCanceledException]::new('메뉴를 종료합니다.') }
        $number = 0
        if ([int]::TryParse($answer, [ref]$number) -and $number -ge 1 -and $number -le $Options.Count) { return $number }
        Write-Host '목록에 있는 번호를 입력하세요.' -ForegroundColor Yellow
    }
}

function Select-BenchmarkRef([string]$Root, [string]$Role) {
    $kind = Read-BenchmarkChoice "$Role 선택" @('로컬·원격 브랜치 목록', '최근 커밋 목록', '커밋 SHA·태그·브랜치 직접 입력')
    $gitBase = @('-c', "safe.directory=$($Root.Replace('\','/'))", '-C', $Root)
    if ($kind -eq 1) {
        Write-Host '원격 목록은 마지막 fetch 기준입니다. 자동 fetch는 하지 않습니다.'
        $refs = @(& git @gitBase for-each-ref '--format=%(refname:short)' refs/heads refs/remotes | Where-Object { $_ -notmatch '/HEAD$' })
        if ($LASTEXITCODE -ne 0 -or $refs.Count -eq 0) { throw '브랜치를 조회하지 못했습니다.' }
        $selected = $refs[(Read-BenchmarkChoice $Role $refs) - 1]
    } elseif ($kind -eq 2) {
        $commits = @(& git @gitBase log --all -30 '--format=%H %s')
        if ($LASTEXITCODE -ne 0 -or $commits.Count -eq 0) { throw '커밋을 조회하지 못했습니다.' }
        $selected = $commits[(Read-BenchmarkChoice $Role $commits) - 1].Split(' ')[0]
    } else { $selected = Read-Host "$Role 커밋 SHA / 태그 / 브랜치" }
    if ([string]::IsNullOrWhiteSpace($selected)) { throw '빈 커밋은 선택할 수 없습니다.' }
    $sha = (& git @gitBase rev-parse --verify --end-of-options "$selected^{commit}" | Out-String).Trim()
    if ($LASTEXITCODE -ne 0) { throw "커밋을 찾을 수 없습니다: $selected" }
    Write-Host "$Role : $selected → $sha" -ForegroundColor Green
    return $sha
}

function Read-BenchmarkMenu([string]$Root) {
    $choice = Read-BenchmarkChoice 'WSL Docker 테스트' @(
        'DB 개선: 개선 전 OFF ↔ 최종 OFF',
        'Redis 효과: 최종 커밋 OFF ↔ ON',
        '종합 효과: 개선 전 OFF ↔ 최종 ON',
        '위 세 비교 모두 실행 (개선 전 OFF / 최종 OFF / 최종 ON)',
        '현재 작업 코드 실행',
        '기존 개별 기능 테스트')
    $settings = @{ Mode='site'; Refs=@(); Comparison='all'; Suite='all'; Smoke=$false; CacheMode='branch' }
    if ($choice -eq 6) {
        $settings.Mode = 'components'
        $suites = @('all','booking','main','movies','theaters','showtimes','seats','distance','login')
        $settings.Suite = $suites[(Read-BenchmarkChoice '개별 기능' $suites) - 1]
        $settings.CacheMode = @('compare','off','on')[(Read-BenchmarkChoice 'Redis' @('OFF ↔ ON', 'OFF', 'ON')) - 1]
    } elseif ($choice -le 4) {
        $settings.Mode = 'compare'
        $settings.Comparison = @('db','redis','overall','all')[$choice - 1]
        if ($choice -ne 2) { $settings.Refs += Select-BenchmarkRef $Root '개선 전' }
        $settings.Refs += Select-BenchmarkRef $Root '최종'
        if ($settings.Refs.Count -eq 2 -and $settings.Refs[0] -eq $settings.Refs[1]) { throw '개선 전과 최종이 같은 커밋입니다. Redis 효과 메뉴를 사용하세요.' }
    } else {
        $settings.CacheMode = @('branch','off','on')[(Read-BenchmarkChoice 'Redis' @('배포 Compose 기본값', 'OFF', 'ON')) - 1]
    }
    if ($settings.Mode -ne 'components') {
        $suites = @('all','browse','smart','manual','login')
        $settings.Suite = $suites[(Read-BenchmarkChoice '측정 시나리오' @('전체 흐름', '조회', '스마트예매', '일반예매', '로그인')) - 1]
    }
    $defaultProfile = if ($settings.Mode -eq 'components') { '기존 시나리오의 기본 부하' } else { '기본 부하 (사용자 100명, 60초)' }
    $profiles = @('빠른 기능 확인 (스모크)', $defaultProfile, '직접 설정')
    if ($settings.Mode -ne 'components') { $profiles += '단계별 부하 (지연·오류 기준을 통과한 최고 관측 처리량)' }
    $profile = Read-BenchmarkChoice '실행 규모' $profiles
    $settings.Smoke = $profile -eq 1
    if ($profile -eq 3) {
        $fields = @(@('Users','동시 사용자 수',1,10000), @('Clients','k6 컨테이너 수',1,100), @('Repeats','반복 횟수',1,10))
        if ($settings.Mode -eq 'components') {
            Write-Host 'booking은 기존 100/400/800 VU 프로필을 사용합니다. 아래 설정은 조회·로그인 테스트에 적용됩니다.'
            $fields = @(@('Rate','조회 초당 요청 수',1,10000), @('LoginUsers','로그인 사용자 수',1,10000))
        }
        foreach ($field in $fields) {
            do { $raw = Read-Host $field[1]; $n = 0; $valid = [int]::TryParse($raw,[ref]$n) -and $n -ge $field[2] -and $n -le $field[3] } until ($valid)
            $settings[$field[0]] = $n
        }
        do { $duration = Read-Host '실행 시간 (예: 60s, 3m)' } until ($duration -match '^[1-9][0-9]*(s|m)$')
        $settings.Duration = $duration
    }
    if ($profile -eq 4) {
        do {
            $raw = Read-Host '사용자 단계 (Enter: 25,50,100,200)'
            if ([string]::IsNullOrWhiteSpace($raw)) { $raw = '25,50,100,200' }
            $valid = $raw -match '^\d+(,\d+)+$'
            if ($valid) {
                try { $levels = @($raw.Split(',') | ForEach-Object { [int]$_ } | Sort-Object -Unique) } catch { $valid = $false }
                if ($valid) { $valid = $levels.Count -ge 2 -and $levels[0] -ge 1 -and $levels[-1] -le 10000 }
            }
        } until ($valid)
        $settings.UserLevels = $levels
        do {
            $raw = Read-Host '업무 응답 p95 기준 ms (Enter: 1000)'
            if ([string]::IsNullOrWhiteSpace($raw)) { $raw = '1000' }
            $limit = 0
        } until ([int]::TryParse($raw,[ref]$limit) -and $limit -ge 1 -and $limit -le 100000)
        $settings.P95LimitMs = $limit
        Write-Host '각 사용자 단계에서 구성별로 60초씩 실행합니다. 각 실행은 새 DB로 시작합니다.'
    }
    Write-Host "`n실행: $($settings.Mode) / $($settings.Comparison) / $($settings.Suite) / 스모크=$($settings.Smoke)"
    Write-Host '단계별 부하도 지정 범위 내 관측치입니다. 부하 생성기와 WSL 자원을 포함한 결과이며 절대 최대치를 확정하지 않습니다.'
    return $settings
}
