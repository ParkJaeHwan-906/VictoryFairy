#!/bin/bash
# One-shot, idempotent FMKorea KBO backfill over a DATE RANGE -> S3.
#
# Backfills the 2026 KBO regular season so far: 2026-03-28 (opening day) ..
# 2026-07-18, keeping only popular posts (list recommend >= 30). It is a single
# continuous newest->oldest date walk (kbo_collector.run.land_community_range),
# NOT a per-day loop -- one pass over the range, so FMKorea's list is fetched
# once front-to-back instead of O(N^2) re-fetching that trips its HTTP 430.
#
# POLITE CRAWL (required): sequential (--concurrency 1) with a long per-request
# delay (--delay-ms 1500), so requests stay slow and well-spaced.
#
# RESIDENTIAL IP ONLY: FMKorea returns HTTP 430 to AWS/datacenter IPs, so run
# this from a residential IP (e.g. your MacBook), never from Lambda/EC2.
#
# IDEMPOTENT / re-run safe: each post lands at community/fmkorea/<post_date>/<id>
# and an already-landed post is skipped before re-fetching, so re-running writes
# nothing new. Safe to stop and resume.
#
# Uses the date-ordered target (config/targets.fmkorea.backfill.yaml), NOT the
# daily popular target -- the popular sort can't page into the past.
#
# Single command:   bash deploy/local/backfill_fmkorea.sh

# Package dir resolved from this script's location (portable, no hardcoded cwd).
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PY="$(command -v python3)"
[ -x "$DIR/.venv/bin/python" ] && PY="$DIR/.venv/bin/python"
LOG="$DIR/logs/fmkorea-backfill.log"

cd "$DIR" || exit 1
mkdir -p logs

echo "=== $(date '+%Y-%m-%d %H:%M:%S') fmkorea backfill start ===" >> "$LOG"
COLLECTOR_TARGETS_FILE="config/targets.fmkorea.backfill.yaml" \
  "$PY" -m kbo_collector.run community \
    --from 2026-03-28 --to 2026-07-18 \
    --min-recommend 30 --concurrency 1 --delay-ms 1500 >> "$LOG" 2>&1
rc=$?
echo "=== $(date '+%Y-%m-%d %H:%M:%S') fmkorea backfill exit=$rc ===" >> "$LOG"
exit $rc
