# SmartTicketing

사용자의 거주지와 영화관·좌석 선호를 관리하는 Spring Boot + React 기반 영화 예매 프로젝트입니다. 사용자 선호 기반 좌석 추천과 예매 동시성 제어를 목표로 개발하고 있습니다.

## 기술 스택

| 구분 | 기술 |
| --- | --- |
| 프런트엔드 | React, Vite |
| 백엔드 | Java, Spring Boot, Spring Security, Spring Data JPA |
| 인증 | JWT, OAuth 2.0 |
| 외부 API | Kakao Maps |

## 주변 영화관 조회 성능 비교

`perf/benchmark-500` 브랜치에서 공통 실행 도구를 제공합니다.
[다른 컴퓨터에서 준비하고 실행하는 방법](scripts/benchmark/README.md)을 참고하세요.
세 `perf` 브랜치를 같은 조건으로 측정하고 응답 검증, CSV, 평균/p95 보고서를 생성합니다.
