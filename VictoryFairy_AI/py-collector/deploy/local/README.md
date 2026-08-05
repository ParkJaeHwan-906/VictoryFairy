# Local FMKorea crawl (residential IP)

FMKorea returns HTTP **430** to AWS/datacenter IPs, so it can't run on Lambda.
It runs here, from a residential IP. DCInside + game data run on Lambda every
10 min / nightly (see `deploy/lambda/`); this only adds FMKorea's popular **KBO**
posts (`config/targets.fmkorea.yaml`, category=4332282).

## One command

```bash
bash deploy/local/crawl_fmkorea.sh
```

Crawls the current KST day's popular KBO posts and writes them to S3. It's
**idempotent** — each post lands at `community/fmkorea/<KST-date>/<post_id>` and
is skipped if already there, so re-running the same day writes nothing new. Safe
to run as often as you like.

## Run it once a day (your own cron)

```bash
crontab -e
```
Add (adjust the absolute path; 01:10 KST example):
```
10 1 * * *  /bin/bash /ABS/PATH/to/py-collector/deploy/local/crawl_fmkorea.sh
```
Output is appended to `logs/fmkorea-cron.log`.

## Check

```bash
tail -f logs/fmkorea-cron.log
aws s3 ls s3://victoryfairy-crawl-local/community/fmkorea/$(TZ=Asia/Seoul date +%F)/ | wc -l
```

## Notes
- AWS credentials come from `~/.aws` (boto3); S3 bucket/region from `.env` in the
  package dir. The script `cd`s there so both load.
- UA matters: FMKorea 430s some UAs even from a residential IP. The collector
  sends a current desktop Windows Chrome UA (`kbo_collector/fetch.py`); if FMKorea
  starts 430-ing, bump that version.
- Backfill a past date: `COLLECTOR_TARGETS_FILE=config/targets.fmkorea.yaml`
  `python -m kbo_collector.run community --date YYYY-MM-DD` (deep past dates may 430).
- The old hourly launchd job (`com.victoryfairy.community-crawl`) is retired; if
  you still have it loaded: `launchctl unload ~/Library/LaunchAgents/com.victoryfairy.community-crawl.plist`.
