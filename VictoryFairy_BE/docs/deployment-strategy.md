# 배포 전략 (Deployment Strategy)

> main 브랜치 merge → GitHub Actions 빌드 → GHCR → EC2 자동 배포
> 운영 절차/명령어는 [cicd-runbook.md](./cicd-runbook.md) 참고. 이 문서는 "왜 이렇게 설계했는가"를 다룬다.

---

## 전체 아키텍처

```
① 내 노트북        ② GitHub            ③ GitHub 러너            ④ EC2 (운영 서버)
 (코드 작성)        (코드+레시피 보관)    (빌드 전용 임시 PC)        (앱 실행)

 git push / PR ──► main 에 merge ──► Actions 발동
                                      ├ user/quiz 이미지 빌드
                                      └ GHCR 로 push (:latest, :<sha>)
                                                    │
                                                    ▼
                                         EC2 가 SSH 로 받은 명령 실행
                                          ├ docker compose pull
                                          └ docker compose up -d
                                                    │
   인터넷 ──80/443──► [nginx] ──► 127.0.0.1:8080~8081 컨테이너 ──► AWS RDS(MySQL)
```

등장하는 컴퓨터는 4개이며 책임이 분리돼 있다.

---

## 핵심 설계 결정과 이유

### 1. 빌드는 GitHub 러너, 실행은 EC2 (build once, run anywhere)
- EC2 는 `t2.micro`(1GB RAM) 라 Gradle 멀티모듈 빌드 시 **OOM** 위험이 크다.
- 따라서 무거운 **빌드는 GitHub 러너**(무료·고성능)에서 수행하고, EC2 는 완성된 이미지를 **받아서 실행만** 한다.
- dev/prod 가 **동일한 이미지**를 쓰고, 환경 차이는 실행 시 주입하는 환경변수로만 가른다.

### 2. 이미지 레지스트리: GHCR
- GitHub Container Registry 사용. `GITHUB_TOKEN` 으로 push/pull 인증이 되어 별도 비용·설정이 적다.
- 이미지 경로: `ghcr.io/<owner-lowercase>/victoryfairy-<module>:<tag>`
- 태그는 `latest` 와 커밋 `:<sha>` 두 가지 → sha 태그로 특정 버전 롤백 가능.

### 3. 배포 트리거: main push (= PR merge)
- `.github/workflows/deploy.yml` 가 `on: push: branches: [main]` 로 발동.
- 수동 실행(`workflow_dispatch`)도 가능.

### 4. dev/prod 분리 = Dockerfile 1개 + 프로파일 yaml
- **Dockerfile 은 dev/prod 공용 1개.** 절대 두 개로 나누지 않는다.
- 환경 차이는 `application-dev.yaml` / `application-prod.yaml` 에만 존재
  (예: dev `ddl-auto: create`·SQL로그 on / prod `ddl-auto: none`·로그 off·스택트레이스 숨김).
- 어느 프로파일을 켤지는 `application.yaml` 의 `${SPRING_PROFILES_ACTIVE:dev}` 가 결정 → **`.env` 의 값으로 스위치**.

### 5. 설정/비밀 보관 원칙
| 대상 | 보관 위치 | git | 이유 |
|------|----------|:---:|------|
| `application*.yaml` | 각 모듈 `src/main/resources/` | ✅ 커밋 | 비밀값 없이 구조만(placeholder). 빌드 시 jar 에 포함돼야 함 |
| `.env` (실제 비밀값) | 노트북·EC2 에 직접 (`chmod 600`) | ❌ | 유출 방지. 머신마다 따로 둠 |
| 배포용 비밀(SSH 키 등) | GitHub Secrets | ❌ | 앱 비밀과 분리 |

- **주의**: application yaml 을 gitignore 하면 GitHub 러너 체크아웃에 파일이 없어 **빌드가 깨진다.** 그래서 비밀 없는 yaml 은 반드시 커밋한다.
- 앱 비밀값(DB_PASSWORD, JWT_SECRET)은 **GitHub 에 넣지 않는다.** 빌드는 비밀이 필요 없고, 실행 시 EC2 의 `.env` 만 읽으면 되기 때문.

### 6. 리버스 프록시(nginx)와 포트 보안
- nginx 를 **compose 서비스**로 운영(`docker-compose.prod.yml` 의 `nginx`). 외부엔 80(추후 443)만 노출.
- 설정은 `nginx.conf`(repo) → deploy 단계에서 `~/app/nginx.conf` 로 scp → 컨테이너에 마운트.
- 같은 도커 네트워크라 앱을 **서비스명**으로 프록시: `/api/auth`→`user:8080`, `/api/quiz`→`quiz:8081`.
  (호스트 `127.0.0.1` 바인딩이 아닌 도커 DNS 사용 → k8s Service 개념과 유사, 전환 시 Ingress 규칙으로 이전)
- 앱 컨테이너 포트는 `127.0.0.1:` 로 한정(디버그용), 외부 직접 접근 차단.
- AWS 보안 그룹: 80/443/22 만 개방, 8080~8081·3306 은 닫음.
- 참고: `infra/nginx/victoryfairy.conf` 는 **호스트 nginx** 용 대안(현재는 compose nginx 사용).
- k8s 전환 시: 이 nginx 는 **Ingress Controller + 클라우드 LB** 로 대체. 라우팅 규칙/이미지/파이프라인은 그대로 이전.

### 7. 데이터베이스: prod 는 AWS RDS
- prod 는 mysql 컨테이너 대신 **AWS RDS(MySQL)** 사용 → `docker-compose.prod.yml` 에 mysql 서비스 없음.
- 앱은 `.env` 의 `DB_HOST`(RDS 엔드포인트)로 접속. EC2 `~/app/.env` 의 `DB_HOST` 를 RDS 주소로 설정.
- 이점: 1GB EC2 에서 mysql 컨테이너(~400MB) 부담 제거 → OOM 위험 완화, DB 백업/관리는 RDS 가 담당.
- 전제: RDS 보안 그룹에서 **EC2 의 접근(3306)** 허용, prod 프로파일 `ddl-auto: none` 이므로 **스키마가 RDS 에 미리 있어야** 함.
- 로컬 개발용 `docker-compose.yml` 은 기존대로 mysql 컨테이너 사용 가능(dev).

### 8. HTTPS (보류)
- 무료 인증서(Let's Encrypt)는 **도메인이 필요** → 도메인 확보 후 진행 예정.
- 절차: 도메인 DNS A레코드 → EC2(EIP) → 443 개방 → `certbot --nginx` (기존 리버스 프록시 설정 위에 TLS 만 추가됨).
- 도메인 없이 가능한 대안: 무료 서브도메인(DuckDNS/nip.io)+Let's Encrypt, 또는 self-signed(브라우저 경고).

---

### 9. 선택적 빌드 (변경 모듈만)
- `detect` 잡이 변경 경로를 보고 **의존성 그래프**에 따라 빌드 대상을 계산 → 동적 matrix.
- 규칙: `common`/`domain`/루트(build.gradle·Dockerfile·워크플로) 변경 → **전체**;
  `user` 변경 → user+quiz(quiz가 user 의존); `quiz` 변경 → 해당 모듈만.
- 배포는 **`:latest` 태그 기반** → 바뀐 모듈만 새 이미지로 갱신되고 `up -d`가 그 컨테이너만 재기동.
- compose 만 바뀌어도(모듈 빌드 없이) 배포는 수행(`deploy=true`).
- 전제: 각 모듈의 `:latest` 가 GHCR 에 이미 존재(최초 1회 전체 빌드 후 성립).

## 관련 파일
| 파일 | 역할 |
|------|------|
| `.github/workflows/deploy.yml` | 빌드→GHCR push→EC2 배포 |
| `Dockerfile` | dev/prod 공용 멀티스테이지 빌드 (ARG MODULE 로 모듈 선택) |
| `docker-compose.yml` | 로컬 개발용(직접 빌드) |
| `docker-compose.prod.yml` | EC2 운영용(GHCR 이미지 실행, 포트 localhost 한정) |
| `infra/nginx/victoryfairy.conf` | nginx 리버스 프록시 |
| `application*.yaml` | 환경별 앱 설정(비밀 없음) |
| `.env` | 비밀값 (git 제외, 머신별) |

## 첫 배포 전 체크리스트
- [ ] user/quiz yaml 커밋 (gitignore 규칙 제거 완료)
- [ ] GitHub Secrets: `EC2_HOST`, `EC2_USER`, `EC2_SSH_KEY`
- [ ] EC2: `~/app/.env` 생성(prod 값) + `docker compose` 설치 확인
- [ ] EC2: 스왑 메모리 추가 (1GB RAM 에 앱3+MySQL → OOM 방지)
- [ ] 첫 배포 후: nginx 설정 적용 + 보안 그룹 정리
