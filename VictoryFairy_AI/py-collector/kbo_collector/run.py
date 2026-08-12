import argparse
import logging
import random
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import date as date_cls
from datetime import datetime, timedelta, timezone

from . import (community, dimensions, fetch, game_records, kbo_register, kbo_schedule,
               kbo_trade, keys, naver)
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


def _fetch_list_page_refs(target, url, *, settings, client, journal, today, sleep):
    """Fetch+parse one list page, tolerating an intermittent failure.

    Re-attempts the same page with a growing pause (e.g. FMKorea's 430 rate-limit)
    and treats a 200-with-zero-rows page as a rate-limit/interstitial (cool down &
    retry, not the end of the board). Returns the parsed refs, or None once
    `community_list_retries` is exhausted -- a persistent block or the genuine end
    of the listing -- recording a single 'list-fail' journal entry in that case.
    Shared by the date-filtered daily walk and the range backfill walk.
    """
    source = target["source"]
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
            return parsed
        # 200 but zero post rows is usually a rate-limit / interstitial page,
        # not the real end of the board -> cool down, then retry before believing it.
        last_err = "empty page (0 post rows)"
        sleep(settings.rate_limit_cooldown_s + random.uniform(0, settings.retry_backoff_base))
    journal.record(status="list-fail", source=source, item_id=url, error=str(last_err)[:200])
    return None


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
        refs = _fetch_list_page_refs(target, url, settings=settings, client=client,
                                     journal=journal, today=today, sleep=sleep)
        if refs is None:
            break  # persistent list block, or the genuine end of listing
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
    # 배치가 인기 신호를 조금이라도 제공하면(어느 행이든 recommend/views 값 있음),
    # 값이 빠진 행은 0으로 간주해 임계와 비교한다 — FMKorea 추천란 빈칸(None)은
    # 추천 ~0이라 min_recommend에 미달해 걸러진다. 배치 전체가 신호가 없을 때만
    # (모든 값이 None인 소스/픽스처) 판단 불가로 보고 그대로 둔다.
    has_rec = any(r.recommend is not None for r in refs)
    has_view = any(r.views is not None for r in refs)
    kept = []
    for r in refs:
        if not has_rec and not has_view:
            kept.append(r)
            continue
        rec = r.recommend if r.recommend is not None else 0
        by_rec = min_recommend > 0 and rec >= min_recommend
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


def land_community_range(start, end, *, settings, sink, client, journal, force=False,
                         sleep=time.sleep, today=None, concurrency=None, sources=None,
                         min_recommend=None, view_factor=None) -> int:
    """[start, end] 날짜 구간 백필: 날짜순(최신->과거) 리스트 walk **한 번**으로 훑는다.

    날짜별로 land_community(단일 date)를 반복 호출하면 뒤 날짜일수록 앞 날짜를 매번
    재fetch해 O(N^2)가 되고 FMKorea의 430 rate-limit을 유발한다. 그래서 [start, end]를
    한 번의 연속 walk로 처리한다(리스트는 date-ordered, 최신->과거 정렬 전제):
      - post_date > end        -> 아직 범위보다 최신 -> skip(계속 탐색)
      - start <= post_date <= end -> 인기 필터 통과분을 글의 post_date별 키로 적재
      - post_date < start      -> 이후는 전부 더 오래됨 -> walk 종료
    각 글은 keys.community_key(source, post_date, post_id)로 적재되고, sink.exists로
    이미 적재된 글은 skip(멱등: 재실행 안전). 디테일 fetch/적재는 _land_one_detail을
    date=post_date로 그대로 재사용한다. community_max_pages backstop,
    community_list_retries, rate_limit_cooldown, _polite 지터 딜레이도 재사용.
    인기 필터(_filter_popular)는 페이지 단위로 적용한다(view arm의 평균은 페이지별).
    FMKorea는 조회수가 없어 recommend arm(--min-recommend)만 유효하며, 그 경우 그룹핑과
    무관하게 결과가 동일하다.
    """
    run_id = journal.run_id
    today = today or _kst_today()
    if concurrency is None:
        concurrency = settings.community_concurrency
    if min_recommend is None:
        min_recommend = settings.community_min_recommend
    if view_factor is None:
        view_factor = settings.community_view_factor
    workers = max(1, concurrency)
    only = {s.upper() for s in sources} if sources else None
    jlock = threading.Lock()
    log = logging.getLogger("kbo_collector.community")
    landed = 0
    landed_keys: list[str] = []
    dead: list[str] = []
    for target in load_targets(settings.targets_file):
        source = target["source"]
        team = target.get("team")
        if only and source.upper() not in only:
            continue  # --source filter
        # One continuous newest->oldest walk. List paging stays serial: the
        # date early-stop depends on page order (detail fetches parallelize).
        stop = False
        for page in range(1, settings.community_max_pages + 1):
            url = _page_url(target["url"], page)
            refs = _fetch_list_page_refs(target, url, settings=settings, client=client,
                                         journal=journal, today=today, sleep=sleep)
            if refs is None:
                break  # persistent list block, or the genuine end of listing
            in_range: list = []
            for ref in refs:
                if ref.post_date is None or ref.post_date > end:
                    continue  # unknown/newer than the range -> keep looking
                if ref.post_date < start:
                    stop = True  # date-ordered: everything after is older -> stop
                    break
                in_range.append(ref)  # start <= post_date <= end
            kept = _filter_popular(in_range, min_recommend, view_factor)
            if len(kept) != len(in_range):
                journal.record(status="filtered", source=source,
                               item_id=f"{source}-popular", bytes=len(kept))
            journal.record(status="page", source=source, item_id=f"{source}-p{page}",
                           bytes=len(kept))
            with ThreadPoolExecutor(max_workers=workers) as ex:
                futures = [
                    ex.submit(_land_one_detail, ref, source=source, team=team,
                              date=ref.post_date, settings=settings, sink=sink,
                              client=client, run_id=run_id, journal=journal,
                              jlock=jlock, force=force, sleep=sleep)
                    for ref in kept
                ]
                for fut in as_completed(futures):  # accumulate on the main thread
                    status, key, dead_item = fut.result()
                    if status == "ok":
                        landed += 1
                        landed_keys.append(key)
                    elif status == "dead":
                        dead.append(dead_item)
            if stop:
                break
            _polite(sleep, settings)  # jittered polite delay between list pages
        log.info("%s %s: range %s..%s landed=%d", source, team or "", start, end, landed)
    # One manifest for the whole range (date param -> "<start>_<end>" so it never
    # collides with a single-date community manifest).
    _write_manifest(sink, "community", f"{start}_{end}", run_id, landed_keys, dead)
    return landed


def land_registrations(date=None, *, settings, db, client,
                       teams=dimensions.TEAM_CODES,
                       fetch_html=None, current=None, fetch_trades=None,
                       trade_days=7) -> list[str]:
    """KBO 공식 1군 등록명단 -> players upsert + registrations 일별 스냅샷.

    이후 이동현황(Trade.aspx)을 보조 반영한다: 등록명단이 못 잡는 갭(미등록
    선수의 트레이드/개명/등번호 변경)만 메우는 역할이라, 실패해도 잡은 성공이다.
    """
    log = logging.getLogger("registrations")
    fetch_html = fetch_html or kbo_register.fetch_register_html
    current = current or kbo_register.current_date
    fetch_trades = fetch_trades or kbo_trade.fetch_trades
    snapshot_date = date or current(settings, client)  # 'YYYY-MM-DD'
    date_compact = snapshot_date.replace("-", "")

    team_ids = db.upsert_teams(dimensions.TEAMS)  # FK 충족 위해 팀 시드 먼저
    synced: list[str] = []
    for code in teams:
        try:
            html = fetch_html(code, date_compact, settings=settings, client=client)
            rows = kbo_register.parse_register(html)
        except Exception:
            continue  # 팀 실패: 스킵
        db.upsert_roster_players(rows, team_ids[code])
        db.upsert_registrations(snapshot_date, rows, team_ids[code])
        synced.append(code)

    try:
        cutoff = (date_cls.fromisoformat(snapshot_date)
                  - timedelta(days=trade_days)).isoformat()
        recent = [t for t in fetch_trades(snapshot_date[:4], settings=settings,
                                          client=client) if t.date >= cutoff]
        by_name = {t.name: team_ids[t.team_code] for t in dimensions.TEAMS
                   if t.team_code in team_ids}
        applied = db.apply_trades(recent, by_name)
        log.info("trades: %d recent rows, applied %s", len(recent), applied or {})
    except Exception:
        log.warning("trades sync failed (registrations already landed)", exc_info=True)
    return synced


def land_game_records(date, *, settings, db, client, team_ids=None) -> tuple[list[str], list[str]]:
    """하루치 완료 경기 record를 운영 MySQL(games/game_lineups)에 적재.

    반환: (적재 성공 game_id, 실패 game_id).
    """
    log = logging.getLogger("records")
    url = game_records.schedule_url(settings, date)
    resp = fetch.fetch(client, url, settings=settings, referer=settings.naver_referer)
    finished = game_records.list_finished_games(resp.json())
    team_ids = team_ids or db.upsert_teams(dimensions.TEAMS)
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
            player_map = db.resolve_players(game.players, team_ids)
            status = "DRAW" if game.winner == "draw" else "FINISHED"
            game_pk = db.upsert_game(
                game, team_ids=team_ids,
                stadium_id=db.stadium_id(game.stadium),
                status_id=db.status_id(status))
            lineups = game_records.build_lineups(game)
            db.upsert_lineups(game_pk, lineups, player_map, team_ids)
            # 경기 전 preview 로 깔아둔 선발이 직전 교체됐으면 박스스코어에 없는
            # 유령 행으로 남는다 — 확정 적재 직후 그 차집합을 걷어낸다.
            ghosts = db.delete_lineups_except(
                game_pk, [player_map[r.pcode] for r in lineups if r.pcode in player_map])
            if ghosts:
                log.info("%s: 유령 라인업 %d행 정리", gid, ghosts)
            db.upsert_batting(game_pk, game.batting, player_map)
            db.upsert_pitching(game_pk, game.pitching, player_map)
            loaded.append(gid)
        except Exception as exc:  # 한 경기 실패가 백필 전체를 막지 않도록
            log.warning("record fail %s: %s", gid, exc)
            failed.append(gid)
    log.info("%s: loaded=%d failed=%d", date, len(loaded), len(failed))
    return loaded, failed


def _scheduled_game_stadium(settings, client, game, status, log):
    """경기 전 구장 이름 (얻지 못하면 None).

    스케줄 목록 응답에는 구장 필드가 아예 없어 경기 상세를 한 번 더 부른다
    (land_results 가 쓰는 것과 같은 엔드포인트). SCHEDULED 일 때만 부르는 이유:
    경기가 시작되면 records 잡이 박스스코어에서 구장을 확정 적재하므로 중복이고,
    라이브 10분 주기에 얹히면 얻을 것 없이 호출량만 늘기 때문.
    """
    if status != "SCHEDULED":
        return None
    try:
        resp = fetch.fetch(client, naver.result_url(settings, game["gameId"]),
                           settings=settings, referer=settings.naver_referer)
        return ((resp.json().get("result") or {}).get("game") or {}).get("stadium") or None
    except Exception as exc:  # 구장은 부가 정보 — 실패해도 일정 적재까지 막지 않는다
        log.warning("stadium fetch fail %s: %s", game.get("gameId"), exc)
        return None


LINEUP_PENDING_STATUSES = ("SCHEDULED", "IN_PROGRESS")


def _land_preview_lineups(settings, db, client, pending, team_ids, log) -> int:
    """아직 라인업이 안 찬 경기의 preview 선발 공시를 game_lineups 에 적재.

    `pending` 은 (game_id, game_pk) 목록. 이미 완성된 경기는 DB 판정으로 걸러
    preview 호출 자체를 하지 않는다 — 그날 경기가 다 공시되고 나면 이 단계의
    외부 호출은 0 이 되므로 1분 폴링에서도 비용이 붙지 않는다.

    한 경기의 실패가 나머지 경기를 막지 않도록 경기 단위로 격리한다. 이 단계
    전체가 실패해도 호출자의 상태 동기화는 이미 끝나 있다.
    """
    done = db.lineup_done_games([pk for _, pk in pending])
    landed = 0
    for gid, pk in pending:
        if pk in done:
            continue  # 이미 적재 완료 -> preview 호출 자체를 건너뛴다
        try:
            resp = fetch.fetch(client, naver.preview_url(settings, gid),
                               settings=settings, referer=settings.naver_referer)
            rows, refs = game_records.parse_preview_lineups(resp.json())
            if not rows:
                continue  # 아직 미공시 -> 다음 폴링에서 재시도
            db.upsert_lineups(pk, rows, db.resolve_players(refs, team_ids), team_ids)
            landed += 1
            log.info("lineup landed %s: %d rows", gid, len(rows))
        except Exception as exc:  # 한 경기 실패가 나머지 라인업·잡을 막지 않도록
            log.warning("preview lineup fail %s: %s", gid, exc)
    return landed


def _sync_games_for_date(settings, db, client, date, team_ids, log, live_window=True) -> int:
    """하루치 경기를 games 에 동기화.

    `live_window`(단일 날짜 호출 = live 룰) 일 때만 선발 라인업까지 따라간다.
    선적재(morning/nightly, 오늘~+N일)에서는 켜지 않는다 — 미래 경기의 preview 엔
    라인업이 있을 리 없어 매 실행마다 날짜 수만큼 헛호출이 된다.
    """
    resp = fetch.fetch(client, game_records.schedule_url(settings, date),
                       settings=settings, referer=settings.naver_referer)
    games = game_records.list_kbo_games(resp.json())
    # 구장은 한 번 채우면 변하지 않는다 — 아는 경기는 상세 API 를 다시 부르지 않는다.
    known_stadium = db.games_with_stadium([g.get("gameId") for g in games])
    synced, skipped, failed = 0, 0, 0
    pending: list[tuple[str, int]] = []
    for g in games:
        status = game_records.map_status(g)
        if status is None:
            log.warning("unknown status %s: %s", g.get("gameId"), g.get("statusCode"))
            skipped += 1
            continue
        # 경기 하나의 실패가 그날 나머지 경기를 막지 않게 격리한다. 격리가 없으면
        # 예외가 job_games_sync_range 의 **날짜 단위** 핸들러까지 올라가, 한 경기
        # 때문에 그 날짜의 뒤쪽 경기가 통째로 안 들어간다. 이 잡은 1분마다 도는
        # 멱등 upsert 라, 실패한 경기는 다음 폴링에서 자연히 회수된다.
        #
        # DB 제약 위반이 대표적이다 — games 의 CHECK(ck_games_current_inning 등)는
        # 우리 코드가 아니라 DB 가 강제하므로, 파서 상한(INNING_MAX)과 DB 상한이
        # 어긋나는 순간(예: DB CHECK 를 올리기 전에 수집기를 먼저 올린 배포)
        # 여기로 떨어진다.
        try:
            live_or_done = status in ("IN_PROGRESS", "FINISHED", "DRAW")
            dt = (g.get("gameDateTime") or "").replace("T", " ") or f"{date} 00:00:00"
            stadium = (None if g.get("gameId") in known_stadium
                       else _scheduled_game_stadium(settings, client, g, status, log))
            # 이닝은 진행 중일 때만 채운다 — 그 밖의 상태는 None 이 그대로 나가 NULL 로
            # 덮인다. statusInfo 는 경기가 끝나도 마지막 이닝("9회말")을 그대로 들고
            # 있어서(강우콜드면 "6회말") 상태로 거르지 않으면 종료 경기까지 값이 박힌다.
            inning, inning_half = (
                game_records.parse_inning(g.get("statusInfo"))
                if status == "IN_PROGRESS" else (None, None))
            if status == "IN_PROGRESS" and inning is None:
                # 진행 중인데 이닝이 안 읽힌 경우만 남긴다: 미지 포맷이거나 상한 초과다.
                # 이닝이 없어도 상태·점수 동기화는 그대로 진행한다.
                log.warning("이닝 파싱 실패 %s: statusInfo=%r",
                            g.get("gameId"), g.get("statusInfo"))
            game_pk = db.sync_game(
                naver_game_id=g["gameId"], game_dt=dt,
                home_team_id=team_ids[g["homeTeamCode"]],
                away_team_id=team_ids[g["awayTeamCode"]],
                home_score=g.get("homeTeamScore") if live_or_done else None,
                away_score=g.get("awayTeamScore") if live_or_done else None,
                status_id=db.status_id(status),
                stadium_id=db.stadium_id(stadium),
                current_inning=inning, inning_half=inning_half)
            synced += 1
            if not live_window:
                continue
            if status in LINEUP_PENDING_STATUSES:
                # 끝난 경기는 제외 — 확정 라인업은 records 잡이 박스스코어로 적재한다.
                pending.append((g["gameId"], game_pk))
            elif status == "CANCELED":
                # 공시 뒤 취소된 경기의 preview 라인업을 걷어낸다(records 가 안 도는 경로).
                purged = db.delete_lineups_if_unplayed(game_pk)
                if purged:
                    log.info("%s 취소: 라인업 %d행 정리", g["gameId"], purged)
        except Exception as exc:
            failed += 1
            log.warning("경기 동기화 실패 %s: %s", g.get("gameId"), exc)
    lineups = _land_preview_lineups(settings, db, client, pending, team_ids, log) if pending else 0
    log.info("%s: synced=%d skipped=%d failed=%d lineups=%d",
             date, synced, skipped, failed, lineups)
    return synced


def job_games_sync(settings, db, date):
    """당일 KBO 경기 전부의 상태를 games 테이블에 동기화 (취소·예정 포함)."""
    return job_games_sync_range(settings, db, date, date)


def job_games_sync_range(settings, db, start, end, sleep=time.sleep) -> int:
    """[start, end] 각 날짜의 KBO 경기를 games 에 동기화하고 총 건수 반환.

    오늘~+N일로 부르면 아직 열리지 않은 경기가 SCHEDULED 행으로 미리 깔린다 —
    이 잡에서는 일정 적재와 상태 동기화가 같은 upsert 다. 점수는 LIVE/RESULT
    에서만 채운다: SCHEDULED/CANCELED 의 0-0 은 껍데기라 NULL 로 적재해야
    미시작 경기가 0:0 무승부처럼 보이지 않는다.

    단일 날짜 호출(start==end)은 EventBridge 의 live 룰(days 없음)이 오는 길이라
    '당일 폴링'으로, 구간 호출은 morning/nightly 의 '선적재'로 취급한다 — 무엇을
    더 받아올지가 갈리는 근거는 _sync_games_for_date 참고.
    """
    log = logging.getLogger("games_sync")
    team_ids = db.upsert_teams(dimensions.TEAMS)
    d0, d1 = date_cls.fromisoformat(start), date_cls.fromisoformat(end)
    live_window = d0 == d1
    total = 0
    with fetch.build_client(settings) as client:
        d = d0
        while d <= d1:
            day = d.isoformat()
            try:
                total += _sync_games_for_date(settings, db, client, day, team_ids, log,
                                              live_window=live_window)
            except Exception as exc:  # 하루가 막혀도 나머지 날짜는 적재한다
                log.warning("games_sync fail %s: %s", day, exc)
            d += timedelta(days=1)
            if d <= d1:
                sleep(settings.fetch_delay_ms / 1000)
    return total


def _cancel_reason_months(date_str: str) -> list[str]:
    """훑을 달 목록: 기준일의 달 + 사흘 전의 달.

    대개 같은 달이라 호출 1회고, 월초 사흘만 2회가 된다 — 월말 취소분이 달을
    넘겨 조회에서 빠지는 걸 막는 최소한의 여유다.
    """
    d = date_cls.fromisoformat(date_str)
    return sorted({d.strftime("%Y-%m"), (d - timedelta(days=3)).strftime("%Y-%m")})


def job_cancel_reasons(settings, db, date=None, months=None) -> int:
    """KBO 공식 일정표의 취소 사유를 games.cancel_reason 에 반영, **바뀐** 행 수 반환.

    네이버에는 사유가 없어서(취소를 "경기취소" 로만 준다) 이 잡만 KBO 를 긁는다.
    games_sync 에 얹지 않고 따로 둔 이유는 KBO 응답이 월 단위이기 때문 — 날짜
    루프에 넣으면 같은 달을 날짜 수만큼 반복해서 받게 된다.

    반환값은 "새로 채우거나 사유가 달라진 행"이다. 이미 같은 사유가 적힌 행은 세지
    않으므로 **평상시 재실행은 0 이 정상**이고, 0 이 아니면 그날 새로 취소된 경기가
    있었다는 뜻이다. 매일 같은 수가 찍히면 멱등성이 깨진 신호로 읽으면 된다.
    """
    log = logging.getLogger("cancel_reasons")
    months = months or _cancel_reason_months(date or _kst_today())
    team_ids = db.upsert_teams(dimensions.TEAMS)
    total = 0
    with fetch.build_client(settings) as client:
        for ym in months:
            season, month = ym.split("-")
            try:
                rows = kbo_schedule.fetch_month(season, month, settings=settings, client=client)
            except Exception as exc:  # 한 달이 막혀도 나머지 달은 반영한다
                log.warning("kbo schedule fetch fail %s: %s", ym, exc)
                continue
            cancelled = kbo_schedule.cancelled_rows(rows)
            updated = 0
            for r in cancelled:
                home = team_ids.get(r["home_code"])
                away = team_ids.get(r["away_code"])
                if home is None or away is None:
                    continue
                updated += db.set_cancel_reason(
                    date=r["date"], home_team_id=home, away_team_id=away, reason=r["note"])
            log.info("%s: cancelled=%d changed=%d", ym, len(cancelled), updated)
            total += updated
    return total


def land_game_records_range(start, end, *, settings, db, client, sleep=time.sleep) -> dict:
    """[start, end] 날짜 구간 백필. teams 시드 후 일자별 반복."""
    team_ids = db.upsert_teams(dimensions.TEAMS)
    d0 = date_cls.fromisoformat(start)
    d1 = date_cls.fromisoformat(end)
    loaded, failed = [], []
    d = d0
    while d <= d1:
        lo, fa = land_game_records(d.isoformat(), settings=settings, db=db,
                                   client=client, team_ids=team_ids)
        loaded += lo
        failed += fa
        d += timedelta(days=1)
        if d <= d1:
            sleep(settings.fetch_delay_ms / 1000)
    return {"loaded": loaded, "failed": failed}


# --------------------------------------------------------------------------- CLI
def main(argv=None) -> int:
    parser = argparse.ArgumentParser(prog="kbo_collector")
    parser.add_argument("job", choices=["schedule", "result", "relay", "game",
                                        "community", "all", "teams", "registrations",
                                        "records", "games_sync", "cancel_reasons",
                                        "collect", "export"])
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
        from .sources import base as source_base
        src = source_base.get_source(args.target or "")
        db = None
        if getattr(src, "needs_db", True):
            from .db import DbSink
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
            if db is not None:
                db.close()
        return 0

    if args.job == "export":
        from .exports import exporter
        db = None
        if (args.target or "") not in exporter.DB_FREE:
            from .db import DbSink
            db = DbSink(settings)
        try:
            n = exporter.export(args.target or "", settings=settings, db=db,
                                sink=S3RawSink(settings), date=args.date)
            logging.getLogger("export").info("%s: exported=%d", args.target, n)
        finally:
            if db is not None:
                db.close()
        return 0

    if args.job in ("teams", "registrations", "records", "games_sync", "cancel_reasons"):
        from .db import DbSink
        db = DbSink(settings)
        try:
            if args.job == "cancel_reasons":
                # job_cancel_reasons 도 클라이언트를 자체 관리한다(KBO 세션 쿠키를
                # 선발급받아야 해서 호출부와 수명을 맞출 이유가 없다).
                job_cancel_reasons(settings, db, date=args.date)
            elif args.job == "games_sync":
                # job_games_sync_range 는 fetch 클라이언트를 자체 관리한다(Lambda
                # 핸들러가 settings/db/구간만으로 직접 호출하는 것과 동일 경로).
                # records 와 같은 --from/--to 로 구간(예: 오늘~+7일)을 받는다.
                start = args.from_date or date
                end = args.to_date or start
                job_games_sync_range(settings, db, start, end)
            else:
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
            sources = args.source.split(",") if args.source else None
            if args.job == "community" and (args.from_date or args.to_date):
                # Date-range backfill: one continuous newest->oldest walk over
                # [from, to]. Needs a date-ordered target file (order != popular),
                # e.g. config/targets.fmkorea.backfill.yaml.
                start = args.from_date or args.to_date
                end = args.to_date or args.from_date
                land_community_range(
                    start, end, settings=settings, sink=sink, client=client,
                    journal=Journal("community", f"{start}_{end}", run_id, settings.journal_dir),
                    force=args.force, concurrency=args.concurrency, sources=sources,
                    min_recommend=args.min_recommend, view_factor=args.view_factor)
            else:
                land_community(date, settings=settings, sink=sink, client=client,
                               journal=Journal("community", date, run_id, settings.journal_dir),
                               force=args.force, cap=args.cap, concurrency=args.concurrency,
                               sources=sources, incremental=args.incremental,
                               min_recommend=args.min_recommend, view_factor=args.view_factor)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
