import gen_all_time_seed as gas

SNAP = {"page": "history-player-hitter", "date": "2026-07-30",
        "tables": [{"headers": ["순위", "선수명", "홈런"],
                    "rows": [["1", "최정", "500"], ["2", "이승엽", "467"]]}]}


def test_seed_from_snapshots():
    seed = gas.seed_from_snapshots({"history-player-hitter": SNAP}, top_n=1)
    assert seed["asOf"] == "2026-07-30"
    cat = seed["categories"][0]
    assert cat["sourcePage"] == "history-player-hitter"
    assert cat["entries"] == [{"rank": 1, "name": "최정", "value": "500"}]


def test_empty_snapshot_yields_no_category():
    assert gas.seed_from_snapshots({})["categories"] == []
