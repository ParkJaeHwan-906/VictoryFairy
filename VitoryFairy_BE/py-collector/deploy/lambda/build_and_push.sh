#!/usr/bin/env bash
# Build the Lambda container image and push it to ECR.
# Called by Terraform (null_resource) with ECR_URL/REGION, or run manually:
#   ECR_URL=<acct>.dkr.ecr.<region>.amazonaws.com/kbo-collector REGION=ap-northeast-2 \
#     ./deploy/lambda/build_and_push.sh
set -euo pipefail

: "${ECR_URL:?set ECR_URL=<account>.dkr.ecr.<region>.amazonaws.com/<repo>}"
: "${REGION:?set REGION}"
TAG="${TAG:-latest}"
PLATFORM="${PLATFORM:-linux/amd64}"   # Lambda x86_64; use linux/arm64 for arm64 funcs

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"   # -> py-collector/
cd "$ROOT"

REGISTRY="${ECR_URL%%/*}"
aws ecr get-login-password --region "$REGION" | docker login --username AWS --password-stdin "$REGISTRY"

# --provenance/--sbom=false: BuildKit otherwise pushes an OCI image index carrying an
# attestation manifest, which Lambda rejects ("image manifest ... not supported"). It
# only accepts a single-platform Docker v2 manifest.
docker build --platform "$PLATFORM" --provenance=false --sbom=false \
  -f deploy/lambda/Dockerfile -t "${ECR_URL}:${TAG}" .
docker push "${ECR_URL}:${TAG}"
echo "pushed ${ECR_URL}:${TAG}"
