# EC2 두 대의 분산 기능 검증

목표는 입장 제어·요청 분산·좌석 정합성 확인이다. 최대 수용 인원과 TPS는 측정하지 않는다.
EC2 A는 Nginx·백엔드 A·MySQL·Redis, B는 백엔드 B를 실행한다.
컨테이너 CPU·메모리 제한은 없다. 세 번째 EC2는 필요하지 않다.

서버 준비와 최초 스키마·fixture 생성은 [실행 가이드](README.md)의 1~2단계를 따른다.
[CD](CD.md)를 사용하면 이미지 빌드·전달·서버 갱신은 자동으로 처리된다.
아래 검증 명령은 EC2 A의 저장소 루트에서 실행한다. 직접 A/B로 요청하므로 외부에 백엔드 포트를 열 필요가 없다.
테스트 중에는 다른 사용자가 실험 환경에 접속하지 않도록 한다.

## 1. 접속 대기열과 로드밸런싱

A/B의 `scripts/distributed/.env`에 같은 값을 저장한다.

```dotenv
ADMISSION_ENABLED=true
ADMISSION_CAPACITY=100
ADMISSION_BATCH_SIZE=10
ADMISSION_NAMESPACE=distributed-lab
LAB_MODE=two
```

위 정원은 기존 설정 예시다. EC2 두 대를 배포한 뒤 테스트 인원과 함께 정한다. 서버의 최대 수용 인원을 의미하지 않는다.
`CLIENTS`(테스트 인원), `REQUESTS`(총 요청 수)를 실행 전에 직접 지정하고, 인원을 단계적으로 늘려 반복한다.
로드밸런싱의 `--clients`는 동시 HTTP 요청 작업 수다. 실제 사용자의 조회·예매 행동 전체를 재현하는 수치는 아니다.
대기열 검증에서는 `CLIENTS`를 설정한 정원보다 크게 지정한다.
기존 `.env`에도 위 항목을 추가한다. `init`을 다시 실행해 비밀번호를 바꾸지 않는다.

```bash
# B에서 먼저 실행
python3 scripts/distributed/lab.py up --role b
# A에서 실행
python3 scripts/distributed/lab.py up --role a
python3 scripts/distributed/lab.py preflight
python3 scripts/distributed/lab.py balancing --clients "$CLIENTS" --requests "$REQUESTS"
python3 scripts/distributed/lab.py admission --clients "$CLIENTS"
```

| 검증 | 통과 조건 |
|---|---|
| 로드밸런싱 | 직접 지정한 수의 Nginx 경유 readiness 요청이 모두 성공하고 A/B upstream이 모두 관측됨 |
| 전체 정원 | 설정한 정원만큼 입장하고 초과 인원은 대기. 상태 반복 조회 중 관측 입장자가 정원을 넘지 않음 |
| 입장권 공유 | 같은 입장 쿠키로 A/B 양쪽의 영화 조회 성공 |
| 우회 차단 | 대기 사용자의 영화 조회는 A/B 양쪽에서 429, 쿠키 없는 프록시 요청도 429 |
| 퇴장·추가 입장 | 입장자 일부(최대 5명) 퇴장 후 같은 수의 기존 대기자가 입장하고 전체 입장 수가 다시 정원과 같아짐 |

스크립트는 각 사용자의 쿠키를 별도로 유지한다. 검증 후 자신의 입장·대기 세션만 퇴장 처리한다.
Redis 전체 데이터를 지우지 않는다. 전용 namespace를 사용하며 다른 세션이 남아 있으면 통과하지 못할 수 있다.
이 검증은 관측한 상태와 요청 결과에 대한 증거이며, 모든 순간의 정원을 계측하는 성능 실험은 아니다.
로드밸런싱은 두 upstream을 실제 확인하며 정확한 50:50 비율은 요구하지 않는다.

## 2. A/B 동시 예매

두 서버의 `.env`에서 `ADMISSION_ENABLED=false`로 바꾸고 각각 `up --role b`, `up --role a`를 다시 실행한다.
이 단계는 입장 제어와 분리해 DB의 동시 선점을 검증한다. 대기열을 끄지 않으면 `race`가 중단된다.

```bash
# A: EC2 준비 후 지정한 인원·횟수로 반복
python3 scripts/distributed/lab.py race --clients "$CLIENTS" --rounds "$ROUNDS"
python3 scripts/distributed/lab.py invariant
```

회차별 통과 조건은 선점 성공 1건, 나머지 요청은 선점 충돌, 활성 예약의 중복 좌석 0건, 잔여석 불일치 0건이다.
선점 결과를 반대 서버에서 조회하고 취소한 뒤 다음 회차를 실행한다.
예매 검증의 `--clients`는 현재 fixture의 사용자 수 때문에 2~200명까지 가능하다. 더 큰 인원은 fixture부터 확장한다.
이는 서버의 수용 한도가 아니다. `--rounds`는 1~100회다. 로드밸런싱·대기열 검증에는 200명 상한을 두지 않는다.
실패한 회차는 원인을 확인하고 DB 상태를 점검한 뒤 재실행한다.

## 결과 보관

실행별 성공·실패와 원인은 `.local/distributed/*-{action}.json`에 저장된다.
`balancing`에는 upstream별 요청 수, `admission`에는 입장·대기·추가 입장 수,
`race`에는 회차별 A/B 요청 수·성공·충돌·DB 정합성 결과가 남는다.
서버 유형, 코드 커밋, 이미지 ID, 테스트 설정과 Nginx 로그도 함께 보관한다.
쿠키·인증 토큰·비밀번호는 포트폴리오에 포함하지 않는다.

알림, Outbox 복구, 백엔드 중단, k6 성능 비교는 기존 가이드의 선택 검증이다.
이번 두 시나리오를 통과한 사실을 최대 수용 인원이나 EC2 전체 장애 대응의 근거로 쓰지 않는다.
