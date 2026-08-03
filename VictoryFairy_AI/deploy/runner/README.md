# 퀴즈 러너 CronJob 배포 가이드

Bedrock 기반 일일 퀴즈 생성 파이프라인을 EKS 클러스터에서 CronJob으로 실행하는 절차. Task 8의 `victoryfairy-quiz-runner` 이미지를 배포하고 필요한 권한(IRSA)을 설정한 후 모니터링까지 다룬다.

**소유권**: 이 문서의 IAM 역할 생성(§2), kubectl apply 실행(§3)은 dev_infra 소유자가 수행한다. 이 리포는 파일과 절차만 제공한다.

## 1. ECR 리포 생성 · 이미지 푸시

### 1-1. ECR 리포 생성

```bash
# 리포가 없으면 생성 (이미 있는 경우 스킵)
aws ecr create-repository \
  --repository-name victoryfairy-quiz-runner \
  --region ap-northeast-2 \
  --image-tag-mutability IMMUTABLE \
  --image-scanning-configuration scanOnPush=true
```

### 1-2. 빌드 및 푸시

노드 아키텍처에 맞춘 `--platform` 플래그를 사용해 빌드한다. 클러스터 노드 아키텍처는 아래 명령으로 확인한다:

```bash
# 노드 아키텍처 확인 (예: linux/arm64 또는 linux/amd64)
kubectl get nodes -o jsonpath='{.items[0].status.nodeInfo.architecture}'
```

빌드 및 푸시 (리포 루트에서 실행):

```bash
# Task 8 빌드 명령 + --platform 플래그
# 모든 경로는 리포 루트(VictoryFairy_AI/) 기준
ARCH=$(kubectl get nodes -o jsonpath='{.items[0].status.nodeInfo.architecture}')
PLATFORM="linux/$ARCH"
ACCOUNT=555209622409
REGION=ap-northeast-2

docker build \
  --platform "$PLATFORM" \
  -f VictoryFairy_AI/runner/Dockerfile \
  -t victoryfairy-quiz-runner:latest \
  VictoryFairy_AI

# ECR 로그인
aws ecr get-login-password --region "$REGION" | \
  docker login --username AWS --password-stdin "$ACCOUNT.dkr.ecr.$REGION.amazonaws.com"

# 태그 및 푸시
docker tag victoryfairy-quiz-runner:latest \
  "$ACCOUNT.dkr.ecr.$REGION.amazonaws.com/victoryfairy-quiz-runner:latest"

docker push "$ACCOUNT.dkr.ecr.$REGION.amazonaws.com/victoryfairy-quiz-runner:latest"
```

## 2. ServiceAccount · IRSA 역할 생성

EKS 클러스터에서 Pod이 AWS 리소스(S3, Bedrock)에 접근하기 위해 IRSA(IAM Roles for Service Accounts)를 설정한다.

### 2-1. ServiceAccount 생성

```bash
kubectl create serviceaccount quiz-runner \
  --namespace victoryfairy
```

### 2-2. IRSA 역할 생성 (dev_infra)

기존 IRSA 패턴(kube-system 애드온들과 동일)을 따른다. 리포 루트에서 실행한다.

```bash
# 계정 및 클러스터 정보
ACCOUNT=555209622409
CLUSTER=victoryfairy-dev
REGION=ap-northeast-2

# OIDC ID 동적 조회 (2026-08-03 실측값: 3613BBAC68BE9C5508DB6133B2D1EC16)
OIDC_ISSUER=$(aws eks describe-cluster --name "$CLUSTER" --query "cluster.identity.oidc.issuer" --output text)
OIDC_ID=${OIDC_ISSUER##*/}

# 신뢰 대상 (ServiceAccount)
TRUST_ENTITY="system:serviceaccount:victoryfairy:quiz-runner"

# 신뢰 정책 JSON 생성
cat > /tmp/trust-policy.json <<EOF
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "Federated": "arn:aws:iam::$ACCOUNT:oidc-provider/oidc.eks.$REGION.amazonaws.com/id/$OIDC_ID"
      },
      "Action": "sts:AssumeRoleWithWebIdentity",
      "Condition": {
        "StringEquals": {
          "oidc.eks.$REGION.amazonaws.com/id/$OIDC_ID:sub": "$TRUST_ENTITY",
          "oidc.eks.$REGION.amazonaws.com/id/$OIDC_ID:aud": "sts.amazonaws.com"
        }
      }
    }
  ]
}
EOF

# IAM 역할 생성
aws iam create-role \
  --role-name victoryfairy-quiz-runner \
  --assume-role-policy-document file:///tmp/trust-policy.json \
  --region "$REGION"

# 정책 첨부 (${BUCKET}은 실제 버킷명으로 치환, 리포 루트에서 실행)
BUCKET=$(grep '^COLLECTOR_S3_BUCKET=' VictoryFairy_AI/py-collector/.env | cut -d= -f2)
sed "s/\${BUCKET}/$BUCKET/g" VictoryFairy_AI/deploy/runner/irsa-policy-runner.json > /tmp/irsa-policy-runner.json

aws iam put-role-policy \
  --role-name victoryfairy-quiz-runner \
  --policy-name victoryfairy-quiz-runner-policy \
  --policy-document file:///tmp/irsa-policy-runner.json
```

### 2-3. ServiceAccount에 IAM 역할 어노테이션 추가 (dev_infra)

```bash
kubectl annotate serviceaccount quiz-runner \
  --namespace victoryfairy \
  eks.amazonaws.com/role-arn=arn:aws:iam::555209622409:role/victoryfairy-quiz-runner \
  --overwrite
```

## 3. CronJob 배포 (dev_infra)

리포 루트에서 실행한다.

```bash
# CronJob 배포
kubectl apply -f VictoryFairy_AI/deploy/runner/cronjob-quiz.yaml

# 배포 확인
kubectl get cronjob -n victoryfairy quiz-runner
```

## 4. 1회 수동 실행 검증

배포 후 한 번 수동으로 실행해 정상 동작을 검증한다:

```bash
# 수동 Job 생성 (CronJob 기반)
kubectl create job --from=cronjob/quiz-runner quiz-runner-manual \
  -n victoryfairy

# Pod 상태 확인 (Running → Completed 대기)
kubectl get pods -n victoryfairy -l job-name=quiz-runner-manual -w

# 로그 확인
kubectl logs -n victoryfairy -l job-name=quiz-runner-manual -f

# Job 완료 후 상태 확인
kubectl describe job quiz-runner-manual -n victoryfairy
```

## 5. 모니터링

### 5-1. quiz-candidates 파티션 적재 확인

오늘자 `quiz-candidates/{date}/` 파티션에 파일이 적재되었는지 매일 확인한다. 파이프라인 목표는 일일 10문항이고, 며칠 연속 0건이면 조사가 필요하다. 리포 루트에서 실행한다.

```bash
# 버킷명 확인
BUCKET=$(grep '^COLLECTOR_S3_BUCKET=' VictoryFairy_AI/py-collector/.env | cut -d= -f2)

# 오늘자 파티션 건수 확인 (KST 기준)
TODAY=$(TZ=Asia/Seoul date +%Y-%m-%d)
aws s3 ls "s3://$BUCKET/quiz-candidates/$TODAY/" --recursive | wc -l
```

### 5-2. 러너 로그 확인

```bash
# 최근 CronJob 실행 조회 (라벨로 필터)
kubectl get pods -n victoryfairy -l app.kubernetes.io/name=quiz-runner --sort-by=.metadata.creationTimestamp | tail -5

# 특정 Pod 로그 확인
kubectl logs -n victoryfairy <pod-name>

# CloudWatch 로그 확인 (EKS logging 설정 시)
aws logs tail /aws/eks/victoryfairy-dev/cluster --follow
```

## 6. 운영 전제 조건

실제 운영에 올리기 전에 [`../routines/README.md`](../routines/README.md)의 **§5. 운영 전제 조건**에 기록된 갭들을 반드시 확인한다. 특히:

- `question-source/player_profile/` S3 초기화 필요 여부
- `question-source/game_result/` 일일 export 스케줄 상태
- `all-time-records.yaml` 데이터 정확성 검수

이들 갭이 해소되지 않은 상태로 CronJob을 운영 스케줄로 올리면 일부 데이터가 누락되거나 부정확할 수 있다.

---

**CronJob 스케줄**: 매일 08:50 KST (`50 23 * * *` UTC cron — UTC 전날 23:50)

**최대 실행 시간**: 30분 (activeDeadlineSeconds: 1800)

**실패 시 재시도**: 1회 (backoffLimit: 1)
