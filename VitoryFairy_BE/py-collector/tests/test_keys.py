from kbo_collector import keys


def test_schedule_key_is_fixed_name_per_date():
    assert keys.schedule_key("2026-07-10") == "raw-json/schedule/2026-07-10/schedule.json"
    # deterministic: same date -> same key (idempotent overwrite)
    assert keys.schedule_key("2026-07-10") == keys.schedule_key("2026-07-10")


def test_result_key_uses_game_id():
    assert keys.result_key("2026-07-08", "20260708LGSS02026") == \
        "raw-json/result/2026-07-08/20260708LGSS02026.json"
    # distinct games -> distinct keys, same game -> same key
    assert keys.result_key("2026-07-08", "g1") != keys.result_key("2026-07-08", "g2")


def test_relay_key_uses_game_and_inning():
    assert keys.relay_key("20260710LGOB02026", 3) == "raw-json/relay/20260710LGOB02026/3.json"


def test_community_key_lowercases_source():
    assert keys.community_key("FMKOREA", "2026-07-10", "8523491") == \
        "community/fmkorea/2026-07-10/8523491.json"
    assert keys.community_key("DCINSIDE", "2026-07-10", "12345") == \
        "community/dcinside/2026-07-10/12345.json"


def test_dead_letter_and_manifest_keys():
    assert keys.dead_letter_key("result", "2026-07-10", "gid1") == \
        "dead-letter/result/2026-07-10/gid1.json"
    assert keys.manifest_key("community", "2026-07-10", "run-9") == \
        "manifests/community/2026-07-10/run-9.json"
