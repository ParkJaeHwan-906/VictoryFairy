import json
from pathlib import Path

import httpx
import respx

from kbo_collector import fetch, run
from kbo_collector.journal import Journal
from kbo_collector.sink import S3RawSink

FIX = Path(__file__).parent / "fixtures"


def _journal(settings, job, tmp_path):
    return Journal(job, "2026-07-10", "run-test", str(tmp_path))


@respx.mock
def test_land_schedule_lands_raw_and_returns_gameids(settings, s3_bucket, tmp_path):
    body = (FIX / "naver" / "schedule.json").read_text(encoding="utf-8")
    respx.get(url__startswith="https://api-gw.sports.naver.com/schedule/games?").mock(
        return_value=httpx.Response(200, text=body,
                                    headers={"content-type": "application/json"})
    )
    sink = S3RawSink(settings)
    with fetch.build_client(settings) as client:
        gids = run.land_schedule("2026-07-10", settings=settings, sink=sink, client=client,
                                 journal=_journal(settings, "schedule", tmp_path))
    assert gids == ["20260710LGOB02026", "20260710HTSK02026"]
    # raw JSON landed byte-for-byte under a self-describing key (no opaque hash)
    listed = sink.client.list_objects_v2(Bucket=s3_bucket, Prefix="raw-json/schedule/2026-07-10/")
    assert listed["KeyCount"] == 1
    assert listed["Contents"][0]["Key"] == "raw-json/schedule/2026-07-10/schedule.json"


@respx.mock
def test_land_schedule_writes_manifest_and_object_metadata(settings, s3_bucket, tmp_path):
    body = (FIX / "naver" / "schedule.json").read_text(encoding="utf-8")
    respx.get(url__startswith="https://api-gw.sports.naver.com/schedule/games?").mock(
        return_value=httpx.Response(200, text=body,
                                    headers={"content-type": "application/json"}))
    sink = S3RawSink(settings)
    with fetch.build_client(settings) as client:
        run.land_schedule("2026-07-10", settings=settings, sink=sink, client=client,
                          journal=Journal("schedule", "2026-07-10", "run-xyz", str(tmp_path)))
    # run traceability lives in S3 user metadata, NOT in the (idempotent) key
    head = sink.client.head_object(
        Bucket=s3_bucket, Key="raw-json/schedule/2026-07-10/schedule.json")
    assert head["Metadata"]["run-id"] == "run-xyz"
    assert head["Metadata"]["job"] == "schedule"
    # a per-run manifest indexes what this run landed
    man = sink.client.get_object(
        Bucket=s3_bucket, Key="manifests/schedule/2026-07-10/run-xyz.json")
    manifest = json.loads(man["Body"].read().decode("utf-8"))
    assert manifest["run_id"] == "run-xyz"
    assert manifest["landed"] == 1
    assert manifest["keys"] == ["raw-json/schedule/2026-07-10/schedule.json"]


@respx.mock
def test_land_results_checkpoint_skips_existing(settings, s3_bucket, tmp_path):
    respx.get(url__startswith="https://api-gw.sports.naver.com/schedule/games/").mock(
        return_value=httpx.Response(200, text='{"result":"raw"}')
    )
    sink = S3RawSink(settings)
    with fetch.build_client(settings) as client:
        n1 = run.land_results("2026-07-10", ["gid1", "gid2"], settings=settings, sink=sink,
                              client=client, journal=_journal(settings, "result", tmp_path))
        assert n1 == 2
        # second run: both already exist -> landed count 0
        n2 = run.land_results("2026-07-10", ["gid1", "gid2"], settings=settings, sink=sink,
                              client=client, journal=_journal(settings, "result", tmp_path))
        assert n2 == 0


@respx.mock
def test_land_results_deadletters_failed_item_and_continues(settings, s3_bucket, tmp_path):
    def handler(request):
        if request.url.path.endswith("/good"):
            return httpx.Response(200, text='{"ok":1}')
        return httpx.Response(500)
    respx.get(url__startswith="https://api-gw.sports.naver.com/schedule/games/").mock(
        side_effect=handler)
    sink = S3RawSink(settings)
    with fetch.build_client(settings) as client:
        n = run.land_results("2026-07-10", ["bad", "good"], settings=settings, sink=sink,
                             client=client, journal=_journal(settings, "result", tmp_path))
    assert n == 1  # only "good" landed
    dl = sink.client.get_object(Bucket=s3_bucket, Key="dead-letter/result/2026-07-10/bad.json")
    assert "500" in dl["Body"].read().decode()


@respx.mock
def test_land_relays_stops_at_empty_inning(settings, s3_bucket, tmp_path):
    inning = (FIX / "naver" / "relay_inning.json").read_text(encoding="utf-8")
    empty = (FIX / "naver" / "relay_empty.json").read_text(encoding="utf-8")

    def handler(request):
        n = int(request.url.params["inning"])
        return httpx.Response(200, text=inning if n <= 3 else empty)
    respx.get(url__startswith="https://api-gw.sports.naver.com/schedule/games/g1/relay").mock(
        side_effect=handler)
    sink = S3RawSink(settings)
    with fetch.build_client(settings) as client:
        landed = run.land_relays("2026-07-10", ["g1"], settings=settings, sink=sink,
                                 client=client, journal=_journal(settings, "relay", tmp_path))
    assert landed == 3  # innings 1..3 landed, 4 is empty -> stop
    listed = sink.client.list_objects_v2(Bucket=s3_bucket, Prefix="raw-json/relay/g1/")
    assert listed["KeyCount"] == 3


@respx.mock
def test_land_community_end_to_end(settings, s3_bucket, tmp_path, monkeypatch):
    # single FMKorea target so the test is deterministic
    tfile = tmp_path / "targets.yaml"
    tfile.write_text(
        'targets:\n  - { source: FMKOREA, url: "https://www.fmkorea.com/list" }\n',
        encoding="utf-8")
    monkeypatch.setattr(settings, "targets_file", str(tfile))
    monkeypatch.setattr(settings, "community_max_pages", 1)  # one mocked list page

    list_html = (FIX / "community" / "fmkorea_list.html").read_text(encoding="utf-8")
    detail_html = (FIX / "community" / "fmkorea_detail.html").read_text(encoding="utf-8")
    respx.get("https://www.fmkorea.com/list").mock(return_value=httpx.Response(200, text=list_html))
    respx.get(url__regex=r"https://www\.fmkorea\.com/\d+$").mock(
        return_value=httpx.Response(200, text=detail_html))

    sink = S3RawSink(settings)
    with fetch.build_client(settings) as client:
        n = run.land_community("2026-07-10", settings=settings, sink=sink, client=client,
                               journal=_journal(settings, "community", tmp_path),
                               sleep=lambda _s: None, today="2026-07-14")  # no real delay in tests
    assert n == 2  # two non-notice posts
    obj = sink.client.get_object(Bucket=s3_bucket, Key="community/fmkorea/2026-07-10/8523491.json")
    post = json.loads(obj["Body"].read().decode("utf-8"))
    assert post["source"] == "FMKOREA"
    assert post["schemaVersion"] == 2


@respx.mock
def test_land_community_concurrent_lands_all(settings, s3_bucket, tmp_path, monkeypatch):
    # concurrency>1 must still land every post exactly once (thread-safe accumulation).
    tfile = tmp_path / "targets.yaml"
    tfile.write_text(
        'targets:\n  - { source: FMKOREA, url: "https://www.fmkorea.com/list" }\n',
        encoding="utf-8")
    monkeypatch.setattr(settings, "targets_file", str(tfile))
    monkeypatch.setattr(settings, "community_max_pages", 1)

    list_html = (FIX / "community" / "fmkorea_list.html").read_text(encoding="utf-8")
    detail_html = (FIX / "community" / "fmkorea_detail.html").read_text(encoding="utf-8")
    respx.get("https://www.fmkorea.com/list").mock(return_value=httpx.Response(200, text=list_html))
    respx.get(url__regex=r"https://www\.fmkorea\.com/\d+$").mock(
        return_value=httpx.Response(200, text=detail_html))

    sink = S3RawSink(settings)
    with fetch.build_client(settings) as client:
        n = run.land_community("2026-07-10", settings=settings, sink=sink, client=client,
                               journal=_journal(settings, "community", tmp_path),
                               sleep=lambda _s: None, today="2026-07-14", concurrency=3)
    assert n == 2
    keys = {o["Key"] for o in sink.client.list_objects_v2(
        Bucket=s3_bucket, Prefix="community/fmkorea/2026-07-10/").get("Contents", [])}
    assert keys == {
        "community/fmkorea/2026-07-10/8523491.json",
        "community/fmkorea/2026-07-10/8523492.json",
    }


@respx.mock
def test_land_community_contains_single_post_parse_failure(settings, s3_bucket, tmp_path, monkeypatch):
    # one bad post must not abort the whole crawl: it goes to dead-letter,
    # the other post still lands, and the run count reflects only the good one.
    tfile = tmp_path / "targets.yaml"
    tfile.write_text(
        'targets:\n  - { source: FMKOREA, url: "https://www.fmkorea.com/list" }\n',
        encoding="utf-8")
    monkeypatch.setattr(settings, "targets_file", str(tfile))
    monkeypatch.setattr(settings, "community_max_pages", 1)  # one mocked list page

    list_html = (FIX / "community" / "fmkorea_list.html").read_text(encoding="utf-8")
    detail_html = (FIX / "community" / "fmkorea_detail.html").read_text(encoding="utf-8")
    respx.get("https://www.fmkorea.com/list").mock(return_value=httpx.Response(200, text=list_html))
    respx.get(url__regex=r"https://www\.fmkorea\.com/\d+$").mock(
        return_value=httpx.Response(200, text=detail_html))

    import kbo_collector.community as community_mod
    real = community_mod.parse_fmkorea_detail

    def boom(html, ref, salt, top_n, crawled_at):
        if ref.post_id == "8523491":
            raise RuntimeError("bad live DOM")
        return real(html, ref, salt, top_n, crawled_at)

    monkeypatch.setattr("kbo_collector.run.community.parse_fmkorea_detail", boom)

    sink = S3RawSink(settings)
    with fetch.build_client(settings) as client:
        n = run.land_community("2026-07-10", settings=settings, sink=sink, client=client,
                               journal=_journal(settings, "community", tmp_path),
                               sleep=lambda _s: None, today="2026-07-14")
    assert n == 1  # only the good post landed
    obj = sink.client.get_object(Bucket=s3_bucket, Key="community/fmkorea/2026-07-10/8523492.json")
    post = json.loads(obj["Body"].read().decode("utf-8"))
    assert post["source"] == "FMKOREA"

    dl = sink.client.get_object(
        Bucket=s3_bucket, Key="dead-letter/community/2026-07-10/FMKOREA-8523491.json")
    assert "bad live DOM" in dl["Body"].read().decode()


@respx.mock
def test_land_community_list_parse_failure_records_list_fail_and_continues(
        settings, s3_bucket, tmp_path, monkeypatch):
    # a target whose list parse raises must not abort the whole community run
    tfile = tmp_path / "targets.yaml"
    tfile.write_text(
        'targets:\n  - { source: FMKOREA, url: "https://www.fmkorea.com/list" }\n',
        encoding="utf-8")
    monkeypatch.setattr(settings, "targets_file", str(tfile))
    monkeypatch.setattr(settings, "community_max_pages", 1)  # one mocked list page

    list_html = (FIX / "community" / "fmkorea_list.html").read_text(encoding="utf-8")
    respx.get("https://www.fmkorea.com/list").mock(return_value=httpx.Response(200, text=list_html))

    def boom(_html):
        raise RuntimeError("unexpected live DOM shape")

    monkeypatch.setattr("kbo_collector.run.community.parse_fmkorea_list", boom)

    sink = S3RawSink(settings)
    with fetch.build_client(settings) as client:
        n = run.land_community("2026-07-10", settings=settings, sink=sink, client=client,
                               journal=_journal(settings, "community", tmp_path),
                               sleep=lambda _s: None, today="2026-07-14")
    assert n == 0  # nothing landed, but no exception propagated


# --------------------------------------------------------------------------- community range backfill
def _fm_list_page(rows):
    """FMKorea list page HTML. rows: (post_id, 'YYYY.MM.DD', recommend|None)."""
    trs = ""
    for pid, d, rec in rows:
        rec_td = f'<td class="m_no m_no_voted">{rec}</td>' if rec is not None else ""
        trs += (f'<tr><td class="title"><a href="/{pid}">t{pid}</a></td>'
                f'<td class="time">{d}</td>{rec_td}</tr>')
    return f'<table class="bd_lst"><tbody>{trs}</tbody></table>'


def _fm_range_target(settings, tmp_path, monkeypatch):
    # single date-ordered FMKorea target so the backfill walk is deterministic
    tfile = tmp_path / "targets.yaml"
    tfile.write_text(
        'targets:\n  - { source: FMKOREA, order: date, url: "https://www.fmkorea.com/list" }\n',
        encoding="utf-8")
    monkeypatch.setattr(settings, "targets_file", str(tfile))
    monkeypatch.setattr(settings, "community_max_pages", 10)


@respx.mock
def test_land_community_range_walks_filters_and_stops(settings, s3_bucket, tmp_path, monkeypatch):
    # One continuous newest->oldest walk over [2026-03-28, 2026-07-18]:
    #  - 2026-07-20 is newer than `to`   -> skipped (keep walking)
    #  - 2026-07-09 recommend 5 (<30)    -> popularity-filtered out
    #  - 2026-07-10 / 2026-05-01         -> landed under their OWN post_date key
    #  - 2026-03-01 is older than `from` -> ends the walk (page 3 never fetched)
    _fm_range_target(settings, tmp_path, monkeypatch)
    pages = {
        1: _fm_list_page([("900", "2026.07.20", 99), ("710", "2026.07.10", 50),
                          ("709", "2026.07.09", 5)]),
        2: _fm_list_page([("501", "2026.05.01", 40), ("301", "2026.03.01", 80),
                          ("201", "2026.02.01", 90)]),
    }
    fetched_pages: list[int] = []

    def list_handler(request):
        page = int(request.url.params["page"])
        fetched_pages.append(page)
        return httpx.Response(200, text=pages.get(page, _fm_list_page([])))

    detail_html = (FIX / "community" / "fmkorea_detail.html").read_text(encoding="utf-8")
    respx.get(url__startswith="https://www.fmkorea.com/list").mock(side_effect=list_handler)
    respx.get(url__regex=r"https://www\.fmkorea\.com/\d+$").mock(
        return_value=httpx.Response(200, text=detail_html))

    sink = S3RawSink(settings)
    with fetch.build_client(settings) as client:
        n = run.land_community_range(
            "2026-03-28", "2026-07-18", settings=settings, sink=sink, client=client,
            journal=_journal(settings, "community", tmp_path),
            sleep=lambda _s: None, today="2026-07-18", min_recommend=30, view_factor=0)
    assert n == 2                       # only 710 and 501
    assert fetched_pages == [1, 2]      # stopped at page 2's older-than-`from` row; page 3 untouched
    keys = {o["Key"] for o in sink.client.list_objects_v2(
        Bucket=s3_bucket, Prefix="community/fmkorea/").get("Contents", [])}
    assert keys == {
        "community/fmkorea/2026-07-10/710.json",   # in-range, keyed by its own post_date
        "community/fmkorea/2026-05-01/501.json",
    }  # 07-20 (newer), 07-09 (below threshold), 03-01/02-01 (older) all absent


@respx.mock
def test_land_community_range_idempotent_rerun_lands_nothing(settings, s3_bucket, tmp_path, monkeypatch):
    # re-running the same range must write nothing new (sink.exists checkpoint).
    _fm_range_target(settings, tmp_path, monkeypatch)
    pages = {1: _fm_list_page([("710", "2026.07.10", 50), ("301", "2026.03.01", 80)])}

    def list_handler(request):
        return httpx.Response(200, text=pages.get(int(request.url.params["page"]), _fm_list_page([])))

    detail_html = (FIX / "community" / "fmkorea_detail.html").read_text(encoding="utf-8")
    respx.get(url__startswith="https://www.fmkorea.com/list").mock(side_effect=list_handler)
    respx.get(url__regex=r"https://www\.fmkorea\.com/\d+$").mock(
        return_value=httpx.Response(200, text=detail_html))

    sink = S3RawSink(settings)
    with fetch.build_client(settings) as client:
        n1 = run.land_community_range(
            "2026-03-28", "2026-07-18", settings=settings, sink=sink, client=client,
            journal=_journal(settings, "community", tmp_path),
            sleep=lambda _s: None, today="2026-07-18", min_recommend=30, view_factor=0)
        n2 = run.land_community_range(
            "2026-03-28", "2026-07-18", settings=settings, sink=sink, client=client,
            journal=_journal(settings, "community", tmp_path),
            sleep=lambda _s: None, today="2026-07-18", min_recommend=30, view_factor=0)
    assert n1 == 1  # 710 landed on the first pass
    assert n2 == 0  # already in S3 -> skipped, re-run lands nothing


def _stub_main_io(monkeypatch):
    monkeypatch.setattr(run, "S3RawSink", lambda settings: object())
    import kbo_collector.fetch as fetch_mod
    monkeypatch.setattr(fetch_mod, "build_client",
                        lambda settings: __import__("contextlib").nullcontext(object()))


def test_main_community_with_from_to_calls_range_not_single(monkeypatch):
    calls = []
    monkeypatch.setattr(run, "land_community_range",
                        lambda *a, **k: calls.append(("range", a, k)))
    monkeypatch.setattr(run, "land_community", lambda *a, **k: calls.append(("single", a, k)))
    _stub_main_io(monkeypatch)
    rc = run.main(["community", "--from", "2026-03-28", "--to", "2026-07-18",
                   "--min-recommend", "30", "--concurrency", "1"])
    assert rc == 0
    assert [c[0] for c in calls] == ["range"]           # range only; single not called
    _, a, k = calls[0]
    assert a == ("2026-03-28", "2026-07-18")            # start, end passed positionally
    assert k["min_recommend"] == 30 and k["concurrency"] == 1


def test_main_community_without_from_to_calls_single(monkeypatch):
    # no --from/--to -> unchanged single-date land_community (regression guard).
    calls = []
    monkeypatch.setattr(run, "land_community_range", lambda *a, **k: calls.append("range"))
    monkeypatch.setattr(run, "land_community", lambda *a, **k: calls.append("single"))
    _stub_main_io(monkeypatch)
    rc = run.main(["community", "--date", "2026-07-10", "--min-recommend", "30"])
    assert rc == 0
    assert calls == ["single"]


def test_main_game_job_runs_schedule_result_relay_not_community(monkeypatch):
    calls = []
    monkeypatch.setattr(run, "land_schedule", lambda *a, **k: (calls.append("schedule") or ["g1"]))
    monkeypatch.setattr(run, "land_results", lambda *a, **k: calls.append("result"))
    monkeypatch.setattr(run, "land_relays", lambda *a, **k: calls.append("relay"))
    monkeypatch.setattr(run, "land_community", lambda *a, **k: calls.append("community"))
    monkeypatch.setattr(run, "S3RawSink", lambda settings: object())
    import kbo_collector.fetch as fetch_mod
    monkeypatch.setattr(fetch_mod, "build_client",
                        lambda settings: __import__("contextlib").nullcontext(object()))
    rc = run.main(["game", "--date", "2026-07-10"])
    assert rc == 0
    assert calls == ["schedule", "result", "relay"]  # no community


class _RecordingDb:
    """운영 스키마 DbSink 흉내: 팀 upsert 는 code->id 맵을 돌려준다."""
    def __init__(self): self.calls = []
    def upsert_teams(self, teams):
        self.calls.append(("teams", len(teams)))
        return {t.team_code: i + 1 for i, t in enumerate(teams)}
    def upsert_roster_players(self, players, team_id):
        self.calls.append(("players", team_id, len(players)))
    def upsert_registrations(self, snapshot_date, players, team_id):
        self.calls.append(("registrations", snapshot_date, team_id, len(players)))
        return len(players)
    def apply_trades(self, trades, team_ids_by_name):
        self.calls.append(("trades", [t.player_name for t in trades],
                           dict(team_ids_by_name)))
        return {}


def _no_trades(season, *, settings, client):
    return []


def test_land_registrations_current_date_upserts_by_team_pk(monkeypatch, settings):
    from kbo_collector import dimensions, kbo_register
    monkeypatch.setattr(kbo_register, "current_date", lambda s, c: "2026-07-13")
    def fake_fetch(code, date_compact, *, settings, client):
        assert date_compact == "20260713"
        return f"<html>{code}</html>"
    monkeypatch.setattr(kbo_register, "fetch_register_html", fake_fetch)
    monkeypatch.setattr(kbo_register, "parse_register",
                        lambda html: [dimensions.PlayerRow("p1", "N", "1", "투수", "우투우타", None, None, None)])
    db = _RecordingDb()
    synced = run.land_registrations(None, settings=settings, db=db, client=object(),
                                    teams=["OB", "LG"], fetch_trades=_no_trades)
    assert synced == ["OB", "LG"]
    # dimensions.TEAMS 순서상 OB=1, LG=2 -> team_id 로 upsert
    assert ("players", 1, 1) in db.calls and ("players", 2, 1) in db.calls
    # 같은 명단이 일별 스냅샷으로도 남는다
    assert ("registrations", "2026-07-13", 1, 1) in db.calls
    assert ("registrations", "2026-07-13", 2, 1) in db.calls


def test_land_registrations_backfill_uses_given_date(monkeypatch, settings):
    from kbo_collector import dimensions, kbo_register
    monkeypatch.setattr(kbo_register, "current_date",
                        lambda s, c: (_ for _ in ()).throw(AssertionError("no site call")))
    def fake_fetch(code, dc, *, settings, client):
        assert dc == "20260501"  # 지정 날짜 사용, 사이트 현재일 조회 안 함
        return "<html></html>"
    monkeypatch.setattr(kbo_register, "fetch_register_html", fake_fetch)
    monkeypatch.setattr(kbo_register, "parse_register",
                        lambda html: [dimensions.PlayerRow("p1", "N", "1", "투수", "우투우타", None, None, None)])
    db = _RecordingDb()
    run.land_registrations("2026-05-01", settings=settings, db=db, client=object(),
                           teams=["LG"], fetch_trades=_no_trades)
    assert any(c[0] == "players" for c in db.calls)


class _RecordingRecordsDb:
    """운영 스키마 DbSink 흉내: land_game_records 배선(호출 순서/인자) 검증용."""
    def __init__(self, player_map, team_ids):
        self.calls = []
        self._player_map = player_map
        self._team_ids = team_ids

    def upsert_teams(self, teams):
        return self._team_ids

    def resolve_players(self, refs, team_ids):
        return self._player_map

    def stadium_id(self, name):
        return 9

    def status_id(self, name):
        return 7

    def upsert_game(self, game, *, team_ids, stadium_id, status_id):
        self.calls.append(("game", game.game_id))
        return 501

    def upsert_lineups(self, game_pk, lineups, player_map, team_ids):
        self.calls.append(("lineups", game_pk, player_map))

    def upsert_batting(self, game_pk, rows, player_map):
        self.calls.append(("batting", game_pk, rows, player_map))

    def upsert_pitching(self, game_pk, rows, player_map):
        self.calls.append(("pitching", game_pk, rows, player_map))


def test_land_game_records_upserts_batting_and_pitching_after_lineups(monkeypatch, settings):
    # records 잡: db.upsert_lineups 바로 다음 줄에서 upsert_batting/upsert_pitching이
    # 호출돼야 한다. player_map에 없는 pcode("PX")는 run.py가 필터링하지 않고 그대로
    # DbSink에 넘긴다 — 실제 스킵은 DbSink.upsert_batting/pitching 쪽 책임(db 단 테스트로 검증).
    from kbo_collector import dimensions
    from kbo_collector.game_records import GameRow, BattingRow, PitchingRow

    fixed_game = GameRow(
        game_id="g1", game_date="2026-07-10", game_type="regular", round_no=1,
        stadium="잠실", start_time="18:30", away_team_code="LG", home_team_code="OB",
        away_score=3, home_score=5, away_hits=8, home_hits=10,
        away_errors=0, home_errors=1, away_bb=2, home_bb=3,
        winner="home", away_starter_pcode="P9", home_starter_pcode="P8",
        inn_scores={"away": [], "home": []},
        pitching=[
            PitchingRow(pcode="P1", team_code="OB", is_home=True, seq=0, decision="W",
                       ip_display="6", ip_outs=18, batters_faced=25, at_bats=22, hits=5,
                       runs=2, earned_runs=2, home_runs=1, walks_hbp=1, strikeouts=7),
            PitchingRow(pcode="PX", team_code="LG", is_home=False, seq=0, decision="L",
                       ip_display="5", ip_outs=15, batters_faced=20, at_bats=18, hits=6,
                       runs=3, earned_runs=3, home_runs=0, walks_hbp=2, strikeouts=4),
        ],
        batting=[
            BattingRow(pcode="P2", team_code="OB", is_home=True, bat_order=3, position="중",
                      at_bats=4, runs=1, hits=2, home_runs=1, rbi=2, walks=0, strikeouts=1,
                      stolen_bases=0),
            BattingRow(pcode="PX", team_code="LG", is_home=False, bat_order=4, position="포",
                      at_bats=3, runs=0, hits=1, home_runs=0, rbi=0, walks=1, strikeouts=0,
                      stolen_bases=0),
        ],
        players=[],
    )
    player_map = {"P1": 11, "P2": 12}  # "PX" 미해소

    class _FakeResp:
        def json(self):
            return {"result": {"recordData": {"dummy": True}}}

    monkeypatch.setattr(run.fetch, "fetch", lambda *a, **k: _FakeResp())
    monkeypatch.setattr(run.game_records, "list_finished_games", lambda js: [{"gameId": "g1"}])
    monkeypatch.setattr(run.game_records, "parse_record", lambda gid, record: fixed_game)

    db = _RecordingRecordsDb(player_map, team_ids={"OB": 1, "LG": 2})
    loaded, failed = run.land_game_records("2026-07-10", settings=settings, db=db, client=object())

    assert loaded == ["g1"] and failed == []
    assert [c[0] for c in db.calls] == ["game", "lineups", "batting", "pitching"]
    _, game_pk, rows, pm = next(c for c in db.calls if c[0] == "batting")
    assert game_pk == 501 and rows == fixed_game.batting and pm == player_map
    _, game_pk2, rows2, pm2 = next(c for c in db.calls if c[0] == "pitching")
    assert game_pk2 == 501 and rows2 == fixed_game.pitching and pm2 == player_map


class _RecordingSyncDb:
    """운영 스키마 DbSink 흉내: job_games_sync 배선(호출 인자) 검증용."""
    def __init__(self, team_ids, done=(), with_stadium=()):
        self.calls = []
        self.lineup_calls = []
        self.purged = []
        self.upsert_teams_calls = 0
        self._done = set(done)
        self._with_stadium = set(with_stadium)
        self._team_ids = team_ids
        self._status_ids = {"SCHEDULED": 1, "IN_PROGRESS": 2, "FINISHED": 3,
                            "DRAW": 4, "CANCELED": 5}
        self._stadium_ids = {}

    def upsert_teams(self, teams):
        self.upsert_teams_calls += 1
        return self._team_ids

    def status_id(self, name):
        return self._status_ids[name]

    def stadium_id(self, name):
        # DbSink 와 같은 계약: 이름이 비면 None (INSERT 시 NULL 로 들어가 COALESCE 로 보존)
        if not name:
            return None
        return self._stadium_ids.setdefault(name, 900 + len(self._stadium_ids))

    def sync_game(self, *, naver_game_id, game_dt, home_team_id, away_team_id,
                  home_score, away_score, status_id, stadium_id=None):
        self.calls.append(dict(
            naver_game_id=naver_game_id, game_dt=game_dt, home_team_id=home_team_id,
            away_team_id=away_team_id, home_score=home_score, away_score=away_score,
            status_id=status_id, stadium_id=stadium_id))
        return len(self.calls)  # 실제 sync_game 처럼 game PK 를 돌려준다

    def lineup_done_games(self, game_pks):
        return {pk for pk in game_pks if pk in self._done}

    def resolve_players(self, refs, team_ids):
        return {r.pcode: 900 + i for i, r in enumerate(refs)}

    def upsert_lineups(self, game_pk, lineups, player_map, team_ids):
        self.lineup_calls.append((game_pk, lineups))


def _games_sync_schedule_games():
    return [
        {"gameId": "finished", "categoryId": "kbo", "statusCode": "RESULT", "cancel": False,
         "homeTeamCode": "OB", "awayTeamCode": "LG",
         "homeTeamScore": 7, "awayTeamScore": 3, "gameDateTime": "2026-07-10T18:30:00"},
        {"gameId": "draw", "categoryId": "kbo", "statusCode": "RESULT", "cancel": False,
         "homeTeamCode": "OB", "awayTeamCode": "LG",
         "homeTeamScore": 4, "awayTeamScore": 4, "gameDateTime": "2026-07-10T18:30:00"},
        {"gameId": "live", "categoryId": "kbo", "statusCode": "LIVE", "cancel": False,
         "homeTeamCode": "OB", "awayTeamCode": "LG",
         "homeTeamScore": 2, "awayTeamScore": 1, "gameDateTime": "2026-07-10T18:30:00"},
        {"gameId": "scheduled", "categoryId": "kbo", "statusCode": "BEFORE", "cancel": False,
         "homeTeamCode": "OB", "awayTeamCode": "LG",
         "homeTeamScore": 0, "awayTeamScore": 0, "gameDateTime": "2026-07-10T18:30:00"},
        {"gameId": "cancelled", "categoryId": "kbo", "statusCode": "BEFORE", "cancel": True,
         "winner": "DRAW", "homeTeamCode": "OB", "awayTeamCode": "LG",
         "homeTeamScore": 0, "awayTeamScore": 0, "gameDateTime": "2026-07-10T18:30:00"},
        {"gameId": "no_dt", "categoryId": "kbo", "statusCode": "LIVE", "cancel": False,
         "homeTeamCode": "OB", "awayTeamCode": "LG",
         "homeTeamScore": 1, "awayTeamScore": 0},  # gameDateTime 키 자체가 결측
        # 실재하지 않는 코드여야 한다 — "READY" 는 당일 미시작 상태로 실재하며
        # 이제 SCHEDULED 로 매핑된다(test_map_status_ready_is_scheduled 참고).
        {"gameId": "unknown", "categoryId": "kbo", "statusCode": "NOT_A_REAL_STATUS",
         "cancel": False, "homeTeamCode": "OB", "awayTeamCode": "LG"},
    ]


class _FakeScheduleResp:
    def json(self):
        return {"result": {"games": _games_sync_schedule_games()}}


def test_job_games_sync_scores_live_or_done_only_and_skips_unknown(monkeypatch, settings, caplog):
    import contextlib
    monkeypatch.setattr(run.fetch, "build_client", lambda settings: contextlib.nullcontext(object()))
    monkeypatch.setattr(run.fetch, "fetch", lambda *a, **k: _FakeScheduleResp())
    db = _RecordingSyncDb(team_ids={"OB": 1, "LG": 2})

    with caplog.at_level("WARNING", logger="games_sync"):
        synced = run.job_games_sync(settings, db, "2026-07-10")

    assert synced == 6  # "unknown" 은 skip
    by_id = {c["naver_game_id"]: c for c in db.calls}
    assert set(by_id) == {"finished", "draw", "live", "scheduled", "cancelled", "no_dt"}

    assert by_id["finished"]["status_id"] == 3
    assert (by_id["finished"]["home_score"], by_id["finished"]["away_score"]) == (7, 3)
    # gameDateTime "2026-07-10T18:30:00" -> "T"를 공백으로 치환해 DATETIME 리터럴로
    assert by_id["finished"]["game_dt"] == "2026-07-10 18:30:00"

    assert by_id["draw"]["status_id"] == 4
    assert (by_id["draw"]["home_score"], by_id["draw"]["away_score"]) == (4, 4)

    assert by_id["live"]["status_id"] == 2
    assert (by_id["live"]["home_score"], by_id["live"]["away_score"]) == (2, 1)

    # SCHEDULED/CANCELED 의 0-0 은 껍데기 -> None 으로 적재(0:0 무승부처럼 보이면 안 됨)
    assert by_id["scheduled"]["status_id"] == 1
    assert (by_id["scheduled"]["home_score"], by_id["scheduled"]["away_score"]) == (None, None)

    assert by_id["cancelled"]["status_id"] == 5
    assert (by_id["cancelled"]["home_score"], by_id["cancelled"]["away_score"]) == (None, None)

    # gameDateTime 결측 -> f"{date} 00:00:00" 폴백
    assert by_id["no_dt"]["game_dt"] == "2026-07-10 00:00:00"

    assert by_id["finished"]["home_team_id"] == 1 and by_id["finished"]["away_team_id"] == 2

    assert "unknown status" in caplog.text and "NOT_A_REAL_STATUS" in caplog.text


def test_job_games_sync_game_dt_falls_back_for_empty_string_datetime(monkeypatch, settings):
    # gameDateTime이 빈 문자열("")로 오는 경우도 결측과 동일하게 취급해 date 자정으로
    # 폴백해야 한다 (None 결측과는 별개 분기: `(g.get(...) or "").replace(...) or fallback`).
    import contextlib

    class _EmptyDtResp:
        def json(self):
            return {"result": {"games": [
                {"gameId": "empty_dt", "categoryId": "kbo", "statusCode": "LIVE",
                 "cancel": False, "homeTeamCode": "OB", "awayTeamCode": "LG",
                 "homeTeamScore": 0, "awayTeamScore": 0, "gameDateTime": ""},
            ]}}

    monkeypatch.setattr(run.fetch, "build_client", lambda settings: contextlib.nullcontext(object()))
    monkeypatch.setattr(run.fetch, "fetch", lambda *a, **k: _EmptyDtResp())
    db = _RecordingSyncDb(team_ids={"OB": 1, "LG": 2})

    run.job_games_sync(settings, db, "2026-07-11")

    assert db.calls[0]["game_dt"] == "2026-07-11 00:00:00"


class _FakeGameDetailResp:
    def __init__(self, stadium):
        self._stadium = stadium

    def json(self):
        return {"result": {"game": {"stadium": self._stadium}}}


def _dispatching_fetch(seen_urls, stadium="잠실"):
    """스케줄 목록 URL 과 경기 상세 URL 을 구분해 응답한다.

    schedule_url 은 '/schedule/games?fields=...' 쿼리형, result_url 은
    '/schedule/games/{gameId}' 경로형이라 '?' 유무로 갈린다.
    """
    def _fetch(client, url, **kwargs):
        seen_urls.append(url)
        return _FakeScheduleResp() if "?" in url else _FakeGameDetailResp(stadium)
    return _fetch


def test_job_games_sync_fetches_stadium_only_for_scheduled(monkeypatch, settings):
    # 스케줄 목록엔 구장이 없어 경기 상세를 한 번 더 부르는데, 시작한 경기의 구장은
    # records 잡이 박스스코어로 확정하므로 SCHEDULED 일 때만 불러야 한다.
    import contextlib
    urls = []
    monkeypatch.setattr(run.fetch, "build_client", lambda settings: contextlib.nullcontext(object()))
    monkeypatch.setattr(run.fetch, "fetch", _dispatching_fetch(urls))
    db = _RecordingSyncDb(team_ids={"OB": 1, "LG": 2})

    run.job_games_sync(settings, db, "2026-07-10")

    # preview(라인업)도 쿼리스트링이 없으므로 구장 조회만 따로 센다.
    detail_urls = [u for u in urls if "?" not in u and not u.endswith("/preview")]
    assert len(detail_urls) == 1 and detail_urls[0].endswith("/scheduled")

    by_id = {c["naver_game_id"]: c for c in db.calls}
    assert by_id["scheduled"]["stadium_id"] == 900
    # 나머지는 None -> GAME_SYNC_UPSERT 의 COALESCE 가 기존 구장을 지킨다
    for gid in ("finished", "live", "draw", "cancelled", "no_dt"):
        assert by_id[gid]["stadium_id"] is None


def test_job_games_sync_stadium_failure_still_lands_the_game(monkeypatch, settings, caplog):
    import contextlib

    def _fetch(client, url, **kwargs):
        if "?" in url:
            return _FakeScheduleResp()
        raise RuntimeError("naver 500")

    monkeypatch.setattr(run.fetch, "build_client", lambda settings: contextlib.nullcontext(object()))
    monkeypatch.setattr(run.fetch, "fetch", _fetch)
    db = _RecordingSyncDb(team_ids={"OB": 1, "LG": 2})

    with caplog.at_level("WARNING", logger="games_sync"):
        synced = run.job_games_sync(settings, db, "2026-07-10")

    # 구장은 부가 정보 — 못 얻어도 일정 자체는 그대로 적재된다
    assert synced == 6
    assert next(c for c in db.calls if c["naver_game_id"] == "scheduled")["stadium_id"] is None
    assert "stadium fetch fail" in caplog.text


def test_job_games_sync_range_walks_each_date_and_seeds_teams_once(monkeypatch, settings):
    import contextlib
    dates = []

    def _fetch(client, url, **kwargs):
        if "?" not in url:
            return _FakeGameDetailResp("잠실")
        dates.append(url.split("fromDate=")[1].split("&")[0])
        return _FakeScheduleResp()

    monkeypatch.setattr(run.fetch, "build_client", lambda settings: contextlib.nullcontext(object()))
    monkeypatch.setattr(run.fetch, "fetch", _fetch)
    db = _RecordingSyncDb(team_ids={"OB": 1, "LG": 2})

    total = run.job_games_sync_range(settings, db, "2026-08-11", "2026-08-14",
                                     sleep=lambda _s: None)

    assert dates == ["2026-08-11", "2026-08-12", "2026-08-13", "2026-08-14"]
    assert total == 6 * 4
    assert db.upsert_teams_calls == 1  # 날짜마다 반복 시드하지 않는다


def test_job_games_sync_range_continues_after_one_bad_date(monkeypatch, settings, caplog):
    import contextlib

    def _fetch(client, url, **kwargs):
        if "?" not in url:
            return _FakeGameDetailResp("잠실")
        if "fromDate=2026-08-12" in url:
            raise RuntimeError("naver 503")
        return _FakeScheduleResp()

    monkeypatch.setattr(run.fetch, "build_client", lambda settings: contextlib.nullcontext(object()))
    monkeypatch.setattr(run.fetch, "fetch", _fetch)
    db = _RecordingSyncDb(team_ids={"OB": 1, "LG": 2})

    with caplog.at_level("WARNING", logger="games_sync"):
        total = run.job_games_sync_range(settings, db, "2026-08-11", "2026-08-13",
                                         sleep=lambda _s: None)

    assert total == 6 * 2  # 8/12 만 빠지고 앞뒤 날짜는 적재된다
    assert "games_sync fail 2026-08-12" in caplog.text


class _RecordingCancelDb:
    def __init__(self, team_ids, changed=1):
        self.team_ids = team_ids
        self.calls = []
        self._changed = changed

    def upsert_teams(self, teams):
        return self.team_ids

    def set_cancel_reason(self, *, date, home_team_id, away_team_id, reason):
        self.calls.append((date, home_team_id, away_team_id, reason))
        return self._changed


def _kbo_row(date, away, home, note="-"):
    return {"date": date, "away_code": away, "home_code": home,
            "stadium": "잠실", "note": note}


def test_cancel_reason_months_is_one_call_mid_month_and_two_at_month_start():
    # 월말 취소분이 달을 넘겨 빠지지 않게 사흘 여유를 둔다 — 그 대가는 월초 3일의 1회 추가 호출뿐.
    assert run._cancel_reason_months("2026-08-15") == ["2026-08"]
    assert run._cancel_reason_months("2026-08-02") == ["2026-07", "2026-08"]


def test_job_cancel_reasons_updates_only_cancelled_rows(monkeypatch, settings):
    import contextlib
    monkeypatch.setattr(run.fetch, "build_client",
                        lambda settings: contextlib.nullcontext(object()))
    monkeypatch.setattr(run.kbo_schedule, "fetch_month", lambda season, month, **k: [
        _kbo_row("2026-08-09", "HT", "LG"),                      # 정상 편성
        _kbo_row("2026-08-09", "LT", "KT", note="폭염취소"),
        _kbo_row("2026-08-09", "WO", "HH", note="우천취소"),
    ])
    db = _RecordingCancelDb(team_ids={"HT": 7, "LG": 3, "LT": 9, "KT": 4, "WO": 5, "HH": 6})

    total = run.job_cancel_reasons(settings, db, date="2026-08-15")

    assert db.calls == [
        ("2026-08-09", 4, 9, "폭염취소"),   # home=KT(4), away=LT(9)
        ("2026-08-09", 6, 5, "우천취소"),   # home=HH(6), away=WO(5)
    ]
    assert total == 2


def test_job_cancel_reasons_walks_each_month_and_sums(monkeypatch, settings):
    import contextlib
    seen = []

    def fake_fetch(season, month, **k):
        seen.append((season, month))
        return [_kbo_row(f"{season}-{month}-05", "LT", "KT", note="폭염취소")]

    monkeypatch.setattr(run.fetch, "build_client",
                        lambda settings: contextlib.nullcontext(object()))
    monkeypatch.setattr(run.kbo_schedule, "fetch_month", fake_fetch)
    db = _RecordingCancelDb(team_ids={"LT": 9, "KT": 4})

    total = run.job_cancel_reasons(settings, db, date="2026-08-02")

    assert seen == [("2026", "07"), ("2026", "08")]
    assert total == 2


def test_job_cancel_reasons_one_bad_month_does_not_stop_the_others(monkeypatch, settings, caplog):
    import contextlib

    def fake_fetch(season, month, **k):
        if month == "07":
            raise RuntimeError("kbo 503")
        return [_kbo_row("2026-08-05", "LT", "KT", note="폭염취소")]

    monkeypatch.setattr(run.fetch, "build_client",
                        lambda settings: contextlib.nullcontext(object()))
    monkeypatch.setattr(run.kbo_schedule, "fetch_month", fake_fetch)
    db = _RecordingCancelDb(team_ids={"LT": 9, "KT": 4})

    with caplog.at_level("WARNING", logger="cancel_reasons"):
        total = run.job_cancel_reasons(settings, db, date="2026-08-02")

    assert total == 1
    assert "kbo schedule fetch fail 2026-07" in caplog.text


def test_job_cancel_reasons_returns_zero_when_nothing_changed(monkeypatch, settings):
    # 평상시 재실행: 사유가 이미 같아 DB 가 아무 행도 안 바꾼다 -> 0 이 정상이다.
    # 여기서 매일 같은 수가 찍히면 멱등성이 깨진 신호다.
    import contextlib
    monkeypatch.setattr(run.fetch, "build_client",
                        lambda settings: contextlib.nullcontext(object()))
    monkeypatch.setattr(run.kbo_schedule, "fetch_month", lambda season, month, **k: [
        _kbo_row("2026-08-09", "LT", "KT", note="폭염취소"),
        _kbo_row("2026-08-09", "WO", "HH", note="폭염취소"),
    ])
    db = _RecordingCancelDb(team_ids={"LT": 9, "KT": 4, "WO": 5, "HH": 6}, changed=0)

    total = run.job_cancel_reasons(settings, db, date="2026-08-15")

    assert len(db.calls) == 2  # 조회·시도는 하되
    assert total == 0          # 바뀐 건 없다


def test_job_cancel_reasons_skips_rows_whose_team_is_not_seeded(monkeypatch, settings):
    import contextlib
    monkeypatch.setattr(run.fetch, "build_client",
                        lambda settings: contextlib.nullcontext(object()))
    monkeypatch.setattr(run.kbo_schedule, "fetch_month", lambda season, month, **k: [
        _kbo_row("2026-08-09", "LT", "KT", note="폭염취소"),
    ])
    db = _RecordingCancelDb(team_ids={"LT": 9})  # KT 미시드

    total = run.job_cancel_reasons(settings, db, date="2026-08-15")

    assert db.calls == [] and total == 0


def test_main_games_sync_lazily_creates_db_and_calls_job(monkeypatch, settings):
    calls = []

    class _FakeDbSink:
        def __init__(self, settings):
            calls.append(("db_created",))

        def close(self):
            calls.append(("db_closed",))

    monkeypatch.setattr("kbo_collector.db.DbSink", _FakeDbSink)

    def fake_job(settings, db, start, end):
        calls.append(("job", start, end))
        assert isinstance(db, _FakeDbSink)
        return 3

    monkeypatch.setattr(run, "job_games_sync_range", fake_job)
    rc = run.main(["games_sync", "--date", "2026-07-10"])
    assert rc == 0
    # --from/--to 미지정이면 그날 하루만 (start == end)
    assert calls == [("db_created",), ("job", "2026-07-10", "2026-07-10"), ("db_closed",)]


def test_main_games_sync_from_to_walks_range_like_records(monkeypatch, settings):
    class _FakeDbSink:
        def __init__(self, settings):
            pass

        def close(self):
            pass

    monkeypatch.setattr("kbo_collector.db.DbSink", _FakeDbSink)
    seen = {}

    def fake_job(settings, db, start, end):
        seen["range"] = (start, end)
        return 0

    monkeypatch.setattr(run, "job_games_sync_range", fake_job)
    rc = run.main(["games_sync", "--from", "2026-08-11", "--to", "2026-08-18"])
    assert rc == 0
    assert seen["range"] == ("2026-08-11", "2026-08-18")


def test_main_games_sync_defaults_date_to_today_like_records(monkeypatch, settings):
    # records 잡과 동일 규약: --date 미지정 시 UTC 오늘(_today()) 사용 (community 만 KST).
    class _FakeDbSink:
        def __init__(self, settings):
            pass

        def close(self):
            pass

    monkeypatch.setattr("kbo_collector.db.DbSink", _FakeDbSink)
    seen = {}

    def fake_job(settings, db, start, end):
        seen["range"] = (start, end)
        return 0

    monkeypatch.setattr(run, "job_games_sync_range", fake_job)
    monkeypatch.setattr(run, "_today", lambda: "2026-07-27")
    rc = run.main(["games_sync"])
    assert rc == 0
    assert seen["range"] == ("2026-07-27", "2026-07-27")


def test_land_registrations_failed_team_skipped(monkeypatch, settings):
    from kbo_collector import dimensions, kbo_register
    monkeypatch.setattr(kbo_register, "current_date", lambda s, c: "2026-07-13")
    def fake_fetch(code, dc, *, settings, client):
        if code == "OB":
            raise RuntimeError("kbo down")
        return "<html></html>"
    monkeypatch.setattr(kbo_register, "fetch_register_html", fake_fetch)
    monkeypatch.setattr(kbo_register, "parse_register",
                        lambda html: [dimensions.PlayerRow("p1", "N", "1", "투수", "우투우타", None, None, None)])
    db = _RecordingDb()
    synced = run.land_registrations(None, settings=settings, db=db, client=object(),
                                    teams=["LG", "OB"], fetch_trades=_no_trades)
    assert synced == ["LG"]  # OB 실패 -> 제외
    assert ("players", 2, 1) in db.calls  # LG(=id 2)만 적재


def test_land_registrations_applies_recent_trades_with_team_name_map(monkeypatch, settings):
    from kbo_collector import dimensions, kbo_register
    monkeypatch.setattr(kbo_register, "current_date", lambda s, c: "2026-08-04")
    monkeypatch.setattr(kbo_register, "fetch_register_html",
                        lambda code, dc, *, settings, client: "<html></html>")
    monkeypatch.setattr(kbo_register, "parse_register", lambda html: [])
    recent = dimensions.TradeRow("2026-08-01", "트레이드", "한화", "최근", "투수", "KIA→한화")
    old = dimensions.TradeRow("2026-05-01", "트레이드", "한화", "옛날", "투수", "KIA→한화")
    def fake_trades(season, *, settings, client):
        assert season == "2026"
        return [recent, old]
    db = _RecordingDb()
    run.land_registrations(None, settings=settings, db=db, client=object(),
                           teams=["OB", "LG"], fetch_trades=fake_trades)
    kind, names, by_name = next(c for c in db.calls if c[0] == "trades")
    assert names == ["최근"]  # trade_days(7일) 밖 행은 제외
    # 팀 짧은 이름 -> teams.id (dimensions.TEAMS 순서 기반 페이크 id)
    assert by_name["KIA"] == 6 and by_name["한화"] == 7 and len(by_name) == 10


def test_land_registrations_trade_failure_keeps_roster_success(monkeypatch, settings):
    from kbo_collector import kbo_register
    monkeypatch.setattr(kbo_register, "current_date", lambda s, c: "2026-08-04")
    monkeypatch.setattr(kbo_register, "fetch_register_html",
                        lambda code, dc, *, settings, client: "<html></html>")
    monkeypatch.setattr(kbo_register, "parse_register", lambda html: [])
    def boom(season, *, settings, client):
        raise RuntimeError("kbo down")
    db = _RecordingDb()
    synced = run.land_registrations(None, settings=settings, db=db, client=object(),
                                    teams=["LG"], fetch_trades=boom)
    assert synced == ["LG"]  # 이동현황 실패는 잡 실패가 아니다
    assert not any(c[0] == "trades" for c in db.calls)


# --------------------------------------------------------------------------- preview 라인업
def _preview_payload(away_code="LG", home_code="OB"):
    # 포지션 코드는 실제 preview 표기: 1=투수(선발), 나머지 9개가 타순 순서대로.
    _BATTER_POSITIONS = ("8", "9", "7", "0", "5", "3", "2", "4", "6")

    def team(prefix):
        return {"fullLineUp": [
            {"positionName": "선발투수", "playerName": f"{prefix}선발",
             "playerCode": f"{prefix}0", "position": "1"},
        ] + [
            {"positionName": "타자", "playerName": f"{prefix}타{i}",
             "playerCode": f"{prefix}{i}", "position": pos}
            for i, pos in enumerate(_BATTER_POSITIONS, start=1)
        ]}
    return {"result": {"previewData": {
        "gameInfo": {"aCode": away_code, "hCode": home_code},
        "awayTeamLineUp": team("A"), "homeTeamLineUp": team("H"),
    }}}


class _PreviewResp:
    def json(self):
        return _preview_payload()


def _lineup_fetch(seen_urls, fail_on=(), stadium="잠실"):
    """스케줄 / 경기 상세(구장) / preview 세 갈래를 구분해 응답한다."""
    def _fetch(client, url, **kwargs):
        seen_urls.append(url)
        if "?" in url:
            return _FakeScheduleResp()
        if url.endswith("/preview"):
            if any(f"/{gid}/preview" in url for gid in fail_on):
                raise RuntimeError("boom")
            return _PreviewResp()
        return _FakeGameDetailResp(stadium)
    return _fetch


def _previewed(urls):
    return [u.rsplit("/games/", 1)[1].split("/")[0] for u in urls if u.endswith("/preview")]


def test_job_games_sync_lands_preview_lineups_for_pending_games(monkeypatch, settings):
    # SCHEDULED/IN_PROGRESS 만 preview 를 본다 — 끝난 경기의 확정 라인업은 records 몫.
    import contextlib
    urls = []
    monkeypatch.setattr(run.fetch, "build_client", lambda settings: contextlib.nullcontext(object()))
    monkeypatch.setattr(run.fetch, "fetch", _lineup_fetch(urls))
    db = _RecordingSyncDb(team_ids={"OB": 1, "LG": 2})

    run.job_games_sync(settings, db, "2026-07-10")

    assert set(_previewed(urls)) == {"live", "scheduled", "no_dt"}
    # 팀당 선발투수 1 + 타순 9 = 10행, 양 팀 20행
    _, rows = db.lineup_calls[0]
    assert len(rows) == 20
    assert sorted(r.bat_order for r in rows if r.bat_order) == sorted(list(range(1, 10)) * 2)
    assert [r.bat_order for r in rows if r.position == "투"] == [None, None]


def test_job_games_sync_skips_preview_for_already_landed_games(monkeypatch, settings):
    # 요구사항: 라인업이 이미 적재된 경기는 그 작업만 빼고 나머지는 마저 돈다.
    # "빼는" 지점이 적재가 아니라 preview 호출 자체여야 1분 폴링에서도 싸다.
    import contextlib
    urls = []
    monkeypatch.setattr(run.fetch, "build_client", lambda settings: contextlib.nullcontext(object()))
    monkeypatch.setattr(run.fetch, "fetch", _lineup_fetch(urls))
    # sync_game 호출 순서대로 PK 가 1..6 이므로 live=3, scheduled=4, no_dt=6.
    db = _RecordingSyncDb(team_ids={"OB": 1, "LG": 2}, done={3, 4})

    synced = run.job_games_sync(settings, db, "2026-07-10")

    assert _previewed(urls) == ["no_dt"]
    assert synced == 6  # 상태 동기화는 그대로 6건 완주
    assert [pk for pk, _ in db.lineup_calls] == [6]


def test_job_games_sync_lineup_failure_does_not_break_status_sync(monkeypatch, settings, caplog):
    # 라인업이 통째로 터져도 상태 동기화 결과는 남아야 한다(단계 순서 + 경기 단위 격리).
    import contextlib
    urls = []
    monkeypatch.setattr(run.fetch, "build_client", lambda settings: contextlib.nullcontext(object()))
    monkeypatch.setattr(run.fetch, "fetch",
                        _lineup_fetch(urls, fail_on=("live", "scheduled", "no_dt")))
    db = _RecordingSyncDb(team_ids={"OB": 1, "LG": 2})

    with caplog.at_level("WARNING", logger="games_sync"):
        synced = run.job_games_sync(settings, db, "2026-07-10")

    assert synced == 6 and db.lineup_calls == []
    assert "preview lineup fail" in caplog.text
    assert len(_previewed(urls)) == 3  # 한 경기 실패가 나머지 시도를 막지 않는다


def test_job_games_sync_lookahead_range_does_not_fetch_previews(monkeypatch, settings):
    # 선적재(morning/nightly)는 미래 경기까지 훑는다 — preview 에 라인업이 있을 리 없어
    # 날짜 수만큼 헛호출이 되므로 라인업 단계는 당일 폴링에서만 돈다.
    import contextlib
    urls = []
    monkeypatch.setattr(run.fetch, "build_client", lambda settings: contextlib.nullcontext(object()))
    monkeypatch.setattr(run.fetch, "fetch", _lineup_fetch(urls))
    db = _RecordingSyncDb(team_ids={"OB": 1, "LG": 2})

    run.job_games_sync_range(settings, db, "2026-07-10", "2026-07-12", sleep=lambda s: None)

    assert _previewed(urls) == []
    assert db.lineup_calls == []
