"""C1 작문 콜 — 유일하게 '창작'이 허용되는 지점 (문구·오답 보기)."""
import json
from pathlib import Path

REQUIRED = ("templateId", "format", "question", "options", "kind")

CONTRACT = """
[출력 계약 — 반드시 지켜라]
- 출력은 JSON 배열만. 설명·코드펜스 금지.
- 각 항목 필드: quizId(가제), gameId, kind, type, templateId, format, question,
  options([{id,text}] — OX는 O/X 2개, BINARY 2개, MULTI4 4개), answer, evidence
  ({source, quote}), settlement, difficulty, pointReward(EASY30/MEDIUM50/HARD80/EXPERT120),
  status="PENDING", createdAt="", deadlineAt="", createdBy="AI_ENGINE".
- evidence.quote는 입력 sources의 원문에서 글자 그대로 복사한다. 요약·의역 금지.
  근거가 없으면 그 문제를 만들지 마라.
- PREDICTION은 answer=null, evidence=null, settlement={metric, gameId} 필수.
- 질문은 1문장 40자 이내. 정답 보기가 유난히 길지 않게.
"""


def _read(repo_root: Path, rel: str) -> str:
    return (repo_root / rel).read_text(encoding="utf-8")


def build_prompt(repo_root, combos, bindings, today):
    system = "\n\n".join([
        _read(repo_root, "question-gen/prompts/generation-rules.md"),
        _read(repo_root, "question-gen/casebook/good.md"),
        _read(repo_root, "question-gen/casebook/bad.md"),
        CONTRACT,
    ])
    items = []
    for (t, entity), b in zip(combos, bindings):
        items.append({"templateId": t["id"], "kind": t.get("kind"),
                      "format": t.get("format"), "intent": t.get("intent"),
                      "difficulty": t.get("difficulty"),
                      "distractor": t.get("distractor"), "entity": entity,
                      "sources": b["sources"]})
    user = json.dumps({"today": today, "requests": items}, ensure_ascii=False)
    return system, user


def run_generate(client, model_id, repo_root, combos, bindings, today):
    system, user = build_prompt(repo_root, combos, bindings, today)
    raw = client.invoke_json(model_id, system, user, max_tokens=16000)
    out = []
    for cand in raw if isinstance(raw, list) else []:
        if isinstance(cand, dict) and all(k in cand for k in REQUIRED):
            cand["quizId"] = f"RAW-{len(out) + 1:02d}"
            out.append(cand)
    return out
