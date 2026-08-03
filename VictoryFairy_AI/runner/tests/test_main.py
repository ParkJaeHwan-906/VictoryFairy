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
