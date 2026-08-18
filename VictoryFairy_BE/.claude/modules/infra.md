# infra 모듈 (배포 · 인프라)

> 이 파일은 infra/배포 작업 시에만 로드되는 슬림 컨텍스트다.
> 최종 업데이트: 2026-08-12
>
> **서빙은 EKS다.** EC2+docker-compose 배포 경로는 2026-07-27 폐기됐다 — 대상 인스턴스가 이미
> 사라져 있었고 워크플로도 실패만 하고 있었다. `deploy.yml`·`docker-compose.prod.yml`·`nginx.conf`를
> 삭제했다. 되살리자는 제안을 먼저 하지 말 것(필요하면 git 히스토리에 있다).

---

## 서빙 구조

```
victoryfairy.com ──HTTPS──► ALB ──► user-app(8080) / quiz-app(8081) 파드
                                          │
                                          └──► EC2 자체 호스팅 MySQL (victoryfairy-mysql-dev, 10.0.0.14)
```

- **코드 위치**: 인프라 Terraform·k8s 매니페스트는 이 BE 트리가 아니라 상위 레포의 `VictoryFairy_Infra/`에 있다.
  `main`에는 `VictoryFairy_BE/`·`VictoryFairy_Infra/`·`VictoryFairy_AI/`가 함께 있지만 `dev_*` 브랜치는 각자
  담당 디렉터리만 가진 **분리된 트리**라 `dev_be`에서는 `VictoryFairy_Infra/`가 보이지 않는다.
  인프라 파일을 봐야 하면 **`main` 기준**으로 볼 것. Terraform 규약은 루트 `.claude/skills/terraform-infra`.
  상태는 S3 백엔드(`victoryfairy-tfstate`, key `dev/terraform.tfstate`).
- **EKS**: 클러스터 `victoryfairy-dev`, k8s **1.30**(⚠ 표준 지원 종료 → 연장 지원 과금 구간, 업그레이드 필요).
  노드그룹 2개 — `app`(user·quiz 공용 t3.medium On-Demand, HPA+Cluster Autoscaler),
  `batch`(Spot, 평소 0대, CronJob 시각에만 0→N→0). 워커는 프라이빗 서브넷, 파드 권한은 IRSA(OIDC).
- **DB**: RDS 미사용 — **EC2 자체 호스팅 MySQL+Redis 컨테이너**(`modules/mysql-ec2`), EBS 영속 볼륨,
  SSM 포트포워딩 접근(22/3306 인입 미개방), mysqldump→S3 백업 크론.
  ⚠ **MySQL EC2가 두 대다**(2026-08-01 AWS 실측, 헷갈리기 쉬우니 "운영 DB 확인했다"고 말할 때 어느 쪽인지 구분할 것):
  - `victoryfairy-mysql-dev` — private `10.0.0.14` / public `43.200.82.148`. **EKS 파드가 쓰는 쪽**
    (클러스터의 헤드리스 Service `mysql`, k8s `30-external-data.yaml`의 Endpoints가 이 IP).
  - `victoryfairy-devdb-dev` — private `10.0.0.163` / public `52.78.153.242`. BE 레포 `.env`의 `DB_HOST`가
    가리키는 **로컬 개발용**(매일 새벽 프로덕션 덤프를 복원).
  - 두 DB는 데이터가 다르다.
- **레지스트리/CI**: ECR(`victoryfairy-user`, `victoryfairy-quiz`) + GitHub Actions keyless(OIDC).

## 앱 설정 주입

user·quiz 파드가 `envFrom`으로 ConfigMap `app-config` + Secret `app-secret`을 함께 읽는다
(`DB_HOST/PORT/NAME`, `REDIS_HOST/PORT`, `SPRING_PROFILES_ACTIVE`, `JAVA_TOOL_OPTIONS` 등).

- ⚠ **두 앱이 같은 ConfigMap을 공유**한다. 여기에 키를 넣으면 한쪽만 바꿀 수 없고 양쪽에 적용된다.
- ⚠ `envFrom`은 **핫리로드가 안 된다.** ConfigMap을 고쳤으면 `kubectl rollout restart`로 파드를 새로 띄워야 반영된다.
- `ddl-auto`는 ConfigMap으로 덮어쓰지 않는다(2026-07-27 `SPRING_JPA_HIBERNATE_DDLAUTO` 키 제거).
  각 앱의 `application-prod.yaml`이 출처다 — **user `update`, quiz `none`**.
  종전에는 ConfigMap이 `update`를 양쪽에 강제해 quiz의 yaml 값이 무시되고 있었다.
- ⚠ **prod 스키마는 `user` 앱이 만든다.** `UserApplication`이 `@EntityScan("com.skhynix")`로 domain 엔티티를
  전부 스캔하는데 `user`가 `ddl-auto=update`라, quiz가 `none`이어도 채팅 테이블까지 user 기동 시 생성된다.
  뒤집어 말하면 **user를 `none`으로 되돌리는 순간 새 엔티티는 아무도 만들지 않는다.** Flyway는 없다.

## 도메인 + HTTPS (적용 완료 2026-07-27)

루트 `victoryfairy.com`이 Route53(신규 존)+ACM(DNS 검증, ISSUED) 인증서로 ALB에 연결돼 HTTPS로 서비스 중.
AWS Load Balancer Controller + ExternalDNS(`k8s/23-external-dns.yaml`, apex A(ALIAS)→ALB 자동 레코드).
모듈: `VictoryFairy_Infra/modules/{dns,alb}`. runbook: `VictoryFairy_Infra/docs/domain-https-setup.md`.

- **Ingress는 2개다**(`k8s/22-ingress.yaml`): `victoryfairy-user`(`/api`) / `victoryfairy-quiz`(`/rt`).
  `group.name: victoryfairy`를 같게 줘서 **ALB는 하나**로 묶인다. 쪼갠 이유는 헬스체크 경로가 앱마다 다르기
  때문 — `healthcheck-path`는 Ingress 단위 어노테이션이라 하나로는 두 값을 담을 수 없다.
- ALB 헬스체크: `/api/actuator/health/readiness`, `/rt/actuator/health/readiness`.
  ⚠ `/actuator/health` 전체가 아니라 **readiness 그룹**이다(전체는 db·redis 인디케이터를 합산해 DOWN을 내므로
  MySQL EC2가 흔들리면 멀쩡한 파드까지 타깃에서 빠진다).
- ⚠ **ALB는 forward 시 경로 rewrite를 못 한다**(redirect만 가능). Ingress path와 앱의
  `server.servlet.context-path`가 문자 그대로 일치해야 한다.
- ⚠ 레지스트라 NS: 도메인 재등록·이전 시 가비아 네임서버가 기본값으로 초기화된다. Route53 존 NS 4개로
  다시 등록해야 하며, 그동안 도메인 전체가 SERVFAIL이 된다(2026-07-27 실제 발생).

## CI/CD

**`deploy-eks.yml` 하나뿐이다**(저장소 루트 `.github/workflows/`).
push(main) + `workflow_dispatch` → 변경 모듈 감지 → Docker 빌드 → ECR push(**커밋 SHA 7자리**, 태그 IMMUTABLE)
→ 매니페스트(`k8s/20-user-app.yaml`·`21-quiz-app.yaml`) 렌더+치환 검증 → **`kubectl apply -f`**(파일 전체)
→ 블루-그린 롤아웃(`maxSurge 100%/maxUnavailable 0`) → **실패 시 자동 `rollout undo`**.
인증은 OIDC AssumeRole(`victoryfairy-dev-github-actions`)이라 액세스 키 시크릿이 없다.

- **매니페스트가 단일 진실 공급원이다.** 종전 `kubectl set image`는 이미지만 갈아끼워 매니페스트에 정의를
  넣어도 클러스터에 반영되지 않았다(IRSA ServiceAccount를 사람이 손으로 apply+patch 해야 했던 원인).
  이제 매 배포마다 파일 전체를 apply하므로 Deployment 외 Service·HPA·ServiceAccount까지 함께 수렴한다.
  **역으로 클러스터에만 손으로 넣어둔 설정은 다음 배포 때 되돌아간다.**
- 두 Deployment 모두 `replicas`를 두지 않는다 — HPA(user min1/max2, quiz min1/max4)가 레플리카 소유권을
  갖는다. 넣으면 apply마다 1로 되돌아가 HPA와 싸운다. **되돌리지 말 것.**
- 워크플로 안에서 렌더/검증(`id: render`, 클러스터 미변경)과 apply+rollout(`id: deploy`)이 **분리된 스텝**이다.
  `Rollback on failure`는 `steps.deploy.outcome == 'failure'` 조건이라, 합치면 클러스터를 건드리지도 않은
  실패(매니페스트 누락·치환 실패)에서도 `rollout undo`가 돌아 멀쩡한 파드를 되돌린다. **합치지 말 것.**
- `rollout undo`는 **Deployment만** 되돌린다. 같은 커밋에서 Service·HPA·SA를 함께 바꿨다면 그 변경은
  클러스터에 남으므로 직전 커밋 매니페스트를 수동 재apply해야 한다.
- ⚠ **`VictoryFairy_Infra/k8s/**`만 바꾼 커밋은 이 워크플로를 트리거하지 않는다** — push 경로 필터가
  `VictoryFairy_BE/**`와 워크플로 자신뿐이다. BE 변경이 뒤따를 때까지 매니페스트만 고쳐서는 반영 안 됨.
- 로컬 수동 배포 `VictoryFairy_Infra/scripts/deploy-app.sh`도 같은 `sed | kubectl apply -f -` 방식.

## DB 마이그레이션 선행 조건 (수동 적용, `infra/sql/migrate-*.sql`)

**`deploy-eks.yml`엔 SQL 실행 단계가 없다** — 위 CI/CD가 하는 일은 `kubectl apply -f`뿐이라, 마이그레이션은 항상 사람이 devdb·운영 DB에 직접 돌려야 한다. 순서를 지키지 않으면(SQL 나중, 배포 먼저) **앱이 아예 기동하지 못하는 사례가 실제로 있다.**

- ~~**`infra/sql/migrate-reserved-uids-to-uuid.sql`**~~ — 해소(2026-08-18 신설, 같은 날 devdb·운영 DB 양쪽 적용 완료). 예약 계정 2건(SYSTEM · `(알수없음)` 더미 계정)과 구단 공용 채팅방 10건의 `uid`를 손으로 지어낸 순차값(`00000000-0000-0000-0000-00000000000X`)에서 실제 UUID로 바꾸는 12건의 `UPDATE`(id는 안 건드려 자식 참조는 온전, 재실행 시 0행 매칭 no-op).
  ⚠ 적용은 끝났지만 **왜 순서가 강제였는지는 남긴다**(같은 형태의 시드+부트스트랩 조합이 또 나오면 그대로 재현된다) — 코드를 먼저 배포하고 SQL을 나중에 돌리면 `chat-init.sql`의 멱등성 가드가 새 uid로 `WHERE NOT EXISTS`를 걸게 바뀌는데 DB엔 아직 옛 uid 행만 있어 SYSTEM 계정·구단 채팅방 10건이 **중복 생성**되고, `UnknownAccountBootstrapper`(`ApplicationRunner`라 **기동 경로**에 있다)는 새 uid로 `(알수없음)` 계정을 못 찾아 새로 만들려다 `users.email`/`users.tel` UNIQUE 충돌로 **INSERT가 실패해 앱이 아예 뜨지 않는다.** "SQL 먼저, 배포 나중"이 순서다. 상세는 `.claude/modules/user.md`(`UnknownAccountPolicy`)·`.claude/modules/domain.md`.
  참고: 채팅방 uid는 SSE 구독 URL 등 외부 노출 식별자라 적용 순간 그 값을 물고 있던 클라이언트 연결이 끊긴다(방 목록을 다시 받으면 새 uid로 복구되나 무중단은 아니다 — 트래픽 적은 시간대 권장). 계정 쪽 uid 2건은 로그인이 성립하지 않는 예약 계정(BCrypt 패턴이 아닌 placeholder 비밀번호)이라 발급된 토큰이 없고, 무효화되는 실사용자 토큰도 없다.
- ~~**`infra/sql/migrate-quiz-like-account-set-null.sql`**~~ — 해소(2026-08-18, devdb·운영 DB 양쪽 적용 완료). `quizzes_like`의 계정 FK를 `ON DELETE CASCADE`→`SET NULL`로 바꾼다(만료 데이터 정리 스케줄러가 계정을 하드 삭제할 때 추천 수가 조용히 깎이지 않게 하는 선행 조건). 상세는 `.claude/modules/domain.md`.

## 로컬 개발

- `docker-compose.yml` — `mysql:8.0`(3306, `mysql-data` 볼륨) + `redis:7.2-alpine`(6379). 둘 다 healthcheck 있음.
  `user`·`quiz`는 `profiles: ["prod"]` 뒤에 숨어 있어 **기본 실행은 DB만 뜬다.** 앱까지 띄우려면 `--profile prod`.
- `Dockerfile` — **EKS CI도 이 파일로 빌드한다**(`docker build --build-arg MODULE=...`). 로컬 전용이 아니므로
  함부로 바꾸면 운영 빌드가 깨진다.
- ⚠ `docker compose down -v`는 `mysql-data` 볼륨을 지운다. 사용자가 로컬 개발 DB로 쓰고 있다.

## redis

`user`: 이메일 인증 상태 저장(TTL 휘발성, 영속 볼륨 불필요). `quiz`: prod 프로파일 실시간 fan-out
(`RedisPubSubPublisher`, 상세는 `.claude/modules/quiz.md`). ⚠ **`quiz`도 redis를 쓴다**(종전 "quiz는
안 씀" 서술 정정 — `docker-compose.yml` 확인 결과 `user`·`quiz` 둘 다 `SPRING_DATA_REDIS_HOST`/`PORT`가
주입되고, 같은 `redis:7.2-alpine` 컨테이너를 공유한다).

## 이메일 발송 (Mailjet SMTP, user 전용, prod 프로파일)

`SmtpEmailSender`(`@Profile("prod")`)가 인증번호를 실발송한다. dev/test는 `LogEmailSender`(mock)라 설정 불필요.
필요한 값: `MAIL_HOST`(`in-v3.mailjet.com`)·`MAIL_PORT`(587, STARTTLS)·`MAIL_USERNAME`(API Key)·
`MAIL_PASSWORD`(Secret Key)·`MAIL_FROM`(`no-reply@victoryfairy.com`).
**EKS에서는 ConfigMap `app-config`/Secret `app-secret`으로 주입한다.** 가이드: `docs/deployment/email-verification-mailjet.md`.

## 알려진 갭

- **Mailjet SPF 미등록** — DKIM·도메인 검증은 통과했으나 SPF가 없다. apex TXT를 ExternalDNS 소유권 레코드가
  점유하고 있어 SPF를 넣어도 1분 안에 덮어써진다. `--txt-prefix=_externaldns.`로 소유권 TXT를 하위 도메인으로
  내리면 함께 풀린다. 도달률(Gmail·네이버)에 영향.
- **ExternalDNS가 1분마다 같은 레코드를 UPSERT한다** — v0.15는 소유권 TXT를 구형/신형 두 개 쓰는데, apex
  레코드라 신형 이름이 `cname-victoryfairy.com`이 되어 **존 밖으로 나간다**(`no hosted zone matching record
  DNS Name`). 영영 못 만들어 매 루프 재시도한다. DNS는 정상이라 실질 피해는 로그 노이즈뿐. 위 SPF 건과 같은 방법으로 해소된다.
- **k8s probe가 전부 `tcpSocket`이다** — 포트가 열렸는지만 보고 앱이 건강한지는 안 본다. actuator readiness가
  생겼으니 `httpGet`으로 올릴 수 있다(`VictoryFairy_Infra/k8s/20-user-app.yaml`·`21-quiz-app.yaml`).
- ~~**`game_statuses` 시드 0행**~~ — 해소(2026-08-05). `infra/sql/game-statuses-init.sql`을 `user` 앱이 dev·prod 모두
  기동 시 실행한다(`spring.sql.init`). 배포 전 수동 SQL 불필요. 상세는 `.claude/modules/domain.md`.
- **CI에 테스트 단계가 없다** — 빌드만 하고 배포한다. 테스트는 32개 있으므로 넣을 명분이 있다.
- **`deploy-eks.yml`에 concurrency 설정이 없다** — 연속 push 시 두 워크플로 실행이 겹쳐 롤아웃이 경합할 수 있다.
- **EKS 1.30 연장 지원 과금 구간** — 버전 업그레이드 필요.
- ~~**`docker-compose.yml`의 `user`/`quiz` `environment:`에 `JWT_SECRET`이 없다**~~ — 해소(#342, 2026-08-11). 두 서비스 모두 `JWT_SECRET: ${JWT_SECRET}`이 주입되며(2026-08-12 재확인), 종전 증상(로컬 `docker compose --profile prod` 로그인이 refresh 토큰 길이 초과로 500)은 재현되지 않는다 — 로그인 200 실측 확인.

## 미결정 사항
- [x] 클러스터 방식: **EKS 채택** (managed control plane, `victoryfairy-dev`)
- [x] 도메인·HTTPS: **적용 완료**, `https://victoryfairy.com` EKS 서빙 (2026-07-27 종단 확인)
- [x] 앱 헬스 엔드포인트: **actuator readiness 도입 완료**, ALB 타깃 healthy 확인
- [x] EC2+compose 경로 정리: **폐기 완료** (2026-07-27)
- [ ] EKS k8s **1.30 → 상위 버전 업그레이드**
- [ ] Mailjet SPF 등록 (ExternalDNS txt-prefix 조정과 함께)
- [ ] 앱 개수 / 예상 트래픽 (스케일 상한 재산정)
