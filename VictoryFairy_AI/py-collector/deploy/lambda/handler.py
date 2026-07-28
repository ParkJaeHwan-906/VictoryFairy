"""AWS Lambda entrypoint for the KBO collector.

Thin adapter: an EventBridge schedule invokes this with an event that names the
job, and we call the same orchestration-agnostic core (kbo_collector.run.land_*).

Events:
  {"job": "community"}   -> crawl community posts (every 10 min schedule)
  {"job": "game"}        -> schedule -> results -> relays for a date (03:00 KST)
  {"job": "game", "date": "2026-07-08"}   -> a specific date (backfill)
  ("daily" is accepted as a legacy alias for "game".)
  {"job": "records"}                        -> finished games -> prod MySQL (03:30 KST)
  {"job": "records", "from": .., "to": ..}  -> date-range backfill
  {"job": "registrations"}                  -> KBO 1-gun roster -> prod MySQL (11:00 KST)

records/registrations write to the prod DB, so they run on the VPC-attached
"-db" function (COLLECTOR_DB_* env; see terraform/lambda_db.tf) — the S3 jobs'
function stays outside the VPC. Both functions share this handler and image.

Env (set by Terraform): COLLECTOR_S3_BUCKET, COLLECTOR_S3_REGION,
COLLECTOR_PII_SALT (from Secrets Manager), COLLECTOR_TARGETS_FILE,
JOURNAL_DIR=/tmp/journal (Lambda's only writable path); the -db function gets
COLLECTOR_DB_HOST/PORT/NAME/USER/PASSWORD instead of the S3 vars.
"""
import datetime
import uuid

from kbo_collector import fetch, run
from kbo_collector.config import get_settings
from kbo_collector.db import DbSink
from kbo_collector.journal import Journal, setup_logging
from kbo_collector.sink import S3RawSink


def _today() -> str:
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%d")


def _kst_today() -> str:
    # Community list times are KST; anchor its "today" there.
    kst = datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(hours=9)
    return kst.strftime("%Y-%m-%d")


def handler(event, context):
    setup_logging()
    settings = get_settings()
    event = event or {}
    job = event.get("job", "community")
    is_community = job in ("community", "all")
    date = event.get("date") or (_kst_today() if is_community else _today())
    run_id = (getattr(context, "aws_request_id", None) or uuid.uuid4().hex)[:16]

    summary: dict = {"job": job, "date": date}

    # DB 적재 잡: S3 를 안 쓰므로 sink 없이 DbSink 로 바로 처리하고 끝낸다.
    if job in ("records", "registrations"):
        db = DbSink(settings)
        try:
            with fetch.build_client(settings) as client:
                if job == "registrations":
                    # date 미지정(None)이면 KBO 사이트가 알려주는 최신 등록일 스냅샷.
                    summary["registrations"] = run.land_registrations(
                        event.get("date"), settings=settings, db=db, client=client)
                else:
                    start = event.get("from") or date
                    end = event.get("to") or start
                    res = run.land_game_records_range(
                        start, end, settings=settings, db=db, client=client)
                    # 성공은 개수만, 실패는 재시도용으로 gameId 목록 그대로.
                    summary["records"] = {"loaded": len(res["loaded"]),
                                          "failed": res["failed"]}
        finally:
            db.close()
        return summary

    sink = S3RawSink(settings)

    with fetch.build_client(settings) as client:
        if job in ("game", "daily", "schedule", "result", "relay", "all"):
            game_ids = run.land_schedule(
                date, settings=settings, sink=sink, client=client,
                journal=Journal("schedule", date, run_id, settings.journal_dir))
            summary["gameIds"] = len(game_ids)
            if job in ("game", "daily", "result", "all"):
                summary["results"] = run.land_results(
                    date, game_ids, settings=settings, sink=sink, client=client,
                    journal=Journal("result", date, run_id, settings.journal_dir))
            if job in ("game", "daily", "relay", "all"):
                summary["relays"] = run.land_relays(
                    date, game_ids, settings=settings, sink=sink, client=client,
                    journal=Journal("relay", date, run_id, settings.journal_dir))
        if is_community:
            # Popular-only: crawls each source's popular listing (config/targets.yaml)
            # over the recommend/view threshold. Those lists are shallow, so the ~10-min
            # EventBridge cadence re-scans + S3-dedups cheaply (no deep-page 430). Tuning
            # (concurrency/delay/thresholds) comes from env (see lambda.tf).
            summary["community"] = run.land_community(
                date, settings=settings, sink=sink, client=client,
                journal=Journal("community", date, run_id, settings.journal_dir),
                incremental=event.get("incremental", False))

    return summary
