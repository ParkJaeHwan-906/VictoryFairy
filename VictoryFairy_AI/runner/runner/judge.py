"""C2 심사 콜 — 의미중복·안전 뉘앙스·재미·난이도. 분류 작업이라 Haiku급."""
import json
from pathlib import Path

CONTRACT = """
[출력 계약] JSON 배열만: [{quizId, duplicate(bool — recent 또는 후보끼리 같은
사실이면 true), safety_violation(bool — banned 소재·우회 표기·비하), fun(1~5),
difficulty(적정 난이도로 재분류: EASY|MEDIUM|HARD|EXPERT), reason(한 줄)}]
"""


def build_prompt(repo_root: Path, candidates, recent):
    system = "\n\n".join([
        (repo_root / "question-gen/prompts/verification-pass.md").read_text(encoding="utf-8"),
        "[banned-topics]\n" + (repo_root / "question-gen/config/banned-topics.txt").read_text(encoding="utf-8"),
        CONTRACT,
    ])
    slim = [{k: c.get(k) for k in ("quizId", "templateId", "question", "options", "difficulty")}
            for c in candidates]
    user = json.dumps({"candidates": slim, "recent": recent}, ensure_ascii=False)
    return system, user


def run_judge(client, model_id, repo_root, candidates, recent):
    system, user = build_prompt(repo_root, candidates, recent)
    raw = client.invoke_json(model_id, system, user, max_tokens=8000)
    by_id = {v.get("quizId"): v for v in raw if isinstance(v, dict)} if isinstance(raw, list) else {}
    out = {}
    for c in candidates:
        v = by_id.get(c["quizId"])
        out[c["quizId"]] = v if v else {
            "duplicate": True, "safety_violation": False, "fun": 0,
            "difficulty": c.get("difficulty"), "reason": "심사 응답 누락 — 보수적 폐기"}
    return out
