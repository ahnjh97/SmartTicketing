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
