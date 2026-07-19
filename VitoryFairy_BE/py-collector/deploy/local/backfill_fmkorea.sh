#!/bin/bash
# Resilient, idempotent FMKorea KBO popular-post backfill over a DATE RANGE -> S3.
#
# Backfills the 2026 KBO regular season: FROM (opening day 2026-03-28) .. TO,
# keeping only popular posts (list recommend >= MIN_RECOMMEND). Each pass is a
# single continuous newest->oldest date walk (kbo_collector.run.land_community_
# range) over the popularity-sorted board (config/targets.fmkorea.backfill.yaml:
# sort_index=pop, which is date-descending AND recommend-pre-filtered), NOT a
# per-day loop -- one pass over the range, so the list is fetched once
# front-to-back instead of O(N^2) re-fetching.
#
# WHY A RESTART LOOP: the full season is thousands of posts. FMKorea rate-limits
# a residential IP with HTTP 429/430 after a burst (~30 req/min trips it), so a
# single pass eventually stalls. This runner crawls SLOWLY (DELAY_MS) and, when a
# pass makes no new progress because it got blocked, sleeps a long BLOCK_COOLDOWN
# and resumes. Resume is free: every landed post is skipped before re-fetching
# (sink.exists), so each restart continues from the oldest date reached so far.
# It stops when two consecutive passes land nothing new (whole range present, or
# a hard block) or after MAX_RUNS.
#
# RESIDENTIAL IP ONLY: FMKorea 430s AWS/datacenter IPs -- run from a MacBook, not
# Lambda/EC2.
#
# BUCKET: set COLLECTOR_S3_BUCKET explicitly (this script does NOT default it, so
# a dev backfill never lands in the wrong bucket). Example below.
#
# Run (dev):
#   COLLECTOR_S3_BUCKET=victoryfairy-crawl-dev bash deploy/local/backfill_fmkorea.sh
# Tunables (env, with defaults):
#   FROM=2026-03-28 TO=2026-07-18 MIN_RECOMMEND=30 DELAY_MS=4000
#   INITIAL_COOLDOWN=0 BLOCK_COOLDOWN=480 MAX_RUNS=40

set -u

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PY="$(command -v python3)"
[ -x "$DIR/.venv/bin/python" ] && PY="$DIR/.venv/bin/python"
cd "$DIR" || exit 1
mkdir -p logs
LOG="${LOG:-$DIR/logs/fmkorea-backfill.log}"   # catchup_fmkorea.sh overrides this

: "${COLLECTOR_S3_BUCKET:?set COLLECTOR_S3_BUCKET (e.g. victoryfairy-crawl-dev) before running}"
FROM="${FROM:-2026-03-28}"
TO="${TO:-2026-07-18}"
MIN_RECOMMEND="${MIN_RECOMMEND:-30}"
DELAY_MS="${DELAY_MS:-4000}"          # ~13 req/min: slow enough to sustain
INITIAL_COOLDOWN="${INITIAL_COOLDOWN:-0}"  # wait first if we're already rate-limited
BLOCK_COOLDOWN="${BLOCK_COOLDOWN:-480}"    # 8 min pause after a blocked (no-progress) pass
MAX_RUNS="${MAX_RUNS:-40}"

# Ride out a 429/430 window WITHIN a pass instead of dying fast: long cooldown per
# rate-limited request, a few attempts, a few list-page retries.
export RATE_LIMIT_COOLDOWN_S="${RATE_LIMIT_COOLDOWN_S:-60}"
export RETRY_ATTEMPTS="${RETRY_ATTEMPTS:-4}"
export COLLECTOR_COMMUNITY_LIST_RETRIES="${COLLECTOR_COMMUNITY_LIST_RETRIES:-4}"
export COLLECTOR_COMMUNITY_MAX_PAGES="${COLLECTOR_COMMUNITY_MAX_PAGES:-2000}"
export COLLECTOR_TARGETS_FILE="config/targets.fmkorea.backfill.yaml"

log() { echo "=== $(date '+%F %T') $* ===" | tee -a "$LOG"; }

# Count landed community objects in the target bucket (progress signal for the loop).
s3_count() {
  aws s3 ls "s3://${COLLECTOR_S3_BUCKET}/community/fmkorea/" --recursive 2>/dev/null | wc -l | tr -d ' '
}

log "backfill start bucket=${COLLECTOR_S3_BUCKET} range=${FROM}..${TO} min_rec=${MIN_RECOMMEND} delay=${DELAY_MS}ms"
if [ "$INITIAL_COOLDOWN" -gt 0 ]; then
  log "initial cooldown ${INITIAL_COOLDOWN}s (avoid an active rate-limit)"
  sleep "$INITIAL_COOLDOWN"
fi

dry=0
for run in $(seq 1 "$MAX_RUNS"); do
  before="$(s3_count)"
  log "pass ${run}/${MAX_RUNS} start (landed so far=${before})"
  "$PY" -m kbo_collector.run community \
    --from "$FROM" --to "$TO" \
    --min-recommend "$MIN_RECOMMEND" --concurrency 1 --delay-ms "$DELAY_MS" >> "$LOG" 2>&1
  after="$(s3_count)"
  # A count of 0 while we had objects before means `aws s3 ls` itself failed (network
  # blip / dropped Wi-Fi), NOT that the range emptied. Don't let that read count as a
  # "no progress" dry pass (which could trip the two-dry stop) -- pause briefly & retry.
  if [ "$after" -eq 0 ] && [ "$before" -gt 0 ]; then
    log "pass ${run}: S3 count read 0 (prior=${before}) -> transient network/read failure; short retry"
    sleep 60
    continue
  fi
  gained=$(( after - before ))
  log "pass ${run} done: +${gained} new (total=${after})"
  if [ "$gained" -le 0 ]; then
    dry=$(( dry + 1 ))
    if [ "$dry" -ge 2 ]; then
      log "two dry passes in a row -> range complete or hard-blocked; stopping (total=${after})"
      break
    fi
    log "no progress -> block cooldown ${BLOCK_COOLDOWN}s then resume"
    sleep "$BLOCK_COOLDOWN"
  else
    dry=0
    sleep 30   # brief breather between productive passes
  fi
done

log "backfill finished total=$(s3_count)"
