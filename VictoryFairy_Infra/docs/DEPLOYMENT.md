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

## 6. 관련 리소스 위치

| 무엇 | 어디 |
|---|---|
| CI 워크플로 | `.github/workflows/deploy-eks.yml` (레포 루트) |
| 수동 배포 스크립트 | `VictoryFairy_Infra/scripts/deploy-app.sh` |
| k8s 매니페스트 | `VictoryFairy_Infra/k8s/` |
| CI IAM 역할 (Terraform) | `VictoryFairy_Infra/modules/security/` |
| ECR (Terraform) | `VictoryFairy_Infra/modules/ecr/` |
| 아키텍처 상세 | [ARCHITECTURE.md](ARCHITECTURE.md) |
