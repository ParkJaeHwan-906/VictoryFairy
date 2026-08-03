"""카탈로그 로드 + (템플릿 × 엔티티) 결정적 선택 — LLM 없음.

원 ROUTINE.md 3단계의 판단("의외성 선호")은 버리고 규칙만 남긴다(스펙 §4):
enabled → needs 가용 → 최근 7일 편중 오름차순 → 라운드로빈.
"""
from pathlib import Path

import yaml

MAX_PER_TEMPLATE = 2


def load_catalog(path: Path) -> list:
    raw = yaml.safe_load(path.read_text(encoding="utf-8")) or []
    out = []
    for entry in raw:
        entry = dict(entry)
        entry.setdefault("enabled", True)
        out.append(entry)
    return out


def select_combos(catalog, available, entities_by_template,
                  recent_template_counts, limit: int = 15):
    eligible = [
        t for t in catalog
        if t["enabled"] and set(t.get("needs") or []) <= set(available)
        and entities_by_template.get(t["id"])
    ]
    eligible.sort(key=lambda t: (recent_template_counts.get(t["id"], 0), t["id"]))

    combos, cursor = [], {t["id"]: 0 for t in eligible}
    # 라운드로빈: 각 템플릿에서 1개씩, 다 돌면 2번째 엔티티
    for round_no in range(MAX_PER_TEMPLATE):
        for t in eligible:
            if len(combos) >= limit:
                return combos
            ents = entities_by_template[t["id"]]
            if cursor[t["id"]] < len(ents):
                combos.append((t, ents[cursor[t["id"]]]))
                cursor[t["id"]] += 1
    return combos
