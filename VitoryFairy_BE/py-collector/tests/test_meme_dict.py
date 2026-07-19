from pathlib import Path
from types import SimpleNamespace

import kbo_collector.sources  # noqa: F401
from kbo_collector.sources import base
from kbo_collector.sources.meme_dict import resolve_player_uid

FIXTURE = Path(__file__).parent / "fixtures" / "memes" / "memes.yaml"


class FakeDb:
    """name+team → uid 매핑을 흉내내는 fetch_all 스텁."""
    def __init__(self, rows_by_name):
        self.rows_by_name = rows_by_name

    def fetch_all(self, sql, params=()):
        return self.rows_by_name.get(params[0], [])


class FakeSink:
    def __init__(self):
        self.puts = []

    def put_json(self, key, obj, metadata=None):
        self.puts.append((key, obj))
        return 1


def test_resolve_uid_unique_found():
    db = FakeDb({"김도영": [(123,)]})
    assert resolve_player_uid(db, "김도영", "HT") == (123, None)


def test_resolve_uid_not_found_and_duplicate():
    db = FakeDb({"김도영": [], "이철수": [(1,), (2,)]})
    assert resolve_player_uid(db, "김도영", "HT") == (None, "not-found")
    assert resolve_player_uid(db, "이철수", "LG") == (None, "duplicate-name")


def test_collect_emits_envelopes_with_resolution():
    db = FakeDb({"김도영": [(123,)], "없는선수": []})
    sink = FakeSink()
    settings = SimpleNamespace(memes_file=str(FIXTURE))
    ctx = base.CollectContext(settings=settings, db=db, sink=sink)
    res = base.get_source("meme_dict").collect(ctx)

    assert res.loaded == 2 and res.failed == []
    by_id = {obj["docId"]: obj for _, obj in sink.puts}

    ok = by_id["player_meme:HT:김도영:월관보음"]
    assert ok["docType"] == "player_meme"
    assert ok["entities"]["playerUids"] == [123]
    assert ok["entities"]["teamCodes"] == ["HT"]
    assert "월관보음" in ok["content"] and "김도영" in ok["content"]
    assert "밈" in ok["tags"] and "별명" in ok["tags"]

    ghost = by_id["player_meme:LG:없는선수:유령밈"]
    assert ghost["entities"]["playerUids"] == []
    assert ghost["entities"]["unresolved"] == [
        {"kind": "player", "name": "없는선수", "reason": "not-found"}]


def test_s3_keys_under_question_source():
    db = FakeDb({"김도영": [(123,)], "없는선수": []})
    sink = FakeSink()
    settings = SimpleNamespace(memes_file=str(FIXTURE))
    base.get_source("meme_dict").collect(
        base.CollectContext(settings=settings, db=db, sink=sink))
    assert all(k.startswith("question-source/player_meme/") for k, _ in sink.puts)


def test_malformed_entry_is_isolated(tmp_path):
    bad = tmp_path / "memes.yaml"
    bad.write_text(
        "- memes:\n"
        "    - text: \"고아밈\"\n"
        "      origin: \"player 키 없음\"\n"
        "- player: { name: 김도영, team: HT }\n"
        "  memes:\n"
        "    - text: \"월관보음\"\n"
        "      origin: \"정상 항목\"\n"
        "      tags: [별명]\n",
        encoding="utf-8")
    db = FakeDb({"김도영": [(123,)]})
    sink = FakeSink()
    settings = SimpleNamespace(memes_file=str(bad))
    res = base.get_source("meme_dict").collect(
        base.CollectContext(settings=settings, db=db, sink=sink))
    assert res.loaded == 1          # 정상 항목은 적재됨
    assert len(res.failed) == 1     # 불량 항목은 failed로 격리


def test_db_error_is_isolated():
    class BoomDb:
        def fetch_all(self, sql, params=()):
            raise RuntimeError("db down")
    sink = FakeSink()
    settings = SimpleNamespace(memes_file=str(FIXTURE))
    res = base.get_source("meme_dict").collect(
        base.CollectContext(settings=settings, db=BoomDb(), sink=sink))
    assert res.loaded == 0
    assert len(res.failed) == 2     # 픽스처의 항목(선수) 2건 모두 failed로
    assert sink.puts == []
