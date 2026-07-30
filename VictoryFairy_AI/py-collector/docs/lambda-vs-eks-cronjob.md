# DB 적재 잡 실행 기반: Lambda vs EKS CronJob (결정 기록)

> 2026-07 결정: **당분간 Lambda(`kbo-collector-db`), EKS 배치 체계가 완성되면 재평가.**
> 대상 잡: records(완료 경기 → games/game_lineups, 03:30 KST) · registrations(1군
> 등록명단 → players, 11:00 KST). 둘 다 운영 MySQL(데이터 EC2, VPC 안)에 쓴다.

## 전제 (두 안이 공유하는 것)

- **코드는 동일** — 같은 컨테이너 이미지(ECR `kbo-collector`)로 Lambda 핸들러 대신
  `python -m kbo_collector.run records` 를 실행하면 CronJob 이 된다. 전환에 코드 변경 0.
- **네트워크도 동일 패턴** — 둘 다 VPC 안에서 참조 SG 방식으로 MySQL 3306 접근.
  EKS 노드 SG는 이미 mysql SG 인바운드에 있어서(k8s 앱들이 쓰는 경로) CronJob 이 되면
  SG 작업조차 필요 없다.
- 적재는 자연키 upsert 라 멱등 — 어느 쪽이든 재시도·중복 실행이 무해하다.

## 비교

| | **Lambda (현행)** | **EKS CronJob (batch 노드그룹)** |
|---|---|---|
| 비용 | 호출당 과금. arm64 512MB × 하루 몇 분 ≈ **월 수십 원** (프리티어면 0) | Spot m5.xlarge 0→1→0. 잡당 노드 기동 포함 십수 분 청구 ≈ 하루 2회면 **월 1~2천 원대**. 상시 노드에 얹으면 한계비용 0이지만 app 노드 리소스를 잠식 |
| 기동 속도 | 콜드스타트 수 초 | Cluster Autoscaler 스케일업 + 노드 부팅 + 이미지 풀 = **분 단위** (배치 잡이라 지연 자체는 무해) |
| 시간 제한 | **900초 하드리밋** (현재 840s 설정, 시즌 백필은 보름 단위 분할 호출로 우회 중) | 없음 — 장시간 백필을 한 방에 돌릴 수 있음 |
| 운영 복잡도 | EventBridge + 테라폼 스택 하나. **이미 CI 완비** (dev_infra 머지 = apply) | CronJob 매니페스트 + DB 자격증명 k8s Secret + Autoscaler 신뢰 + 배치 오케스트레이션(경량 컨트롤러 vs Argo, **미정**) |
| 스케줄 관리 | EventBridge cron (테라폼) | k8s CronJob spec (매니페스트) — 잡이 많아지면 이쪽이 한눈에 들어옴 |
| 관측 | CloudWatch Logs | pod 로그 + k8s 이벤트 (기존 EKS 관측 체계에 합류) |
| 현재 준비도 | **운영 중** (474경기 백필 + 일일 스케줄 검증 완료) | `k8s/41-batch-cronjob.yaml` 이 부트스트랩 뼈대(오케스트레이션 미결정·RBAC TODO — 매니페스트 주석에 명시). EKS 는 1.35 업그레이드 완료(2026-07-30 라이브 확인) |

## 왜 지금은 Lambda 인가

1. **배치 인프라가 미완성** — batch 노드그룹(Spot, min 0)은 준비됐지만 CronJob 매니페스트는
   스켈레톤이고 오케스트레이션 방식이 미정. 완성될 것을 기다릴 이유가 없었다.
2. **비용·규모가 Lambda 의 스위트스팟** — 하루 2회, 회당 몇 분, 메모리 512MB. 노드를
   깨울 만큼의 일이 아니다.
3. **CI 까지 이미 갖춰짐** — 크롤러는 dev_ai 머지, 인프라는 dev_infra 머지로 자동 배포.
   전환하면 이 체계(OIDC 롤, EventBridge, 스택)를 다시 만드는 비용이 발생한다.

## 나중에 CronJob 으로 옮긴다면 (전환 절차)

1. `k8s/` 에 CronJob 매니페스트 추가 — image 는 기존 ECR `kbo-collector`,
   command 만 `python -m kbo_collector.run records` / `... registrations` (스케줄은 UTC 로 환산).
2. DB 자격증명을 k8s Secret 으로 (victoryfairy-batch 네임스페이스에 mysql Endpoints 이미 존재).
3. collector-lambda 스택에서 DB 잡만 내리기 — `db_subnet_ids = []` 로 비우면
   kbo-collector-db 함수·스케줄·SG 규칙이 통째로 제거된다(S3 잡 함수는 무관).
4. 이미지 CI(collector-image.yml)는 그대로 — Lambda 갱신 스텝만 빼거나 놔둬도 무해.

## 재평가 트리거 (이 중 하나면 다시 논의)

- 잡 런타임이 15분 제한에 근접 (예: 시즌 통짜 백필을 일상적으로 돌리게 될 때)
- 배치 오케스트레이션(경량 컨트롤러 vs Argo)이 확정돼 CronJob 이 팀 표준이 될 때
  (EKS 1.35 업그레이드는 완료됐으므로 남은 선행조건은 이것뿐)
- DB 적재 잡 종류가 늘어나 스케줄·의존성 관리가 EventBridge 로 산만해질 때
