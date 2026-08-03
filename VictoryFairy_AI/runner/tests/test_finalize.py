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
    assert final[0]["difficulty"] == "MEDIUM" and final[0]["pointReward"] == 50
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
