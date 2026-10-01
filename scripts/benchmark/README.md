# 주변 영화관 실제 API 성능 비교

기본 실행은 **실제 카카오 API + 실제 서울 영화관 DB 데이터**를 사용합니다.
검색 50ms/경로 100ms 같은 인위적인 지연은 없습니다. API 키나 실제 데이터가 없으면 중단하며 가상 데이터로 대체하지 않습니다.

## 준비

Python 3.12+, JDK 21, Git, 로컬 MySQL(127.0.0.1:3306)이 필요합니다.
원본 데이터는 팀원이 준비한 MySQL의 `theaters` 테이블에서 읽습니다. 먼저 팀원이 데이터를 적재한 환경에서 실행하거나 해당 데이터를 로컬 DB에 복원하세요.

```powershell
git fetch origin
Copy-Item scripts/benchmark/benchmark.env.example .env.benchmark
# 이미 파일이 있으면 덮어쓰지 말고 필요한 항목을 편집하세요.
```

`.env.benchmark`에 다음을 설정합니다. 이 파일은 Git에 올리지 않습니다.

```dotenv
# 임시 테스트 DB를 생성/삭제할 로컬 MySQL 계정
DB_USERNAME=root
DB_PASSWORD=로컬DB비밀번호

# 실제 서울 영화관 데이터가 있는 원본 DB (읽기 전용으로 접근)
BENCH_SOURCE_DB_URL=jdbc:mysql://127.0.0.1:3306/smart_ticketing?serverTimezone=Asia/Seoul
# 원본 DB 계정이 다르면 별도로 지정 (생략 시 DB_USERNAME/PASSWORD 사용)
# BENCH_SOURCE_DB_USERNAME=reader
# BENCH_SOURCE_DB_PASSWORD=원본DB비밀번호

KAKAO_MAP_REST_API_KEY=실제카카오REST_API키
```

`DB_URL`도 원본 URL의 대체 설정으로 읽습니다. `BENCH_SOURCE_DB_URL`이 우선합니다.
`BOOKING_TEST_MYSQL_USER/PASSWORD` 환경변수는 임시 DB 계정에 우선 적용됩니다.
원본 DB 계정에는 SELECT 권한, 임시 DB 계정에는 테스트 DB 생성/삭제 권한이 필요합니다.
기존 `.env.benchmark`가 이전 가상 모드용이면 원본 URL과 API 키를 추가해야 합니다.

## 실행

```powershell
# 실제 API 소규모 확인: 본 측정 18회 + 사전 검증/워밍업 18회
.\scripts\benchmark-nearby.ps1 -Runs 2 -WarmupRuns 1 -Rounds 1

# 본 측정: 세 브랜치 × 세 출발점 × 3라운드 × 100회
.\scripts\benchmark-nearby.ps1 -Runs 100 -WarmupRuns 20 -Rounds 3

# 실제 API를 호출하지 않고 세 브랜치 빌드만 검증
.\scripts\benchmark-nearby.ps1 -PrepareOnly
```

반복 횟수는 HTTP 요청 수입니다. 요청 하나가 영화관 수만큼 외부 경로 API를 호출하므로,
먼저 작은 실행에서 실제 응답과 계정 쿼터를 확인하세요. 실행시간은 실제 API 지연에 따라 달라집니다.
Python이 PATH에 없으면 `-Python 'C:\path\to\python.exe'`로 지정할 수 있습니다.
기존 `benchmark-nearby-500.ps1`도 동일한 실제 API 기본값으로 연결됩니다.
PowerShell 실행 정책에 막히면 `python scripts/benchmark/runner.py --runs 2 --warmup 1 --rounds 1`로 실행합니다.

## DB와 출발점

원본 DB에서 다음 조건으로 영화관을 **한 번만 읽어** `seoul-theaters.csv`로 고정합니다.

- `is_active = 1`
- 위도/경도가 존재
- `address`가 `서울`로 시작
- `kakao_place_id`, `name`, `brand`, `address`, `latitude`, `longitude` 컬럼 사용

주소 표기가 다르면 팀원 데이터와 필터를 맞춰야 합니다. 주소 조건은 행정구역 경계 GIS 판정이 아닙니다.
활성 서울 영화관이 없으면 측정을 시작하지 않습니다. 회원/예약 등 다른 테이블은 복사하지 않습니다.
원본 DB에는 INSERT/UPDATE/DELETE/DDL을 실행하지 않습니다.

브랜치·출발점·라운드마다 새 임시 DB에 **동일한 전체 서울 영화관 목록**을 적재합니다.
기본 출발점은 서울시청·강남·홍대 인근입니다. 이것은 조회자의 위치이며 영화관 좌표는 DB에서 가져옵니다.
실제 반경 10km 후보가 15개를 넘거나 여러 출발점에서 겹치는 경우도 지원합니다.

출발점을 바꾸려면 JSON을 만들어 `-Origins origins.json`으로 전달합니다.
영화관 목록이나 가상 경로 시간은 직접 입력하지 않습니다.

```json
[
  {"name": "seoul-cityhall", "latitude": 37.5665, "longitude": 126.9780},
  {"name": "seoul-gangnam", "latitude": 37.4979, "longitude": 127.0276}
]
```

## 비교 범위와 검증

| 브랜치 | 실제 수행 작업 |
|---|---|
| `origin/perf/api-original` | 실제 영화관 검색, 후보별 DB 조회/저장, 대중교통·도보 순차 호출, 최종 최대 15개 |
| `origin/perf/db-sequential` | DB 조회, 반경 필터, 실제 대중교통 순차 호출 |
| `origin/perf/benchmark-500` | DB 조회, 반경 필터, 실제 대중교통 8스레드 병렬 호출 |

실제 모드에서는 서비스의 카카오 URL과 처리 코드를 변경하지 않습니다.
사용 URL은 [카카오맵 공식 REST API 문서](https://developers.kakao.com/docs/ko/kakaomap/rest-api)의 경로 조회 API입니다.
현재 브랜치 구현에 따라 원본만 도보와 검색까지 호출합니다. 전체 개선과 순수 병렬화 효과를 구분하세요.

HTTP 200이어도 빈 결과, null 경로, 잘못된 정렬은 실패입니다. DB 두 버전은 DB 스냅샷의 후보 전체와 비교합니다.
원본은 외부 검색과 15개 제한 때문에 다른 후보를 반환할 수 있습니다. 실제 후보 집합을 별도 해시로 기록하고,
비교할 두 버전의 후보 집합이 다르거나 반복 중 변하면 **개선율을 출력하지 않습니다.** 응답시간·후보 수는 그대로 기록합니다.
후보 집합이 같더라도 경로 시간은 실제 API 상황에 따라 달라질 수 있습니다. 응답 내용 해시도 별도로 기록합니다.

출발점별 사전 검증 1회와 워밍업 뒤 본 측정을 합니다. 사전 검증이 실패하면 API 키/권한/경로 응답을 확인해야 합니다.
서비스가 외부 API 오류를 null로 바꾸는 경우도 실패로 처리하며 빠른 오류 응답을 개선으로 집계하지 않습니다.
반복 측정은 동시 사용자 1명이며 라운드마다 브랜치 순서를 회전합니다. 최대 처리량 부하 테스트는 아닙니다.

## 산출물 및 정리

`benchmark-results/<실행ID>/`에 아래 파일이 생성됩니다. 전체 폴더는 Git 제외입니다.

- `report.md`, `summary.csv`: 평균·p50·p95·오류율, 라운드별 요약
- `raw.csv`: 요청별 실제 HTTP 응답시간, 정상 여부, 후보 수/해시, 경로 결과 해시
- `seoul-theaters.csv`, `fixtures.json`, `seed.sql`: 고정한 DB 목록과 출발점별 후보
- `commits.json`, `environment.json`, `harness/`: 실제 커밋 SHA, 실행 조건, 데이터 해시, 도구 사본
- `sources/`, 빌드/서버 로그: 재현·진단 자료

응답시간은 요청 시작부터 본문 수신 완료까지이고 JSON 파싱/검증은 제외합니다. p95는 nearest-rank 방식입니다.
서로 다른 출발점을 섞어 백분위수를 계산하지 않습니다. 실제 모드의 외부 호출 수 컬럼은 미계측으로 비워 둡니다.
서버 로그의 병렬 API 시간 합계는 실제 경과시간과 다르므로 HTTP 응답시간을 대표 지표로 사용합니다.

Git 체크아웃을 바꾸지 않고 커밋별 소스를 빌드 폴더에 추출합니다. 별도 Git worktree는 만들지 않습니다.
Windows의 실제 JVM 실행 파일을 찾아 실행하고, 파일 신호로 Spring/DB 연결 풀을 닫은 뒤 임시 DB만 삭제합니다.
원본 DB는 그대로 두며, 강제 종료로 정리가 실패하면 `run.json`의 `nearby_bench_...` 이름과 로그로 확인합니다.
실제 데이터와 키를 아직 준비하지 않았다면 실제 성능 수치는 생성되지 않습니다.

## 도구 자체 테스트

```powershell
python -B -m unittest discover -s scripts/benchmark -p 'test_*.py' -v
```

`BENCH_TEST_MYSQL=1` 환경변수를 설정하면 전용 임시 DB를 사용하는 JDBC 스냅샷 통합 테스트도 실행합니다.
이 테스트는 원본 행을 변경하지 않고 활성 서울 좌표만 복사하는지 검사하며, 실제 API 성능 측정과는 별개입니다.

단위 테스트용 가상 응답 코드는 유지하지만 기본 실행에서는 사용하지 않습니다.
개발자가 명시적으로 `-Mode stub`을 지정한 경우에만 가상 데이터/지연 모드가 작동합니다.
그 결과는 실제 API 측정과 구분해서 표시되며 포트폴리오의 실제 성능 수치로 사용하지 않습니다.
