# Linux k6 실행

```powershell
.\scripts\benchmark\run-linux.ps1
```

테스트와 Redis 모드를 번호로 고른다. `local.cmd`나 개발 백엔드를 켤 필요가 없다.

```powershell
# 전체 항목의 짧은 기능 확인
.\scripts\benchmark\run-linux.ps1 -Suite all -Smoke
# 메인 조회 ON/OFF 비교
.\scripts\benchmark\run-linux.ps1 -Suite main -Rate 100 -Duration 60s
# Redis ON만 실행
.\scripts\benchmark\run-linux.ps1 -Suite booking -CacheMode on
```

결과는 `benchmark-results/index.html`에서 항목 탭과 실행 기록을 선택해서 본다.
`update-dashboard.ps1`로 기존 결과만 다시 모을 수도 있다.

## 배포 구성과 맞춘 부분

- 현재 체크아웃을 Java 21로 빌드한 `bootJar`를 루트 `Dockerfile`의 `prebuilt` 타깃에 넣는다. AWS CI와 같은 이미지 구성 방식이다.
- 이미지의 원래 `deploy/backend-entrypoint.sh`가 `java -jar app.jar`를 실행한다. 테스트 클래스가 서버를 직접 실행하지 않는다.
- 빌드한 JAR와 이미지 속 JAR의 SHA-256 일치를 확인한다. 결과 폴더에 체크섬과 이미지 ID를 저장한다.
- 백엔드, k6, MySQL, Redis, 데이터 준비 도구는 별도 컨테이너다. Docker 소켓을 컨테이너에 제공하지 않고 호스트에서 수명 주기를 관리한다.
- MySQL 8.4, Redis 7 및 Redis AOF 활성화를 배포 구성과 맞춘다.
- 각 항목·모드마다 백엔드 컨테이너와 임시 DB를 새로 만든다. 이 실행이 소유한 Redis만 초기화한 뒤 동일하게 예열한다.
- Redis 공통 스위치만 ON/OFF로 바꾼다. 로컬 캐시 500ms, Redis 조회 TTL 2초는 동일하다. 일반 비교는 OFF→ON→ON→OFF, 스모크는 OFF→ON이다.
- 백엔드 준비 실패, k6 실패 및 비교 조건 불일치는 실패로 남긴다. 종료 시 해당 실행의 컨테이너·볼륨을 제거한다.

## 해석 범위

현재 작업 내용으로 만든 이미지이므로 **현재 AWS에 배포된 이미지와 바이트까지 동일하다는 뜻은 아니다.** 운영 이미지의 정확한 재현에는 해당 이미지 digest가 필요하다.

백엔드는 기본 프로필을 사용한다. 테스트 데이터 보존을 위해 외부 수집·자동 시딩을 끄고, 임시 DB·인증 키·고정 기준일을 사용한다. JPA는 이미 준비한 스키마를 validate한다. OAuth와 외부 API는 측정 범위에서 제외한다.

k6는 `http://backend:8080`으로 요청하므로 Nginx·HTTPS·외부 네트워크는 포함하지 않는다. 백엔드 4 CPU/4 GiB, k6 2 CPU/2 GiB, MySQL 2 CPU/1.5 GiB, Redis 1 CPU/256 MiB의 로컬 상한이며 실제 EC2 사양을 재현하지 않는다. 컨테이너를 분리해도 PC의 물리 자원은 공유한다.

DB 버퍼 풀과 OS 캐시는 강제로 비우지 않는다. 예열 후 ON/OFF 상대 비교이며, 스모크 결과로 성능 향상을 판단하지 않는다. JVM 내부 CPU·Tomcat 연결 수는 현재 컨테이너 외부에서 수집하지 않아 빈 지표로 표시한다.

기존 `run-redis.ps1`은 Docker 방식의 예매 테스트로 연결한다. Windows 전용 k6/Java 경로 인자는 더 이상 지원하지 않는다.


## 대기순위 개선 전후 브랜치 비교

PowerShell에서 저장소 루트 기준으로 다음을 실행합니다.

```powershell
.scriptsenchmarkun-waiting-rank-comparison.ps1
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
