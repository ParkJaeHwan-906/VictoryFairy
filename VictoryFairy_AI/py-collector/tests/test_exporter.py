import json
from pathlib import Path
from types import SimpleNamespace

import pytest

import kbo_collector.sources  # noqa: F401
from kbo_collector.exports import exporter

FIXTURES = Path(__file__).parent / "fixtures"


class FakeDb:
    def __init__(self, results_by_sql_frag):
        self.frags = results_by_sql_frag

    def fetch_all(self, sql, params=()):
        for frag, rows in self.frags.items():
            if frag in sql:
                return rows
        return []


class FakeSink:
    def __init__(self):
        self.puts = []

    def put_json(self, key, obj, metadata=None):
        self.puts.append((key, obj))
        return 1


# (games.id, naver_game_id, 날짜, 시각, 구장, away 코드/이름, home 코드/이름,
#  away/home 점수, 상태명)
GAME_ROW = (55, "20260328HTSK02026", "2026-03-28", "14:00", "문학",
            "HT", "KIA", "SK", "SSG", 6, 7, "FINISHED")
DECISIONS = [("W", "김민"), ("L", "조상우"), ("S", None)]


def _db():
    return FakeDb({"FROM games": [GAME_ROW],
                   "FROM game_lineups": [(d, n) for d, n in DECISIONS if n]})


def test_games_sql_exports_only_finished_or_draw():
    # games_sync 가 만드는 SCHEDULED/IN_PROGRESS/CANCELED 행이 envelope 로
    # 새지 않게 하는 계약 — 점수 NULL 경기는 draw 오판정("무승부로 끝났다")된다.
    assert "gs.name IN ('FINISHED','DRAW')" in exporter._GAMES_SQL


def test_read_game_results_renders_content():
    envs = list(exporter.read_game_results(_db(), date="2026-03-28"))
    assert len(envs) == 1
    e = envs[0]
    assert e.doc_type == "game_result"
    assert e.doc_id == "game_result:20260328HTSK02026"
    assert e.entities["gameId"] == "20260328HTSK02026"
    assert e.entities["teamCodes"] == ["HT", "SK"]
    for frag in ("2026-03-28", "KIA", "SSG", "6", "7", "김민", "조상우"):
        assert frag in e.content
    assert "박스스코어" in e.tags


def test_export_writes_envelopes_to_s3():
    sink = FakeSink()
    n = exporter.export("game_result", settings=SimpleNamespace(), db=_db(),
                        sink=sink, date="2026-03-28")
    assert n == 1
    key, obj = sink.puts[0]
    assert key.startswith("question-source/game_result/")
    assert obj["envelopeVersion"] == 1 and obj["content"]


def test_export_partition_key_uses_specified_date_not_execution_date():
    # 리뷰 C1-1: 이전엔 date 인자와 무관하게 항상 실행일(UTC)을 파티션 키로 썼다 —
    # game_result처럼 date를 명시해 특정 날짜로 호출하는 잡도 오늘 파티션에 쌓여
    # 매일 실행마다 같은 데이터가 새 날짜 밑에 중복 적재됐다(시즌 통계 오염 원인).
    # date 인자가 있으면 그 날짜가 파티션 키가 되어야 한다.
    sink = FakeSink()
    n = exporter.export("game_result", settings=SimpleNamespace(), db=_db(),
                        sink=sink, date="2026-03-28")
    assert n == 1
    key, _ = sink.puts[0]
    assert key.startswith("question-source/game_result/2026-03-28/")


def test_export_unknown_doc_type_raises():
    with pytest.raises(KeyError, match="game_result"):
        exporter.export("no_such_doc", settings=None, db=None, sink=None)


def test_export_delegates_to_source_when_no_reader():
    # player_meme은 reader가 없고 meme_dict 소스가 doc_type을 소유 → collect 위임
    called = {}

    class StubSource:
        source_id = "meme_dict"
        doc_types = ("player_meme",)

        def collect(self, ctx):
            called["ctx"] = ctx
            return SimpleNamespace(loaded=3, failed=[])

    from kbo_collector.sources import base
    saved = dict(base.REGISTRY)
    base.REGISTRY.clear()
    base.REGISTRY["meme_dict"] = StubSource()
    try:
        n = exporter.export("player_meme", settings="S", db="D", sink="K")
        assert n == 3 and called["ctx"].db == "D"
    finally:
        base.REGISTRY.clear()
        base.REGISTRY.update(saved)


# (kbo_player_id, players.id, 이름, 팀 코드, 팀 이름)
PLAYER_ROW = ("53554", 123, "김민석", "LT", "롯데")


def test_read_player_profiles_renders_content():
    db = FakeDb({"FROM players": [PLAYER_ROW]})
    envs = list(exporter.read_player_profiles(db))
    e = envs[0]
    assert e.doc_id == "player_profile:53554"
    assert e.entities["playerUids"] == [123]
    assert e.entities["teamCodes"] == ["LT"]
    for frag in ("김민석", "롯데"):
        assert frag in e.content
    assert "프로필" in e.tags


class FakeS3Sink(FakeSink):
    def __init__(self, docs):
        super().__init__()
        self.docs = docs  # key -> RawPost dict

    def iter_keys(self, prefix):
        return iter([k for k in self.docs if k.startswith(prefix)])

    def get_json(self, key):
        return self.docs[key]


RAWPOST = {"schemaVersion": 2, "source": "DCINSIDE", "postExternalId": "111",
           "sourceUrl": "https://gall.dcinside.com/...&no=111",
           "title": "오늘 경기 미쳤다", "body": "9회말 역전 실화냐",
           "engagement": {"viewCount": 10, "likeCount": 2,
                          "dislikeCount": 0, "commentCount": 1},
           "topComments": [], "team": "DOOSAN",
           "crawledAt": "2026-07-11T10:00:00+00:00", "crawlerVersion": "community-v3"}


def test_read_community_posts_repackages():
    sink = FakeS3Sink({"community/dcinside/2026-07-11/111.json": RAWPOST})
    envs = list(exporter.read_community_posts(None, date="2026-07-11", sink=sink))
    e = envs[0]
    assert e.doc_id == "community_post:DCINSIDE:111"
    assert e.title == "오늘 경기 미쳤다"
    assert e.content.startswith("오늘 경기 미쳤다")   # 제목+본문 통과
    assert "9회말 역전 실화냐" in e.content
    assert {"커뮤니티", "여론"} <= set(e.tags) and "DOOSAN" in e.tags
    assert e.pii == {"masked": True}


def test_read_community_posts_requires_date():
    with pytest.raises(ValueError, match="date"):
        list(exporter.read_community_posts(None, sink=FakeS3Sink({})))


def test_export_skips_invalid_envelope_and_continues():
    from kbo_collector.exports.envelope import Envelope

    def bad_then_good(db, date=None, sink=None):
        yield Envelope(doc_id="d:bad", doc_type="stub_doc", source="s",
                       source_ref="r", collected_at="t", title="t", content="")
        yield Envelope(doc_id="d:good", doc_type="stub_doc", source="s",
                       source_ref="r", collected_at="t", title="t", content="ok")

    sink = FakeSink()
    exporter.READERS["stub_doc"] = bad_then_good
    try:
        n = exporter.export("stub_doc", settings=None, db=None, sink=sink)
    finally:
        del exporter.READERS["stub_doc"]
    assert n == 1                      # 불량 1건 skip, 정상 1건 적재
    assert [obj["docId"] for _, obj in sink.puts] == ["d:good"]


class FakeScheduleSink(FakeSink):
    def __init__(self, sched, key):
        super().__init__()
        self.sched, self.key = sched, key

    def exists(self, key):
        return key == self.key

    def get_json(self, key):
        assert key == self.key
        return self.sched


def _sched_sink(date="2026-07-31"):
    sched = json.loads((FIXTURES / "schedule_before.json").read_text(encoding="utf-8"))
    return FakeScheduleSink(sched, f"raw-json/schedule/{date}/schedule.json")


def test_read_game_schedules_emits_before_games_only():
    envs = list(exporter.read_game_schedules(None, date="2026-07-31", sink=_sched_sink()))
    assert envs, "픽스처에 BEFORE 상태 KBO 경기가 최소 1개 필요"
    e = envs[0]
    assert e.doc_type == "game_schedule"
    assert e.doc_id == f"game_schedule:{e.entities['gameId']}"
    assert len(e.entities["teamCodes"]) == 2
    assert "예정" in e.content
    assert set(e.payload) == {"gameId", "startTime", "stadium", "awayStarter", "homeStarter"}
    # 실측 불변식 고정: 픽스처(운영 schedule 잡 필드셋)에는 stadium/선발투수
    # 필드가 없다 — 매핑 로직이 잘못된 기본값을 채워도 조용히 통과하지 않도록
    # None/'' 값 자체를 단언한다.
    assert e.payload["awayStarter"] is None and e.payload["homeStarter"] is None
    assert e.payload["stadium"] == ""


def test_read_game_schedules_requires_date():
    with pytest.raises(ValueError, match="--date"):
        list(exporter.read_game_schedules(None, date=None, sink=FakeSink()))


def test_read_game_schedules_missing_raw_raises():
    with pytest.raises(ValueError, match="schedule"):
        list(exporter.read_game_schedules(None, date="1999-01-01", sink=_sched_sink()))


def test_export_game_schedule_writes_to_s3():
    sink = _sched_sink()
    n = exporter.export("game_schedule", settings=SimpleNamespace(), db=None,
                        sink=sink, date="2026-07-31")
    assert n >= 1
    key, obj = sink.puts[0]
    assert key.startswith("question-source/game_schedule/")
    assert obj["envelopeVersion"] == 1
