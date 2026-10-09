# 좌석 선점 잠금 성능 비교 (k6)

이 도구는 `BookingHoldService.lockInventory()`의 기존 구현과 개선 구현을 같은 조건으로 비교합니다. 실제 예매 API인 `POST /api/booking-groups/{groupId}/manual-hold`를 호출하고 p95/p99, 처리량, 201 성공, 409 충돌, 예상 외 응답을 HTML 리포트로 만듭니다.

## 중요한 비교 조건

- 기존 버전: `lockInventory()`에서 `.setLockMode(LockModeType.PESSIMISTIC_WRITE)`를 적용한 코드
- 개선 버전: 해당 잠금 옵션을 제거한 코드
- **서버와 DB를 각각 분리**하고 테스트 데이터는 동일한 규모/상태로 준비하세요. 선점 요청은 그룹/좌석 상태를 변경하므로 같은 DB에 연속 실행하면 공정한 비교가 아닙니다.
- 각 fixture에는 실행당 한 번만 쓸 수 있는 서로 다른 `groupId`를 준비하세요. fixture의 `cases` 배열 길이는 `Vus × Iterations` 이상이어야 합니다.
- 각 케이스의 `loginId/password`, `groupId`, `seatIds`는 해당 서버/DB에 실제로 존재해야 합니다. 스크립트가 측정 전 각 계정으로 로그인해 토큰을 발급받습니다.
- 테스트 계정과 테스트 회차만 사용하세요. 운영 서버에 실행하지 마세요.

## 1. Fixture 준비

다음 두 파일을 로컬에서 만들고 비밀 토큰은 Git에 커밋하지 마세요.

`baseline-fixture.json`:
```json
{
  "cases": [
    { "loginId": "load_user_01", "password": "LOCAL_TEST_PASSWORD", "groupId": 1001, "seatIds": [501] },
    { "loginId": "load_user_02", "password": "LOCAL_TEST_PASSWORD", "groupId": 1002, "seatIds": [502] }
  ]
}
```

`optimized-fixture.json`도 같은 형태로, 개선 서버 DB의 실제 JWT/그룹/좌석 ID를 넣으세요. 예시는 형식 설명용 가짜 ID/계정이며 그대로 실행하면 안 됩니다. 비밀번호가 들어가는 fixture 파일은 Git에 커밋하지 마세요.

각 그룹은 선택된 회차가 설정되어 있고, 해당 `seatIds`가 그 회차의 좌석이어야 합니다. 성능 비교를 위해서는 가능한 한 좌석이 서로 겹치지 않는 케이스를 사용하세요. 같은 좌석 경쟁을 재현하고 싶다면 별도 경쟁 fixture를 만들고, 201/409 분포를 함께 해석하세요.

## 2. 실행

PowerShell에서 저장소 루트 기준:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\k6\booking-lock-benchmark\run-comparison.ps1 `
  -BaselineUrl "http://localhost:8081" `
  -OptimizedUrl "http://localhost:8082" `
  -BaselineFixture ".\baseline-fixture.json" `
  -OptimizedFixture ".\optimized-fixture.json" `
  -Vus 10 -Iterations 10
```

각 서버를 별도 포트로 실행하세요. 기존 서버는 잠금 옵션이 있는 코드, 개선 서버는 잠금 옵션을 제거한 코드여야 합니다. 두 서버의 DB도 분리하세요.

## 3. 결과

결과 위치: `benchmark-results/booking-lock/booking-lock-comparison.html`

함께 저장되는 원본:
- `baseline-summary.json`
- `optimized-summary.json`

HTML은 UTF-8 BOM과 `<meta charset="utf-8">`를 사용하므로 Windows 한국어 환경에서도 한글이 깨지지 않도록 저장됩니다.

## 측정 지표

- 평균 / p95 / p99 / 최대 응답시간
- 요청 처리량(요청/초)
- HTTP 요청 수
- 선점 성공(201), 충돌(409), 예상 외 응답 수

409는 서비스가 좌석 충돌 또는 그룹 상태 문제로 반환할 수 있는 응답입니다. 예상한 경쟁 테스트에서는 유효한 업무 결과일 수 있지만, 서로 다른 좌석을 배정한 테스트에서 409가 많다면 fixture와 서버 상태를 확인하세요.

## 주의

이 테스트는 **API 부하 비교 도구**이며, 별도의 동시성 정합성 검증을 대체하지 않습니다. 성능 수치가 좋아져도 중복 선점이나 재고 불일치가 발생하면 개선이 안전하다는 뜻은 아닙니다.
