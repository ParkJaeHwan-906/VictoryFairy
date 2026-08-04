# VictoryFairy 인프라 아키텍처 (결정 기록)

> 이 문서는 **확정된 인프라 설계와 그 근거(왜 이렇게 정했나)**를 기록한다.
> - 강제 규약: `.claude/skills/terraform-infra/SKILL.md`
> - 코드: `VictoryFairy_Infra/modules/` · `environments/`
> - state 관리·이관 절차: `docs/STATE.md`
> - 이 문서: 결정 기록(ADR 성격). 코드를 읽어도 모르는 "선택과 트레이드오프"를 남긴다.

## 확정 아키텍처 한눈에

| 계층 | 구성 | 스케일 |
|------|------|--------|
| 네트워크 | VPC `10.0.0.0/16`, **2a 운영 / 2c 예비** | — |
| 앱 컴퓨트 | EKS 1.30, 노드그룹 **app**(user+quiz 공용) **· batch**(분리) | 둘 다 오토스케일 |
| 데이터 | **단일 고정 EC2**(비 EKS)에 MySQL + 서비스 Redis | 스케일 없음(수직만) |
| 정제 | **서버리스** — Lambda + S3 이벤트 + SQS + DynamoDB (§4). 이미지 배포는 **CI 소유** | 이벤트 구동 |
| batch 노드그룹 | Spot xlarge 0→N→0. **정제에는 미사용 — 문제 생성 단계용 보류** | 일시적 |
| 접근 | SSM Session Manager only (DB·EKS 노드 SSH 모두 터널 경유, 22/3306 인입 없음) | — |

---

## 1. 네트워크 — 왜 2 AZ인데 노드는 2a에만?

- 서브넷은 **2개 AZ(2a, 2c)**에 선언한다. 이유는 HA가 아니라 **EKS의 강제 요건**(컨트롤플레인은 최소 2개 AZ 서브넷 필요). 1개 AZ면 클러스터가 생성되지 않는다.
- **실제 노드·DB는 2a에 집중**하고 `2c`는 **예비 서브넷**(노드 미배치)으로 둔다.
  - 근거: DB(MySQL/Redis)가 2a 단일 AZ 고정이라, 2a 전체 장애 시 앱을 2c에 벌려도 어차피 서비스가 멈춘다. 앱만 멀티 AZ로 벌리면 **크로스 AZ 지연·전송요금만 늘고 HA는 DB에서 막힌다**(반쪽 HA).
  - 그래서 앱·DB를 같은 AZ(2a)에 모아 크로스 AZ 비용을 없앤다. NAT Gateway도 2a 단일.
- **진짜 AZ 장애 대비 HA는 현재 없음.** 필요해지는 시점 = DB를 이중화(2c에 MySQL standby / Redis 복제)하기로 결정할 때. 그때 2c를 켠다.
- 노드 레벨 이중화는 유지된다: 같은 AZ 안에서도 노드 2대 이상이면 인스턴스/랙 장애는 버틴다(실제 장애의 대부분).

## 2. 앱 컴퓨트 — 노드그룹은 app(공용) · batch 2개

두 개의 Spring 앱(user·quiz)을 **같은 노드그룹에 동거**시킨다.

- **app 노드그룹** — `label: workload=app`, taint 없음. user·quiz 둘 다 `nodeSelector`만으로 스케줄(toleration 불필요). 두 앱 모두 HPA(user min1/max2, quiz min1/max4)로 파드를 늘리고, 노드 자원이 차면 Cluster Autoscaler가 노드를 붙인다(min1/max4).
  - (설계 변경 2026-07: 이전엔 user·quiz를 taint로 분리한 전용 노드그룹 2개였으나, 트래픽 규모상 분리 이점보다 관리 오버헤드가 커 **공용 풀로 통합**. quiz 폭주가 user를 잠식하는 리스크는 HPA 상한 + CA 여유로 완화.)
- **batch 노드그룹** — §4 배치 전용. `capacity_type=SPOT`, `min 0 / max 6`, `taint: workload=batch`. 평소 0대(비용 $0), CronJob 시각에만 뜬다.
- **노드 SSH(신규)** — 노드그룹에 `remote_access`(EC2 키페어 `VictoryFairy`) + 노드 롤에 `AmazonSSMManagedInstanceCore`를 추가해 MySQL EC2와 동일한 **SSM 터널 경유 SSH**를 노드에도 열었다(§6 접근 패턴 재사용). 22는 인터넷에 열지 않고 소스를 클러스터 SG로 한정 — 실제 접속은 SG를 거치지 않는 SSM 터널(스크립트: `scripts/db-tunnel.sh`)뿐이라 SG가 닫혀 있어도 무관하다. `remote_access` 추가는 기존 노드그룹의 **교체(재생성)**를 유발한다(의도된 변경).

> **부하 분산 = 신규 EC2 노드.** "필요 시 신규 EC2"는 app 노드그룹에 Cluster Autoscaler가 노드를 붙이는 것을 뜻한다. 데이터 EC2를 늘리는 게 아니다.

## 3. 데이터 티어 — 단일 고정, 앱과 격리

- **단일 고정 EC2**(t3.small, 비 EKS). MySQL + Redis 컨테이너, 데이터는 EBS `gp3`(`prevent_destroy`).
- **격리 원칙**: 이 EC2엔 앱 워크로드를 올리지 않고 오토스케일도 없다(장애 blast radius 축소). 부족하면 스케일아웃이 아니라 **인스턴스 승급(수직)**.
- **서비스 Redis(6379)는 브로커 전용**: 채팅·퀴즈 pub/sub 팬아웃, 이메일 인증 TTL 키. quiz가 다중 파드로 스케일아웃돼도 pub/sub이 파드 간 SSE 이벤트를 팬아웃한다.
  - ⚠ t3.small(2GB)은 MySQL+Redis에 빠듯하다. `innodb_buffer_pool_size` + Redis `maxmemory`/`maxmemory-policy`로 상한을 나눠 잡고 스왑 대비.
- **백업**(RDS 자동백업 대체): MySQL `mysqldump` cron → S3, 병행 EBS DLM 스냅샷. 이 백업 없이는 인스턴스/AZ 장애 = 데이터 유실.
- **접근**: SSM 포트포워딩만. SG 인입은 `3306 ← user·quiz·batch`, `6379 ← user·quiz`.
  - ⚠️ **정제 파이프라인(§4)은 MySQL을 쓰지 않는다** — 산출물이 S3에서 끝난다. `3306 ← batch`는
    **문제 생성 단계가 생길 때** 필요해지는 것이고, 지금 배치가 실제로 쓰는 것은 **S3 + Bedrock**뿐이다.
    IRSA 권한을 최소로 잡을 때 이 구분을 지킬 것.

### 3.1 개발용 DB — 운영 DB의 매일 restore 복제본 (`dev-db` 모듈)

위 운영 EC2와 별개로 **개발자용 DB EC2가 하나 더 있다.** 데이터 원본이 아니라 운영 백업의 리프레시 복제본이라 위 §3에서 빠져 있었다.

- **목적**: 개발자가 **로컬에서 직접** MySQL/Redis 에 붙어 쓰기 위한 비프로덕션 DB. 그래서 운영 DB(SSM 터널만)와 달리 **퍼블릭 서브넷(2a) + 퍼블릭 IP** 로 띄운다.
- **접근**: SSM 이 아니라 **직접 접속.** SG 인입은 `22/3306/6379 ← allowed_cidrs` 하나에서만(개발자 IP `/32`). `0.0.0.0/0` 은 변수 검증으로 금지. EKS 노드 SG 를 소스로 쓰지 않는다(운영 DB 와 다른 점).
- **데이터 갱신**: 운영 `mysql-ec2` 의 mysqldump S3 백업을 **매일 restore** 로 받아 프로덕션과 같은 상태로 리프레시. 백업 방향이 반대다 — 운영은 S3 로 **내보내고**, dev-db 는 S3 에서 **받아온다**(읽기 전용). root 비번도 같은 SSM 파라미터(`/victoryfairy/mysql/root-password`)를 공유해 복원 후에도 일관.
- **조건부 생성**: `dev_db_allowed_cidrs` 가 비면 `count=0` → 아예 안 만들어진다(plan 에도 안 뜬다). tfvars 에 자기 IP `/32` 를 넣은 개발자에게만 뜬다. `dev_db_use_eip=true` 면 stop/start 후에도 퍼블릭 IP 가 고정(EIP).
- ⚠️ **퍼블릭 노출은 의도된 트레이드오프다.** 운영 DB 는 절대 퍼블릭이 아니며, 이 노출은 운영 데이터가 아닌 '매일 덮어써지는 복제본'에 한정된다. 그래도 인입은 반드시 단일 `/32` 로 좁게 유지할 것.

## 4. 정제 파이프라인 — 크롤 → 패턴 검열 → LLM 검열 (서버리스)

> **개정 이력 — 이 절은 2026-07-26 하루에 두 번 바뀌었다. 둘 다 근거를 남긴다.**
>
> 1차: `raw/`→`clean/`→`done/` 파일 이동 + `INCR` 카운터 → **작업 집합(Set) + 마커**.
>    카운터는 완료분이 줄지 않아 **종료 판정이 불가능**했다(미결정 "배치 완료 판정"이 안 풀린 원인).
> 2차(현행): **EKS 배치 Job + 컨트롤러 → Lambda 서버리스.**
>    계기는 실측이었다 — 크롤러가 이미 **Lambda(`kbo-collector`, EventBridge `rate(10 minutes)`)** 로
>    돌고 있었는데, 문서와 AI 요구사항은 "02:00에 크롤 Job을 띄운다"를 전제하고 있었다.
>    **Lambda 는 VPC 밖이라 `batch-redis`(ClusterIP)에 도달할 수 없다** → 크롤러가
>    `SADD pending:pattern` 을 못 하므로 `SCARD` 트리거가 영원히 발동하지 않는다.
>    크롤러를 EKS로 옮기는 안도 검토했으나(크롤러 코드 수정 + EventBridge 해제 필요),
>    **Lambda 로 통일하면 우리가 짜야 할 컨트롤러 12개 조항이 통째로 사라진다**는 점이 결정적이었다.

계약 정본은 `VictoryFairy_AI/docs/requirements/pipeline/two-stage-batch.md`(현재 PR #49).
2차 개정으로 그 문서의 **약 27%(23/86조항)** 가 폐기·개정 대상이 됐다 — 컨트롤러 12건,
Redis 작업 집합 6건, 비용 카운터 2건, Spot 전제 3건. **판정 규칙·S3 키 규약·마커 멱등·
예산 금액 계약(73%)은 실행 환경과 무관해 그대로 살아남는다.**

### 구성

```
kbo-collector (Lambda)  ──▶ S3 community/{source}/{date}/{postId}.json
   EventBridge 상시              │  S3 이벤트 알림 (ObjectCreated)
                                 ▼
                     pattern (Lambda)   게시글 1건 · 사전/정규식 · LLM 없음
                                 │  통과분만 메시지 발행
                                 ▼
                             SQS 큐 (+ DLQ)
                                 │  이벤트 소스 매핑 batch_size = 7
                                 ▼
                     bedrock (Lambda)   7건 묶어 1회 호출 · 예약 동시성 1
                                 │
                                 ▼
                        S3 validation/bedrock/{success,failed}/…
```

- **트리거가 인프라 부품이 됐다.** S3 이벤트 알림이 1단계를, SQS 이벤트 소스 매핑이 2단계를
  발화한다. 폴링 컨트롤러·`batch-redis`·RBAC·Spot 회수 대응이 **전부 불필요해졌다.**
- **SQS 는 선택이 아니라 필수다.** S3 이벤트를 Bedrock Lambda 에 직결해 게시글 1건씩 부르면
  시스템 프롬프트 2,470토큰이 호출마다 붙어 **하루 $44.8 로 상한 $30 을 넘긴다.**
  5건 묶으면 $8.97 이다. `batch_size` 가 비용을 좌우한다.
- **`batch_size` 는 7 이다 (2026-07-29 실측으로 확정).** 예약 동시성이 1이라 **처리량을 늘리는
  합법적 레버는 이 값뿐**이다(동시성을 올리면 예산 상한의 두 번째 겹이 무너진다). 비용은
  사실상 전부 시스템 프롬프트라 대략 `$44.8/N` 으로 움직인다 — 7 이면 하루 $6.4 다.
  - ⚠ **진짜 상한은 비용이 아니라 출력 토큰이다.** `lambda_bedrock.handler` 는 배치를
    쪼개지 않고 **전건을 한 번의 `judge_batch()` 로 부르며**, 게시글 1건은 `1 + 댓글 수`
    개의 판정 단위로 펼쳐진다. 그 판정 전부가 `BEDROCK_MAX_TOKENS = 4096`(Lambda 환경변수로
    주입 — AI 저장소 `bedrock/core/config.py` 의 기본값 2048 을 덮는다)을 나눠 쓴다.
  - **초과했을 때가 진짜 위험이다.** 응답이 잘리면 "항목 수 불일치"가 되고, 2회 재시도 후
    **배치 전건이 폴백 통과 처리된다**(BRK-LLM-15). 실패로 떨어져 DLQ 로 가는 게 아니라
    **미검열 콘텐츠가 조용히 통과한다** — 검열 파이프라인이 뚫리는 경로다.
    호출 비용은 재시도까지 3번 다 나간다.
  - **이 경로는 가설이 아니라 이미 터졌던 사고다.** CloudWatch 로그에 폴백 통과가 7일간
    21건(하루 약 1,280 호출의 0.43%) 쌓여 있었고, 그 로그가 두 가지를 확정해 줬다 —
    ① 판정 단위당 약 24토큰이라 2048 은 약 85단위, 4096 은 약 170단위가 수용량이다.
    ② **게시글당 단위 수 추정이 3배 틀렸다.** "댓글 5개 = 6단위" 로 잡았는데 실패 배치는
    게시글당 17~20단위였다 — 인기글이 크롤러 댓글 상한을 그대로 채운다.
  - 그래서 값을 **최악 케이스 보장** 기준으로 잡았다. 댓글은 `COLLECTOR_TOP_COMMENTS=20`
    에서 잘리므로 게시글당 최대 21단위이고, `N × 21 ≤ 수용량` 이면 전건이 인기글이어도
    안전하다. **N=7 → 147단위 ≈ 3,530토큰 < 4096.** 이론상 8까지 가능하지만(168단위
    ≈ 4,030토큰) 여유가 없어 7 에서 멈췄다.
  - **더 올리려면 먼저 실측할 것.** 지금 로그에는 실패 배치만 남아 표본이 편향돼 있다.
    `judge_batch()` 직후 `judgement.usage.output_tokens` 와 `len(items)` 를 찍어 성공
    배치의 분포를 확보한 뒤에 판단한다. 출력 토큰 상한 자체는 **4096 이 이 모델의 천장**
    이다(Converse 를 베타 헤더 없이 부르므로 8192 불가 — `modules/refine-pipeline` validation).
- **프롬프트 캐싱은 배선만 해두고 꺼놨다 (`BEDROCK_PROMPT_CACHE = "false"`).** 비용의
  거의 전부가 매 호출 재전송되는 시스템 프롬프트 2,470토큰이라 캐싱이 `batch_size` 보다
  큰 레버인데, **쓸 수 있는 모델이 서울에 없다.**
  - 앱 쪽 배선은 이미 있다 — `bedrock/core/prompt.py` 의 `build_system_blocks()` 가 시스템
    블록 **뒤에** `cachePoint` 를 붙여 프리픽스가 캐싱되게 하고, 판정 대상 텍스트는 그 뒤
    user 메시지로 간다. 플래그만 켜면 동작하는 상태다.
  - 켜면 **전 호출이 죽는다.** 현행 `anthropic.claude-3-5-sonnet-20240620-v1:0` 은 캐싱
    미지원이고, `cachePoint` 를 붙이면 `AccessDeniedException`("unsupported model or your
    request did not allow prompt caching")이 난다. 이건 fail-open 대상이 아니라
    `BedrockFatalError` 라 러너가 한 건도 처리하지 못한다.
  - **모델을 바꿔서 풀 수도 없다.** 서울 ON_DEMAND Anthropic 모델은 3.5 Sonnet 과
    3 Haiku 둘뿐이고 **둘 다 캐싱 미지원**이다(2026-07-31 `list-foundation-models` 확인).
    캐싱 되는 Claude 4 계열·Nova 는 전부 추론 프로파일 전용인데, `apac.*` 는 도쿄로
    라우팅돼 SCP `p-meobeew3` 가 거부하고 `global.*` 은 데이터 리전 제약으로 금지다
    (BRK-LLM-6b/6d). 서울만 도는 `apne2.*` 프로파일은 존재하지 않는다.
  - **참고: 시스템 프롬프트를 쓴다고 토큰이 줄지 않는다.** Converse 에서 `system` 이든
    `user` 든 입력 토큰 과금은 같다. `system` 의 값은 지시 우선순위와 "캐시 프리픽스를
    놓을 자리"뿐이라, 캐싱이 안 되면 비용상 이점이 0이다. 이 질문이 반복돼서 못박아 둔다.
  - → 서울에 캐싱 지원 ON_DEMAND 모델이 들어오거나 SCP 가 완화되면 그때 켠다.
    그 전까지 남은 레버는 `batch_size` 와 시스템 프롬프트 길이 줄이기 둘뿐이다.
- **예산 상한은 DynamoDB 원자적 카운터**로 옮긴다(구 Redis `INCRBYFLOAT`). 함께
  **Bedrock Lambda 의 예약 동시성을 1로 묶는다** — 여러 개가 동시에 뜨면 상한을 넘겨 놓고
  뒤늦게 안다. 카운터 접근 실패는 **하드 스톱**이다(카운터를 잃으면 상한이 조용히 사라진다).
- **멱등은 그대로 S3 `_manifest` 마커**다. 완결된 게시글은 마커를 보고 skip 한다.
  입력 prefix 는 읽기전용이며 원본을 이동하지 않는다. Lambda 재시도·SQS 재전달에도 안전하다.
- **`BATCH_DATE`** 는 이벤트에서 S3 키의 `{date}` 를 파싱해 얻는다. EKS 안에서 필요했던
  "컨트롤러가 주입" 배선이 사라진다.

### 이미지 배포 — CI 가 소유한다 (2026-07-29)

`pattern`·`bedrock` 두 함수는 **`victoryfairy-pipeline` 이미지 하나를 공유**하고
`image_config.command` 로 핸들러만 갈린다. 이 이미지는 `.github/workflows/deploy-ai.yml`
이 빌드·push 하고 `update-function-code` 로 반영한다(상세는 [DEPLOYMENT.md §6](DEPLOYMENT.md)).

- **왜 CI 로 옮겼나**: 컨테이너 Lambda 는 태그를 생성 시점에 **digest 로 고정**한다.
  ECR push 만으로는 절대 반영되지 않아, 종전에는 사람이 빌드·push 하고 `refine_image_tag`
  를 고쳐 `apply` 하는 3단계를 밟아야 했다.
- **대가**: `image_uri` 에 `ignore_changes` 를 걸어 **Terraform 이 이미지의 소유권을 포기**했다.
  `var.refine_image_tag` 는 이제 **최초 생성용 부트스트랩 값**일 뿐이며, 실제 배포된 코드는
  코드가 아니라 런타임(`aws lambda get-function`)에 물어봐야 안다. 인프라 코드만 읽고
  "지금 무슨 이미지가 도는지" 알 수 없게 된 것이 이 결정의 비용이다.
- **CI 역할 권한**: `modules/security` 가 대상 함수 ARN 에 한정해 `UpdateFunctionCode` +
  `GetFunction*` 을 부여한다(목록이 비면 statement 자체를 만들지 않는다).
- **py-collector 도 같은 경로로 들어왔다**(2026-08-05). `kbo-collector` 이미지는
  `.github/workflows/deploy-collector.yml` 이 굽고 두 수집 함수를 갱신한다. 다만 **정제와
  방식이 하나 다르다** — 그쪽 스택은 `image_uri` 를 `latest` 다이제스트에 핀하므로
  `ignore_changes` 로 소유권을 넘기는 대신 **CI 가 `:latest` 를 함께 갱신해 핀을 맞춘다.**
  함수 설정(VPC/SG·스케줄)은 여전히 그쪽 `apply` 소관이다.

### 한계 — 알고 들어간다

- **Lambda 15분 상한**: 이벤트당 처리량이 작아 정상 경로는 문제없다. 다만 **백필(누적분 순회)은
  대량 반복이라 이 모델에 맞지 않는다** — Step Functions + Map 이나 별도 처리가 필요하다. 미결정.
- **콜드 스타트**: 정제 이미지가 274MB 컨테이너다. 야간 일괄이라 지연 자체는 문제가 아니다.
- **`kbo-collector` 는 이 레포의 Terraform 밖**이다 — 없는 게 아니라 **`dev_ai` 트리의 자체
  스택**(`py-collector/deploy/lambda/terraform`)이 함수·EventBridge 규칙·ECR 을 소유한다.
  정제 Lambda 는 이 레포가 관리하므로 **한 파이프라인이 두 state 에 걸친다.** 이미지 배포만
  2026-08-05 에 CI 로 통일됐고(§ 위), 스택 흡수 여부는 미결정.
- **`batch` 노드그룹(Spot, min 0)은 정제에 쓰이지 않게 됐다.** 제거하지 않고 **문제 생성 단계용으로
  보류**한다 — 그 단계는 Claude API + **MySQL(VPC 안)** 저장이라 Lambda 를 VPC 에 붙여야 하고,
  NAT 경유·ENI 콜드스타트가 붙는다. EKS 가 유리할 수 있다. `k8s/40~42-*.yaml` 도 같은 이유로 남긴다.

## 5. Terraform / Kubernetes 경계

- **Terraform(`.tf`, 이 레포)**: VPC·서브넷·NAT, EKS 클러스터, 노드그룹 2개(app/batch), MySQL EC2·EBS·SG·IAM, S3, **ECR 리포지토리**, 그리고 **정제 파이프라인 일체**(Lambda 2개·SQS+DLQ·DynamoDB·S3 이벤트 알림·IAM 실행 롤). **클러스터와 노드그룹까지 + 서버리스 정제 전부.**
  - ⚠️ **정제는 EKS 를 쓰지 않으므로 IRSA 가 아니라 Lambda 실행 롤**이다(§4). IRSA 는 문제 생성 단계가 EKS 로 갈 때 다시 본다.
  - ⚠️ **`kbo-collector` Lambda 와 EventBridge 규칙은 `dev_ai` 트리의 자체 Terraform 스택 소유**다(이 레포 밖·state 분리). 한 파이프라인이 두 state 에 걸친다 — 흡수 여부는 미결정.
- **Kubernetes(YAML/Helm, `VictoryFairy_Infra/k8s/`)**: Deployment(user/quiz), HPA(user/quiz), taint↔toleration/nodeSelector, Kubernetes Dashboard(학습용). Spring `SPRING_PROFILES_ACTIVE=prod`. (앱 코드는 별도 레포/브랜치지만, 배포 매니페스트는 결합도가 큰 Terraform과 **같은 인프라 레포에 co-locate** — 도구/레이어 경계는 유지)
  - `40~42-batch-*.yaml` 은 **정제에 쓰이지 않는다.** 문제 생성 단계용으로 보류된 뼈대다(§4 한계).
- **애플리케이션 코드(`VictoryFairy_AI/`, `dev_ai` 브랜치)**: 판정 로직과 **Lambda 핸들러**. 인프라는 그것을 **띄우는 함수 정의·트리거 배선·권한**을 맡는다. 트리거 판단 로직(구 컨트롤러)은 **S3 이벤트와 SQS 가 대신하므로 앱에서 사라진다.**
- **커플링 주의**: TF의 노드그룹 `taint`/label ↔ YAML의 `toleration`/`nodeSelector`가 반드시 일치해야 한다(`workload=app|batch`, app은 taint 없음). 한쪽만 바꾸면 파드가 스케줄되지 않는다.

## 미결정 / TODO

### 닫힌 항목 (2026-07-26)

- [x] **트리거 오케스트레이션** → **서버리스 이벤트 배선**(§4). 구 "경량 컨트롤러 vs Argo" 는 둘 다
      기각됐다. S3 이벤트 알림이 패턴 단계를, SQS 이벤트 소스 매핑이 Bedrock 단계를 발화한다.
      **직접 짜야 할 폴링 컨트롤러가 없어졌다** — 이게 Lambda 로 간 가장 큰 이유다.
- [x] **배치 완료 판정** → **판정 자체가 불필요해졌다.** 이벤트 구동이라 "파이프라인 종료" 라는
      순간이 없다. 큐가 비면 Lambda 가 안 뜰 뿐이고, 내릴 노드도 없다.
- [x] **N 임계치** → **SQS `batch_size` 로 대체**. 1000건 게이트는 "호출을 모아 시스템 프롬프트
      비용을 아끼려던" 장치였고, SQS 가 그 일을 한다. 값은 **7(게시글)** 이다 — 1건씩 부르면
      하루 $44.8 로 상한 $30 을 넘긴다.
- [x] **batch 노드 SG** → **정제에 한해 무의미해졌다.** Lambda 는 VPC 밖이고 MySQL 도 안 쓴다.
      문제 생성 단계가 EKS 로 갈 때 다시 연다.

### 열린 항목

- [ ] **백필을 어떻게 돌릴 것인가**: 누적분 순회는 대량 반복이라 Lambda 15분 상한에 맞지 않는다.
      Step Functions + Map / EKS Job / 로컬 실행 중 택일. **정제 본류와 분리해 판단할 것.**
- [ ] **`kbo-collector` 스택을 이 레포로 흡수할 것인가**: 크롤(`dev_ai` 자체 스택)과 정제(이 레포)가
      한 파이프라인에 걸쳐 있다. 흡수하면 일관되지만 기존 배포 절차를 바꿔야 한다.
      ⚠️ **역추적 문제는 2026-08-05 에 절반 닫혔다** — `deploy-collector.yml` 이 커밋 SHA 태그를
      함께 push 하므로 이제 도는 이미지가 어느 커밋인지 알 수 있다. 다만 그 리포지토리는
      여전히 `modules/ecr` 를 타지 않아 `IMMUTABLE`·`scan_on_push` 가 없고, `:latest` 핀
      구조상 **`MUTABLE` 이어야만 한다**(DEPLOYMENT.md §6-1) — 한 파이프라인 안에서 규약이 갈린다.
- [ ] **크롤 주기를 유지할 것인가**: 현재 `rate(10 minutes)` 상시 수집이다. 이벤트 구동이라
      야간 일괄로 바꿀 이유가 사라졌지만, **정제 비용이 하루 종일 발생**하게 된다(총액은 같다).
      예산 상한을 일 단위로 보는 지금 설계와 어긋나는지 확인 필요.
- [ ] **batch 노드그룹 존치 여부**: 정제에 쓰이지 않는다. `min 0` 이라 비용은 없지만,
      문제 생성 단계가 Lambda 로도 가능하다면 노드그룹·`k8s/40~42` 를 정리할 수 있다.
- [ ] **batch 인스턴스 타입**: 현재 `m5.xlarge`(배포됨). 위 항목이 정해진 뒤에 볼 것.

## 참고

시각화: 대화로 생성한 아키텍처 다이어그램(Artifact)이 이 문서의 그림 버전이다.
