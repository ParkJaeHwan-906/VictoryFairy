# VictoryFairy 인프라 아키텍처 (결정 기록)

> 이 문서는 **확정된 인프라 설계와 그 근거(왜 이렇게 정했나)**를 기록한다.
> - 강제 규약: `.claude/skills/terraform-infra/SKILL.md`
> - 코드: `VictoryFairy_Infra/modules/` · `environments/`
> - 이 문서: 결정 기록(ADR 성격). 코드를 읽어도 모르는 "선택과 트레이드오프"를 남긴다.

## 확정 아키텍처 한눈에

| 계층 | 구성 | 스케일 |
|------|------|--------|
| 네트워크 | VPC `10.0.0.0/16`, **2a 운영 / 2c 예비** | — |
| 앱 컴퓨트 | EKS 1.30, 노드그룹 **user · quiz · batch** (분리) | quiz·batch만 오토스케일 |
| 데이터 | **단일 고정 EC2**(비 EKS)에 MySQL + 서비스 Redis | 스케일 없음(수직만) |
| 배치 | 매일 03:15 KST, **Spot xlarge** 노드그룹 0→N→0 | 일시적 |
| 접근 | SSM Session Manager only (22/3306 인입 없음) | — |

---

## 1. 네트워크 — 왜 2 AZ인데 노드는 2a에만?

- 서브넷은 **2개 AZ(2a, 2c)**에 선언한다. 이유는 HA가 아니라 **EKS의 강제 요건**(컨트롤플레인은 최소 2개 AZ 서브넷 필요). 1개 AZ면 클러스터가 생성되지 않는다.
- **실제 노드·DB는 2a에 집중**하고 `2c`는 **예비 서브넷**(노드 미배치)으로 둔다.
  - 근거: DB(MySQL/Redis)가 2a 단일 AZ 고정이라, 2a 전체 장애 시 앱을 2c에 벌려도 어차피 서비스가 멈춘다. 앱만 멀티 AZ로 벌리면 **크로스 AZ 지연·전송요금만 늘고 HA는 DB에서 막힌다**(반쪽 HA).
  - 그래서 앱·DB를 같은 AZ(2a)에 모아 크로스 AZ 비용을 없앤다. NAT Gateway도 2a 단일.
- **진짜 AZ 장애 대비 HA는 현재 없음.** 필요해지는 시점 = DB를 이중화(2c에 MySQL standby / Redis 복제)하기로 결정할 때. 그때 2c를 켠다.
- 노드 레벨 이중화는 유지된다: 같은 AZ 안에서도 노드 2대 이상이면 인스턴스/랙 장애는 버틴다(실제 장애의 대부분).

## 2. 앱 컴퓨트 — 노드그룹 3개로 분리

두 개의 Spring 앱을 **서로 다른 노드**에서 돌린다.

- **user 노드그룹** — `nodeSelector: workload=user`. 안정 규모(min 2 / max 3).
- **quiz 노드그룹** — 실시간 채팅·퀴즈. `taint: workload=quiz` + 파드 `toleration`으로 전용 격리. 트래픽 폭주 대비 **오토스케일**(HPA로 파드 → Cluster Autoscaler로 신규 EC2 노드, min 2 / max 8). quiz 폭주가 user 노드를 잠식하지 못한다.
- **batch 노드그룹** — §4 배치 전용. `capacity_type=SPOT`, `min 0 / max N`, `taint: workload=batch`. 평소 0대(비용 $0), CronJob 시각에만 뜬다.

> **부하 분산 = 신규 EC2 노드.** "필요 시 신규 EC2"는 quiz 노드그룹에 Cluster Autoscaler가 노드를 붙이는 것을 뜻한다. 데이터 EC2를 늘리는 게 아니다.

## 3. 데이터 티어 — 단일 고정, 앱과 격리

- **단일 고정 EC2**(t3.small, 비 EKS). MySQL + Redis 컨테이너, 데이터는 EBS `gp3`(`prevent_destroy`).
- **격리 원칙**: 이 EC2엔 앱 워크로드를 올리지 않고 오토스케일도 없다(장애 blast radius 축소). 부족하면 스케일아웃이 아니라 **인스턴스 승급(수직)**.
- **서비스 Redis(6379)는 브로커 전용**: 채팅·퀴즈 pub/sub 팬아웃, 이메일 인증 TTL 키. quiz가 다중 파드로 스케일아웃돼도 pub/sub이 파드 간 SSE 이벤트를 팬아웃한다.
  - ⚠ t3.small(2GB)은 MySQL+Redis에 빠듯하다. `innodb_buffer_pool_size` + Redis `maxmemory`/`maxmemory-policy`로 상한을 나눠 잡고 스왑 대비.
- **백업**(RDS 자동백업 대체): MySQL `mysqldump` cron → S3, 병행 EBS DLM 스냅샷. 이 백업 없이는 인스턴스/AZ 장애 = 데이터 유실.
- **접근**: SSM 포트포워딩만. SG 인입은 `3306 ← user·quiz·batch`(batch는 최종 저장), `6379 ← user·quiz`.

## 4. 배치 파이프라인 — 야간 크롤→정제→생성

매일 **03:15 KST**(K8s CronJob `15 3 * * *`, `timeZone: Asia/Seoul`)에 시작해 완료까지 돈다.

- **일시적 Spot 노드**: CronJob이 워크플로를 띄우면 Pending 파드 → Cluster Autoscaler가 batch 노드그룹(Spot xlarge)을 **0→N** 증설 → 마지막 파드 종료 시 **0으로 축소, EC2 소멸**.
- **스트리밍 파이프라인(단계가 겹쳐 흐름)**: 단계 배리어가 아니라 **S3 개수 트리거**로 이어진다.
  1. **크롤링** — 소스별 fan-out. 결과를 `s3://.../raw/`에 저장(**파일 1개 = 1건**).
  2. `raw/` **파일 개수 ≥ N** → 크롤이 도는 중에 **정제** 시작. 결과를 `clean/`에 저장(**JSON 배열 파일, 여러 건**).
  3. `clean/` **레코드 개수 ≥ N** → 정제가 도는 중에 **문제 생성** 시작(Claude API). 
  4. 최종 문제 → **MySQL(quiz)**.
- **트리거 = 워커 주도 카운터(폴링 아님)**, 저장소는 **배치 전용 임시 Redis**(§아래):
  - `raw/`: 파일 1건=1개라 **파일 개수 = 데이터 개수**. 크롤러가 `INCR raw_count`(중복은 `SADD seen <키>`가 0 반환으로 차단).
  - `clean/`: 배열이라 파일 수 ≠ 데이터 수. **정제 워커가 담은 레코드 수 M을 알고 있으니** `INCRBY clean_count M`. (S3 파일을 다시 파싱해 세지 않는다.)
  - 발사: `INCR` 반환값이 N 경계를 넘으면 워커가 Redis 리스트/스트림에 신호 → 컨트롤러(또는 Argo Events Redis 소스)가 다음 단계 기동.
- **배치 상태 Redis는 서비스 Redis와 물리 분리**: 데이터 EC2가 아니라 **배치와 함께 뜨고 사라지는 임시 Redis 파드**. 서비스 실시간 지연·메모리를 보호하고, 배치는 데이터 EC2의 3306(최종 저장)만 접근한다. (논리 분리(같은 Redis, DB 인덱스)는 2GB 박스에서 자원 격리가 안 돼 부족 → 물리 분리 선택.)
- **처리 추적·멱등**: 카운터는 "기동 신호"일 뿐, 처리는 **파일 단위**. 처리한 파일은 `done/`으로 이동해 중복 차단. **진짜 원천은 S3 `done/`** — 임시 Redis가 Spot 회수로 날아가도 야간 배치 멱등 재실행으로 복구. `"≥ N"`은 소프트 게이트(약간 초과 처리 가능).

## 5. Terraform / Kubernetes 경계

- **Terraform(`.tf`, 이 레포)**: VPC·서브넷·NAT, EKS 클러스터, 노드그룹 3개(user/quiz/batch), MySQL EC2·EBS·SG·IAM, S3. **클러스터와 노드그룹까지.**
- **Kubernetes(YAML/Helm, 앱 레포 VitoryFairy_BE)**: Deployment(user/quiz), HPA(quiz), CronJob(배치), 배치 워커, 배치 Redis 파드, taint↔toleration/nodeSelector, Argo/트리거 컨트롤러. Spring `SPRING_PROFILES_ACTIVE=prod`.
- **커플링 주의**: TF의 노드그룹 `taint`/label ↔ YAML의 `toleration`/`nodeSelector`가 반드시 일치해야 한다(`workload=user|quiz|batch`). 한쪽만 바꾸면 파드가 스케줄되지 않는다.

## 미결정 / TODO

- [ ] batch 인스턴스 타입: CPU 바운드(`c*`) vs 균형(`m*`) vs 혼합(Spot 재고·ICE 회피)
- [ ] 배치 완료 판정: 크롤 종료 마커 + 큐/폴더 소진 감지 → scale 0
- [ ] 트리거 오케스트레이션: 경량 컨트롤러 vs Argo Workflows/Events
- [ ] N 임계치: raw/·clean/ 동일값 vs 단계별 상이
- [ ] batch 노드 SG: 공용 노드 SG vs 전용 SG(전용이면 데이터 호스트 3306 인입에 batch SG 추가)
- [ ] 모든 `modules/*`는 아직 스캐폴딩(TODO) — 실제 리소스 미구현

## 참고

시각화: 대화로 생성한 아키텍처 다이어그램(Artifact)이 이 문서의 그림 버전이다.
