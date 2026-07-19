from types import SimpleNamespace
from unittest.mock import patch

import kbo_collector.sources  # noqa: F401  (등록 트리거)
from kbo_collector.sources import base


def test_builtin_sources_registered():
    assert {"naver_games", "kbo_roster", "community_posts"} <= set(base.REGISTRY)


def test_doc_types_mapping():
    assert base.REGISTRY["naver_games"].doc_types == ("game_result",)
    assert base.REGISTRY["kbo_roster"].doc_types == ("player_profile",)
    assert base.REGISTRY["community_posts"].doc_types == ("community_post",)


def test_naver_games_delegates_to_land_range():
    ctx = base.CollectContext(settings="S", client="C", db="D", date="2026-07-01")
    with patch("kbo_collector.run.land_game_records_range",
               return_value={"loaded": ["g1", "g2"], "failed": ["g3"]}) as m:
        res = base.get_source("naver_games").collect(ctx)
    m.assert_called_once_with("2026-07-01", "2026-07-01",
                              settings="S", db="D", client="C")
    assert res.loaded == 2 and res.failed == ["g3"]


def test_kbo_roster_delegates():
    ctx = base.CollectContext(settings="S", client="C", db="D", date=None)
    with patch("kbo_collector.run.land_registrations",
               return_value=["OB", "LG"]) as m:
        res = base.get_source("kbo_roster").collect(ctx)
    m.assert_called_once_with(None, settings="S", db="D", client="C")
    assert res.loaded == 2 and res.failed == []


def test_community_posts_defaults_to_kst_today(tmp_path):
    settings = SimpleNamespace(journal_dir=str(tmp_path))
    ctx = base.CollectContext(settings=settings, client="C", sink="K", date=None)
    with patch("kbo_collector.run._kst_today", return_value="2026-07-15") as kst, \
         patch("kbo_collector.run.land_community", return_value=0) as m:
        base.get_source("community_posts").collect(ctx)
    kst.assert_called_once()
    assert m.call_args.args[0] == "2026-07-15"
