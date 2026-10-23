# EC2 두 대에 자동 배포

`main` push → 기존 CI 테스트 → 이미지 한 번 빌드 → 같은 이미지를 A/B에 전달 →
A 시작 → B 시작 → A/B·Nginx 준비 상태 확인 순서로 실행한다.

현재 분산 실험 구성인 백엔드 API를 배포한다. A의 MySQL·Redis·Nginx와 B의 백엔드를 관리하며,
기존 운영 프런트엔드·HTTPS 배포와는 대상이 다르다.
EC2 생성과 최초 서버 설정은 한 번 필요하다. 이후 이미지 전달·컨테이너 갱신은 CD가 처리한다.

## 1. EC2 최초 준비

- 같은 VPC에 x86_64 Linux EC2 A/B를 준비한다. 현재 GitHub CI는 linux/amd64 이미지를 만든다.
- 두 서버에 Docker Engine, Compose v2, Git, Python 3.12+를 설치한다. Docker 서비스의 자동 시작을 활성화한다.
- 배포 사용자에게 Docker 실행 권한을 주고, 두 서버의 `$HOME/SmartTicketing`에 저장소를 clone한다.
- 두 서버에서 `git fetch origin main`이 비대화식으로 성공해야 한다. 비공개 저장소는 별도 읽기 인증을 준비한다.
- 두 서버 모두 이미지 다운로드를 위한 외부 연결이 필요하다.
- GitHub Actions runner에서 두 서버의 SSH로 접속할 수 있어야 한다. SSH 주소와 서버 간 사설 IP는 용도가 다르다.
- [실행 가이드](README.md)의 A/B 통신 보안 그룹을 설정한다. 세 번째 EC2는 필요하지 않다.

A에서 실제 사설 IP로 설정을 한 번 만들고, 생성된 파일을 B의 같은 위치에 안전하게 복사한다.

```bash
cd ~/SmartTicketing
python3 scripts/distributed/lab.py init --server-a-ip 10.0.1.10 --server-b-ip 10.0.1.11
```

양쪽 `scripts/distributed/.env`는 내용이 같아야 한다. `chmod 600 scripts/distributed/.env`로 보호한다.
대기열 ON/OFF 등 공유 설정을 바꿀 때도 양쪽 파일을 함께 바꾼다.
기존 설정 파일이 있다면 `init`을 다시 실행하지 않는다.

## 2. GitHub Actions 설정

저장소 Settings → Secrets and variables → Actions에서 등록한다.

| 종류 | 이름 | 값 |
|---|---|---|
| Secret | `AWS_DISTRIBUTED_A_HOST` | A의 SSH용 공인 IP 또는 DNS 이름 |
| Secret | `AWS_DISTRIBUTED_B_HOST` | B의 SSH용 공인 IP 또는 DNS 이름 |
| Secret | `AWS_USERNAME` | 두 서버 공통 SSH 사용자, 예: `ubuntu` |
| Secret | `AWS_SSH_KEY` | 두 서버에 접속 가능한 공통 SSH 개인 키 |
| Variable | `DEPLOY_TARGET` | `distributed` |

기존 `AWS_USERNAME`, `AWS_SSH_KEY`를 두 서버에서 사용할 수 있으면 그대로 사용한다.
DB·Redis 비밀번호와 JWT 키는 서버의 `.env`에만 둔다. CD는 비밀번호를 새로 만들거나 출력하지 않는다.

`DEPLOY_TARGET`이 없거나 `production`이면 기존 단일 EC2 운영 배포를 실행한다.
`distributed`이면 두 EC2의 분산 API 배포만 실행하고 기존 운영 배포 job은 실행하지 않는다.
이 값을 선택한다고 기존 운영 컨테이너를 종료하거나 운영 DB를 옮기지는 않는다.
정의되지 않은 값은 CI에서 거부한다.

SSH 서버 키는 기존 배포와 동일하게 실행 시 네트워크에서 조회한다.
이 방식은 별도로 고정한 서버 키와 대조하는 방식은 아니다.

## 3. 최초 실행과 이후 자동 배포

변경 코드를 `main`에 반영한 뒤 GitHub Actions의 **Test and deploy SmartTicketing**을 수동 실행한다.
전용 실험 DB에 스키마가 아직 없다면 `initialize_distributed_schema`를 최초 한 번만 선택한다.
이 옵션은 A의 `distributed_lab` 스키마를 생성·갱신하며, 기존 데이터를 지우거나 fixture를 적재하지 않는다.

이후 `main` push마다 자동 배포된다. 서버에서 이미지를 직접 빌드하거나 tar 파일을 복사할 필요가 없다.
일반 배포는 `DDL_MODE=validate`를 사용한다. 스키마가 바뀌면 별도 반영 절차가 필요하며 자동 삭제·초기화하지 않는다.

예매 테스트용 데이터가 아직 없다면 배포 후 A에서 한 번 실행한다.

```bash
python3 scripts/distributed/lab.py fixture
```

fixture는 사용자 테이블이 비어 있을 때만 생성된다. 기존 데이터가 있으면 중단한다.
대기열·동시 예매 검증은 [시나리오 가이드](SCENARIOS.md)를 따른다.

## 배포 성공 조건과 기록

- A/B에 전달된 압축 파일의 체크섬과 백엔드 이미지 내용·설정 fingerprint가 CI 결과와 일치해야 한다.
- 두 서버의 `.env`가 일치해야 컨테이너 갱신을 시작한다. 이미지 태그는 테스트한 커밋 SHA로 양쪽에 기록한다.
- 두 백엔드 readiness와 Nginx 경유 readiness가 성공해야 한다.
- CD는 테스트 인원과 요청 수를 정하지 않는다. EC2 두 대를 준비한 뒤 인원을 지정하고 늘려가며 로드밸런싱을 별도 검증한다.
- 어느 단계든 실패하면 Actions 배포 job이 실패한다. 한 서버만 성공한 것을 전체 성공으로 표시하지 않는다.

GitHub Actions의 `distributed-deployment-*` artifact에 단계별 성공 기록이 남는다.
각 서버의 `.local/distributed/deployment.json`에는 시작한 이미지·커밋·역할이 남고,
A에는 `preflight` 검증 JSON도 남는다. 서버 기록만으로 전체 배포 성공을 판단하지 않고 Actions 결과를 함께 확인한다.

이 배포는 A/B를 차례로 갱신한다. 배포 중 일시적인 중단이나 서로 다른 버전의 동시 실행이 가능하며,
무중단 배포와 자동 롤백은 제공하지 않는다. B 배포 실패 시 A는 새 버전으로 남을 수 있으므로 원인을 수정한 뒤 재실행한다.
배포용 checkout은 지정한 `main` 커밋으로 맞추므로 서버에서 추적 파일을 직접 수정하지 않는다.
