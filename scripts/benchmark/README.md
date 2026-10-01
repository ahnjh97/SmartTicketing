# 주변 영화관 조회 반복 벤치마크

현재 체크아웃을 변경하지 않고 아래 remote-tracking ref를 실행 시작 시 SHA로 고정해 비교합니다.
자동 fetch는 하지 않습니다. 원격 변경을 반영하려면 실행 전에 `git fetch origin`을 실행하세요.

| 비교 대상 | 실제 수행 작업 |
|---|---|
| `origin/perf/api-original` | 브랜드 검색 3회, 후보별 DB 조회/저장, 대중교통과 도보 순차 호출, 최종 15개 반환 |
| `origin/perf/db-sequential` | DB 조회, 10km 필터, 대중교통만 순차 호출 |
| `origin/perf/benchmark-500` | DB 조회, 10km 필터, 공용 8스레드로 대중교통 호출 |

원본 → DB 순차의 개선에는 작업 제거와 응답 필드 변화가 포함됩니다. DB 순차 → 병렬은 같은 대중교통 결과에 대한 병렬화 비교입니다.
원본의 도보 필드/장소 URL까지 동일한 응답 계약이라고 주장하면 안 됩니다.

## 실행

필수: Python 3.12 이상(표준 라이브러리만 사용), Java/JDK 21, Git, 로컬 MySQL `127.0.0.1:3306`.
Gradle wrapper는 필요한 배포본/의존성을 다운로드할 수 있어야 합니다.
DB 계정에는 새 테스트 DB를 생성/삭제할 권한이 필요합니다.

### 다른 컴퓨터에서 처음 준비

```powershell
git clone --branch perf/benchmark-500 https://github.com/ahnjh97/SmartTicketing.git
cd SmartTicketing
git fetch origin
Copy-Item scripts/benchmark/benchmark.env.example .env.benchmark
# .env.benchmark의 DB_USERNAME / DB_PASSWORD를 이 컴퓨터의 MySQL 계정으로 수정
.\scripts\benchmark-nearby.ps1 -PrepareOnly
.\scripts\benchmark-nearby.ps1 -Runs 2 -WarmupRuns 1 -Rounds 1 -EnvFile .env.benchmark
```

이미 받은 저장소에서는 작업을 보존한 뒤 `git switch perf/benchmark-500`, `git pull --ff-only`,
`git fetch origin`으로 업데이트하세요. 단일 브랜치로 clone했다면 비교 브랜치도 명시적으로 가져옵니다.

```powershell
git fetch origin refs/heads/perf/api-original:refs/remotes/origin/perf/api-original refs/heads/perf/db-sequential:refs/remotes/origin/perf/db-sequential refs/heads/perf/benchmark-500:refs/remotes/origin/perf/benchmark-500
```

실행 정책으로 ps1이 차단되면 시스템 정책을 변경하지 않고 `python scripts/benchmark/runner.py`에
`--runs 2 --warmup 1 --rounds 1 --env-file .env.benchmark`를 전달해 실행할 수 있습니다.
`-PrepareOnly`는 DB 연결 없이 빌드만 확인하며, 이후 작은 실행에서 DB 권한·응답을 검증합니다.
기존 `benchmark-nearby-500.ps1` 파일도 같은 공통 도구로 연결됩니다.
기존 스크립트의 `Port`, `Sort`, `Latitude`, `Longitude`, `Address` 옵션은 사용하지 않습니다.
포트는 자동 할당하고 정렬은 TRANSIT으로 고정하며, 위치는 `-Fixtures` JSON으로 지정합니다.

```powershell
# 기존 .env에서 DB_USERNAME / DB_PASSWORD만 읽어 고정 응답 모드 실행
# DB_URL은 사용하지 않습니다. 기존 애플리케이션 DB를 건드리지 않습니다.
.\scripts\benchmark-nearby.ps1 -Runs 2 -WarmupRuns 1 -Rounds 1 -EnvFile .env

# 본 측정: 시나리오당 브랜치당 300회, 총 2,700회 (워밍업/사전 검증 별도)
.\scripts\benchmark-nearby.ps1 -Runs 100 -WarmupRuns 20 -Rounds 3 -EnvFile .env

# Python이 PATH에 없으면 실행 파일의 절대 경로를 지정
.\scripts\benchmark-nearby.ps1 -Python 'C:\path\to\python.exe' -EnvFile .env

# DB 없이 세 스냅샷의 빌드만 검증
.\scripts\benchmark-nearby.ps1 -PrepareOnly

# 도구 자체 회귀 테스트 (DB/JDK/API 키 불필요)
python -m unittest discover -s scripts/benchmark -p 'test_*.py' -v
```

환경변수 `BOOKING_TEST_MYSQL_USER` / `BOOKING_TEST_MYSQL_PASSWORD`가 있으면 DB_USERNAME / DB_PASSWORD보다 우선합니다.
`.env`는 실행 코드로 평가하지 않습니다. 단순 KEY=VALUE 형식 및 바깥 따옴표만 지원합니다.
토큰·DB 비밀번호·API 키는 결과 파일이나 명령 인수에 기록하지 않습니다.

## 공통 실행 방식

1. 세 SHA의 백엔드 소스를 `git archive`로 결과 폴더에 추출합니다. 현재 브랜치나 Git index는 변경하지 않습니다.
   worktree 대신 소스 스냅샷을 사용해 도구 실행에 Git 메타데이터 쓰기 권한이 필요하지 않습니다.
2. 세 스냅샷에 동일한 작은 오버레이를 적용합니다: 외부 API URL 환경변수 주입, 시작 시 공통 영화관 fixture 삽입 및 준비 파일 생성.
   각 스냅샷의 `benchmark-overlay.json`에 변경 설명과 서비스 파일 전후 해시가 남습니다.
   측정 대상 메서드, 순차/병렬 처리, 성능 로그/저장 코드는 유지합니다.
   병렬 브랜치의 생성자 컴파일 오류는 원격의 `4fbb14e`에서 수정되었습니다.
   도구는 컴파일 오류를 자동 보정하지 않습니다. 오래된 원격 추적 ref에서 오류가 나면 `git fetch origin`으로 갱신하세요.
3. 각각 `bootJar`로 빌드합니다. 앱의 기존 설정을 완전히 대체하여 영화 가져오기·예매 시드·OAuth 외부 연동을 끕니다.
4. **브랜치 × 라운드 × 위치마다** UUID 기반 새 DB를 만들고 동일한 전체 데이터셋을 넣습니다.
   Java 프로세스 종료 후 이 도구가 성공적으로 생성한 DB만 삭제합니다. 기존 DB를 초기화하지 않습니다.
5. 한 번에 서버 하나만 실행합니다. 준비 확인 후 공통 테스트 JWT로 실제 인증 필터를 통과합니다.
   로그인 API는 응답시간에 포함하지 않습니다. 운영 사용자의 토큰은 필요하지 않습니다.
6. 사전 검증 1회, 워밍업, 본 측정을 순서대로 수행합니다. 라운드별 A/B/C, B/C/A, C/A/B 순으로 회전합니다.
   같은 HTTP 연결을 재사용하며 클라이언트 동시성은 1입니다. 성능 측정 DB 기록은 세 구현 모두 기존대로 유지합니다.

영화관은 원본에도 미리 적재하므로 반복 조회 상태를 비교합니다. 최초 영화관 수집/INSERT 비용을 비교하는 실험은 아닙니다.
원본의 `save()` 호출이 매번 실제 SQL UPDATE를 발생시킨다는 의미도 아닙니다.
새 DB를 생성해도 MySQL 서버 전체의 버퍼 캐시를 비우지는 않으며, 서버 재시작과 워밍업 조건을 동일하게 맞춥니다.

기본 고정 응답 모드는 서로 10km 이상 떨어진 세 출발점에 영화관 3/9/15개를 배치합니다.
영화관 검색 지연은 50ms, 각 경로 호출 지연은 100ms이며 대역 서버는 요청을 동시에 처리합니다.
세 후보 규모의 결과는 위치별로 따로 비교합니다. 15개 제한과 후보 차이 때문에 생기는 불공정 비교를 방지합니다.
모든 API 응답은 합성 데이터이며 실제 서울/분당/수원의 영화관이나 경로를 의미하지 않습니다.

세밀한 옵션:

```powershell
python scripts/benchmark/runner.py --runs 100 --warmup 20 --rounds 3 --route-ms 100 --search-ms 50 --env-file .env
```

기본 설정은 워밍업/사전 검증을 포함한 대역 서버 지연만 계산해도 약 55분입니다. 빌드·앱 시작·DB/HTTP 시간이 추가됩니다.
먼저 작은 실행으로 검증하세요. 비교 중 다른 무거운 작업은 피하고 같은 장비/JDK/DB 설정을 유지하세요.

## 정상 응답 검증

- HTTP 상태를 실제 값으로 기록합니다. HTTP 200이라도 빈 배열/누락 경로는 실패입니다.
- 후보 식별자/개수, 중복, 대중교통 시간·거리, 정렬을 검증합니다. 원본은 도보 시간도 검증합니다.
- 고정 응답 모드는 예상 경로 값과 실제 외부 요청 수까지 검증합니다.
  원본은 `3 + 2N`, DB 두 버전은 `N`회가 예상됩니다.
- DB PK는 달라질 수 있어 `kakaoPlaceId` 기준으로 비교하고 공통 경로 결과의 해시를 저장합니다.
- 사전 검증/워밍업 실패 시 중단합니다. 본 측정의 잘못된 응답은 실패율에 포함하고 유효 응답 지연과 분리합니다.
  전송 실패/시간 초과는 남아 있는 서버 작업이 후속 요청에 섞이지 않도록 기록 후 실험을 중단합니다.
- 실패하거나 중단된 실험은 개선율을 출력하지 않습니다. 워밍업 0회와 모든 요청 실패도 정상 처리합니다.

## 결과 읽기

각 실행의 `benchmark-results/<timestamp-id>/`에 저장됩니다(전체 폴더 Git 제외).

- `report.md`: 위치별 평균·중앙값·p95·오류율과 유효한 완료 실험의 p95 개선율.
- `raw.csv`: 사전 검증/워밍업/본 측정의 요청별 SHA, 시간, 상태, 검증 결과, 후보 수, 결과 해시, 외부 호출 수.
  매 요청마다 flush하여 중단 전 측정값을 보존합니다.
- `summary.csv`: 위치/브랜치별 전체 요약과 라운드별 요약. 잘못된 응답은 지연 통계에서 제외하고 오류율로 명시합니다.
- `environment.json`, `commits.json`, `fixtures.json`, `seed.sql`: 실행 조건·커밋·데이터. 도구 소스 해시도 기록합니다.
- `sources/`, `build-*.log`, `round-*/.../server.log`: 실제 빌드 코드와 진단 로그.
- `harness/`: 실행 당시 도구 소스 사본. MySQL 버전은 각 `db-create.log`에 기록합니다.

응답시간은 HTTP 요청 시작부터 본문을 전부 받기까지의 단조 시계 시간입니다. JSON 파싱/검증은 제외합니다.
p50/p95는 nearest-rank 방식이며 위치가 다른 표본을 합쳐 백분위수를 만들지 않습니다.
라운드별 결과도 확인해 한 번의 우연한 차이를 개선으로 해석하지 마세요.
이 도구는 동시 사용자 1명의 응답 지연 실험이며 최대 처리량/부하 한계 테스트는 아닙니다.
공용 8스레드의 경합을 검증하는 동시 사용자 5/10명 부하 실험은 별도 후속 실험입니다.

서버의 기존 `apiResponseTime`은 병렬 호출들의 시간 **합계**라 실제 경과시간과 다릅니다.
기존 `totalResponseTime`은 성능 결과 저장/트랜잭션 완료/HTTP 직렬화까지 포함하지 않으므로 대표 지표로 쓰지 않습니다.
DB 저장 계측 또한 실제 flush/commit 전체 비용은 아닙니다. 해당 로그는 원인 분석 참고 자료입니다.

## 실제 API 모드

```powershell
.\scripts\benchmark-nearby.ps1 -Mode live -Fixtures .\real-theaters.json -EnvFile .env -Runs 2 -WarmupRuns 1 -Rounds 1
```

`KAKAO_MAP_REST_API_KEY`와 **실제 장소 ID/좌표를 가진 fixture**가 필수입니다.
출력된 `fixtures.json`을 형식 예제로 삼되 합성 ID는 실제 ID로 바꾸세요.
각 시나리오는 `name`, `latitude`, `longitude`, `theaters`를 가지며 극장 항목은 `id`(카카오 장소 ID),
`name`, `brand`(CGV/LOTTE_CINEMA/MEGABOX), `latitude`, `longitude`, `transitMinutes`, `transitDistance`를 가집니다.
live에서는 경로 예상값과의 정확한 일치는 검사하지 않고 정상 값/정렬/동일 후보를 검사합니다.
실제 API 결과와 후보 집합이 다르거나 경로 API가 실패하면 사전 검증에서 중단합니다.
특히 현재 코드의 경로 URL이 실제 계정에서 유효하게 동작하는지 작은 실행부터 확인해야 합니다.
HTTP 200과 null 경로를 정상 성능으로 보고하지 않습니다.

live 요청은 네트워크/제공자 상태·쿼터 영향을 받으며 고정 응답 결과와 별도 보고합니다.
DB 사전 적재를 위한 데이터 수집 시간/갱신 비용은 이 요청 지연 실험에 포함되지 않습니다.

## 포트폴리오 표현

> 고정 데이터·동일 JVM/MySQL 환경에서 외부 API 지연을 100ms로 통제하고,
> 영화관 후보 N개에 대해 3라운드 × 100회 측정했다. DB 순차 대비 8스레드 병렬 조회의
> p95가 X ms에서 Y ms로 Z% 감소했고, 결과 동등성과 오류율 0%를 확인했다.

실제로 본 측정을 완료한 결과로만 X/Y/Z를 채우세요. 2회 smoke test 수치를 포트폴리오 성과로 쓰지 않습니다.
실제 API 모드의 결과가 없으면 실제 서비스 응답속도 개선이라고 표현하지 않습니다.

강제 종료/PC 재부팅으로 정리가 실행되지 못하면 `round-*/.../run.json`의 생성 DB 이름을 확인하세요.
이 이름의 테스트 DB와 결과 폴더만 수동 정리하면 됩니다. 자동 정리 실패는 `db-drop.log`에 남고 실행은 실패 처리됩니다.
