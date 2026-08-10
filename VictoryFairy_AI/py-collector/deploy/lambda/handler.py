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
  {"job": "games_sync"}                     -> today's games' status -> prod MySQL
  {"job": "games_sync", "date": ..}         -> a specific date (backfill)
  {"job": "games_sync", "days": 7}          -> 오늘~+7일 일정 선적재 (하루 1회 룰)
  {"job": "games_sync", "from": .., "to": ..} -> date-range backfill
  {"job": "kbo_records"}                    -> KBO 기록실 스냅샷 -> S3 (07:00 KST)
  {"job": "game_schedule"}                  -> 당일(KST) 예정경기 -> S3 export (08:30 KST)
  {"job": "game_schedule", "date": ..}      -> 특정 날짜 백필
  {"job": "export", "target": "game_result"} -> docType envelope export -> S3 (04:00 KST)

records/registrations/games_sync/export write to (or read from) the prod DB, so they
run on the VPC-attached "-db" function (COLLECTOR_DB_* env; see dev_infra's
VictoryFairy_Infra/collector-lambda/lambda_db.tf) — the S3-only jobs' function
stays outside the VPC. Both functions share this handler and image.

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


# games_sync 가 한 번에 훑는 날짜 수의 천장. days/from/to 는 EventBridge 룰의 input
# (Terraform)에서 오는 값이라, 라이브 10분 주기 룰에 실수로 붙으면 하루 42회 발화 x
# 날짜 수만큼 원천을 두드리게 된다. 코드에서 잘라 그 사고를 구조적으로 막는다.
MAX_SYNC_DAYS = 14


def _plus_days(date_str: str, days: int) -> str:
    d = datetime.date.fromisoformat(date_str) + datetime.timedelta(days=days)
    return d.isoformat()


def handler(event, context):
    setup_logging()
    settings = get_settings()
    event = event or {}
    job = event.get("job", "community")
    is_community = job in ("community", "all")
    # game_schedule("당일 예정경기")·games_sync("당일 경기 상태")는 오늘을 내다보는
    # 잡이라 KST-오늘 기준이어야 한다 — 03:00 KST game 잡의 UTC-오늘(=KST-어제 완료
    # 경기용) 기준과는 반대.
    kst_anchored = is_community or job in ("game_schedule", "games_sync")
    date = event.get("date") or (_kst_today() if kst_anchored else _today())
    run_id = (getattr(context, "aws_request_id", None) or uuid.uuid4().hex)[:16]

    summary: dict = {"job": job, "date": date}

    # DB 잡: -db 함수(VPC 안)에서만 도달한다. export 는 docType에 따라 DB 를 읽거나
    # (game_result 등) S3 만 쓰기도 하지만(exporter.DB_FREE), 어느 쪽이든 이 함수가
    # 맡는다 — S3 전용 함수엔 COLLECTOR_DB_* 자격증명이 없다.
    if job in ("records", "registrations", "export", "games_sync"):
        db = DbSink(settings)
        try:
            if job == "games_sync":
                # job_games_sync_range 는 fetch 클라이언트를 자체 관리한다(CLI main()과
                # 동일 경로) — 다른 DB 잡처럼 여기서 별도 client 를 만들 필요가 없다.
                # days 없이 부르면 start==end 라 당일치 그대로다(라이브 10분 룰).
                start = event.get("from") or date
                end = event.get("to") or _plus_days(
                    start, min(int(event.get("days") or 0), MAX_SYNC_DAYS))
                end = min(end, _plus_days(start, MAX_SYNC_DAYS))  # ISO 문자열 비교 = 날짜 비교
                summary["from"], summary["to"] = start, end
                summary["gamesSynced"] = run.job_games_sync_range(settings, db, start, end)
            else:
                with fetch.build_client(settings) as client:
                    if job == "registrations":
                        # date 미지정(None)이면 KBO 사이트가 알려주는 최신 등록일 스냅샷.
                        summary["registrations"] = run.land_registrations(
                            event.get("date"), settings=settings, db=db, client=client)
                    elif job == "export":
                        from kbo_collector.exports import exporter
                        summary["exported"] = exporter.export(
                            event["target"], settings=settings, db=db,
                            sink=S3RawSink(settings), date=event.get("date"))
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
        if job == "kbo_records":
            import kbo_collector.sources  # noqa: F401 — REGISTRY 등록
            from kbo_collector.sources import base as source_base
            src = source_base.get_source("kbo_records")
            res = src.collect(source_base.CollectContext(
                settings=settings, client=client, sink=sink, date=event.get("date")))
            summary["kboRecords"] = {"loaded": res.loaded, "failed": res.failed}
        if job == "game_schedule":
            # `date` 는 위에서 이미 KST-오늘로 앵커링됨(kst_anchored) — land_schedule 후
            # 같은 날짜로 export.
            run.land_schedule(date, settings=settings, sink=sink, client=client,
                              journal=Journal("schedule", date, run_id, settings.journal_dir))
            from kbo_collector.exports import exporter
            summary["gameSchedule"] = exporter.export(
                "game_schedule", settings=settings, db=None, sink=sink, date=date)

    return summary
