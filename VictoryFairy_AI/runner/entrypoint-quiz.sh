#!/bin/bash
# VictoryFairy_AI/runner/entrypoint-quiz.sh
# 퀴즈 러너 — question-gen/ROUTINE.md의 컨테이너 구현체. 문서와 어긋나면 문서가 정답.
set -euo pipefail
: "${S3_BUCKET:?S3_BUCKET 환경변수를 설정하라}"
cd /app/VictoryFairy_AI
TODAY=$(TZ=Asia/Seoul date +%Y-%m-%d)
WORK=.work
mkdir -p "$WORK"/{game_result,game_schedule,player_profile,kbo-records,wiki,quiz-candidates,stats}

# ── 1. 동기화 (ROUTINE.md 1단계 그대로) ──
LATEST_GR=$(aws s3 ls "s3://$S3_BUCKET/question-source/game_result/" 2>/dev/null | awk '{print $2}' | tr -d '/' | sort | tail -1)
[ -n "$LATEST_GR" ] && aws s3 sync "s3://$S3_BUCKET/question-source/game_result/$LATEST_GR/" "$WORK/game_result/$LATEST_GR/" --exclude "*" --include "*.json" --only-show-errors
for i in 0 1 2 3 4 5 6; do
  D=$(date -d "$TODAY -$i days" +%Y-%m-%d)
  aws s3 sync "s3://$S3_BUCKET/quiz-candidates/$D/" "$WORK/quiz-candidates/$D/" --exclude "*" --include "*.json" --only-show-errors 2>/dev/null || true
done
aws s3 sync "s3://$S3_BUCKET/question-source/game_schedule/$TODAY/" "$WORK/game_schedule/$TODAY/" --exclude "*" --include "*.json" --only-show-errors 2>/dev/null \
  || echo "경고: 오늘($TODAY) game_schedule 없음 — 예측 템플릿 제외" >&2
aws s3 sync "s3://$S3_BUCKET/wiki/" "$WORK/wiki/" --only-show-errors
aws s3 sync "s3://$S3_BUCKET/kbo-records/" "$WORK/kbo-records/" --only-show-errors

# ── 2. 통계 재집계 + 업로드 (md는 charset 명시 — ROUTINE.md 2단계) ──
python question-gen/scripts/aggregate_stats.py \
  --envelopes-dir "$WORK/game_result" --kbo-dir "$WORK/kbo-records" \
  --out-dir "$WORK/stats" --date "$TODAY"
aws s3 sync "$WORK/stats/" "s3://$S3_BUCKET/wiki/stats/" \
  --exclude "*" --include "season.md" --include "kbo-official.md" \
  --content-type "text/markdown; charset=utf-8" --only-show-errors
aws s3 sync "$WORK/stats/" "s3://$S3_BUCKET/wiki/stats/" \
  --exclude "*" --include "season.json" --include "kbo-official.json" --only-show-errors

# ── 3~6. 생성·심사·확정 (Bedrock 2콜) ──
python -m runner.main --work "$WORK" --repo-root /app/VictoryFairy_AI --date "$TODAY"

# ── 6b. 결정적 게이트 (검증 패스와 독립 — 항상 실행) ──
VALIDATE_DIR="$WORK/candidates/$TODAY"
if [ -d "$VALIDATE_DIR" ] && [ -n "$(ls -A "$VALIDATE_DIR" 2>/dev/null)" ]; then
  python question-gen/scripts/validate_candidates.py --dir "$VALIDATE_DIR"
  # ── 7. 업로드 (멱등) ──
  aws s3 cp --recursive "$VALIDATE_DIR/" "s3://$S3_BUCKET/quiz-candidates/$TODAY/" --only-show-errors
  echo "업로드 완료: $(ls "$VALIDATE_DIR" | wc -l)건 → quiz-candidates/$TODAY/"
else
  echo "오늘 채택 문항 0건 — 업로드 생략(정상 축소일 수 있음, 로그 확인)" >&2
fi
