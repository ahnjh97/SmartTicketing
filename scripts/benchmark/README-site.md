# WSL Docker 테스트 실행

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

결과는 출력된 `benchmark-results/site-*/index.html`과 `comparison.json`, 각 실행의 로그에 남는다. 이미지 ID, JAR 체크섬, 실행 커밋과 설정을 기록한다. 실패·스모크 실행으로 성능 개선을 주장하지 않는다. p95/p99는 모든 클라이언트의 원시 표본을 합산한다. 별도 예열 없이 시작한 혼합 사용자 흐름이며 순수 API 최대 처리량과 다르다. 반복 실행에서는 브랜치 순서를 교대로 바꾼다. 임시 컨테이너와 DB 볼륨은 종료 시 정리하고 이미지·결과는 보관한다.

SQL 수는 [MySQL Performance Schema digest](https://dev.mysql.com/doc/refman/8.4/en/performance-schema-statement-digests.html)의 부하 전후 차이로 수집한다. 시딩은 제외하지만 백그라운드 워커 SQL은 포함된다. SQL/요청은 구간 합계 비율이며 요청별 추적값이 아니다. digest 누락·초기화가 감지되면 숫자를 표시하지 않는다. 성공 업무 요청/s는 k6 클라이언트별 성공 요청률의 합이다.

메뉴의 **단계별 부하** 또는 `-UserLevels`로 사용자 수를 늘려 측정한다. 각 단계마다 새 DB를 사용하며 지정된 반복이 모두 성공하고 업무 p95가 `-P95LimitMs` 이하인 단계만 통과한다. k6의 업무·입장 오류율 기준도 적용된다. 통과 단계의 반복 처리량 중앙값 중 가장 큰 값을 `capacity.variants.*.bestObservedPassingRate`에 기록한다. 이는 **선택 범위 내 최고 관측 통과 처리량**이며 절대 최대 처리량이 아니다. 가장 높은 사용자 단계도 통과했다면 한계를 찾지 못한 상태다. WSL·부하 생성기 병목, 입장 대기 시간도 별도로 해석해야 한다. 스모크나 단일 부하는 capacity를 `not-measured`로 표시한다.

## 기존 개별 기능 테스트

```powershell
# Redis ON/OFF, 대기순번·취소표 배정 등을 포함하는 기존 테스트
.\scripts\benchmark\run-site.ps1 -Mode components -Suite all -Smoke
.\scripts\benchmark\run-site.ps1 -Mode components -Suite booking -CacheMode compare
.\scripts\benchmark\run-site.ps1 -Mode components -Suite main -Rate 100 -Duration 60s
```

이 모드는 기존 백엔드 직접 호출 테스트이므로 입장 대기열·Nginx 경로를 포함하지 않는다. 결과는 기존 `benchmark-results/index.html` 대시보드에서 확인한다. 전체 서비스 실행 결과와 섞어서 비교하지 않는다. `run-redis.ps1`, `run-waiting-rank.ps1`도 이 모드로 연결된다.

## 대기순위 개선 전후 브랜치 비교

PowerShell에서 저장소 루트 기준으로 다음을 실행합니다.

```powershell
.\scripts\benchmark\run-waiting-rank-comparison.ps1
```

스크립트가 origin의 `feature/jusang`(개선 전)과 `improve/waiting-rank-redis-v2`(개선 후)를 fetch한 다음, 각 커밋을 별도 임시 Git worktree에 체크아웃하고 같은 `booking` 부하 테스트를 실행합니다. 현재 작업 중인 브랜치를 checkout/switch하지 않으며, `main`과 `feature/jusang`에 커밋하지 않습니다. 각 worktree의 실행이 끝나면 로그와 JSON/HTML 산출물을 본 저장소의 비교 실행 폴더에 복사하고 worktree를 정리합니다.

생성 파일:

- `benchmark-results/waiting-rank-comparison.html` — 전용 브랜치 비교 리포트
- `benchmark-results/waiting-rank-comparison.json` — 최신 비교 데이터
- `benchmark-results/waiting-rank-comparison/<실행 ID>/baseline/` — 개선 전 원본 로그/산출물
- `benchmark-results/waiting-rank-comparison/<실행 ID>/improved/` — 개선 후 원본 로그/산출물
- `benchmark-results/waiting-rank-comparison/<실행 ID>/comparison.json` — 해당 실행의 전체 비교 데이터

기존 `benchmark-results/index.html` 통합 대시보드는 변경하지 않습니다. 한쪽 빌드나 테스트가 실패하면 실패 상태를 기록하고 성능 향상으로 판정하지 않습니다. DB COUNT 쿼리 수와 Outbox→Redis 전파 시간 등 현재 벤치마크가 수집하지 않는 값은 임의로 계산하지 않고 '측정 안 됨'으로 둡니다.

첫 실행은 Docker 이미지와 Gradle 의존성 다운로드 때문에 시간이 걸릴 수 있습니다. 다운로드/빌드 실패 시 HTML 리포트에 성능 결과가 있는 것처럼 보지 말고 해당 실행 폴더의 `runner.log`를 확인하세요.
