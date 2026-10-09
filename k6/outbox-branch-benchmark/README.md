# Outbox branch benchmark (no Docker image build)

이 도구는 로컬에서 실행 중인 SmartTicketing 백엔드의 대기열 조회 API를 k6로 측정합니다. Docker 이미지 빌드는 하지 않습니다.

## 1. 준비

- k6 설치: `k6 version`
- 테스트할 브랜치의 백엔드와 로컬 DB/Redis를 평소 방식으로 실행
- `fixture.example.json`을 복사해 `fixture.local.json`을 만들고 실제 로컬 테스트 계정 정보를 입력
- 계정 소유의 유효한 대기열 그룹 ID와 해당 응답의 `items[0].aheadCount` 값을 입력
- 해당 그룹에는 대기열 항목이 정확히 하나 있어야 합니다.

비밀번호가 포함된 `fixture.local.json`은 Git에 커밋하지 마세요. 운영 서버에서는 실행하지 마세요.

## 2. 브랜치별 실행

저장소 루트에서 실행합니다. 먼저 아래 파일을 로컬에서 복사/수정하세요.

```powershell
Copy-Item .\k6\outbox-branch-benchmark\fixture.example.json .\k6\outbox-branch-benchmark\fixture.local.json
# fixture.local.json을 실제 테스트 계정/groupId/aheadCount로 수정
New-Item -ItemType Directory -Force .\benchmark-results | Out-Null

$env:BASE_URL = "http://localhost:8080"
$env:FIXTURE = (Resolve-Path ".\k6\outbox-branch-benchmark\fixture.local.json").Path
$env:VUS = "20"
$env:DURATION = "60s"
$env:BRANCH = "feature/jusang"
$env:SUMMARY = ".\benchmark-results\feature-jusang.json"
k6 run .\k6\outbox-branch-benchmark\waiting-rank.js
```

각 브랜치에서 서버를 재시작한 뒤 동일한 테스트를 실행하세요. 두 번째 브랜치에서는 `BRANCH`를 `feature/outbox-metrics`, `SUMMARY`를 `.\benchmark-results\feature-outbox-metrics.json`으로 바꿉니다. 각 브랜치의 결과 JSON은 별도 보관합니다.

비교의 공정성을 위해 동일한 VU 수, duration, 테스트 데이터 규모, JVM 설정을 유지하세요. 가능하면 각 브랜치에서 3회 실행하고 결과를 모두 보관하세요. 테스트 중 같은 데이터가 바뀌거나 대기열 상태가 달라지면 결과가 왜곡될 수 있습니다.

## 3. 측정 범위와 한계

측정 항목은 평균/p95/p99/max 응답시간, HTTP 요청 처리량, 정상 응답률, 대기열 항목 수와 `aheadCount` 일치 여부입니다. 이 API는 조회 전용입니다.

이 테스트는 Redis 대기열 프로젝션을 읽는 경로의 성능을 비교합니다. **비동기 Outbox 이벤트가 발생부터 처리 완료까지 걸리는 시간 자체를 직접 측정하지는 않습니다.** 결과를 해석할 때 이 범위를 명시하세요.

401/403/404/5xx 또는 `aheadCount` 불일치가 있으면 성능 결과를 유효하다고 보지 말고 먼저 fixture와 서버 로그를 확인하세요.
