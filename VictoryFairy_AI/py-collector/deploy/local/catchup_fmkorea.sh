#!/bin/bash
# Idempotent daily/periodic CATCH-UP: collect popular KBO posts from the last
# LOOKBACK_DAYS through today, skipping everything already in S3.
#
# "Remember where I left off" without a cursor file: S3 IS the memory. Every post
# lands at community/fmkorea/<post_date>/<post_id> and is skipped before re-fetching
# if already there (sink.exists). So re-collecting a window only downloads posts you
# don't have yet.
#
# Why a lookback WINDOW instead of "just today": runs may be sparse (laptop off).
# A window >= your longest expected gap fills the gap, and idempotency dedups.
# Frequent runs are cheap too -- most firings just top up the current day.
# LOOKBACK_DAYS=3 tolerates a weekend off; raise it (or pass FROM=) if you skip more.
#
# Delegates to the resilient runner (backfill_fmkorea.sh) over [today-LOOKBACK, today]:
# same slow polite pace, 429 backoff, and idempotent restart, just a small window.
#
# RESIDENTIAL IP ONLY: FMKorea 430s AWS/datacenter IPs -- run from your MacBook.
#
# Manual:
#   COLLECTOR_S3_BUCKET=victoryfairy-crawl-dev bash deploy/local/catchup_fmkorea.sh
# Scheduled: use launchd (deploy/local/com.victoryfairy.fmkorea-catchup.plist), NOT
#   cron -- launchd runs a MISSED daily run once when the Mac next wakes; cron skips it.
#
# Tunables (env): LOOKBACK_DAYS=3 MIN_RECOMMEND=30 DELAY_MS=1500 (and FROM/TO to override the window).
set -u

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

# If a (long-running, manual) backfill is already crawling FMKorea, this top-up is
# redundant -- the backfill's newest-first walk covers our window -- and running both
# doubles the request rate, inviting a 429/430 block. Skip; launchd retries in 30 min.
if pgrep -f "backfill_fmkorea.sh" >/dev/null 2>&1; then
  echo "catchup: backfill_fmkorea.sh already running; skipping this firing"
  exit 0
fi

LOOKBACK_DAYS="${LOOKBACK_DAYS:-3}"
# KST anchor: FMKorea list times are KST, and posts land under their KST date.
FROM="${FROM:-$(TZ=Asia/Seoul date -v-${LOOKBACK_DAYS}d +%F)}"
TO="${TO:-$(TZ=Asia/Seoul date +%F)}"
: "${COLLECTOR_S3_BUCKET:?set COLLECTOR_S3_BUCKET (e.g. victoryfairy-crawl-dev)}"
export COLLECTOR_S3_BUCKET

echo "catchup window: ${FROM}..${TO} (lookback ${LOOKBACK_DAYS}d) bucket=${COLLECTOR_S3_BUCKET}"
FROM="$FROM" TO="$TO" \
  MIN_RECOMMEND="${MIN_RECOMMEND:-30}" DELAY_MS="${DELAY_MS:-1500}" \
  MAX_RUNS="${MAX_RUNS:-6}" BLOCK_COOLDOWN="${BLOCK_COOLDOWN:-300}" \
  LOG="${LOG:-$DIR/logs/fmkorea-catchup.log}" \
  bash "$DIR/deploy/local/backfill_fmkorea.sh"
