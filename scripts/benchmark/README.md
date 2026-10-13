# 변경 목적별 4개 검증

```powershell
# WSL Docker에서 전후 기능·SQL 계측 확인 (성능 결론용 아님)
.\scripts\benchmark\run-linux.ps1 -Smoke
# 좌석도·시간표는 각각 초당 50건·60초, 순번은 1,000건 순차 조회, 선점은 80건 동시 실행
.\scripts\benchmark\run-linux.ps1
# 동일 요청률을 높여 재측정
.\scripts\benchmark\run-linux.ps1 -Rate 100 -Duration 120s
# 항목 하나만 확인
.\scripts\benchmark\run-linux.ps1 -Suite seats -Smoke
```

`Suite`: `focus`(기본·전체), `waiting`, `seats`, `showtimes`, `dispatch`.
`-Distribution Ubuntu`는 설치된 Ubuntu/Ubuntu-26.04를 확인한다.
운영 계정·데이터·볼륨 없이 실행별 임시 MySQL·Redis·앱·k6 컨테이너를 만든다.
결과 경로는 실행 종료 시 출력되는 `.local/benchmark-results/linux-java21-*/index.html`이다.

## 비교 코드

- **개선 전**: `0b40982b2f04db3a0b0cbb996647f5b2fb1d72c6`. 스마트예매 기능이 있는 Redis 예매 캐시 도입 직전 코드.
- **개선 후**: 현재 작업 폴더를 빌드한 배포 JAR, Redis ON.
- `-BaselineRef <commit SHA>`로 기준을 변경할 수 있다. 동일 fixture의 API·스키마 계약이 맞지 않으면 실패하며 현재 코드로 대체하지 않는다.
- 브랜치를 바꾸거나 만들지 않고 `git archive`로 기준 코드를 추출해 별도 이미지를 빌드한다. 두 버전 모두 Java 21과 현재 배포 Dockerfile의 `prebuilt` 실행 구성을 사용한다.
- DB·잠금·폴링·Outbox 코드 개선도 포함하는 **종합 전후 비교**다. Redis만의 순수 기여율로 해석하지 않는다.
- 개선 전에는 업무 Redis OFF와 접근 불가한 Redis 호스트를 적용한다. QR 기능은 측정 범위가 아니다. 입장 대기열은 두 버전 모두 비활성화해 동일한 4개 업무 API를 비교한다.
- 최신 입장 대기열·Nginx·프런트엔드 전체 배포 흐름 테스트는 [run-site.ps1](run-site.ps1) 및 [전체 서비스 측정](#전체-서비스-측정)에 보존했다. 이번 API 비교에는 HTTPS·브라우저 렌더링이 포함되지 않는다.

## 가설에 맞는 지표만 표시

| 항목 | 핵심 지표 (1~2개) | 증명하려는 변경 |
|---|---|---|
| 대기순번 | 순번 계산 SQL/요청, DB 결과 행/요청 | 앞 대기자 목록 조회에서 버전 검증 + Sorted Set으로 전환 |
| 좌석도 | HTTP 요청당 SQL | 동일 120석 회차의 반복 DB 조회를 TTL 캐시로 절감 |
| 상영시간표 | 좌석 재고 결과 행/요청 | 20회차 × 120석 재고를 매번 읽고 계산하던 비용 절감 |
| 동시 취소 → 스마트 선점 | 전체 흐름 성공률, 중복 활성 선점 수 | 조회 쓰기 잠금·즉시 배정에서 읽기 분리·구역 잠금·Outbox로 변경한 종합 효과 |

카드 제목은 **대기순번 조회·좌석도 조회·상영시간표 조회·취소 후 대기자 자동 선점**으로 기능을 나타낸다.
본문에는 MySQL 기반의 이전 처리와 개선 방식을 한 줄씩 설명하고 핵심 지표만 표시한다.
반복·예열 횟수는 표시하지 않는다. 계산식·요청 수·해석·원본 링크는 접힌 **측정 근거 보기**에 보관한다.
예열은 본 측정의 분자·분모에서 제외한다. 데드락 개선 결과는 근거가 확인되면 짧은 결론으로 표시한다.
시간표의 필드 축소는 행 크기에 관한 변경이며, 현재 행 수 지표로 그 바이트·메모리 절감까지 입증하지 않는다.
백엔드 로그에 데드락이 있으면 WARN 줄 수와 순번 GET의 `FOR UPDATE` 확인 여부를 표시한다.
같은 예외의 반복 스택 문구는 중복 집계하지 않으며, WARN 수를 잠금 순환 개수로 해석하지 않는다.
InnoDB 잠금 그래프가 없는 결과에서는 정확한 행·인덱스 순환을 추정해 단정하지 않는다.
네 항목을 전부 응답시간 개선으로 해석하지 않는다. 마지막 항목은 순번 확인→취소→선점→정확한 좌석 검증까지 하나의 흐름이다.
HTTP 오류·데드락도 실패로 포함하며, 성공한 요청의 시간만으로 전체를 대표하지 않는다. 시간은 원본 JSON에 남긴다.
서로 다른 회차의 동시 흐름이며 동일 좌석에 여러 취소 명령을 보내는 테스트는 아니다.
이전 버전의 요청 실패는 측정 결과로 받아들이되 모든 계획 건의 결과가 있어야 한다. 현재 버전의 실패나 중복 선점은 실행을 실패 처리한다.
반복 실행의 중복 활성 선점 수는 최댓값으로 표시한다. Outbox 장애 복구·중복 이벤트 처리의 신뢰성은 이 정상 흐름 측정으로 입증하지 않는다.
모든 테스트 회차는 실제 `DefaultSeatLayout`을 재사용한 A~J행 × 12석(120석), 좌·중앙·우 3–6–3 통로 배치다.
조회 fixture는 120석 모두 AVAILABLE이다. 대기 fixture는 순번을 고정하기 위해 120석 모두 BLOCKED이며,
선점 fixture는 119석 BLOCKED + D4 한 석 HOLDING에서 시작해 그 한 석만 취소·재배정한다.
차단석은 합성 테스트 조건이며 실제 운영 예약 점유율을 재현한 데이터는 아니다.
이전·현재 빌드에 동일한 `src/benchmark/java` 계측을 추가한다. `-PbenchmarkInstrumentation` 빌드와
`benchmark-metrics` 프로필에서만 사용하며 **일반 배포 빌드에는 포함되지 않는다**. 과거 업무 코드는 수정하지 않는다.
DataSource/JDBC 프록시와 HTTP 필터가 요청 스레드의 실행 횟수·`ResultSet.next()`로 소비한 행을 헤더로 반환한다.
별도 스레드의 워커 SQL은 제외한다. JDBC 배치 실행은 호출 1회로 세며, 행 수는 DB 내부 examined rows·네트워크 바이트가 아니다.
순번 SQL은 `waiting_queues` + `COALESCE` + `zone_queue_number` 비교 패턴을 분류한다. 이전 코드에서 0회로 잡히면 분류 실패로 판정한다.
시간표의 재고 행은 `showtime_seats`를 참조하는 SQL만 센다. 캐시 전 영화 존재 검증 SQL은 이 행 지표에서 제외된다.
동일한 프록시·응답 버퍼링 비용이 양쪽 시간에 포함된다. 조회의 정합성·헤더 누락·예열 실패·요청 누락이 있으면 비교를 차단한다.
기존 Performance Schema 전체 SQL은 진단용 원본으로만 유지하며 API SQL로 사용하지 않는다.
예전 리포트의 전체 SQL을 새 요청 SQL로 재해석하지 않는다. 반드시 새 실행이 필요하다.

## 재현 조건과 해석

- 일반 실행은 항목별 **전→후→후→전**, 스모크는 전→후. 각각 새 JVM·새 DB와 Redis 초기화 후 실행한다.
- 좌석도·시간표는 고정 요청률(`Rate`)을 양쪽에 동일 적용한다. 느린 쪽의 요청량을 자동으로 줄여 성공한 것처럼 보이지 않게 dropped iteration도 검사한다.
- 기본 15초 예열, 조회 60초. 스모크 좌석도·시간표는 10건/초·6초다. 스모크는 계측·기능 확인용이다. 선점은 80회차 × 4명 대기(스모크 4회차), 회차별 첫 대기자 배정을 확인한다. `Rate/Duration`은 선점·순번 건수를 바꾸지 않는다.
- 대기순번은 동일한 사용자 순서로 단일 VU에서 1,000건(예열 40건), 스모크 8건(예열 8건)을 조회한다. 순번 계산의 요청당 DB 작업량을 분리하는 실험이며 처리량 테스트가 아니다. 이전 코드의 쓰기 잠금 때문에 병렬 순번 조회 시 데드락이 관찰되어, 경합 측정과 섞지 않는다. 해당 실패 결과도 보존한다.
- 인기 회차를 반복 조회하는 hot-key 부하이며 캐시 TTL은 기본 2초. 임의로 TTL을 늘리거나 DB에 지연을 주지 않는다. 두 앱의 로컬 L1 캐시는 끈다.
- 선점 관찰은 100ms 간격 폴링이며 실제 프런트 폴링 주기나 DB 커밋 시각이 아니다. 초기 데이터만 DB에 준비하고 업무는 실제 HTTP API로 수행한다. 후보 생성~결제 전체 여정을 대체하지 않는다.
- p95 비교값은 반복 실행별 p95 평균이다. 서로 다른 실행의 모든 표본을 합산한 p95가 아니다.
- 배포와 같은 MySQL 8.4·Redis 7 AOF·Java 21, 독립 k6 컨테이너. 양쪽 모두 추가 CPU/메모리 상한 없이 같은 PC 자원을 사용한다. 운영 최대 처리량을 뜻하지 않는다.
- 원본 커밋·작업 파일 상태·추적 파일 diff·JAR 체크섬·이미지 ID를 보관한다. 미추적 파일은 source.patch에 포함되지 않으므로 최종 재현에는 변경을 커밋한 뒤 실행한다.
- 리포트를 생성할 때 사용한 코드도 `report-source.mjs`로 보관한다. 원본 측정 JSON을 유지한 채 최신 표시 규칙으로 HTML을 다시 생성할 수 있다.
- `-CacheMode on/off`는 한쪽의 기능 확인용이며 개선율을 표시하지 않는다. `off`도 현재 코드 OFF가 아니라 기준 커밋이다.

## 로컬 개발

로컬 업무 Redis 기본값은 ON이다. 기존 `.local/cache-mode.properties` 설정은 명시적인 사용자 선택으로 우선한다.
`./scripts/dev/local-dev.ps1 -Action set-on`으로 저장하고 실행 중인 백엔드는 재시작한다.
배포 Compose의 기본값은 이 변경과 별개로 유지한다.


## 전체 서비스 측정


WSL Ubuntu에 Docker Engine, Compose v2, Python 3.12 이상, OpenSSL, Git이 필요하다. Docker 데몬을 시작한 뒤 저장소 루트에서 실행한다. 개발 서버와 `local.cmd`는 필요 없다.

```powershell
# 번호로 비교 목적·커밋·시나리오·규모 선택
.\scripts\benchmark\run-site.ps1
# 현재 코드: 입장 → 조회 → 스마트/일반 예매 → 모의결제 → 취소 → 로그인 기능 확인
.\scripts\benchmark\run-site.ps1 -Smoke
# 같은 경로로 동시 사용자 100명, 3분 부하
.\scripts\benchmark\run-site.ps1 -Users 100 -Clients 10 -Duration 3m
# 아래 이름은 예시이며 실제 브랜치/커밋으로 바꾼다
.\scripts\benchmark\run-site.ps1 -Mode compare -Comparison db -Refs before,final -Smoke
.\scripts\benchmark\run-site.ps1 -Mode compare -Comparison redis -Refs final -Smoke
.\scripts\benchmark\run-site.ps1 -Mode compare -Comparison overall -Refs before,final -Smoke
.\scripts\benchmark\run-site.ps1 -Mode compare -Comparison all -Refs before,final -Users 100 -Duration 3m -Repeats 3
# 단계별 부하: 각 단계에서 지연·오류 기준을 통과한 최고 관측 처리량
.\scripts\benchmark\run-site.ps1 -Mode compare -Comparison overall -Refs before,final -UserLevels 25,50,100,200 -P95LimitMs 1000 -Duration 3m -Repeats 3
```

비교는 **DB 개선(개선 전 OFF ↔ 최종 OFF), Redis 효과(같은 최종 이미지 OFF ↔ ON), 종합 효과(개선 전 OFF ↔ 최종 ON)**로 나눈다. 세 비교 모두 실행할 때도 개선 전·최종 두 커밋이면 된다. Redis OFF는 업무 캐시 설정이며 입장 대기열 Redis는 계속 켜진다. DB 비교에는 Redis 외 코드 차이도 포함되므로 다른 변경이 섞인 커밋을 순수 SQL 튜닝 효과라고 단정하지 않는다.

메뉴에서는 로컬·원격 브랜치 목록, 최근 30개 커밋, SHA·태그 직접 입력 중 선택한다. 원격 브랜치는 마지막 fetch 기준이다. 선택 결과는 SHA로 고정한다. 브랜치를 생성하거나 현재 작업 폴더를 전환하지 않으며, 지정한 커밋을 별도 폴더에 추출한다. 커밋하지 않은 변경은 `-Refs` 실행에 포함되지 않는다. 기존 브랜치에 공통 API·DB 스키마가 없으면 실패로 표시하므로 비교 전에 계약을 맞춰야 한다. 인자가 있으면 명령행 모드이며 `-Menu`로 메뉴를 강제할 수 있다.

기본 실행은 루트 `docker-compose.yml`을 읽어 **MySQL, 업무 Redis, 입장 대기열 Redis, 백엔드, 프런트엔드, Nginx**를 함께 실행한다. k6는 HTTPS/Nginx와 실제 입장 절차를 거친다. CPU·메모리 상한은 추가하지 않는다. `-Clients`는 요청을 보내는 별도 k6 컨테이너 수이며, 사용자 수를 이 컨테이너들에 나눈다. 배포 Nginx의 IP별 요청 제한도 적용된다.

현재 코드나 각 브랜치의 배포 Dockerfile로 이미지를 빌드한다. AWS에 배포한 이미지 파일을 Docker에 미리 로드했다면 다음처럼 그 이미지 자체를 사용할 수 있다.

```powershell
.\scripts\benchmark\run-site.ps1 -BackendImage smartticketing-backend:COMMIT -FrontendImage smartticketing-frontend:COMMIT -Smoke
```

서비스 구성은 배포 Compose 기준이지만 운영 `.env`는 읽지 않는다. 테스트 DB·인증 키·합성 데이터·자체 서명 인증서를 사용하며 외부 수집과 자동 시딩을 끈다. 실제 배포의 별도 환경변수 override, 외부 OAuth·지도 API, EC2 하드웨어는 재현하지 않는다. 프런트 HTML 제공은 확인하지만 브라우저 UI 자동화는 아니다. 기본 `-CacheMode branch`는 **현재 배포 Compose의 기본값**을 쓰며 브랜치의 `.env`를 읽는 옵션이 아니다. 필요하면 `-CacheMode on`을 지정한다.

결과는 출력된 `.local/benchmark-results/site-*/index.html`과 `comparison.json`, 각 실행의 로그에 남는다. 이미지 ID, JAR 체크섬, 실행 커밋과 설정을 기록한다. 실패·스모크 실행으로 성능 개선을 주장하지 않는다. p95/p99는 모든 클라이언트의 원시 표본을 합산한다. 별도 예열 없이 시작한 혼합 사용자 흐름이며 순수 API 최대 처리량과 다르다. 반복 실행에서는 브랜치 순서를 교대로 바꾼다. 임시 컨테이너와 DB 볼륨은 종료 시 정리하고 이미지·결과는 보관한다.

SQL 수는 [MySQL Performance Schema digest](https://dev.mysql.com/doc/refman/8.4/en/performance-schema-statement-digests.html)의 부하 전후 차이로 수집한다. 시딩은 제외하지만 백그라운드 워커 SQL은 포함된다. SQL/요청은 구간 합계 비율이며 요청별 추적값이 아니다. digest 누락·초기화가 감지되면 숫자를 표시하지 않는다. 성공 업무 요청/s는 k6 클라이언트별 성공 요청률의 합이다.

메뉴의 **단계별 부하** 또는 `-UserLevels`로 사용자 수를 늘려 측정한다. 각 단계마다 새 DB를 사용하며 지정된 반복이 모두 성공하고 업무 p95가 `-P95LimitMs` 이하인 단계만 통과한다. k6의 업무·입장 오류율 기준도 적용된다. 통과 단계의 반복 처리량 중앙값 중 가장 큰 값을 `capacity.variants.*.bestObservedPassingRate`에 기록한다. 이는 **선택 범위 내 최고 관측 통과 처리량**이며 절대 최대 처리량이 아니다. 가장 높은 사용자 단계도 통과했다면 한계를 찾지 못한 상태다. WSL·부하 생성기 병목, 입장 대기 시간도 별도로 해석해야 한다. 스모크나 단일 부하는 capacity를 `not-measured`로 표시한다.

## 기존 개별 기능 테스트

```powershell
# Redis ON/OFF, 대기순번·취소표 배정 등을 포함하는 기존 테스트
.\scripts\benchmark\run-site.ps1 -Mode components -Suite all -Smoke
.\scripts\benchmark\run-site.ps1 -Mode components -Suite booking -CacheMode compare
.\scripts\benchmark\run-site.ps1 -Mode components -Suite main -Rate 100 -Duration 60s
```

이 모드는 기존 백엔드 직접 호출 테스트이므로 입장 대기열·Nginx 경로를 포함하지 않는다. 결과는 기존 `.local/benchmark-results/index.html` 대시보드에서 확인한다. 전체 서비스 실행 결과와 섞어서 비교하지 않는다. `run-redis.ps1`, `run-waiting-rank.ps1`도 이 모드로 연결된다.

## 대기순위 개선 전후 브랜치 비교

PowerShell에서 저장소 루트 기준으로 다음을 실행합니다.

```powershell
.\scripts\benchmark\run-waiting-rank-comparison.ps1
```

스크립트가 origin의 `feature/jusang`(개선 전)과 `improve/waiting-rank-redis-v2`(개선 후)를 fetch한 다음, 각 커밋을 별도 임시 Git worktree에 체크아웃하고 같은 `booking` 부하 테스트를 실행합니다. 현재 작업 중인 브랜치를 checkout/switch하지 않으며, `main`과 `feature/jusang`에 커밋하지 않습니다. 각 worktree의 실행이 끝나면 로그와 JSON/HTML 산출물을 본 저장소의 비교 실행 폴더에 복사하고 worktree를 정리합니다.

생성 파일:

- `.local/benchmark-results/waiting-rank-comparison.html` — 전용 브랜치 비교 리포트
- `.local/benchmark-results/waiting-rank-comparison.json` — 최신 비교 데이터
- `.local/benchmark-results/waiting-rank-comparison/<실행 ID>/baseline/` — 개선 전 원본 로그/산출물
- `.local/benchmark-results/waiting-rank-comparison/<실행 ID>/improved/` — 개선 후 원본 로그/산출물
- `.local/benchmark-results/waiting-rank-comparison/<실행 ID>/comparison.json` — 해당 실행의 전체 비교 데이터

기존 `.local/benchmark-results/index.html` 통합 대시보드는 변경하지 않습니다. 한쪽 빌드나 테스트가 실패하면 실패 상태를 기록하고 성능 향상으로 판정하지 않습니다. DB COUNT 쿼리 수와 Outbox→Redis 전파 시간 등 현재 벤치마크가 수집하지 않는 값은 임의로 계산하지 않고 '측정 안 됨'으로 둡니다.

첫 실행은 Docker 이미지와 Gradle 의존성 다운로드 때문에 시간이 걸릴 수 있습니다. 다운로드/빌드 실패 시 HTML 리포트에 성능 결과가 있는 것처럼 보지 말고 해당 실행 폴더의 `runner.log`를 확인하세요.
