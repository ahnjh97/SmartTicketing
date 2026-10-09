# 스마트 예매 재고 잠금 A/B 벤치마크

이 벤치마크는 `BookingHoldService.lockInventory(showId)`의 현재 동작과 `PESSIMISTIC_WRITE`를 좌석 재고 조회에도 적용한 동작을 같은 회차에서 비교합니다. 별도 브랜치 `benchmark/pessimistic-write`에서만 실행하세요. 운영 서버/운영 DB에서는 실행하지 마세요.

## 준비

- 로컬 Spring Boot 서버와 로컬 DB를 실행합니다.
- Node.js 20+ 및 k6가 설치되어 있어야 합니다.
- 테스트는 새 테스트 회원과 예매 그룹을 생성합니다. 기존 회원/예약은 건드리지 않습니다.
- 기본값은 VU 20명, 인원 1명입니다. `VUS`는 2~100, `PARTY_SIZE`는 1~6으로 설정할 수 있습니다.

## 1. 적용 전

저장소 루트 PowerShell에서 실행합니다.

```powershell
$env:VUS = "20"
$env:PARTY_SIZE = "1"
$env:OUT = ".local/booking-lock-benchmark/before-manifest.json"
node scripts/booking-lock-benchmark/prepare.mjs
$env:MANIFEST = ".local/booking-lock-benchmark/before-manifest.json"
$env:OUT = ".local/booking-lock-benchmark/before.json"
$env:LABEL = "before"
$env:LOCK_MODE = "false"
k6 run -e MANIFEST=$env:MANIFEST -e OUT=$env:OUT -e LABEL=$env:LABEL -e LOCK_MODE=$env:LOCK_MODE k6/booking-lock-benchmark/smart-booking-lock.js
node scripts/booking-lock-benchmark/cleanup.mjs .local/booking-lock-benchmark/before-manifest.json
```

자동 회차 검색이 실패하면 `SHOWTIME_ID`, `MOVIE_ID`, `THEATER_ID`, `VIEWING_DATE` 환경변수로 지정할 수 있습니다. 자동으로 선택된 회차 정보는 manifest에 저장됩니다.

## 2. 적용 후

서버를 재시작해 환경변수 `APP_BOOKING_LOCK_INVENTORY_PESSIMISTIC_WRITE=true`를 적용합니다. Spring의 `@Value` 기본값은 false라서 적용 전에는 기존 쿼리를 사용합니다.

```powershell
$env:SCENARIO_FROM = ".local/booking-lock-benchmark/before-manifest.json"
$env:OUT = ".local/booking-lock-benchmark/after-manifest.json"
node scripts/booking-lock-benchmark/prepare.mjs
$env:MANIFEST = ".local/booking-lock-benchmark/after-manifest.json"
$env:OUT = ".local/booking-lock-benchmark/after.json"
$env:LABEL = "after"
$env:LOCK_MODE = "true"
k6 run -e MANIFEST=$env:MANIFEST -e OUT=$env:OUT -e LABEL=$env:LABEL -e LOCK_MODE=$env:LOCK_MODE k6/booking-lock-benchmark/smart-booking-lock.js
node scripts/booking-lock-benchmark/cleanup.mjs .local/booking-lock-benchmark/after-manifest.json
node scripts/booking-lock-benchmark/report.mjs .local/booking-lock-benchmark/before.json .local/booking-lock-benchmark/after.json .local/booking-lock-benchmark/report.html
Start-Process .local/booking-lock-benchmark/report.html
```

> Windows PowerShell에서 환경변수는 서버를 시작하는 동일한 터미널에 설정해야 합니다. 이미 실행 중인 서버는 환경변수를 변경해도 설정이 바뀌지 않으므로 반드시 재시작하세요. 적용 전/후 실행 사이에 같은 로컬 DB와 동일한 회차를 사용하고, 첫 실행의 cleanup이 성공한 것을 확인한 뒤 다음 실험을 진행합니다.

## 측정 지표와 주의

- k6는 각 사용자/그룹에서 스마트 선점 API를 정확히 한 번 호출합니다. 그룹을 재사용하지 않아 그룹 상태 변화가 후속 반복을 왜곡하지 않게 합니다.
- HTML에는 평균, p50, p90, p95, p99, 성공, 409 충돌, 기타 HTTP 응답 및 네트워크 오류를 표시합니다.
- `PESSIMISTIC_WRITE`는 추가 DB row lock을 발생시키므로 더 느려질 수도 있습니다. p95만 보지 말고 성공/충돌 결과와 서버 로그의 deadlock/lock wait도 같이 확인하세요.
- 이 설정은 벤치마크용입니다. 결과와 통합 테스트를 확인하기 전에는 운영 배포하지 마세요.
