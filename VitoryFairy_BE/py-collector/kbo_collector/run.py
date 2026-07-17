import argparse
import logging
import random
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import date as date_cls
from datetime import datetime, timedelta, timezone

from . import community, dimensions, fetch, game_records, keys, kbo_register, naver
from .config import get_settings
from .journal import Journal, setup_logging
from .sink import S3RawSink
from .targets import load_targets

RELAY_MAX_INNING = 15


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


def _today() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%d")


def _kst_today() -> str:
    # Community sites report list times in KST; anchor 'today' there so the
    # HH:MM (recent) / MM.DD (older) date interpretation lines up.
    return (datetime.now(timezone.utc) + timedelta(hours=9)).strftime("%Y-%m-%d")


def _page_url(base_url: str, page: int) -> str:
    sep = "&" if "?" in base_url else "?"
    return f"{base_url}{sep}page={page}"


def _parse_list(source: str, html: str, today: str) -> list:
    if source == "FMKOREA":
        return community.parse_fmkorea_list(html, today=today)
    return community.parse_dcinside_list(html)


def _polite(sleep, settings) -> None:
    # Jittered delay between requests: spreads them out so the cadence looks less
    # machine-regular and eases the source's rate accounting.
    sleep(settings.fetch_delay_ms / 1000 * random.uniform(0.7, 1.3))


def _meta(job: str, run_id: str) -> dict:
    # Attached to every landed object as S3 user metadata for run traceability
    # WITHOUT polluting the (idempotent, content-derived) object key.
    return {"run-id": run_id, "job": job}


def _write_manifest(sink, job, date, run_id, landed_keys, dead_letters) -> None:
    # Per-run index: "what did this run of this job land?" — the run_id lives
    # here (and in object metadata), not in the data keys, so re-runs stay idempotent.
    sink.put_json(
        keys.manifest_key(job, date, run_id),
        {
            "run_id": run_id,
            "job": job,
            "date": date,
            "landed": len(landed_keys),
            "keys": landed_keys,
            "dead_letters": dead_letters,
            "generated_at": _now_iso(),
        },
    )


# --------------------------------------------------------------------------- jobs
def land_schedule(date, *, settings, sink, client, journal, force=False) -> list[str]:
    run_id = journal.run_id
    url = naver.schedule_url(settings, date)
    key = keys.schedule_key(date)
    resp = fetch.fetch(client, url, settings=settings, referer=settings.naver_referer)
    sink.put(key, resp.content, "application/json", metadata=_meta("schedule", run_id))
    journal.record(status="ok", item_id="schedule", s3_key=key, bytes=len(resp.content))
    game_ids = naver.extract_game_ids(resp.json())
    journal.record(status="extracted", item_id="gameIds", bytes=len(game_ids))
    _write_manifest(sink, "schedule", date, run_id, [key], [])
    return game_ids


def land_results(date, game_ids, *, settings, sink, client, journal, force=False) -> int:
    run_id = journal.run_id
    landed = 0
    landed_keys: list[str] = []
    dead: list[str] = []
    for gid in game_ids:
        url = naver.result_url(settings, gid)
        key = keys.result_key(date, gid)
        if not force and sink.exists(key):
            journal.record(status="skip", item_id=gid, s3_key=key)
            continue
        try:
            resp = fetch.fetch(client, url, settings=settings, referer=settings.naver_referer)
            sink.put(key, resp.content, "application/json", metadata=_meta("result", run_id))
            journal.record(status="ok", item_id=gid, s3_key=key, bytes=len(resp.content))
            landed += 1
            landed_keys.append(key)
        except fetch.FetchError as exc:
            sink.dead_letter("result", date, gid, url, exc, settings.retry_attempts, _now_iso())
            journal.record(status="dead-letter", item_id=gid, s3_key=key)
            dead.append(gid)
    _write_manifest(sink, "result", date, run_id, landed_keys, dead)
    return landed


def land_relays(date, game_ids, *, settings, sink, client, journal, force=False) -> int:
    run_id = journal.run_id
    landed = 0
    landed_keys: list[str] = []
    dead: list[str] = []
    for gid in game_ids:
        for inning in range(1, RELAY_MAX_INNING + 1):
            url = naver.relay_url(settings, gid, inning)
            key = keys.relay_key(gid, inning)
            if not force and sink.exists(key):
                continue
            try:
                resp = fetch.fetch(client, url, settings=settings, referer=settings.naver_referer)
            except fetch.FetchError as exc:
                sink.dead_letter("relay", date, f"{gid}-{inning}", url, exc,
                                 settings.retry_attempts, _now_iso())
                journal.record(status="dead-letter", item_id=f"{gid}-{inning}", s3_key=key)
                dead.append(f"{gid}-{inning}")
                break
            if naver.relay_is_empty(resp.json()):
                break  # out of the game's inning range -> next game
            sink.put(key, resp.content, "application/json", metadata=_meta("relay", run_id))
            journal.record(status="ok", item_id=f"{gid}-{inning}", s3_key=key, bytes=len(resp.content))
            landed += 1
            landed_keys.append(key)
    _write_manifest(sink, "relay", date, run_id, landed_keys, dead)
    return landed


def _collect_refs_for_date(target, date, *, settings, client, journal, today, sleep, cap=0,
                           sink=None, incremental=False):
    """Page a target's list, collecting refs authored on `date`.

    Date-ordered targets (default) are newest-first, so rows newer than `date`
    are skipped and the first row older than `date` ends the walk. Targets marked
    `order: popular` have no date order (e.g. FMKorea's popular sort), so instead
    of walking to a date we scan a bounded `community_popular_scan_pages` window
    and keep every row matching `date`.
    `cap` (>0) stops once that many posts are collected. `incremental` (date-order
    only) stops at the first post already in S3 to keep the walk shallow.
    """
    source = target["source"]
    date_ordered = target.get("order", "date") != "popular"
    max_pages = settings.community_max_pages if date_ordered else settings.community_popular_scan_pages
    collected: list = []
    log = logging.getLogger("kbo_collector.community")
    for page in range(1, max_pages + 1):
        url = _page_url(target["url"], page)
        # Tolerate an intermittent list-page failure (e.g. FMKorea's 430
        # rate-limit): re-attempt the same page with a growing pause before
        # giving up on the whole target.
        refs = None
        last_err = None
        for attempt in range(settings.community_list_retries):
            try:
                list_resp = fetch.fetch(client, url, settings=settings, accept="text/html")
                parsed = _parse_list(source, list_resp.text, today)
            except Exception as exc:
                last_err = exc
                sleep(settings.fetch_delay_ms / 1000 * (attempt + 2))  # back off, then retry page
                continue
            if parsed:
                refs = parsed
                break
            # 200 but zero post rows is usually a rate-limit / interstitial page,
            # not the real end of the board -> cool down, then retry before believing it.
            last_err = "empty page (0 post rows)"
            sleep(settings.rate_limit_cooldown_s + random.uniform(0, settings.retry_backoff_base))
        if refs is None:
            # exhausted retries: a persistent block, or the genuine end of listing.
            journal.record(status="list-fail", source=source, item_id=url,
                           error=str(last_err)[:200])
            break
        done = False
        for ref in refs:
            if ref.post_date is None or ref.post_date > date:
                continue  # unknown/newer -> keep looking
            if ref.post_date == date:
                if date_ordered and incremental and sink is not None and \
                        sink.exists(keys.community_key(source, date, ref.post_id)):
                    done = True  # hit the prior run's checkpoint -> caught up
                    break
                collected.append(ref)
                if cap and len(collected) >= cap:
                    done = True
                    break
            elif date_ordered:  # older than target in a date-ordered list -> stop
                done = True
                break
            # popular-ordered: older rows are interspersed -> just skip, keep scanning
        journal.record(status="page", source=source, item_id=f"{source}-p{page}",
                       bytes=len(collected))
        if done:
            break
        _polite(sleep, settings)  # jittered polite delay between list pages
    log.info("%s %s: %d posts on %s (cap=%s)", source, target.get("team") or "",
             len(collected), date, cap or "none")
    return collected


def _filter_popular(refs, min_recommend, view_factor):
    """Keep only 'popular' refs (OR of two arms). Disabled if both thresholds <=0.

    - recommend arm: list recommend count >= min_recommend.
    - views arm: list views >= view_factor * (avg views of these refs). The
      average is per-call (per target/date), so the bar adapts to the gallery.
    A ref with no popularity signal at all (both None, e.g. a source/fixture that
    omits counts) is kept — we can't judge it.
    """
    if min_recommend <= 0 and view_factor <= 0:
        return refs
    seen_views = [r.views for r in refs if r.views is not None]
    avg = (sum(seen_views) / len(seen_views)) if seen_views else None
    kept = []
    for r in refs:
        if r.recommend is None and r.views is None:
            kept.append(r)
            continue
        by_rec = min_recommend > 0 and r.recommend is not None and r.recommend >= min_recommend
        by_view = (view_factor > 0 and r.views is not None and avg
                   and r.views >= view_factor * avg)
        if by_rec or by_view:
            kept.append(r)
    return kept


def _land_one_detail(ref, *, source, team, date, settings, sink, client, run_id,
                     journal, jlock, force, sleep):
    """Fetch+parse+land one post. Runs in a worker thread; journal writes are
    serialized by `jlock`. Returns (status, key, dead_item)."""
    key = keys.community_key(source, date, ref.post_id)
    if not force and sink.exists(key):
        with jlock:
            journal.record(status="skip", source=source, item_id=ref.post_id, s3_key=key)
        return ("skip", key, None)
    _polite(sleep, settings)  # jittered per-worker polite spacing
    try:
        detail = fetch.fetch(client, ref.url, settings=settings, accept="text/html")
        if source == "FMKOREA":
            post = community.parse_fmkorea_detail(
                detail.text, ref, settings.pii_salt, settings.top_comments, _now_iso())
        else:
            post = community.parse_dcinside_detail(detail.text, ref, team, _now_iso())
        sink.put_json(key, post, metadata=_meta("community", run_id))
        with jlock:
            journal.record(status="ok", source=source, item_id=ref.post_id, s3_key=key)
        return ("ok", key, None)
    except Exception as exc:
        sink.dead_letter("community", date, f"{source}-{ref.post_id}", ref.url, exc,
                         settings.retry_attempts, _now_iso())
        with jlock:
            journal.record(status="dead-letter", source=source, item_id=ref.post_id)
        return ("dead", None, f"{source}-{ref.post_id}")


def land_community(date, *, settings, sink, client, journal, force=False,
                   sleep=time.sleep, today=None, cap=None, concurrency=None, sources=None,
                   incremental=False, min_recommend=None, view_factor=None) -> int:
    run_id = journal.run_id
    today = today or _kst_today()
    if cap is None:
        cap = settings.community_per_target_cap
    if concurrency is None:
        concurrency = settings.community_concurrency
    if min_recommend is None:
        min_recommend = settings.community_min_recommend
    if view_factor is None:
        view_factor = settings.community_view_factor
    workers = max(1, concurrency)
    only = {s.upper() for s in sources} if sources else None
    jlock = threading.Lock()
    landed = 0
    landed_keys: list[str] = []
    dead: list[str] = []
    for target in load_targets(settings.targets_file):
        source = target["source"]
        team = target.get("team")
        if only and source.upper() not in only:
            continue  # --source filter
        # List paging stays serial: the date early-stop depends on page order.
        refs = _collect_refs_for_date(target, date, settings=settings, client=client,
                                      journal=journal, today=today, sleep=sleep, cap=cap,
                                      sink=sink, incremental=incremental and not force)
        kept = _filter_popular(refs, min_recommend, view_factor)
        if len(kept) != len(refs):
            journal.record(status="filtered", source=source,
                           item_id=f"{source}-popular", bytes=len(kept))
        refs = kept
        # Detail fetches parallelize across `workers` threads (httpx/boto3 clients
        # are thread-safe; only the journal file write is guarded).
        with ThreadPoolExecutor(max_workers=workers) as ex:
            futures = [
                ex.submit(_land_one_detail, ref, source=source, team=team, date=date,
                          settings=settings, sink=sink, client=client, run_id=run_id,
                          journal=journal, jlock=jlock, force=force, sleep=sleep)
                for ref in refs
            ]
            for fut in as_completed(futures):  # accumulate on the main thread
                status, key, dead_item = fut.result()
                if status == "ok":
                    landed += 1
                    landed_keys.append(key)
                elif status == "dead":
                    dead.append(dead_item)
    _write_manifest(sink, "community", date, run_id, landed_keys, dead)
    return landed


def land_registrations(date=None, *, settings, db, client,
                       teams=dimensions.TEAM_CODES,
                       fetch_html=None, current=None) -> list[str]:
    fetch_html = fetch_html or kbo_register.fetch_register_html
    current = current or kbo_register.current_date
    site_current = current(settings, client)          # 'YYYY-MM-DD'
    snapshot_date = date or site_current
    is_current = snapshot_date == site_current
    date_compact = snapshot_date.replace("-", "")

    db.upsert_teams(dimensions.TEAMS)  # FK 충족 위해 팀 시드 먼저
    synced: list[str] = []
    for code in teams:
        try:
            html = fetch_html(code, date_compact, settings=settings, client=client)
            rows = kbo_register.parse_register(html)
        except Exception:
            continue  # 팀 실패: 스킵(inactive 처리 안 함)
        db.upsert_players(rows, code, snapshot_date)
        db.insert_registrations(snapshot_date, rows, code)
        synced.append(code)
    if is_current:
        for code in synced:
            db.mark_not_first_team(code, snapshot_date)
    return synced


def land_game_records(date, *, settings, db, client) -> tuple[list[str], list[str]]:
    """하루치 완료 경기 record를 MySQL(games/game_pitching/game_batting)에 적재.

    반환: (적재 성공 game_id, 실패 game_id).
    """
    log = logging.getLogger("records")
    url = game_records.schedule_url(settings, date)
    resp = fetch.fetch(client, url, settings=settings, referer=settings.naver_referer)
    finished = game_records.list_finished_games(resp.json())
    loaded, failed = [], []
    for g in finished:
        gid = g["gameId"]
        try:
            rresp = fetch.fetch(client, game_records.record_url(settings, gid),
                                settings=settings, referer=settings.naver_referer)
            record = (rresp.json().get("result") or {}).get("recordData")
            if not record:
                failed.append(gid)
                continue
            game = game_records.parse_record(gid, record)
            uid_map = db.upsert_game_players(game.players)
            db.upsert_game(game, uid_map)
            db.upsert_innings(game)
            db.upsert_pitching(gid, game.pitching, uid_map)
            db.upsert_batting(gid, game.batting, uid_map)
            loaded.append(gid)
        except Exception as exc:  # 한 경기 실패가 백필 전체를 막지 않도록
            log.warning("record fail %s: %s", gid, exc)
            failed.append(gid)
    log.info("%s: loaded=%d failed=%d", date, len(loaded), len(failed))
    return loaded, failed


def land_game_records_range(start, end, *, settings, db, client, sleep=time.sleep) -> dict:
    """[start, end] 날짜 구간 백필. teams 시드 후 일자별 반복, 마지막에 kbo_id 링크."""
    db.upsert_teams(dimensions.TEAMS)
    d0 = date_cls.fromisoformat(start)
    d1 = date_cls.fromisoformat(end)
    loaded, failed = [], []
    d = d0
    while d <= d1:
        lo, fa = land_game_records(d.isoformat(), settings=settings, db=db, client=client)
        loaded += lo
        failed += fa
        d += timedelta(days=1)
        if d <= d1:
            sleep(settings.fetch_delay_ms / 1000)
    db.link_kbo_player_ids()
    return {"loaded": loaded, "failed": failed}


# --------------------------------------------------------------------------- CLI
def main(argv=None) -> int:
    parser = argparse.ArgumentParser(prog="kbo_collector")
    parser.add_argument("job", choices=["schedule", "result", "relay", "game",
                                        "community", "all", "teams", "registrations",
                                        "records", "collect", "export"])
    parser.add_argument("--target", default=None,
                        help="collect: source_id / export: docType")
    parser.add_argument("--date", default=None, help="YYYY-MM-DD (default: today UTC)")
    parser.add_argument("--from", dest="from_date", default=None,
                        help="records 백필 시작일 YYYY-MM-DD")
    parser.add_argument("--to", dest="to_date", default=None,
                        help="records 백필 종료일 YYYY-MM-DD")
    parser.add_argument("--force", action="store_true", help="ignore S3-existence checkpoint")
    parser.add_argument("--cap", type=int, default=None,
                        help="community: max posts to detail-fetch per target (newest-first; 0=unlimited)")
    parser.add_argument("--concurrency", type=int, default=None,
                        help="community: concurrent detail fetches per target (1=serial)")
    parser.add_argument("--delay-ms", type=int, default=None,
                        help="override per-request polite delay in milliseconds")
    parser.add_argument("--source", default=None,
                        help="community: only crawl these sources (comma-separated, e.g. FMKOREA)")
    parser.add_argument("--incremental", action="store_true",
                        help="community: stop paging at the first already-landed post (shallow forward crawl)")
    parser.add_argument("--min-recommend", type=int, default=None,
                        help="community: only keep posts with list recommend >= this (0 disables)")
    parser.add_argument("--view-factor", type=float, default=None,
                        help="community: also keep posts with views >= factor*avg views (0 disables)")
    args = parser.parse_args(argv)

    setup_logging()
    settings = get_settings()
    if args.delay_ms is not None:
        settings.fetch_delay_ms = args.delay_ms
    # community lists report KST times, so its default "today" is KST.
    date = args.date or (_kst_today() if args.job == "community" else _today())
    run_id = uuid.uuid4().hex[:8]
    if args.job == "collect":
        from .db import DbSink
        from .sources import base as source_base
        src = source_base.get_source(args.target or "")
        db = DbSink(settings)
        try:
            with fetch.build_client(settings) as client:
                ctx = source_base.CollectContext(
                    settings=settings, client=client, db=db,
                    sink=S3RawSink(settings), date=args.date)
                result = src.collect(ctx)
                logging.getLogger("collect").info(
                    "%s: loaded=%d failed=%d", src.source_id,
                    result.loaded, len(result.failed))
        finally:
            db.close()
        return 0

    if args.job == "export":
        from .db import DbSink
        from .exports import exporter
        db = DbSink(settings)
        try:
            n = exporter.export(args.target or "", settings=settings, db=db,
                                sink=S3RawSink(settings), date=args.date)
            logging.getLogger("export").info("%s: exported=%d", args.target, n)
        finally:
            db.close()
        return 0

    if args.job in ("teams", "registrations", "records"):
        from .db import DbSink
        db = DbSink(settings)
        try:
            with fetch.build_client(settings) as client:
                if args.job == "teams":
                    db.upsert_teams(dimensions.TEAMS)
                elif args.job == "registrations":
                    land_registrations(args.date, settings=settings, db=db, client=client)
                else:  # records
                    start = args.from_date or date
                    end = args.to_date or start
                    land_game_records_range(start, end, settings=settings, db=db, client=client)
        finally:
            db.close()
        return 0

    sink = S3RawSink(settings)

    with fetch.build_client(settings) as client:
        if args.job in ("schedule", "result", "relay", "game", "all"):
            j_sched = Journal("schedule", date, run_id, settings.journal_dir)
            game_ids = land_schedule(date, settings=settings, sink=sink, client=client,
                                     journal=j_sched, force=args.force)
            if args.job in ("result", "game", "all"):
                land_results(date, game_ids, settings=settings, sink=sink, client=client,
                             journal=Journal("result", date, run_id, settings.journal_dir),
                             force=args.force)
            if args.job in ("relay", "game", "all"):
                land_relays(date, game_ids, settings=settings, sink=sink, client=client,
                            journal=Journal("relay", date, run_id, settings.journal_dir),
                            force=args.force)
        if args.job in ("community", "all"):
            land_community(date, settings=settings, sink=sink, client=client,
                           journal=Journal("community", date, run_id, settings.journal_dir),
                           force=args.force, cap=args.cap, concurrency=args.concurrency,
                           sources=args.source.split(",") if args.source else None,
                           incremental=args.incremental,
                           min_recommend=args.min_recommend, view_factor=args.view_factor)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
