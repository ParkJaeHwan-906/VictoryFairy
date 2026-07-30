import validate_candidates as vc

CATALOG = {"H2H_SEASON_RECORD": {"id": "H2H_SEASON_RECORD", "kind": "KNOWLEDGE",
                                 "format": "BINARY", "enabled": True},
           "YOY_TEAM": {"id": "YOY_TEAM", "kind": "KNOWLEDGE",
                        "format": "MULTI4", "enabled": False},
           "PRED_WIN_LOSE": {"id": "PRED_WIN_LOSE", "kind": "PREDICTION",
                             "format": "BINARY", "enabled": True}}
BANNED = ["음주", "폭행"]


def ok_knowledge():
    return {"quizId": "QZ-20260730-001", "gameId": None, "kind": "KNOWLEDGE",
            "type": "HISTORY", "templateId": "H2H_SEASON_RECORD", "format": "BINARY",
            "question": "올 시즌 잠실 라이벌전 우위 팀은?",
            "options": [{"id": "A", "text": "LG"}, {"id": "B", "text": "두산"}],
            "answer": "A",
            "evidence": {"source": "wiki/stats/season.md#상대전적", "quote": "LG 7-4 두산"},
            "settlement": None, "difficulty": "MEDIUM", "pointReward": 50,
            "status": "PENDING", "createdAt": "2026-07-30T00:00:00Z",
            "deadlineAt": "2026-07-30T16:00:00Z", "createdBy": "AI_ENGINE"}


def test_valid_knowledge_passes():
    assert vc.validate_candidate(ok_knowledge(), CATALOG, BANNED) == []


def test_option_count_must_match_format():
    c = ok_knowledge()
    c["options"].append({"id": "C", "text": "무승부"})
    assert any("options" in v for v in vc.validate_candidate(c, CATALOG, BANNED))


def test_knowledge_requires_evidence_and_answer():
    c = ok_knowledge(); c["evidence"] = None
    assert vc.validate_candidate(c, CATALOG, BANNED)
    c = ok_knowledge(); c["answer"] = "Z"
    assert vc.validate_candidate(c, CATALOG, BANNED)


def test_prediction_requires_settlement_metric():
    c = ok_knowledge()
    c.update(kind="PREDICTION", templateId="PRED_WIN_LOSE", answer=None, evidence=None,
             settlement={"gameId": "20260730LGOB02026", "metric": "WIN_TEAM"},
             gameId="20260730LGOB02026")
    assert vc.validate_candidate(c, CATALOG, BANNED) == []
    c["settlement"]["metric"] = "INNINGS_PITCHED"   # 정산 불가 지표
    assert vc.validate_candidate(c, CATALOG, BANNED)


def test_disabled_or_unknown_template_rejected():
    c = ok_knowledge(); c["templateId"] = "YOY_TEAM"; c["format"] = "MULTI4"
    assert vc.validate_candidate(c, CATALOG, BANNED)
    c = ok_knowledge(); c["templateId"] = "NOPE"
    assert vc.validate_candidate(c, CATALOG, BANNED)


def test_point_must_match_difficulty():
    c = ok_knowledge(); c["pointReward"] = 999
    assert vc.validate_candidate(c, CATALOG, BANNED)


def test_banned_topic_rejected():
    c = ok_knowledge(); c["question"] = "음주운전 사건의 주인공은?"
    assert vc.validate_candidate(c, CATALOG, BANNED)


def test_real_catalog_loads():
    from pathlib import Path
    path = Path(__file__).resolve().parents[1] / "question-gen/config/question-templates.yaml"
    cat = vc.load_catalog(str(path))
    assert "H2H_SEASON_RECORD" in cat and cat["YOY_TEAM"]["enabled"] is False
