import json
from pathlib import Path
from runner.finalize import assign_and_write, check_evidence, select_final

REPO = Path(__file__).parents[2]
TODAY = "2026-08-03"


def _cand(nn, template, diff="EASY", quote="- OB: 2연승", source="wiki/stats/season.md#연승·연패"):
    return {"quizId": f"RAW-{nn:02d}", "gameId": None, "kind": "KNOWLEDGE",
            "type": "STATS", "templateId": template, "format": "OX",
            "question": "q?", "options": [{"id": "A", "text": "O"}, {"id": "B", "text": "X"}],
            "answer": "A", "evidence": {"source": source, "quote": quote},
            "settlement": None, "difficulty": diff, "pointReward": 30,
            "bqReward": 1,
            "status": "PENDING", "createdAt": "", "deadlineAt": "", "createdBy": "AI_ENGINE"}


def _ok(fun=5, diff="EASY"):
    return {"duplicate": False, "safety_violation": False, "fun": fun,
            "difficulty": diff, "reason": "ok"}


def _pred(nn, template, game_id="NOPE"):
    c = _cand(nn, template)
    c["kind"] = "PREDICTION"
    c["evidence"] = None
    c["settlement"] = {"gameId": game_id, "metric": "WIN_TEAM"}
    return c


def test_check_evidence_resolves_stats_md(work):
    assert check_evidence(work, REPO, _cand(1, "STREAK_CURRENT")) is True
    assert check_evidence(work, REPO, _cand(2, "STREAK_CURRENT", quote="없는 문장")) is False


def test_select_final_drops_by_verdict_and_remaps_points():
    cands = [_cand(1, "A"), _cand(2, "B"), _cand(3, "C")]
    verdicts = {"RAW-01": _ok(diff="MEDIUM"), "RAW-02": _ok(fun=3),
                "RAW-03": dict(_ok(), duplicate=True)}
    final, reasons = select_final(cands, verdicts, {})
    assert [c["quizId"] for c in final] == ["RAW-01"]
    # EASY(30P/1BQ)로 생성된 후보가 MEDIUM으로 재분류되면 보상 두 축이 함께 따라온다 —
    # 한쪽만 갱신되면 업로드 직전 게이트(validate_candidates check 6)에서 걸린다.
    assert final[0]["difficulty"] == "MEDIUM"
    assert final[0]["pointReward"] == 50 and final[0]["bqReward"] == 2
    assert len(reasons) == 2                       # fun<4, duplicate


def test_select_final_drops_unrecognized_difficulty():
    cands = [_cand(1, "A")]
    verdicts = {"RAW-01": _ok(diff="ULTRA")}
    final, reasons = select_final(cands, verdicts, {})
    assert final == []
    assert len(reasons) == 1
    assert "ULTRA" in reasons[0]


def test_assign_and_write_skips_prediction_missing_schedule_and_logs_reason(work):
    final = [_pred(9, "PRED_WIN_LOSE", game_id="NOPE")]
    reasons = []
    paths = assign_and_write(final, {}, work, TODAY, reasons)
    assert paths == []
    assert len(reasons) == 1
    assert "RAW-09(PRED_WIN_LOSE)" in reasons[0]
    assert "startTime 미발견" in reasons[0]


def test_assign_and_write_orders_by_template_entity(work):
    final = [_cand(1, "ZZZ"), _cand(2, "AAA")]
    entity_of = {"RAW-01": "x", "RAW-02": "y"}
    paths = assign_and_write(final, entity_of, work, TODAY)
    names = [p.name for p in paths]
    assert names == ["QZ-20260803-001.json", "QZ-20260803-002.json"]
    first = json.loads(paths[0].read_text(encoding="utf-8"))
    assert first["templateId"] == "AAA"            # 사전순 → AAA가 001
    assert first["deadlineAt"] == "2026-08-03T14:59:00Z"


def test_select_final_applies_per_game_quota_independently():
    """경기 문항은 gameId별로 슬롯이 따로 돈다 — 한 경기 재료가 넘쳐도 다른
    경기 몫을 잡아먹지 않고, 초과분만 그 경기 사유로 폐기된다."""
    from runner.finalize import VOLUME
    per_easy = dict(VOLUME["perGame"])["EASY"]
    cands, verdicts = [], {}
    for i in range(per_easy + 2):                  # G1: 슬롯보다 2개 많게
        c = _cand(i, "A"); c["gameId"] = "G1"; cands.append(c)
        verdicts[c["quizId"]] = _ok(fun=5 - (i % 2))
    for i in range(per_easy + 2, per_easy + 4):    # G2: 2개만
        c = _cand(i, "A"); c["gameId"] = "G2"; cands.append(c)
        verdicts[c["quizId"]] = _ok()

    final, reasons = select_final(cands, verdicts, {})
    by_game = {}
    for c in final:
        by_game.setdefault(c["gameId"], []).append(c)
    assert len(by_game["G1"]) == per_easy          # 슬롯만큼만
    assert len(by_game["G2"]) == 2                 # 모자라면 있는 만큼
    assert sum("경기(G1)" in r for r in reasons) == 2
    assert not any("경기(G2)" in r for r in reasons)


def test_select_final_explicit_quota_overrides_grouping():
    cands = [_cand(1, "A"), _cand(2, "A")]
    verdicts = {"RAW-01": _ok(fun=5), "RAW-02": _ok(fun=4)}
    final, reasons = select_final(cands, verdicts, {}, quota=[("EASY", 1)])
    assert [c["quizId"] for c in final] == ["RAW-01"]
    assert len(reasons) == 1 and "물량 슬롯 초과" in reasons[0]
