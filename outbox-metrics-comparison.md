# SmartTicketing 브랜치 비교 보고서

- 기준 브랜치: origin/feature/jusang (ae016b7)
- 비교 대상 브랜치: origin/feature/outbox-metrics (b861080)
- 생성 시각: 2026-10-09 21:39:07

> 이 보고서는 코드 변경량과 모니터링 코드의 존재 여부를 비교합니다. 코드 추가만으로 성능 향상을 입증할 수는 없습니다. 실제 성능 비교는 동일한 조건으로 실행한 k6 결과가 있어야 가능합니다.

## 1. 전체 코드 변경 요약

| 항목 | 값 |
|---|---:|
| 변경된 파일 수 | 16 |
| 추가된 줄 수 | 98 |
| 삭제된 줄 수 | 769 |
| 순증감 줄 수 | -671 |

## 2. Outbox 모니터링 기능 비교

| 검사 항목 | 기준 브랜치 | 비교 대상 브랜치 | 의미 |
|---|---:|---:|---|
| Spring Boot Actuator 의존성 | 없음 | 있음 | 운영 지표를 노출할 기반 |
| Micrometer Counter (처리 건수·재시도 횟수) | 없음 | 있음 | 처리 건수와 재시도 횟수 측정 |
| Micrometer Timer (처리 시간) | 없음 | 있음 | 작업 처리 시간 측정 |
| Micrometer Gauge (대기 작업 수) | 없음 | 있음 | 현재 대기 중인 Outbox 작업 수 관측 |
| Outbox 미처리 건수 조회 | 없음 | 있음 | 처리 대기 중인 Outbox 이벤트 수 조회 |
| Outbox/Actuator 설정 | 있음 | 있음 | 관련 실행 설정 |

- 확인된 모니터링 항목: 기준 브랜치 1/6, 비교 대상 브랜치 6/6
- 확인 항목 수 변화:  증가

## 3. 파일별 변경 내역

| 파일 | 추가된 줄 수 | 삭제된 줄 수 |
|---|---:|---:|
| build.gradle | 4 | 0 |
| gradle/wrapper/gradle-wrapper.properties | 3 | 3 |
| k6/booking-lock-benchmark/booking-lock-test.js | 0 | 100 |
| scripts/benchmark/README.md | 0 | 23 |
| scripts/benchmark/run-waiting-rank-comparison.ps1 | 0 | 132 |
| scripts/benchmark/run-waiting-rank.ps1 | 0 | 47 |
| scripts/benchmark/waiting-rank-comparison-report.mjs | 0 | 38 |
| src/main/java/smartticketing/service/BookingHoldService.java | 3 | 5 |
| src/main/java/smartticketing/service/BookingOutboxStore.java | 7 | 0 |
| src/main/java/smartticketing/service/BookingOutboxWorker.java | 27 | 3 |
| src/main/java/smartticketing/service/BookingWaitingOutboxHandler.java | 4 | 10 |
| src/main/java/smartticketing/service/BookingWaitingProjection.java | 9 | 56 |
| src/main/java/smartticketing/service/BookingWaitingRanks.java | 0 | 83 |
| src/main/java/smartticketing/service/BookingWaitingService.java | 28 | 68 |
| src/main/resources/application.properties | 12 | 3 |
| src/test/java/smartticketing/booking/BookingWaitingRedisTests.java | 1 | 198 |

## 4. 현재 결과 해석

- **모니터링 기능:** 후보 브랜치에서 확인된 모니터링 항목이 5개 늘었습니다. 이는 관측 기능의 추가이며, 그 자체로 예매 성능 향상을 뜻하지는 않습니다.
- **코드 변경 주의:** 후보 브랜치에서 추가한 줄보다 삭제한 줄이 671줄 많습니다. 특히 대기열 코드와 테스트 삭제가 기능 동작에 영향을 주는지 확인해야 합니다.
- **실제 성능:** k6 결과가 아직 없어 응답 시간·실패율·처리량이 개선됐는지 판정할 수 없습니다.

## 5. k6 부하 테스트 성능 비교

k6 결과 JSON이 제공되지 않아 응답 시간, 실패율, 처리량의 개선율을 계산하지 않았습니다.
두 브랜치에서 동일한 조건으로 k6 테스트를 실행하고 결과 JSON을 저장한 뒤 다음 명령을 실행하세요:

    .\scripts\compare-outbox-metrics.ps1 -BaseK6Json .\baseline-summary.json -CandidateK6Json .\outbox-summary.json
