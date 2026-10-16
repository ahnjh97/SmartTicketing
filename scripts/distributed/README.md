# 두 노트북의 백엔드 검증 (Redis ON 고정)

목표: 같은 좌석의 중복 선점 방지, 서버 간 알림, Outbox 재처리, 백엔드 1개/2개의 성능을 검증한다.
서버 A 전체 고가용성, 실제 운영 최대 TPS, Redis 단독 개선율을 증명하는 실험은 아니다.

## 이미 구현된 것 / 추가한 것

| 항목 | 구현과 검증 |
|---|---|
| 좌석 정합성 | 기존 MySQL 구역·좌석 잠금 및 트랜잭션. `race`로 A/B 동시 요청과 DB 결과 확인 |
| Outbox | 기존 `SKIP LOCKED` 작업 획득, lease 만료 재처리, token 기반 완료 처리. `recovery`로 기한 만료와 중복 처리 확인 |
| 서버 간 알림 | 추가한 Redis Pub/Sub 전달. DB 커밋 후 발행, 자기 메시지 재전달 방지, Redis 실패가 완료된 예매를 실패시키지 않음 |
| 알림 복구 | 기존 DB 알림 조회 및 SSE 재접속 시 `connected` 이벤트를 통한 프런트 재조회. Pub/Sub 자체는 영속 메시지 큐가 아님 |
| 서버 중단 | `outage`가 실제 백엔드 A만 SIGKILL하고 Nginx 경유 성공·실패·복구 시간을 기록 |
| 성능 | 동일 이미지·Redis ON, 인스턴스당 동일 자원, 고정 도착률 k6. 예열 결과는 본 측정과 별도 저장 |

## 장비와 연결

| 장비 | 실행 | 공개 포트 |
|---|---|---|
| A / LAN | Nginx, 백엔드 A, MySQL, 업무 Redis, 입장 Redis | 8088, 8080, 3306, 6379, 6380 |
| B / LAN | 백엔드 B | 8080 |
| C / Wi-Fi | k6 | 없음 |

A/B는 WSL Linux 컨테이너의 Docker Engine + Compose v2, A는 Python 3.12+, C는 Docker가 필요하다.
세 장비는 같은 사설망을 사용한다. 지원되는 Windows 11의 WSL mirrored networking을 준비하고
Docker 공개 포트가 실제 LAN 주소에 bind 가능한지 확인한다. NAT 모드에서는 별도 Windows→WSL 전달 설정이 필요하다.
Windows/Hyper-V 방화벽은 필요한 포트와 테스트 장비 IP만 허용한다. 인터넷 포트포워딩은 하지 않는다.
WSL 설명: https://learn.microsoft.com/en-us/windows/wsl/networking

A/B는 **동일 DB URL 문자열**을 쓴다. 일부 Redis 키와 알림 채널이 DB URL로 이름 공간을 나눈다.
각 컨테이너의 `db.internal`, `redis.internal`은 A의 LAN IP로 해석된다.
JWT·DB·Redis 비밀번호도 동일해야 한다. 두 PC의 시간 동기화와 절전 해제를 확인한다.

## 1. 이미지와 설정 준비

아래 명령은 저장소 루트의 WSL 터미널에서 실행한다. Docker 권한이 없으면 해당 환경의 관리자 권한으로 실행한다.

```bash
python3 scripts/distributed/lab.py init
# scripts/distributed/.env의 SERVER_A_IP, SERVER_B_IP를 실제 LAN IP로 변경
docker build -f backend/Dockerfile --target source -t smartticketing-distributed:local .
mkdir -p .local/distributed
docker save -o .local/distributed/backend.tar smartticketing-distributed:local
```

B에 동일 저장소, `backend.tar`, A에서 생성한 `.env`를 복사한다. B에서 별도 init을 실행하지 않는다.

```bash
docker load -i .local/distributed/backend.tar
```

각 서버에서 `docker image inspect smartticketing-distributed:local --format '{{.Id}}'`의 값이 같은지 확인한다.
`.env`, fixture 토큰, 결과는 커밋하지 않는다. 생성된 비밀번호는 실험 전용이다.

## 2. A의 전용 DB 초기화 → 데이터 적재 → B 시작

```bash
# A: 최초 빈 전용 DB에만 update로 스키마 생성
DDL_MODE=update docker compose --env-file scripts/distributed/.env -f scripts/distributed/compose.a.yml up -d --wait backend-a
python3 scripts/distributed/lab.py fixture
# 이후 validate로 전환. 운영 .env나 운영 볼륨을 사용하지 않는다.
docker compose --env-file scripts/distributed/.env -f scripts/distributed/compose.a.yml up -d --wait
```

```bash
# B
docker compose --env-file scripts/distributed/.env -f scripts/distributed/compose.b.yml up -d --wait
```

```bash
# A: 두 대로 라우팅
LAB_MODE=two docker compose --env-file scripts/distributed/.env -f scripts/distributed/compose.a.yml up -d --force-recreate nginx
python3 scripts/distributed/lab.py preflight
```

fixture는 내일 날짜의 20회차 × 100석, 사용자 200명을 만든다. 기존 사용자가 있으면 덮어쓰지 않고 중단한다.
토큰은 24시간 유효하다. 기한이 지나면 새 실험 DB로 다시 준비한다.
`preflight`는 연결·준비 상태 확인이다. 실제 요청 분산은 Nginx 로그의 upstream으로 확인한다.

## 3. 기능 검증 (성능 측정과 분리)

```bash
# A: 20명이 A/B를 통해 같은 좌석에 동시에 요청. 한 명만 성공해야 한다.
python3 scripts/distributed/lab.py race

# A 워커만 끄고 B 워커가 알림을 생성하도록 강제한다. A의 SSE 서버는 유지된다.
A_OUTBOX_ENABLED=false docker compose --env-file scripts/distributed/.env -f scripts/distributed/compose.a.yml up -d --wait --no-deps backend-a
python3 scripts/distributed/lab.py notification
python3 scripts/distributed/lab.py recovery

# 실제 A 프로세스 중단과 Nginx의 B 전환. finally에서 A를 다시 시작한다.
python3 scripts/distributed/lab.py outage
python3 scripts/distributed/lab.py invariant
```

- `notification`: A에 SSE 연결 → B로 취소 → B 워커가 대기자 배정 → A에서 알림·DB 조회 확인.
  측정 시간은 **취소 요청부터 SSE 수신까지**이며 순수 Redis 전달 지연이 아니다.
- `recovery`: 완료된 실제 이벤트에 **인위적인 미완료 lease 상태**를 주입한다. 5초 기한 후 B의 재처리와
  중복 알림·중복 좌석 여부를 확인한다. 실제 프로세스를 claim 직후 종료한 증거와 혼동하지 않는다.
- `outage`: 실제 프로세스 종료를 검증하지만 그 순간 Outbox 작업 중이었다는 보장은 없다.
  실패 요청 수도 결과에 남기며 무중단이라고 가정하지 않는다.
- 검증 결과/실패 원인은 `.local/distributed/*-{action}.json`에 저장된다.
- 기능 실험 후 성능 측정 전에는 A 워커를 기본값 ON으로 복원하고 새 fixture를 사용한다.

## 4. 1대 / 2대 전환

**1대:** B에서 `docker compose --env-file scripts/distributed/.env -f scripts/distributed/compose.b.yml stop backend-b`.
A에서 `LAB_MODE=one docker compose --env-file scripts/distributed/.env -f scripts/distributed/compose.a.yml up -d --force-recreate nginx`.
B를 라우팅에서만 제외하지 말고 워커까지 완전히 멈춘다.

**2대:** B에서 `docker compose --env-file scripts/distributed/.env -f scripts/distributed/compose.b.yml up -d --wait`.
A에서 `LAB_MODE=two docker compose --env-file scripts/distributed/.env -f scripts/distributed/compose.a.yml up -d --force-recreate nginx`.

전환 중 부하를 중단한다. 측정 전 A/B의 자원·이미지·DB 풀·Redis TTL을 동일하게 유지한다.
기본 인스턴스당 2 CPU/2GB, JVM 최대 1GB, DB 풀 10이다. 장비 사양에 맞춰 **양쪽 동일하게** 조정한다.
A는 DB와 자원을 공유하고 B는 원격 DB를 사용한다는 비대칭을 결과에 적는다.

## 5. C에서 부하 발생

A의 `.local/distributed/fixture.json`을 C의 같은 경로로 복사한다. C에는 DB 비밀번호나 `.env`가 필요 없다.
모든 성능 실행은 A의 **같은 Nginx 주소**로 요청한다. 현재 환경은 HTTP 내부망 전용이다.

```bash
# C: 예열 60초 + 본 측정 3분, 기본 조회 10회/s에서 시작
BASE_URL=http://192.168.0.10:8088 NODES=1 SUITE=query RATE=10 bash scripts/distributed/run-load.sh
# 실제 구성을 두 대로 변경한 뒤 같은 조건 실행
BASE_URL=http://192.168.0.10:8088 NODES=2 SUITE=query RATE=10 bash scripts/distributed/run-load.sh
# 예매는 별도 실행. RATE는 요청/s가 아닌 선점→결제→취소 흐름/s
BASE_URL=http://192.168.0.10:8088 NODES=2 SUITE=booking RATE=5 bash scripts/distributed/run-load.sh
```

`NODES`는 결과 라벨이지 원격 서버 전환 명령이 아니다. 실제 구성과 일치시킨다.
조회는 좌석도/시간표를 교대로 호출한다. 예매는 VU별 다른 좌석을 사용하고 취소해 재고를 재활용한다.
예매 완료 수는 결제 성공 시, 전체 흐름 완료 수는 취소까지 성공했을 때 센다.
조회 JSON 검사, API별 p95, 오류율, dropped iteration을 검사하고 실패 시 종료 코드를 반환한다.
MAX_VUS는 기본 100, fixture 한도 200이다. 한도에 막히면 서버 최대 TPS라고 해석하지 않는다.

측정 순서: 낮은 부하에서 기능 확인 → 같은 부하 비교 → 단계적으로 상승 → 조건별 4회 교차 반복.
각 실행 전 새 DB·캐시로 시작해 동일한 예열을 수행한다. DB와 Redis는 ON, 입장 기능은 OFF,
Nginx IP 제한은 없는 **업무 API 성능 실험**이다. 입장 제한 실험과 섞지 않는다.
기본 API별 p95 기준 1000ms와 오류율 기준은 실험 전에 정하고 중간에 유리하게 바꾸지 않는다.

## 6. 결과 근거 수집

`python3 scripts/distributed/report.py`로 실행별 처리량·오류·누락·기준 통과 여부와 API별 p95를 비교한다.
예열·스모크는 기본 제외하며, 표만으로 통계적 유의성이나 절대 최대 처리량을 주장하지 않는다.

- C: `*-measurement.json`의 성공 요청/s, 완료 예매/s, API별 p95·p99, 오류율, dropped_iterations.
- A/B: 측정 중 `docker stats`로 CPU·메모리·네트워크 관찰 및 저장. C의 CPU·Wi-Fi 부하도 확인.
- A: `docker compose --env-file scripts/distributed/.env -f scripts/distributed/compose.a.yml logs --no-color nginx`
  로 upstream별 실제 분산과 장애 응답 확인. DB의 CPU, 활성 연결, InnoDB 상태도 같은 시간대에 수집.
- 코드 커밋/미커밋 diff, 이미지 ID, PC 사양, WSL 제한, 연결 풀, 요청률, 반복 번호, 연결 방식 기록.
- 기능 검증 중 중복 선점/재고 불일치가 하나라도 있으면 성능이 좋아도 성공으로 판정하지 않는다.
- Wi-Fi 병목을 배제하지 못했다면 최고 관측 처리량으로만 보고한다. 최종 수치는 C 유선 재측정을 권장한다.

## 전용 실험 데이터 초기화 / 종료

다음 명령은 **이 실험의 DB·Redis 데이터만 삭제**한다. 필요한 JSON과 로그를 먼저 보관한다.
B를 먼저 중지한 뒤 A에서 실행하고 2단계부터 다시 시작한다.

```bash
docker compose --env-file scripts/distributed/.env -f scripts/distributed/compose.a.yml down -v
```

데이터를 보관하고 종료할 때는 A/B에서 각 compose 파일로 `stop`만 실행한다.

## 개발자: 한 WSL에서 두 인스턴스 기능 확인

`compose.local.yml`은 A 프로젝트에 B를 추가하고 8081을 공개한다. 실제 두 호스트 성능 결과로 사용하지 않는다.
`.env`의 SERVER_A_IP에는 Docker 호스트에서 접근 가능한 WSL IP를 지정한다.

```bash
LAB_MODE=two docker compose --env-file scripts/distributed/.env -f scripts/distributed/compose.a.yml -f scripts/distributed/compose.local.yml up -d --wait
# preflight/race/notification 실행 시 B 주소만 8081로 지정
LAB_B_URL=http://WSL_IP:8081 python3 scripts/distributed/lab.py preflight
```
