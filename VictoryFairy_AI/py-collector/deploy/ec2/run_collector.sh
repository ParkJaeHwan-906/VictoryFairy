#!/usr/bin/env bash
# py-collector 크론 래퍼: GHCR 이미지를 당겨 수집 잡을 1회 실행한다.
# 사용: run_collector.sh <job> [args...]   예) run_collector.sh records
# 환경: 같은 디렉터리의 collector.env (커밋 금지, collector.env.example 참고)
set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")" && pwd)"
ENV_FILE="$APP_DIR/collector.env"
IMAGE="${COLLECTOR_IMAGE:-ghcr.io/parkjaehwan-906/victoryfairy-collector:latest}"
LOG_DIR="$APP_DIR/logs"
mkdir -p "$LOG_DIR"
LOG="$LOG_DIR/$(date -u +%Y%m%d)-${1:-job}.log"

{
  echo "[$(date -u '+%F %T')Z] run $*"
  # pull 실패(네트워크 등)해도 로컬에 있는 이미지로 실행한다
  docker pull -q "$IMAGE" || echo "pull failed; using local image"
  # t3.small(2GB) 예산 보호: 수집 잡 상한 300m
  docker run --rm --memory 300m --env-file "$ENV_FILE" "$IMAGE" "$@"
  echo "[$(date -u '+%F %T')Z] done"
} >> "$LOG" 2>&1

# 30일 지난 로그 정리
find "$LOG_DIR" -name '*.log' -mtime +30 -delete 2>/dev/null || true
