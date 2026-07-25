#!/bin/bash
# One-shot, idempotent FMKorea popular-KBO crawl -> S3.
#
# Run once a day from a RESIDENTIAL IP: FMKorea returns HTTP 430 to AWS/datacenter
# IPs, so it can't run on Lambda (DCInside does, every 10 min — see deploy/lambda/).
#
# Idempotent: each post lands at community/fmkorea/<KST-date>/<post_id>, and a post
# already in S3 is skipped before re-fetching, so re-running the same day writes
# nothing new. Safe to run repeatedly.
#
# Single command:   bash deploy/local/crawl_fmkorea.sh
# Daily cron (01:10 KST example — edit the path to this repo):
#   10 1 * * *  /bin/bash /ABS/PATH/to/py-collector/deploy/local/crawl_fmkorea.sh

# Package dir resolved from this script's location (portable, no hardcoded cwd).
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PY="$(command -v python3)"
[ -x "$DIR/../../.venv/bin/python" ] && PY="$DIR/../../.venv/bin/python"
LOG="$DIR/logs/fmkorea-cron.log"

cd "$DIR" || exit 1
mkdir -p logs

echo "=== $(date '+%Y-%m-%d %H:%M:%S') fmkorea start ===" >> "$LOG"
COLLECTOR_TARGETS_FILE="config/targets.fmkorea.yaml" \
  "$PY" -m kbo_collector.run community --concurrency 3 --delay-ms 400 >> "$LOG" 2>&1
rc=$?
echo "=== $(date '+%Y-%m-%d %H:%M:%S') fmkorea exit=$rc ===" >> "$LOG"
exit $rc
