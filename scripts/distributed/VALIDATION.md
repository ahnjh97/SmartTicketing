# 구현 후 로컬 검증 기록

2026-10-15, WSL 한 대 안의 MySQL 8.4·Redis 7·Nginx·백엔드 두 컨테이너로 확인했다.
**과거 로컬 검증 기록이며 EC2 다중 인스턴스 검증 결과가 아니다. TPS 개선율은 아직 측정하지 않았다.**

| 검증 | 결과 |
|---|---|
| 알림 단위·회귀 테스트 | 5개 통과. 원격 메시지 수신, 자기 메시지 무시, Redis 실패 시 로컬 전달, 커밋 이후 발행, 닫힌 SSE 연결 정리 |
| A/B 동일 좌석 경쟁 | 20개 요청 중 선점 1개, 다른 서버에서 결과 조회 성공 |
| DB 정합성 | 중복 활성 선점 0건, 잔여석 불일치 0건 |
| B 워커 → A SSE | A 워커를 끈 상태에서 B의 취소표 배정 알림 수신, DB 알림 조회 성공 |
| Outbox 재처리 | 인위적으로 남긴 5초 lease 만료 후 B가 재처리, 중복 알림·선점 없음 |
| 실제 백엔드 A 강제 종료 | Nginx 경유 조회 30건 성공, 실패 0건, A 재시작·readiness 확인. 일부 요청은 실패 서버 연결 대기 때문에 느려짐 |
| 두 인스턴스 예매 스모크 | 10초, 목표 2흐름/s. 선점·모의결제·취소 21흐름 완료, HTTP 오류·dropped iteration 0 |
| 한 인스턴스 조회 스모크 | B 중지 및 Nginx A 단독 전환 후 통과 |

Gradle 실행: `backend/gradlew test --tests smartticketing.service.NotificationPublisherTests --tests smartticketing.service.NotificationSseHubTests bootJar`.
검증 원본은 이 작업 환경의 `.local/distributed`에 저장했다. 초기 시행착오 결과도 남아 있으며,
최종 예매 검증은 `local-two-booking-verified.json`, 실제 중단 검증은 `outage-final.log`이다.

`recovery`는 미완료 lease 상태를 주입한 재처리 실험이다. `outage`는 실제 프로세스 중단 실험이다.
두 실험을 합쳐서 “작업 획득 직후 프로세스를 종료해 복구했다”고 서술하지 않는다.

다음 단계는 실행 가이드대로 같은 VPC의 실제 EC2 A/B/C를 연결하고 동일 데이터·부하·반복 조건에서 측정하는 것이다.
