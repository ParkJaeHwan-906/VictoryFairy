import json

import pytest

import validate_candidates as vc

CATALOG = {"H2H_SEASON_RECORD": {"id": "H2H_SEASON_RECORD", "kind": "KNOWLEDGE",
                                 "format": "BINARY", "enabled": True},
           "YOY_TEAM": {"id": "YOY_TEAM", "kind": "KNOWLEDGE",
                        "format": "MULTI4", "enabled": False},
           "PRED_WIN_LOSE": {"id": "PRED_WIN_LOSE", "kind": "PREDICTION",
                             "format": "BINARY", "enabled": True}}
BANNED = ["음주", "폭행"]


def ok_knowledge():
    # deadlineAt은 gameId 없는 KNOWLEDGE 문항 기준이라 값 자체는 임의였지만,
    # PREDICTION 테스트들이 이 fixture를 c.update()로 재사용하며 gameId만 덧붙이므로
    # (아래 test_prediction_*) deadlineAt도 gameId(20260730...) 날짜의 KST 유효 범위
    # 안에 들도록 맞춰둔다 — 아니면 새 deadlineAt sanity 검사(check 8)에 걸린다.
    return {"quizId": "QZ-20260730-001", "gameId": None, "kind": "KNOWLEDGE",
            "type": "HISTORY", "templateId": "H2H_SEASON_RECORD", "format": "BINARY",
            "question": "올 시즌 잠실 라이벌전 우위 팀은?",
            "options": [{"id": "A", "text": "LG"}, {"id": "B", "text": "두산"}],
            "answer": "A",
            "evidence": {"source": "wiki/stats/season.md#상대전적", "quote": "LG 7-4 두산"},
            "settlement": None, "difficulty": "MEDIUM", "pointReward": 50,
            "bqReward": 2,
            "status": "PENDING", "createdAt": "2026-07-30T00:00:00Z",
            "deadlineAt": "2026-07-30T07:30:00Z", "createdBy": "AI_ENGINE"}


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


def test_prediction_gameid_mismatch_rejected():
    # I4: top-level gameId와 settlement.gameId는 같은 경기를 가리켜야 한다.
    c = ok_knowledge()
    c.update(kind="PREDICTION", templateId="PRED_WIN_LOSE", answer=None, evidence=None,
             settlement={"gameId": "20260730LGOB02026", "metric": "WIN_TEAM"},
             gameId="20260731LGOB02026",   # settlement.gameId와 불일치
             deadlineAt="2026-07-30T07:30:00Z")
    violations = vc.validate_candidate(c, CATALOG, BANNED)
    assert any("gameId" in v and "불일치" in v for v in violations)


def test_prediction_deadline_outside_gameid_date_rejected():
    # I4: deadlineAt이 gameId 날짜(KST)와 전혀 무관한 값이면 보수적 sanity 검사로 거부한다.
    # (정확한 경기 시작시각 대조는 candidate에 시작시각 필드가 없어 LLM 검증 패스 몫 —
    # 여기서는 KST 날짜 경계만 결정적으로 검사한다.)
    c = ok_knowledge()
    c.update(kind="PREDICTION", templateId="PRED_WIN_LOSE", answer=None, evidence=None,
             settlement={"gameId": "20260730LGOB02026", "metric": "WIN_TEAM"},
             gameId="20260730LGOB02026",
             deadlineAt="2026-08-15T00:00:00Z")   # gameId 날짜와 무관한 마감
    violations = vc.validate_candidate(c, CATALOG, BANNED)
    assert any("deadlineAt" in v for v in violations)


def test_disabled_or_unknown_template_rejected():
    c = ok_knowledge(); c["templateId"] = "YOY_TEAM"; c["format"] = "MULTI4"
    assert vc.validate_candidate(c, CATALOG, BANNED)
    c = ok_knowledge(); c["templateId"] = "NOPE"
    assert vc.validate_candidate(c, CATALOG, BANNED)


def test_point_must_match_difficulty():
    c = ok_knowledge(); c["pointReward"] = 999
    assert vc.validate_candidate(c, CATALOG, BANNED)


def test_bq_must_match_difficulty():
    # 보상 두 축은 각각 검사된다 — pointReward가 맞아도 bqReward만 틀리면 실패.
    c = ok_knowledge(); c["bqReward"] = 999
    violations = vc.validate_candidate(c, CATALOG, BANNED)
    assert any("bqReward" in v for v in violations)
    assert not any("pointReward" in v for v in violations)


def test_bq_reward_is_required():
    c = ok_knowledge(); del c["bqReward"]
    violations = vc.validate_candidate(c, CATALOG, BANNED)
    assert any("필수 필드 누락" in v and "bqReward" in v for v in violations)


def test_banned_topic_rejected():
    c = ok_knowledge(); c["question"] = "음주운전 사건의 주인공은?"
    assert vc.validate_candidate(c, CATALOG, BANNED)


def test_real_catalog_loads():
    from pathlib import Path
    path = Path(__file__).resolve().parents[1] / "question-gen/config/question-templates.yaml"
    cat = vc.load_catalog(str(path))
    assert "H2H_SEASON_RECORD" in cat and cat["YOY_TEAM"]["enabled"] is False


def test_pitch_velocity_passes_but_arrest_warrant_is_banned():
    # 회귀 테스트: '구속'(투구 속도) 오탐 수정 확인.
    # 실제 banned-topics.txt는 '구속' 단독 항목을 '구속영장'/'구속기소'로 대체했다 —
    # 야구 스탯 용어 '구속'은 통과해야 하고, 법적 맥락 '구속영장'은 여전히 걸려야 한다.
    from pathlib import Path
    path = Path(__file__).resolve().parents[1] / "question-gen/config/banned-topics.txt"
    banned = vc.load_banned(str(path))

    ok = ok_knowledge(); ok["question"] = "최고 구속 155km/h를 던지는 투수는?"
    assert vc.validate_candidate(ok, CATALOG, banned) == []

    bad = ok_knowledge(); bad["question"] = "구속영장이 청구된 사건의 당사자는?"
    assert vc.validate_candidate(bad, CATALOG, banned)


# ── Finding 2: check 5 kind/format 불일치 경로 + check 2 세부 분기 ──────

def test_template_format_mismatch_rejected():
    # H2H_SEASON_RECORD는 카탈로그상 format=BINARY. candidate가 kind는 그대로
    # KNOWLEDGE로 맞추면서 format만 MULTI4로 선언(+보기 4개)하면 옵션 개수·id
    # 규칙(check 2)은 통과하지만 카탈로그와의 format 불일치(check 5)로 걸려야 한다.
    c = ok_knowledge()
    c["format"] = "MULTI4"
    c["options"] = [{"id": "A", "text": "LG"}, {"id": "B", "text": "두산"},
                    {"id": "C", "text": "KT"}, {"id": "D", "text": "SSG"}]
    violations = vc.validate_candidate(c, CATALOG, BANNED)
    assert any("format" in v and "카탈로그" in v for v in violations)


def test_option_id_duplicate_rejected():
    # option id가 [A, A]로 중복되면(순서·유니크 규칙 위반) check 2에서 걸려야 한다.
    c = ok_knowledge()
    c["options"] = [{"id": "A", "text": "LG"}, {"id": "A", "text": "두산"}]
    violations = vc.validate_candidate(c, CATALOG, BANNED)
    assert any("id" in v and "유니크" in v for v in violations)


def test_option_text_blank_rejected():
    # option text가 공백만 있으면(비어있음과 동치) check 2에서 걸려야 한다.
    c = ok_knowledge()
    c["options"] = [{"id": "A", "text": "   "}, {"id": "B", "text": "두산"}]
    violations = vc.validate_candidate(c, CATALOG, BANNED)
    assert any("text" in v and "비어" in v for v in violations)


# ── Finding 1: main() CLI 계약 (--dir 필수·파싱 실패·quizId 중복·exit code) ──

def _write_candidate(path, candidate):
    path.write_text(json.dumps(candidate, ensure_ascii=False), encoding="utf-8")


def test_main_requires_dir_arg():
    # --dir 없이 호출하면 argparse가 필수 인자 누락으로 SystemExit(2)를 던진다.
    with pytest.raises(SystemExit) as exc_info:
        vc.main([])
    assert exc_info.value.code == 2


def test_main_all_valid_exits_zero(tmp_path):
    _write_candidate(tmp_path / "QZ-1.json", ok_knowledge())
    with pytest.raises(SystemExit) as exc_info:
        vc.main(["--dir", str(tmp_path)])
    assert exc_info.value.code == 0


def test_main_json_parse_failure_exits_one(tmp_path):
    _write_candidate(tmp_path / "QZ-1.json", ok_knowledge())
    (tmp_path / "QZ-broken.json").write_text("not json {{{", encoding="utf-8")
    with pytest.raises(SystemExit) as exc_info:
        vc.main(["--dir", str(tmp_path)])
    assert exc_info.value.code == 1


def test_main_duplicate_quiz_id_exits_one(tmp_path):
    c1 = ok_knowledge()
    c2 = ok_knowledge(); c2["question"] = "다른 문항이지만 quizId가 같음"
    _write_candidate(tmp_path / "a.json", c1)
    _write_candidate(tmp_path / "b.json", c2)
    with pytest.raises(SystemExit) as exc_info:
        vc.main(["--dir", str(tmp_path)])
    assert exc_info.value.code == 1
