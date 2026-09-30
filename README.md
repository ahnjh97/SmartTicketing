# SmartTicketing
Spring Boot + React 기반 / 사용자 선호 기반 좌석 추천과 동시성 제어를 적용한 스마트 영화 예매 시스템

## 로컬 실행 설정

IntelliJ의 Run → Edit Configurations → Environment variables에 각 PC의 값을 설정합니다.

- `DB_USERNAME`: MySQL 계정 (생략하면 `root`)
- `DB_PASSWORD`: MySQL 비밀번호
- `JWT_SECRET`: UTF-8 기준 32바이트 이상의 토큰 서명 키

기본 DB URL은 `localhost:3306/smart_ticketing`이며 DB가 없으면 생성합니다. MySQL 서버가 실행 중이고 계정에 DB 생성 권한이 있어야 합니다. `DB_URL`을 별도로 지정하면 자동 생성이 필요한 경우 `createDatabaseIfNotExist=true`도 포함하세요.

소셜 로그인은 기본적으로 비활성화되어 연동 키 없이 실행할 수 있습니다. 사용할 제공자만 `SPRING_PROFILES_ACTIVE`에 지정하고 해당 키를 설정하세요.

| 제공자 | 활성 프로필 | 필요한 환경변수 |
| --- | --- | --- |
| Google | `oauth-google` | `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET` |
| Naver | `oauth-naver` | `NAVER_CLIENT_ID`, `NAVER_CLIENT_SECRET` |
| Kakao | `oauth-kakao` | `KAKAO_CLIENT_ID`, `KAKAO_CLIENT_SECRET` |

여러 제공자는 `SPRING_PROFILES_ACTIVE=oauth-google,oauth-naver,oauth-kakao`처럼 지정합니다. 기존 프로필이 있으면 쉼표로 추가하세요. 활성화한 제공자는 실제 발급된 키가 필요합니다. 클라이언트 ID와 시크릿은 앱의 연동 정보이며 사용자 로그인 아이디/비밀번호가 아닙니다. 시크릿은 서버에서 관리합니다.

주변 영화관 검색은 `KAKAO_MAP_REST_API_KEY`로 별도 설정할 수 있으며, 생략하면 `KAKAO_CLIENT_ID`를 사용합니다. 둘 다 없으면 앱은 실행되지만 주변 영화관 조회는 HTTP 503을 반환합니다. 지도 기능은 소셜 로그인 프로필과 독립적입니다.
