# EC2 두 대의 분산 백엔드 검증 (Redis ON 고정)

이번 작업의 기본 검증은 [EC2 두 대의 분산 기능 시나리오](SCENARIOS.md)다.
접속 대기열·로드밸런싱과 동시 예매만 확인하며, 아래의 별도 부하 발생기·성능 측정은 선택 사항이다.
자동 배포는 [두 EC2용 CD 설정](CD.md)을 따른다. CD를 쓰면 아래의 수동 이미지 빌드·전달 과정은 생략한다.

목표: 같은 좌석의 중복 선점 방지, 서버 간 알림, Outbox 재처리, 백엔드 1개/2개의 성능을 검증한다.
서버 A 전체 고가용성, 실제 운영 최대 TPS, Redis 단독 개선율을 증명하는 실험은 아니다.

## 이미 구현된 것 / 추가한 것

| 항목 | 구현과 검증 |
|---|---|
| 좌석 정합성 | 기존 MySQL 구역·좌석 잠금 및 트랜잭션. `race`로 A/B 동시 요청과 DB 결과 확인 |
| Outbox | 기존 `SKIP LOCKED` 작업 획득, lease 만료 재처리, token 기반 완료 처리. `recovery`로 기한 만료와 중복 처리 확인 |
| 서버 간 알림 | 추가한 Redis Pub/Sub 전달. DB 커밋 후 발행, 자기 메시지 재전달 방지, Redis 실패가 완료된 예매를 실패시키지 않음 |
| 알림 복구 | 기존 DB 알림 조회 및 SSE 재접속 시 `connected` 이벤트를 통한 프런트 재조회. Pub/Sub 자체는 영속 메시지 큐가 아님 |
| 서버 중단 | `outage`가 실제 백엔드 A만 강제 중단하고 Nginx 경유 성공·실패·복구 시간을 기록 |
| 성능 | 동일 이미지·Redis ON, EC2 사양 그대로 사용, 고정 도착률 k6. 예열 결과는 본 측정과 별도 저장 |

## EC2 구성과 연결

로컬 WSL 여러 컨테이너 대신 **같은 VPC의 서로 다른 Linux EC2 A/B**에서 백엔드를 하나씩 실행한다.
Docker는 각 EC2의 실행 도구로만 사용한다. 기능 검증은 A에서 실행하므로 EC2는 A/B 두 대만 필요하다.
선택적으로 k6 성능 실험까지 진행할 때만 별도 부하 발생기 C를 준비한다.

| 인스턴스 | 실행 | 사설 IP에 바인딩하는 포트 |
|---|---|---|
| EC2 A | Nginx, 백엔드 A, MySQL, 업무 Redis, 입장 Redis | 8088, 8080, 3306, 6379, 6380 |
| EC2 B | 백엔드 B | 8080 |
| C (선택) | k6 | 없음 |

A/B는 같은 CPU 아키텍처와 Docker Engine + Compose v2, Python 3.12+가 필요하다.
별도 부하 발생기 C를 사용한다면 Docker와 Bash가 필요하다. Docker 이미지 빌드·다운로드와 저장소 복제를 위한 외부 연결도 준비한다.
컨테이너 CPU·메모리 할당량과 고정 JVM 힙 크기는 지정하지 않는다. 각 EC2의 자원을 사용하며,
JVM 힙은 기본 자동 산정에 맡긴다. A는 MySQL·Redis·Nginx도 함께 실행한다.

`SERVER_A_IP`, `SERVER_B_IP`는 EC2 콘솔의 **프라이빗 IPv4 주소**다.
퍼블릭 IP나 Elastic IP는 컨테이너 포트 바인딩에 쓰지 않는다.
A/B는 동일 DB URL 문자열을 사용하며, 컨테이너의 `db.internal`, `redis.internal`은 A의 사설 IP를 가리킨다.
JWT·DB·Redis 비밀번호와 이미지도 같아야 한다. 시간 동기화를 확인한다.

### 보안 그룹 인바운드

A/B/C에 각각 SG-A/SG-B/SG-C를 연결하고 필요한 TCP 포트만 허용한다.

| 대상 | TCP 포트 | 허용 소스 |
|---|---|---|
| A | 3306, 6379, 6380 | SG-B |
| A | 8080 | SG-C (진단이 필요할 때만) |
| A | 8088 | SG-C |
| B | 8080 | SG-A, SG-C |
| A/B/C | 22 | 관리자의 공인 IP /32 또는 사용하는 관리 접속 경로 |

기본 아웃바운드가 제한되어 있다면 대응하는 사설 목적지·포트도 허용한다.
DB·Redis·백엔드 포트는 인터넷 전체에 열지 않는다. 현재 실험은 VPC 내부 HTTP API 전용이다.
로컬에서 볼 때는 SSH 포트 전달을 사용한다. 예: `ssh -L 8088:10.0.1.10:8088 ubuntu@A_PUBLIC_IP`.
[AWS 사설 IP 설명](https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/using-instance-addressing.html)과
[보안 그룹 참조 규칙](https://docs.aws.amazon.com/vpc/latest/userguide/security-group-rules.html)을 기준으로 설정한다.

A 한 대에 DB·Redis·프록시가 모여 있으므로 **A 인스턴스 전체 장애는 복구하지 못한다**.
여기서는 백엔드 분산과 백엔드 프로세스 중단만 검증한다. 기존 운영 환경과 분리된 실험 환경이며,
CD에서 `DEPLOY_TARGET=distributed`를 선택해 배포할 수 있다.

## 1. 이미지와 설정 준비

아래 명령은 EC2 A의 저장소 루트에서 실행한다. B에도 같은 커밋의 저장소를 준비한다.
기존 운영 인스턴스·DB와 분리된 실험용 EC2를 사용한다.

```bash
python3 scripts/distributed/lab.py init --server-a-ip 10.0.1.10 --server-b-ip 10.0.1.11
# 위 주소는 실제 A/B의 사설 IPv4로 바꾼다. 비밀번호와 JWT 키는 자동 생성된다.
python3 scripts/distributed/lab.py config
docker build -f backend/Dockerfile --target source -t smartticketing-distributed:ec2 .
mkdir -p .local/distributed
docker save -o .local/distributed/backend.tar smartticketing-distributed:ec2
```

B에 동일 커밋의 저장소, `backend.tar`, A에서 생성한 `scripts/distributed/.env`를 SSH/SCP 등으로 안전하게 복사한다.
B에서 별도 init을 실행하지 않는다. 파일 위치는 A와 같게 두고 양쪽에서 `chmod 600 scripts/distributed/.env`를 실행한다.

```bash
docker load -i .local/distributed/backend.tar
```

각 서버에서 `docker image inspect smartticketing-distributed:ec2 --format '{{.Id}}'`의 값이 같은지 확인한다.
`.env`, fixture 토큰, 결과는 커밋하지 않는다. 생성된 비밀번호는 실험 전용이다.

## 2. A의 전용 DB 초기화 → 데이터 적재 → B 시작

```bash
# A: 시작 명령은 임의로 이미지를 내려받지 않으므로 기반 이미지를 먼저 준비한다.
docker compose --env-file scripts/distributed/.env -f scripts/distributed/compose.a.yml pull mysql redis admission-redis nginx
# A: 최초 빈 전용 DB에만 update로 스키마 생성
DDL_MODE=update docker compose --env-file scripts/distributed/.env -f scripts/distributed/compose.a.yml up -d --wait backend-a
python3 scripts/distributed/lab.py fixture
# 이후 validate로 전환. 운영 .env나 운영 볼륨을 사용하지 않는다.
python3 scripts/distributed/lab.py up --role a
```

```bash
# B
python3 scripts/distributed/lab.py up --role b
```

```bash
# A: 기본 LAB_MODE=two로 두 EC2에 라우팅한다. B가 준비된 뒤 확인한다.
python3 scripts/distributed/lab.py preflight
```

`up`은 해당 EC2에서 실행하며, 설정 검증 후 로드된 이미지로만 시작한다.
초기 스키마 생성은 위의 `DDL_MODE=update` 명령으로 한 번만 수행한다. 이후 기본값은 `validate`다.
서비스는 Docker가 시작될 때 자동 재시작한다. EC2에서도 Docker 서비스의 자동 시작을 활성화한다.

fixture는 내일 날짜의 20회차 × 100석, 사용자 200명을 만든다. 기존 사용자가 있으면 덮어쓰지 않고 중단한다.
토큰은 24시간 유효하다. 기한이 지나면 새 실험 DB로 다시 준비한다.
`preflight`는 연결·준비 상태 확인이다. 실제 요청 분산은 Nginx 로그의 upstream으로 확인한다.

## 3. 기능 검증 (성능 측정과 분리)

```bash
# A: EC2 준비 후 CLIENTS를 정한다. 같은 좌석은 한 명만 선점해야 한다.
python3 scripts/distributed/lab.py race --clients "$CLIENTS"

# A 워커만 끄고 B 워커가 알림을 생성하도록 강제한다. A의 SSE 서버는 유지된다.
A_OUTBOX_ENABLED=false python3 scripts/distributed/lab.py up --role a
python3 scripts/distributed/lab.py notification
python3 scripts/distributed/lab.py recovery

# A를 timeout=0으로 강제 중단하고 B 전환 확인. finally에서 A를 다시 시작한다.
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

A의 `scripts/distributed/.env`에 `LAB_MODE=one` 또는 `two`를 저장한 뒤 아래처럼 전환한다.
명령 앞의 환경 변수만 바꾸면 다음 전체 시작 때 `.env` 값으로 돌아가므로 파일도 맞춘다.

**1대:** B에서 `docker compose --env-file scripts/distributed/.env -f scripts/distributed/compose.b.yml stop backend-b`.
A에서 `LAB_MODE=one docker compose --env-file scripts/distributed/.env -f scripts/distributed/compose.a.yml up -d --force-recreate nginx`.
B를 라우팅에서만 제외하지 말고 워커까지 완전히 멈춘다.

**2대:** B에서 `docker compose --env-file scripts/distributed/.env -f scripts/distributed/compose.b.yml up -d --wait`.
A에서 `LAB_MODE=two docker compose --env-file scripts/distributed/.env -f scripts/distributed/compose.a.yml up -d --force-recreate nginx`.

전환 중 부하를 중단한다. 1대/2대 비교 중에는 EC2 인스턴스 유형·이미지·DB 풀·Redis TTL을 유지한다.
컨테이너 자원 제한 없이 실행한다. 기존 `.env`의 `BACKEND_CPUS`, `BACKEND_MEMORY`, `JAVA_TOOL_OPTIONS`는
더 이상 분산 실행 설정에서 참조하지 않는다. DB 풀 기본값 10은 별도의 연결 수 설정이며 측정 후 조정한다.
A는 DB와 자원을 공유하고 B는 원격 DB를 사용한다는 비대칭을 결과에 적는다.

## 5. EC2 C에서 부하 발생

A의 `.local/distributed/fixture.json`을 C의 같은 경로로 복사한다. C에는 DB 비밀번호나 `.env`가 필요 없다. fixture에는 인증 토큰이 있으므로 접근 권한을 제한한다.
모든 성능 실행은 A의 **같은 Nginx 주소**로 요청한다. 현재 환경은 HTTP 내부망 전용이다.

```bash
# C: 예열 60초 + 본 측정 3분, 기본 조회 10회/s에서 시작
BASE_URL=http://10.0.1.10:8088 NODES=1 SUITE=query RATE=10 bash scripts/distributed/run-load.sh
# 실제 구성을 두 대로 변경한 뒤 같은 조건 실행
BASE_URL=http://10.0.1.10:8088 NODES=2 SUITE=query RATE=10 bash scripts/distributed/run-load.sh
# 예매는 별도 실행. RATE는 요청/s가 아닌 선점→결제→취소 흐름/s
BASE_URL=http://10.0.1.10:8088 NODES=2 SUITE=booking RATE=5 bash scripts/distributed/run-load.sh
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
- A/B: 측정 중 `docker stats`로 CPU·메모리·네트워크 관찰 및 저장. C의 CPU·네트워크 부하도 확인.
- A: `docker compose --env-file scripts/distributed/.env -f scripts/distributed/compose.a.yml logs --no-color nginx`
  로 upstream별 실제 분산과 장애 응답 확인. DB의 CPU, 활성 연결, InnoDB 상태도 같은 시간대에 수집.
- 코드 커밋/미커밋 diff, 이미지 ID, EC2 인스턴스 유형·아키텍처·AZ, CPU 크레딧 상태, 연결 풀, 요청률, 반복 번호, 연결 방식 기록.
- 기능 검증 중 중복 선점/재고 불일치가 하나라도 있으면 성능이 좋아도 성공으로 판정하지 않는다.
- C의 자원·네트워크 병목이나 버스트형 EC2의 CPU 크레딧 소진 여부를 기록한다. 병목을 배제하지 못한 수치는 최고 관측 처리량으로만 보고한다.

## 전용 실험 데이터 초기화 / 종료

다음 명령은 **이 실험의 DB·Redis 데이터만 삭제**한다. 필요한 JSON과 로그를 먼저 보관한다.
B를 먼저 중지한 뒤 A에서 실행하고 2단계부터 다시 시작한다.

```bash
docker compose --env-file scripts/distributed/.env -f scripts/distributed/compose.a.yml down -v
```

데이터를 보관하고 종료할 때는 A/B에서 각 compose 파일로 `stop`만 실행한다.

## 주소 확인과 검증 범위

`python3 scripts/distributed/lab.py config`는 비밀번호를 출력하지 않고 A/B/프록시 주소와 이미지·모드를 확인한다.
실제 통신은 `preflight`로 확인한다. 필요한 경우 `LAB_A_URL`, `LAB_B_URL`, `LAB_PROXY_URL`로
진단용 HTTP 주소를 바꿀 수 있다. 이 값은 `outage`에도 동일하게 적용되고 실제 서비스의 바인딩 주소는 바꾸지 않는다.

기존 [VALIDATION.md](VALIDATION.md)는 과거 로컬 검증 기록이다. EC2 결과는 새로 실행해 수집해야 한다.
