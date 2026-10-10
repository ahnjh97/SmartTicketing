# 스마트 예매 재고 잠금 A/B 벤치마크

이 벤치마크는 개선 전 커밋의 회차·전체 좌석 잠금과 개선 후 구역·선택 좌석 잠금을 같은 조건으로 비교합니다. 운영 서버/운영 DB에서는 실행하지 마세요.

현재 예매 경로에서는 `app.booking.lock-inventory-pessimistic-write` 토글을 사용하지 않습니다. 같은 개선 버전에서 이 설정만 ON/OFF하면 개선 전후 비교가 되지 않습니다. `LOCK_MODE`는 리포트에 남기는 설명일 뿐 서버 설정을 바꾸지 않습니다.

스마트 선점과 실제 스마트 후보 생성 모두 필요한 필드만 스칼라 조회하며, 요청 안에서 좌석 배치 정보를 재사용합니다. 후보 생성 재시도는 가용·차단 좌석 ID/상태와 구역별 대기 수를 새로 읽습니다. 예약 중인 좌석의 상세 정보는 가져오지 않습니다. 연석·분할 배정과 중앙 좌석 우선순위를 유지하려면 최초 탐색에는 전체 배치 정보가 필요합니다.

최종 검증은 선택한 구역의 mutex를 확보한 뒤 해당 구역 상태만 다시 읽고, 실제 확보할 좌석만 PK로 쓰기 잠금합니다. 대기 배정도 구역 mutex 아래에서 읽은 배치를 재사용하면서 배정하는 좌석만 잠급니다. 일반예매는 선택 좌석이 걸친 구역을 일정한 순서로 잠근 뒤 좌석을 확보하고, 그 구역에서 배정 가능한 앞선 대기를 우선합니다. Redis 배정 게이트도 구역별로 동작합니다. 이 구조는 모든 예매 쓰기 경로가 같은 구역 잠금 규칙을 따른다는 전제가 있습니다.

쓰기 트랜잭션은 READ_COMMITTED를 사용하며 기존 `waiting_zone_sequences` 행을 `(회차, 구역)` 잠금으로 재사용합니다. 구역 변경은 이전·새 구역을 함께 잠그고, 취소·결제·만료는 관련 구역과 예약 좌석만 잠급니다. 구역이 없는 기존 대기는 모든 구역을 잠그는 호환 경로로 처리합니다.

회차를 처음부터 잠그고 좌석을 탐색하지는 않지만, 회차별 잔여석의 원자적 증감·기존 전역 대기번호·Outbox revision을 기록하는 마지막 쓰기 단계에는 회차 행 잠금이 남습니다. 여러 회차의 이 단계를 ID 순서로 진입하고, FK INSERT 전에 진입하여 공유 FK 잠금의 승격 교착을 피합니다. 따라서 모든 DB 경합이 없어졌다는 뜻은 아닙니다.

## 준비

- 로컬 Spring Boot 서버와 로컬 DB를 실행합니다.
- Node.js 20+ 및 k6가 설치되어 있어야 합니다.
- 테스트는 새 테스트 회원과 예매 그룹을 생성합니다. 기존 회원/예약은 건드리지 않습니다.
- 기본값은 VU 20명, 인원 1명입니다. `VUS`는 2~100, `PARTY_SIZE`는 1~6으로 설정할 수 있습니다.

## 1. 적용 전

개선 전 커밋(예: `41e8a91`)을 별도 체크아웃에서 빌드해 서버를 시작합니다. 서버에는 `APP_BOOKING_LOCK_INVENTORY_PESSIMISTIC_WRITE=true`를 명시하여 전체 좌석 잠금을 적용합니다. 저장소 루트 PowerShell에서 실행합니다.

```powershell
$env:VUS = "20"
$env:PARTY_SIZE = "1"
$env:OUT = ".local/booking-lock-benchmark/before-manifest.json"
node scripts/booking-lock-benchmark/prepare.mjs
$env:MANIFEST = ".local/booking-lock-benchmark/before-manifest.json"
$env:OUT = ".local/booking-lock-benchmark/before.json"
$env:LABEL = "before"
$env:LOCK_MODE = "baseline-full-inventory"
k6 run -e MANIFEST=$env:MANIFEST -e OUT=$env:OUT -e LABEL=$env:LABEL -e LOCK_MODE=$env:LOCK_MODE k6/booking-lock-benchmark/smart-booking-lock.js
node scripts/booking-lock-benchmark/cleanup.mjs .local/booking-lock-benchmark/before-manifest.json
```

자동 회차 검색이 실패하면 `SHOWTIME_ID`, `MOVIE_ID`, `THEATER_ID`, `VIEWING_DATE` 환경변수로 지정할 수 있습니다. 자동으로 선택된 회차 정보는 manifest에 저장됩니다.

## 2. 적용 후

개선 버전을 빌드한 서버로 교체한 뒤 실행합니다. 서버 설정은 적용 전과 동일하게 유지합니다. 이번에는 스마트 예매 전용 경로가 선택한 좌석에만 잠금을 겁니다.

```powershell
$env:SCENARIO_FROM = ".local/booking-lock-benchmark/before-manifest.json"
$env:OUT = ".local/booking-lock-benchmark/after-manifest.json"
node scripts/booking-lock-benchmark/prepare.mjs
$env:MANIFEST = ".local/booking-lock-benchmark/after-manifest.json"
$env:OUT = ".local/booking-lock-benchmark/after.json"
$env:LABEL = "after"
$env:LOCK_MODE = "optimized-zone-and-selected-inventory"
k6 run -e MANIFEST=$env:MANIFEST -e OUT=$env:OUT -e LABEL=$env:LABEL -e LOCK_MODE=$env:LOCK_MODE k6/booking-lock-benchmark/smart-booking-lock.js
node scripts/booking-lock-benchmark/cleanup.mjs .local/booking-lock-benchmark/after-manifest.json
node scripts/booking-lock-benchmark/report.mjs .local/booking-lock-benchmark/before.json .local/booking-lock-benchmark/after.json .local/booking-lock-benchmark/report.html
Start-Process .local/booking-lock-benchmark/report.html
```

> Windows PowerShell에서 환경변수는 서버를 시작하는 동일한 터미널에 설정해야 합니다. 이미 실행 중인 서버는 환경변수를 변경해도 설정이 바뀌지 않으므로 반드시 재시작하세요. 적용 전/후 실행 사이에 같은 로컬 DB와 동일한 회차를 사용하고, 첫 실행의 cleanup이 성공한 것을 확인한 뒤 다음 실험을 진행합니다.

## 측정 지표와 주의

- k6는 각 사용자/그룹에서 스마트 선점 API를 정확히 한 번 호출합니다. 그룹을 재사용하지 않아 그룹 상태 변화가 후속 반복을 왜곡하지 않게 합니다.
- HTML에는 평균, p50, p90, p95, p99, 성공, 409 충돌, 기타 HTTP 응답 및 네트워크 오류를 표시합니다.
- 같은 회차·같은 구역 경쟁과 같은 회차·서로 다른 구역 요청을 따로 측정하세요. p95뿐 아니라 성공/충돌 결과와 서버 로그의 deadlock/lock wait도 확인하세요. 회차별 집계·Outbox 기록의 짧은 쓰기 경합은 남아 있습니다.
- 이 설정은 벤치마크용입니다. 결과와 통합 테스트를 확인하기 전에는 운영 배포하지 마세요.
