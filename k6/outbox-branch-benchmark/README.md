# 대기열 조회 성능 비교 (Docker 이미지 빌드 없음)

목표는 개선 전 `feature/jusang`과 개선 후 `feature/outbox-metrics`의 평균/p95/p99 응답시간과 처리량만 비교하는 것입니다. 대기열 그룹 ID와 aheadCount는 실행 시 API에서 자동 탐색합니다.

## 한 번만

저장소 루트에서 k6가 설치되어 있는지 확인합니다.

```powershell
k6 version
```

백엔드를 로컬에서 실행한 상태에서 아래 명령을 실행합니다. 최초 1회만 로컬 로그인 ID/비밀번호를 입력하면 로컬 전용 `fixture.local.json`에 저장됩니다. 비밀번호는 Git에 커밋되지 않습니다.

## 개선 전 측정

```powershell
.\k6\outbox-branch-benchmark\run.ps1 -Branch feature/jusang
```

## 개선 후 측정

현재 브랜치를 `feature/outbox-metrics`로 전환하고 백엔드를 다시 실행한 뒤 같은 명령을 실행합니다.

```powershell
.\k6\outbox-branch-benchmark\run.ps1 -Branch feature/outbox-metrics
```

결과는 `benchmark-results\feature-jusang.json` 및 `benchmark-results\feature-outbox-metrics.json`에 저장됩니다. 둘 다 보내주면 HTML 비교 보고서로 정리할 수 있습니다.

## 주의

- 이 테스트는 로컬 `GET /api/booking-groups/{groupId}/waiting-queues` 조회 성능만 측정합니다. Outbox 이벤트 생성부터 처리 완료까지의 지연을 직접 측정하지 않습니다.
- 로그인 실패 시 해당 계정이 로컬 DB에서 일반 로그인 가능한지 먼저 확인하세요. 운영 서버 계정/소셜 전용 계정은 로컬 로그인에 실패할 수 있습니다.
- active booking group이 하나도 없으면 로컬 앱에서 대기열/예매를 한 번 생성한 후 다시 실행하세요. ID나 예상 대기 인원은 직접 입력할 필요가 없습니다.
- 결과 비교는 두 브랜치에서 동일한 로컬 DB 상태, JVM 설정, 20 VU, 60초로 실행할 때 의미가 있습니다.
