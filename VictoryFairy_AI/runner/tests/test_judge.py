import json
from pathlib import Path
from runner.bedrock_client import BedrockClient
from runner.judge import build_prompt, run_judge

REPO = Path(__file__).parents[2]
CAND = {"quizId": "RAW-01", "templateId": "MEME_OWNER", "question": "q?",
        "options": [{"id": "A", "text": "김대한"}], "difficulty": "EASY"}


def test_build_prompt_includes_rules_banned_and_recent():
    system, user = build_prompt(REPO, [CAND], [{"quizId": "QZ-x", "question": "old"}])
    assert "검증 패스 규칙" in system
    assert "음주" in system                     # banned-topics 포함
    assert "RAW-01" in user and "old" in user


def test_run_judge_maps_by_id_and_defaults_missing_to_duplicate():
    verdicts = [{"quizId": "RAW-01", "duplicate": False, "safety_violation": False,
                 "fun": 5, "difficulty": "EASY", "reason": "ok"}]
    client = BedrockClient("r", transport=lambda m, b: {
        "content": [{"type": "text", "text": json.dumps(verdicts)}]})
    out = run_judge(client, "model", REPO, [CAND, dict(CAND, quizId="RAW-02")], [])
    assert out["RAW-01"]["fun"] == 5
    assert out["RAW-02"]["duplicate"] is True   # 응답 누락 → 보수적 폐기
