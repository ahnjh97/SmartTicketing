# 개발 및 실행

## 구성

- `src/`: Spring Boot 백엔드, 테스트, 측정용 계측 코드
- `frontend/`: React 웹과 서비스 이미지
- `deploy/`: 배포 스크립트, Nginx 설정, 배포 검증
- `scripts/dev/`: 로컬 실행·Redis 설정·개발 데이터 준비
- `scripts/benchmark/`: 성능 측정 실행기, k6 시나리오, 보고서 생성기
- `docs/images/`: README 흐름도 PNG·SVG
- `docs/assets/`: 보존용 디자인 원본

## 로컬 실행

Java 21, Node.js, MySQL, Redis가 필요하다. 환경변수는 저장소 루트의 Git 제외 파일 `.env`에서 관리한다.

| 설정 | 용도 |
| --- | --- |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | MySQL 연결 |
| `JWT_SECRET` | JWT 서명 |
| `REDIS_HOST`, `REDIS_PORT` | Redis 연결 |
| `VITE_KAKAO_MAP_JS_KEY` | 브라우저 지도 SDK |
| `KAKAO_MAP_REST_API_KEY` | 서버 지도 API |
| `VITE_API_BASE_URL` | 별도 API 주소. 기본 개발 프록시는 `localhost:8080` |
| `VITE_TOSS_CLIENT_KEY` | 토스 테스트 결제 클라이언트 키 |
| `TOSS_SECRET_KEY` | 서버 전용 토스 시크릿 키 |

OAuth 등 추가 연동 설정은 `src/main/resources/application.properties`를 따른다. `VITE_` 변수는 브라우저에 공개되므로 서버 비밀키에는 사용하지 않는다.

저장소 루트에서 백엔드 실행:

```powershell
.\scripts\dev\local.cmd
```

로컬 실행 메뉴는 WSL Ubuntu의 Redis 설치·시작과 업무 캐시 ON/OFF를 지원한다. 업무 캐시 선택은 `.local/cache-mode.properties`에 저장된다. IDE 실행 시 작업 디렉터리는 저장소 루트로 지정한다.

프런트엔드는 별도 터미널에서 실행한다.

```powershell
cd frontend
npm ci
npm run dev
```

Vite는 루트 `.env`를 읽고 `http://localhost:5173`에서 실행된다. `/api`와 OAuth 경로는 백엔드로 전달한다. 환경변수 변경 후 개발 서버를 재시작한다.

## 접속 대기열

로컬 기본값은 비활성, 배포 Compose 기본값은 활성이다. 로컬에서 확인하려면 `ADMISSION_ENABLED=true`와 실행 중인 Redis가 필요하다.

| 설정 | 기본값 | 의미 |
| --- | ---: | --- |
| `ADMISSION_CAPACITY` | 100 | 동시 입장 정원 |
| `ADMISSION_BATCH_SIZE` | 10 | 초당 입장 한도 |
| `ADMISSION_ACTIVE_SECONDS` | 600 | 입장 유효시간 |
| `ADMISSION_WAIT_SECONDS` | 90 | 대기 상태 확인 중단 시 만료 시간 |
| `ADMISSION_MAX_WAITING` | 20000 | 최대 대기 인원 |
| `ADMISSION_REDIS_HOST` | 기존 Redis 주소 | 배포에서는 별도 admission-redis |

입장권은 로그인 인증과 별개다. 입장 제어 Redis 장애 시 `503`을 반환하며, 캐시 ON/OFF 설정으로 입장 제어를 해제하지 않는다. 배포는 같은 사이트의 프런트·API와 HTTPS 쿠키를 사용한다. 현재 Redis 구성은 단일 노드다.

## 결제 및 배포

토스 클라이언트 키와 서버 시크릿 키는 같은 테스트 환경의 키 쌍을 사용한다. 결제 완료는 리다이렉트 화면이 아닌 서버 승인 결과로 확정한다.

배포 설정은 루트 `docker-compose.yml`, `Dockerfile`, `.github/workflows/`, `deploy/`에서 관리한다. 시크릿 키는 백엔드에만 전달한다.

## 검증 및 측정

```powershell
.\gradlew.bat test
npm --prefix frontend run build
npm --prefix frontend run test:core
```

DB·Redis 통합 테스트는 해당 테스트의 실행 환경이 필요하다. 최신 측정 방법은 [측정 안내](../scripts/benchmark/README.md)를 참고한다. 보고서·로그·최종 측정 ZIP은 Git에서 제외한 `.local/benchmark-results/`에 보관한다.
