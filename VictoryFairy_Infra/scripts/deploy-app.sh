#!/usr/bin/env bash
# deploy-app.sh — BE 앱(user/quiz)을 빌드 → ECR 푸시 → EKS 배포한다.
#
# 사용법:
#   ./scripts/deploy-app.sh              # 태그 = 현재 커밋 SHA (권장)
#   ./scripts/deploy-app.sh v0.1.0       # 태그 직접 지정
#   MODULES="quiz" ./scripts/deploy-app.sh   # 특정 모듈만
#
# 전제:
#   - Docker Desktop 실행 중 (Apple Silicon 에서도 --platform linux/amd64 로 빌드)
#   - AWS 자격 증명 + kubectl 컨텍스트(victoryfairy-dev) 설정됨
#   - ECR 리포지토리는 terraform ecr 모듈이 생성(victoryfairy-user/quiz)
#
# ECR 태그는 IMMUTABLE 이므로 같은 태그 재푸시는 거부된다. 이미 존재하는 태그면
# 빌드/푸시를 건너뛰고 배포만 수행한다(같은 커밋 재배포 시나리오).

set -euo pipefail

# Docker Desktop 자격 증명 헬퍼(docker-credential-desktop)가 비대화형 셸 PATH에
# 없는 경우가 있어 보정 (없으면 docker login 이 실패한다).
export PATH="$PATH:/Applications/Docker.app/Contents/Resources/bin:$HOME/.docker/bin"

REGION="ap-northeast-2"
NS="victoryfairy"
MODULES="${MODULES:-user quiz}"

ROOT="$(cd "$(dirname "$0")/../.." && pwd)" # 모노레포 루트
INFRA="$ROOT/VictoryFairy_Infra"

# BE 디렉토리 자동 탐색 — 브랜치에 따라 표기가 다르다(VictoryFairy_BE / VitoryFairy_BE)
BE_DIR=""
for d in VictoryFairy_BE VitoryFairy_BE; do
  if [[ -f "$ROOT/$d/Dockerfile" ]]; then BE_DIR="$ROOT/$d"; break; fi
done
[[ -n "$BE_DIR" ]] || { echo "❌ BE 디렉토리(Dockerfile)를 찾지 못했습니다"; exit 1; }

TAG="${1:-$(git -C "$ROOT" rev-parse --short HEAD)}"
ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
REGISTRY="$ACCOUNT.dkr.ecr.$REGION.amazonaws.com"

echo "▶ BE=$BE_DIR  TAG=$TAG  REGISTRY=$REGISTRY"

# ECR 로그인
aws ecr get-login-password --region "$REGION" \
  | docker login --username AWS --password-stdin "$REGISTRY" >/dev/null
echo "✅ ECR 로그인"

for m in $MODULES; do
  REPO="victoryfairy-$m"
  IMG="$REGISTRY/$REPO:$TAG"

  # IMMUTABLE 태그: 이미 있으면 빌드/푸시 생략
  if aws ecr describe-images --region "$REGION" --repository-name "$REPO" \
       --image-ids imageTag="$TAG" >/dev/null 2>&1; then
    echo "⏭  [$m] 태그 $TAG 이미 존재 — 빌드/푸시 생략"
  else
    echo "🔨 [$m] 빌드 (linux/amd64)"
    docker build --platform linux/amd64 \
      --build-arg MODULE="$m" \
      -t "$IMG" "$BE_DIR"
    echo "⬆️  [$m] 푸시 $IMG"
    docker push "$IMG"
  fi

  # k8s 매니페스트의 image 만 실제 값으로 치환해 apply (원본 파일은 placeholder 유지)
  case "$m" in
    user) MANIFEST="$INFRA/k8s/20-user-app.yaml" ;;
    quiz) MANIFEST="$INFRA/k8s/21-quiz-app.yaml" ;;
    *)    echo "⚠️  [$m] 매니페스트 매핑 없음 — 배포 생략"; continue ;;
  esac
  sed "s|image: \".*/victoryfairy-$m:.*\"|image: \"$IMG\"|" "$MANIFEST" \
    | kubectl apply -f -
  echo "🚀 [$m] 배포 적용"
done

echo ""
echo "=== 롤아웃 상태 ==="
for m in $MODULES; do
  kubectl -n "$NS" rollout status "deploy/${m}-app" --timeout=300s || {
    echo "⚠️  ${m}-app 롤아웃 지연 — 파드 상태:"
    kubectl -n "$NS" get pods -l "app=${m}-app"
  }
done
