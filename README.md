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

세 `perf` 브랜치를 동일한 데이터와 조건으로 반복 측정하는 공통 도구는
[벤치마크 실행 가이드](scripts/benchmark/README.md)를 참고하세요.
기본 실행은 실제 카카오 API와 DB의 서울 영화관 좌표를 사용하며, 응답 검증·요청별 CSV·평균/p95 보고서를 제공합니다.
