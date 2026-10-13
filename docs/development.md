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

## 이메일 인증 및 로그인

일반·소셜 회원가입 모두 이메일 인증이 필수다. 아이디 찾기와 비밀번호 변경도 가입 시 인증한 이메일로 본인을 확인한다. 별도 복구 이메일 등록 기능은 없다.

Spring Mail의 SMTP를 사용한다. 루트 `.env` 또는 배포 환경에 다음 값을 설정하고 백엔드를 재시작한다. Docker Compose도 같은 변수를 전달한다.

| 설정 | 값 |
| --- | --- |
| `MAIL_HOST` | 이용할 메일 서비스의 SMTP 호스트 |
| `MAIL_PORT` | 기본 `587` |
| `MAIL_USERNAME` | SMTP 인증 계정 |
| `MAIL_PASSWORD` | SMTP 전용 비밀번호 또는 앱 비밀번호 |
| `MAIL_FROM` | 해당 서비스에서 발신을 허용한 이메일 주소 |
| `MAIL_SMTP_AUTH` | 기본 `true` |
| `MAIL_STARTTLS` | 기본 `true` |

발송 설정이 없거나 SMTP 연결에 실패하면 인증을 진행할 수 없다. 인증번호를 응답이나 로그로 대신 제공하지 않는다. SMTP 비밀번호는 `VITE_` 변수에 넣지 않는다.

GitHub Actions 배포 시에는 저장소의 **Settings → Secrets and variables → Actions**에 `MAIL_HOST`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM`을 Secrets로 등록한다. `MAIL_PORT`는 Variables로 지정하며 기본값은 `587`이다. 배포는 SMTP 인증과 STARTTLS를 사용한다. 수신 이메일과 발신용 SMTP 계정은 별개이며, `MAIL_FROM`은 SMTP 서비스에서 허용한 발신 주소여야 한다.

배포 워크플로가 이 값을 SSH 표준 입력으로 전달하고 EC2의 Compose 실행 환경에 주입한다. 필수 설정이 빠지면 서버 변경 전에 배포를 중단한다. 설정 등록 후 이 변경이 포함된 커밋을 배포하고 실제 인증 메일 수신까지 확인한다.

6자리 인증번호는 10분간 유효하며 5회 오입력 시 다시 발급받아야 한다. 재발송 간격은 60초, 이메일·목적별 시간당 5회다. IP별 발송은 시간당 20회, 인증 시도는 100회로 제한한다. 인증 후 발급된 확인 토큰은 이메일·목적별로 10분간 한 번만 사용할 수 있다. 비밀번호 변경 시 기존 로그인 토큰을 무효화한다.

로그인·회원가입 후에는 미완료 선호정보 설정을 먼저 진행하고, 로그인 전에 요청한 내부 화면으로 한 번만 돌아간다. 돌아갈 화면이 없으면 홈으로 이동한다. 로그아웃 시 이전 이동 목적지를 삭제한다. 해시 라우터의 `/#/` 경로를 유지하며, 외부 URL은 복귀 경로로 허용하지 않는다.

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
