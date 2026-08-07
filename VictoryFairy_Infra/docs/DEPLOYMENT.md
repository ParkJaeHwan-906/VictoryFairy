# 배포 가이드 (EKS)

> 자주 쓰는 명령어만 빠르게 보려면 → [COMMANDS.md](COMMANDS.md)

## 1. 배포 아키텍처 개요

```
[코드 수정] → [Docker 이미지 빌드] → [ECR push (커밋 SHA 태그)] → [EKS 블루-그린 롤아웃]
```

| 구성요소 | 값 |
|---|---|
| 이미지 저장소 | ECR `victoryfairy-user`, `victoryfairy-quiz` (태그 불변, push 시 스캔, 최근 10개 보관) |
| 이미지 태그 | **커밋 SHA 7자리** (예: `0f140bb`) — `latest` 금지 |
| 클러스터/네임스페이스 | `victoryfairy-dev` / `victoryfairy` |
| 배포 단위 | Deployment `user-app`(8080), `quiz-app`(8081) + HPA(user 1~2, quiz 1~4) |

> 이 문서의 §1~§5는 **BE(EKS) 배포**를 다룹니다. AI 정제 파이프라인은 EKS가 아니라
> Lambda 컨테이너로 돌고 배포 경로가 완전히 다릅니다 → [§6](#6-ai-정제-파이프라인-이미지-배포-lambda)

## 2. 배포 방법 2가지

### A. 자동 배포 — GitHub Actions (기본)

**`main` 브랜치에 push(=PR 머지)되면 자동 실행** — `.github/workflows/deploy-eks.yml`

1. 변경 경로를 감지해 배포 대상 모듈 결정
   - `user/**` 변경 → user만, `quiz/**` → quiz만
   - `common/`·`domain/`·Gradle 루트 파일 변경 → **둘 다** 재배포
2. Docker 빌드 → ECR push (커밋 SHA 태그)
3. `kubectl set image` → 블루-그린 롤아웃 → 실패 시 **자동 롤백**

수동 트리거: GitHub → Actions → "Deploy to EKS" → Run workflow (모듈 선택 가능)

인증은 GitHub OIDC → IAM 역할(`victoryfairy-dev-github-actions`)로 **시크릿 저장 없이** 동작합니다.
역할 권한은 ECR push + victoryfairy 네임스페이스 Edit로 제한 (terraform `modules/security`).

### B. 수동 배포 — 로컬 스크립트

Docker Desktop 실행 후:

```bash
cd VictoryFairy_Infra
./scripts/deploy-app.sh              # 현재 커밋 SHA 태그로 user·quiz 배포
./scripts/deploy-app.sh v0.1.0       # 태그 직접 지정
MODULES="quiz" ./scripts/deploy-app.sh   # 특정 모듈만
```

같은 태그가 ECR에 이미 있으면 빌드를 건너뛰고 배포만 수행합니다(재배포/롤백 시나리오).

## 3. 블루-그린 배포 동작

Deployment 전략 (`k8s/20-user-app.yaml`, `21-quiz-app.yaml`):

```yaml
strategy:
  type: RollingUpdate
  rollingUpdate:
    maxSurge: 100%        # 신버전 파드 '전체'를 구버전과 동시에 띄움
    maxUnavailable: 0     # 신버전 Ready 전까지 구버전을 하나도 내리지 않음
revisionHistoryLimit: 10  # 롤백용 이전 버전(ReplicaSet) 10개 보관
```

배포 시 실제 흐름:

```
1) 구버전(Blue) 서비스 중 ────────────── [v1 v1]
2) 신버전(Green) 전체 기동 ───────────── [v1 v1] + [v2 v2]   ← 동시 존재
3) v2 readiness 프로브 통과 → 트래픽 전환  [v2 v2] ← Service가 v2로
4) 구버전 자동 종료 ─────────────────── [v2 v2]
※ v2가 Ready에 실패하면? → 전환 없음. v1이 계속 서비스(자동 안전장치)
```

- 검증 게이트 = **readiness 프로브** (현재 TCP 8080/8081 — actuator 도입 시 HTTP health로 승격 권장)
- 신·구 동시 기동으로 순간 자원 사용량이 2배 → 부족하면 Cluster Autoscaler가 노드 자동 증설(max 4)
- CI 실패 시 워크플로가 `rollout undo` 자동 실행

## 4. 버전 기록과 롤백

### 버전 기록 확인

```bash
# 배포 이력 (리비전 번호)
kubectl -n victoryfairy rollout history deploy/user-app

# 각 리비전이 어떤 이미지(커밋)인지
kubectl -n victoryfairy rollout history deploy/user-app --revision=3

# ECR에 보관된 이미지 태그 목록 (최근 10개)
aws ecr describe-images --repository-name victoryfairy-user \
  --query "sort_by(imageDetails,&imagePushedAt)[].imageTags[0]" --output table
```

### 롤백 방법 (상황별)

```bash
# ① 직전 버전으로 즉시 복귀 (가장 빠름 — 이미지 pull 불필요)
kubectl -n victoryfairy rollout undo deploy/user-app

# ② 특정 리비전으로 복귀
kubectl -n victoryfairy rollout undo deploy/user-app --to-revision=2

# ③ 특정 커밋(태그)으로 복귀 — ECR 태그 기반, 빌드 생략됨
./scripts/deploy-app.sh <커밋SHA>
```

롤백도 동일한 블루-그린 전략으로 수행되므로 무중단입니다.

## 5. 배포 전 체크리스트

- [ ] 로컬에서 테스트 통과 (`./gradlew test`)
- [ ] DB 스키마 변경이 있다면: 마이그레이션이 **하위호환**인지 확인
      (블루-그린 특성상 신·구 버전이 같은 DB를 동시에 사용하는 구간이 존재)
- [ ] 새 환경변수를 추가했다면: `app-config`(ConfigMap)/`app-secret`(Secret)에 먼저 반영
- [ ] 배포 후: `kubectl -n victoryfairy get pods` 로 Running 확인, 로그에 에러 없는지 확인

## 6. AI 정제 파이프라인 이미지 배포 (Lambda)

BE(EKS)와는 **별개 경로**입니다 — `.github/workflows/deploy-ai.yml`.

```
[AI 코드 수정] → [arm64 빌드] → [ECR victoryfairy-pipeline push (커밋 SHA)]
  → [Lambda update-function-code ×2] → [실패 시 직전 이미지로 자동 롤백]
```

`main` push 시 아래 경로가 바뀌면 자동 실행됩니다.

| 감지 경로 | 이유 |
|---|---|
| `VictoryFairy_AI/pipeline/**` | 핸들러·러너 코드 |
| `VictoryFairy_AI/validation/**` | **검열 사전**(`core/data/*.json`)이 이미지에 함께 구워진다 |
| `VictoryFairy_AI/bedrock/**` | 전용 이미지가 없어 여기 함께 실린다 |

`refine-pattern`·`refine-bedrock` 두 함수가 **이미지 하나를 공유**하고
`image_config.command`로 핸들러만 갈리므로, 한 번 빌드해 두 함수를 함께 갱신합니다.

### 왜 push만으로는 반영되지 않나

컨테이너 Lambda는 이미지 태그를 **생성 시점에 digest로 고정**합니다. ECR에 새 이미지를
올려도 함수는 옛 digest를 계속 씁니다. 그래서 워크플로가 `update-function-code`를
명시적으로 호출합니다. Terraform은 `image_uri`에 `lifecycle { ignore_changes }`를
걸어 두었으므로(`modules/refine-pipeline`) 다음 `apply`가 배포를 되감지 않습니다.
→ **`var.refine_image_tag`는 최초 생성용 부트스트랩 값**이며, 이후 실제 배포된 코드는
`aws lambda get-function --function-name victoryfairy-dev-refine-pattern --query Code.ImageUri`
로 확인합니다.

### 빌드 시 반드시 지켜야 하는 두 가지

- **arm64 고정** — `pipeline/Dockerfile`이 `FROM --platform=linux/arm64`이고 Terraform도
  `architectures = ["arm64"]`입니다. 한쪽만 달라지면 함수가 뜨지 않습니다.
  워크플로는 `ubuntu-24.04-arm` 네이티브 러너를 씁니다(퍼블릭 레포라 무료).
- **`--provenance=false --sbom=false`** — 빠지면 매니페스트가 OCI image index가 되어
  Lambda가 거부합니다. 워크플로에 `imageManifestMediaType` 검증 단계를 두었습니다.

### 수동 배포

```bash
cd VictoryFairy_AI
TAG=$(git rev-parse --short=7 HEAD)
ECR=555209622409.dkr.ecr.ap-northeast-2.amazonaws.com/victoryfairy-pipeline
docker buildx build --platform linux/arm64 --provenance=false --sbom=false \
  -f pipeline/Dockerfile -t "$ECR:$TAG" --push .
for FN in victoryfairy-dev-refine-pattern victoryfairy-dev-refine-bedrock; do
  aws lambda update-function-code --function-name "$FN" --image-uri "$ECR:$TAG"
done
```

## 6-1. 수집기 이미지 배포 (`deploy-collector.yml`)

py-collector의 `kbo-collector` 이미지는 **별도 워크플로**가 맡습니다(다른 이미지, 다른 스택).
`kbo-collector`·`kbo-collector-db` 두 함수가 이 이미지 하나를 공유하고 payload의 `job`으로
갈리므로, 한 번 빌드해 둘을 함께 갱신합니다.

| 트리거 경로 | 왜 |
|---|---|
| `py-collector/kbo_collector/**` | 수집 코어 |
| `py-collector/config/**` | `targets.yaml`이 이미지에 구워진다 — 빠뜨리면 크롤 대상만 옛 버전 |
| `py-collector/deploy/lambda/{Dockerfile,handler.py,requirements.txt}` | 이미지 구성 |

`tests/`·`docs/`·`terraform/`은 이미지에 들어가지 않으므로 트리거에서 제외했습니다.

### `:latest`를 반드시 함께 push하는 이유

`refine-pipeline`과 달리 이 스택은 `image_uri`를
`data.aws_ecr_image(image_tag = "latest")`의 다이제스트에 **핀**합니다(`ignore_changes` 없음).
SHA 태그만 올리면 `latest`가 옛 이미지를 가리킨 채 남아 **다음 `terraform apply`가 함수를
되감습니다.** 그래서 워크플로는 `:latest`와 커밋 SHA를 **둘 다** push합니다(해당 ECR은
`MUTABLE`이라 재push 가능). 같은 이유로, 갱신 실패 시 함수뿐 아니라 **`:latest` 태그도**
직전 이미지로 되돌립니다.

> ⚠ 뒤집어 말하면 **`terraform apply`는 최신 코드 체크아웃에서만 돌려야 합니다.** 옛
> 체크아웃에서 apply하면 `null_resource`가 옛 소스를 빌드해 `:latest`에 덮어쓰고 함수가
> 뒤로 감깁니다. 이 스택은 여전히 로컬 Docker 빌드를 갖고 있습니다.

### 범위 밖 — 함수 설정

이미지만 `deploy-collector.yml`(main) 소관입니다. VPC/SG·환경변수·EventBridge 스케줄 등
**함수 설정은** `VictoryFairy_Infra/collector-lambda`의 `apply` 소관이고, 그 apply 는
`collector-terraform.yml`이 `dev_infra` 머지 때 자동으로 돌립니다.

## 7. 관련 리소스 위치

| 무엇 | 어디 |
|---|---|
| CI 워크플로 (BE·EKS) | `.github/workflows/deploy-eks.yml` (레포 루트) |
| CI 워크플로 (AI·Lambda) | `.github/workflows/deploy-ai.yml` (레포 루트) |
| CI 워크플로 (수집기·Lambda) | `.github/workflows/deploy-collector.yml` (레포 루트) |
| 수동 배포 스크립트 | `VictoryFairy_Infra/scripts/deploy-app.sh` |
| k8s 매니페스트 | `VictoryFairy_Infra/k8s/` |
| CI IAM 역할 (Terraform) | `VictoryFairy_Infra/modules/security/` |
| ECR (Terraform) | `VictoryFairy_Infra/modules/ecr/` |
| 정제 파이프라인 (Terraform) | `VictoryFairy_Infra/modules/refine-pipeline/` |
| 아키텍처 상세 | [ARCHITECTURE.md](ARCHITECTURE.md) |
