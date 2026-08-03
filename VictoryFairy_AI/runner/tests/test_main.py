import json
from pathlib import Path
from runner.bedrock_client import BedrockClient
from runner.main import run

REPO = Path(__file__).parents[2]


def _client_scripted():
    """C1 → 후보 1개(STREAK_CURRENT, 픽스처 원문 인용), C2 → 통과."""
    cand = {"quizId": "x", "gameId": None, "kind": "KNOWLEDGE", "type": "STATS",
            "templateId": "STREAK_CURRENT", "format": "OX", "question": "두산은 2연승 중이다?",
            "options": [{"id": "A", "text": "O"}, {"id": "B", "text": "X"}], "answer": "A",
            "evidence": {"source": "wiki/stats/season.md#연승·연패", "quote": "- OB: 2연승"},
            "settlement": None, "difficulty": "EASY", "pointReward": 30,
            "status": "PENDING", "createdAt": "", "deadlineAt": "", "createdBy": "AI_ENGINE"}
    verdict = [{"quizId": "RAW-01", "duplicate": False, "safety_violation": False,
                "fun": 5, "difficulty": "EASY", "reason": "ok"}]
    responses = iter([json.dumps([cand]), json.dumps(verdict)])
    return BedrockClient("r", transport=lambda m, b: {
        "content": [{"type": "text", "text": next(responses)}]})


def test_run_end_to_end_writes_final_candidate(work):
    summary = run(work, REPO, "2026-08-03", _client_scripted(), "m1", "m2")
    assert summary["written"] == ["QZ-20260803-001"]
    out = work / "candidates/2026-08-03/QZ-20260803-001.json"
    data = json.loads(out.read_text(encoding="utf-8"))
    assert data["pointReward"] == 30 and data["deadlineAt"] == "2026-08-03T14:59:00Z"


def _client_scripted_entity_and_evidence_both_fail():
    """C1 → 2후보: 1) STREAK_CURRENT(정상, 원문 인용) 2) 존재하지 않는
    templateId + 가짜 quote(엔티티 매칭도, evidence 대조도 둘 다 실패해야 함).
    check_evidence가 엔티티 매칭 실패와 무관하게 "전 후보"에 대해 호출됨을
    discarded 사유로 관측한다(오케스트레이션 순서 계약 회귀 테스트)."""
    good = {"quizId": "x", "gameId": None, "kind": "KNOWLEDGE", "type": "STATS",
            "templateId": "STREAK_CURRENT", "format": "OX", "question": "두산은 2연승 중이다?",
            "options": [{"id": "A", "text": "O"}, {"id": "B", "text": "X"}], "answer": "A",
            "evidence": {"source": "wiki/stats/season.md#연승·연패", "quote": "- OB: 2연승"},
            "settlement": None, "difficulty": "EASY", "pointReward": 30,
            "status": "PENDING", "createdAt": "", "deadlineAt": "", "createdBy": "AI_ENGINE"}
    bad = {"quizId": "y", "gameId": None, "kind": "KNOWLEDGE", "type": "STATS",
           "templateId": "NOT_A_REAL_TEMPLATE", "format": "OX", "question": "가짜 질문?",
           "options": [{"id": "A", "text": "O"}, {"id": "B", "text": "X"}], "answer": "A",
           "evidence": {"source": "wiki/stats/season.md#가짜", "quote": "존재하지 않는 원문 문장"},
           "settlement": None, "difficulty": "EASY", "pointReward": 30,
           "status": "PENDING", "createdAt": "", "deadlineAt": "", "createdBy": "AI_ENGINE"}
    verdict = [{"quizId": "RAW-01", "duplicate": False, "safety_violation": False,
                "fun": 5, "difficulty": "EASY", "reason": "ok"}]
    responses = iter([json.dumps([good, bad]), json.dumps(verdict)])
    return BedrockClient("r", transport=lambda m, b: {
        "content": [{"type": "text", "text": next(responses)}]})


def test_run_checks_evidence_even_when_entity_match_fails(work):
    summary = run(work, REPO, "2026-08-03", _client_scripted_entity_and_evidence_both_fail(), "m1", "m2")
    assert summary["written"] == ["QZ-20260803-001"]           # 정상 후보만 기록
    bad_reasons = [r for r in summary["discarded"] if r.startswith("RAW-02")]
    assert len(bad_reasons) == 1
    # 엔티티 매칭 실패와 evidence 대조 실패가 모두 관측돼야 한다 — check_evidence가
    # 엔티티 매칭 여부와 무관하게 호출됐다는 증거.
    assert "엔티티 매칭 실패" in bad_reasons[0]
    assert "evidence 원문 대조 실패" in bad_reasons[0]
