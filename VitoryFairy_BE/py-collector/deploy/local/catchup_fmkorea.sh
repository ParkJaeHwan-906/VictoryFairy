#!/bin/bash
# Idempotent daily/periodic CATCH-UP: collect popular KBO posts from the last
# LOOKBACK_DAYS through today, skipping everything already in S3.
#
# "Remember where I left off" without a cursor file: S3 IS the memory. Every post
# lands at community/fmkorea/<post_date>/<post_id> and is skipped before re-fetching
# if already there (sink.exists). So re-collecting a window only downloads posts you
# don't have yet.
#
# Why a lookback WINDOW instead of "just today": you may run this every 1-3 days, or
# miss days (laptop off). A window >= your longest expected gap fills the gap, and
# idempotency dedups. Running it daily is cheap too -- most days it just tops up the
# current day. LOOKBACK_DAYS=7 tolerates a week-long gap; raise it if you skip more.
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
# Tunables (env): LOOKBACK_DAYS=7 MIN_RECOMMEND=30 DELAY_MS=1500 (and FROM/TO to override the window).
set -u

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LOOKBACK_DAYS="${LOOKBACK_DAYS:-7}"
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
