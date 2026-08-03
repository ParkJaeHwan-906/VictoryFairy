from pathlib import Path
from runner.bedrock_client import BedrockClient
from runner.generate import build_prompt, run_generate

REPO = Path(__file__).parents[2]
COMBO = ({"id": "MEME_OWNER", "kind": "KNOWLEDGE", "format": "MULTI4",
          "needs": ["wiki.별명밈"], "intent": "별명 주인 맞히기",
          "difficulty": "EASY"}, "69238")
BINDING = {"sources": {"wiki/players/69238.md": "- **야구의 신**: 신격화 밈"}}


def test_build_prompt_carries_rules_and_sources():
    system, user = build_prompt(REPO, [COMBO], [BINDING], "2026-08-03")
    assert "문구 생성 규칙" in system            # generation-rules.md 포함
    assert "Casebook" in system                  # casebook 포함
    assert "야구의 신" in user and "MEME_OWNER" in user


def _fake_candidate(**over):
    c = {"quizId": "x", "kind": "KNOWLEDGE", "type": "MEME", "templateId": "MEME_OWNER",
         "format": "MULTI4", "question": "q?", "options": [], "answer": "A",
         "evidence": {"source": "s", "quote": "q"}, "settlement": None,
         "difficulty": "EASY", "pointReward": 30, "status": "PENDING",
         "createdAt": "", "deadlineAt": "", "createdBy": "AI_ENGINE"}
    c.update(over)
    return c


def test_run_generate_drops_malformed_and_renumbers():
    fake = [_fake_candidate(), {"garbage": True}]
    client = BedrockClient("r", transport=lambda m, b: {
        "content": [{"type": "text", "text": __import__("json").dumps(fake)}]})
    out = run_generate(client, "model", REPO, [COMBO], [BINDING], "2026-08-03")
    assert len(out) == 1 and out[0]["quizId"] == "RAW-01"
