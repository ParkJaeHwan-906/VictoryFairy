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
    def __init__(self): self.calls = []
    def upsert_teams(self, teams): self.calls.append(("teams", len(teams)))
    def upsert_players(self, players, code, date): self.calls.append(("players", code, len(players)))
    def insert_registrations(self, date, players, code): self.calls.append(("reg", code, date))
    def mark_not_first_team(self, code, date): self.calls.append(("mark", code, date))


def test_land_registrations_current_date_upserts_and_marks(monkeypatch, settings):
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
                                    teams=["LG", "OB"])
    assert synced == ["LG", "OB"]
    assert ("players", "LG", 1) in db.calls and ("reg", "OB", "2026-07-13") in db.calls
    # 사이트 현재일이므로 mark 수행
    assert ("mark", "LG", "2026-07-13") in db.calls


def test_land_registrations_backfill_does_not_mark(monkeypatch, settings):
    from kbo_collector import dimensions, kbo_register
    monkeypatch.setattr(kbo_register, "current_date", lambda s, c: "2026-07-13")
    monkeypatch.setattr(kbo_register, "fetch_register_html",
                        lambda code, dc, *, settings, client: "<html></html>")
    monkeypatch.setattr(kbo_register, "parse_register",
                        lambda html: [dimensions.PlayerRow("p1", "N", "1", "투수", "우투우타", None, None, None)])
    db = _RecordingDb()
    run.land_registrations("2026-05-01", settings=settings, db=db, client=object(), teams=["LG"])
    assert not any(c[0] == "mark" for c in db.calls)  # backfill -> no mark


def test_land_registrations_failed_team_skipped_not_marked(monkeypatch, settings):
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
                                    teams=["LG", "OB"])
    assert synced == ["LG"]  # OB 실패 -> 제외
    assert not any(c == ("mark", "OB", "2026-07-13") for c in db.calls)
