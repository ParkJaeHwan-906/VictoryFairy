"""needs 어휘 → 파일 가용성·엔티티·바인딩. 전부 결정적 — LLM 없음.

needs 어휘 사전은 question-templates.yaml 머리 주석이 원전이다.
"""
import json
import re
from pathlib import Path

import yaml

DEFAULT_REPO_ROOT = Path("/app/VictoryFairy_AI")

_STANDINGS_TEAM_FAMILIES = {
    "stats.streaks", "stats.standings", "stats.home_away",
    "stats.monthly", "stats.standings_trend", "stats.recent_scoring",
}
_SECTION_BY_WIKI_FAMILY = {
    "wiki.별명밈": "별명·밈",
    "wiki.커리어이력": "커리어 이력",
}
_ALLOWED_GRAPH_EDGE_TYPES = {"밈공유", "커리어교차", "라이벌"}
# rankBasis: "true-rank"=역대 순위(ALL_TIME_LEADER), "chronological"=최초 달성(MILESTONE_FIRST).
# RECORD_OX는 사실확인형이라 둘 다 쓴다. 알 수 없는 템플릿id는 방어적으로 둘 다 허용.
_ALL_TIME_RANK_BASIS_BY_TEMPLATE = {
    "ALL_TIME_LEADER": {"true-rank"},
    "MILESTONE_FIRST": {"chronological"},
    "RECORD_OX": {"true-rank", "chronological"},
}


def _latest_partition(base: Path):
    if not base.is_dir():
        return None
    parts = sorted(p.name for p in base.iterdir() if p.is_dir())
    return base / parts[-1] if parts else None


def _season(work: Path) -> dict:
    p = work / "stats/season.json"
    return json.loads(p.read_text(encoding="utf-8")) if p.exists() else {}


def _wiki_docs(work: Path) -> dict:
    out = {}
    for p in sorted((work / "wiki/players").glob("*.md")) if (work / "wiki/players").is_dir() else []:
        out[p.stem] = p.read_text(encoding="utf-8")
    return out


def _section(md: str, name: str) -> str:
    m = re.search(rf"^## {re.escape(name)}\n(.*?)(?=^## |\Z)", md, re.S | re.M)
    return (m.group(1) if m else "").strip()


def _frontmatter(md: str) -> dict:
    m = re.match(r"^---\n(.*?)\n---\n", md, re.S)
    if not m:
        return {}
    return yaml.safe_load(m.group(1)) or {}


def available_needs(work: Path, today: str) -> set:
    av = set()
    season = _season(work)
    stats_md = (work / "stats/season.md").exists() and (work / "stats/kbo-official.md").exists()
    if stats_md and season.get("headToHead"):
        av.add("stats.head_to_head")
    if stats_md and season.get("standings"):
        av.update({"stats.streaks", "stats.standings", "stats.home_away",
                   "stats.monthly", "stats.standings_trend", "stats.recent_scoring"})
    if (work / "stats/kbo-official.md").exists():
        av.update({"stats.season_leaders", "stats.team_history"})
    # 리포 파일(러너 이미지에 포함) — repo 루트는 work의 두 단계 위가 아니라 인자로
    # 받지 않고, 존재 검사는 bind에서 실패로 처리한다. 여기서는 항상 가용으로 본다.
    av.add("stats.all_time_records")
    if (work / "wiki/stats/trending.md").exists():
        av.add("stats.trending")
    docs = _wiki_docs(work)
    if any(_section(d, "별명·밈") for d in docs.values()):
        av.add("wiki.별명밈")
    if any(_section(d, "커리어 이력") for d in docs.values()):
        av.add("wiki.커리어이력")
    if (work / "wiki/graph.json").exists() and docs:
        av.update({"graph", "wiki"})
    if _latest_partition(work / "game_result"):
        av.update({"envelope.game_result.yesterday", "envelope.game_result.recent7d"})
    if (work / f"game_schedule/{today}").is_dir() and any((work / f"game_schedule/{today}").glob("*.json")):
        av.add("schedule.today")
    return av


# ── enumerate_entities 헬퍼 ──────────────────────────────────

def _template_family(template: dict) -> "str | None":
    needs = template.get("needs") or []
    return needs[0] if needs else None


def _standings_teams(work: Path) -> list:
    season = _season(work)
    teams = {row.get("team") for row in (season.get("standings") or []) if row.get("team")}
    return sorted(teams)


def _all_time_categories(repo_root: Path) -> list:
    p = repo_root / "question-gen/config/all-time-records.yaml"
    if not p.exists():
        return []
    data = yaml.safe_load(p.read_text(encoding="utf-8")) or {}
    return data.get("categories") or []


def _all_time_record_entities(template: dict, repo_root: Path) -> list:
    allowed = _ALL_TIME_RANK_BASIS_BY_TEMPLATE.get(
        template.get("id"), {"true-rank", "chronological"})
    return [c["id"] for c in _all_time_categories(repo_root)
           if c.get("rankBasis") in allowed and c.get("id")]


def _team_history_years(work: Path) -> list:
    """kbo-official.json teamHistory.tables는 연도별 표 하나씩(headers[0]이
    연도 문자열) — 실측 결과 브리프가 가정한 '연도 키를 가진 dict'가 아니라
    표 리스트였다. headers[0]에서 연도를 뽑아 내림차순 상위 3개로 대체한다."""
    p = work / "stats/kbo-official.json"
    if not p.exists():
        return []
    data = json.loads(p.read_text(encoding="utf-8")) or {}
    team_history = data.get("teamHistory") or {}
    years = set()
    for table in team_history.get("tables") or []:
        headers = table.get("headers") or []
        if headers and re.fullmatch(r"\d{4}", str(headers[0])):
            years.add(str(headers[0]))
    return sorted(years, reverse=True)[:3]


def _player_id_ref(ref) -> "str | None":
    if isinstance(ref, str) and ref.startswith("player:"):
        return ref.split(":", 1)[1]
    return None


def _graph_pairs(work: Path) -> list:
    p = work / "wiki/graph.json"
    if not p.exists():
        return []
    data = json.loads(p.read_text(encoding="utf-8")) or {}
    pairs = set()
    for e in data.get("edges") or []:
        if e.get("type") not in _ALLOWED_GRAPH_EDGE_TYPES:
            continue
        a, b = _player_id_ref(e.get("source")), _player_id_ref(e.get("target"))
        if a is None or b is None or a == b:
            continue
        lo, hi = sorted((a, b), key=int)
        pairs.add(f"{lo}|{hi}")
    return sorted(pairs)


def _wiki_entities_with_section(work: Path, section: str) -> list:
    ids = set()
    for stem, md in _wiki_docs(work).items():
        if not _section(md, section):
            continue
        pid = _frontmatter(md).get("kboPlayerId")
        ids.add(str(pid) if pid is not None else stem)
    return sorted(ids)


def _envelope_game_ids(dir_path) -> set:
    ids = set()
    if not dir_path or not dir_path.is_dir():
        return ids
    for f in sorted(dir_path.glob("*.json")):
        try:
            env = json.loads(f.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        gid = ((env or {}).get("entities") or {}).get("gameId")
        if gid:
            ids.add(gid)
    return ids


def enumerate_entities(work: Path, template: dict, today: str, *,
                       repo_root: Path = DEFAULT_REPO_ROOT) -> list:
    family = _template_family(template)
    if family == "stats.head_to_head":
        return sorted((_season(work).get("headToHead") or {}).keys())
    if family in _STANDINGS_TEAM_FAMILIES:
        return _standings_teams(work)
    if family == "stats.season_leaders":
        return ["AVG", "ERA"]
    if family == "stats.all_time_records":
        return _all_time_record_entities(template, repo_root)
    if family == "stats.team_history":
        return _team_history_years(work)
    if family == "stats.trending":
        return ["TOP1"]
    if family and family.startswith("envelope.game_result"):
        ids = _envelope_game_ids(_latest_partition(work / "game_result"))
        return sorted(ids, reverse=True)[:5]
    if family in _SECTION_BY_WIKI_FAMILY:
        return _wiki_entities_with_section(work, _SECTION_BY_WIKI_FAMILY[family])
    if family == "graph":
        return _graph_pairs(work)
    if family == "schedule.today":
        ids = _envelope_game_ids(work / f"game_schedule/{today}")
        return sorted(ids)
    return []


# ── bind 헬퍼 ────────────────────────────────────────────────

def _read_rel(base: Path, rel: str) -> dict:
    p = base / rel
    if not p.exists():
        return {}
    return {rel: p.read_text(encoding="utf-8")}


def _content_and_payload(env: dict) -> str:
    content = (env or {}).get("content") or ""
    payload = (env or {}).get("payload")
    if payload is None:
        return content
    return content + "\n\n" + json.dumps(payload, ensure_ascii=False, sort_keys=True)


def _find_envelope_by_game_id(dir_path, game_id: str):
    if not dir_path or not dir_path.is_dir():
        return None, None
    for f in sorted(dir_path.glob("*.json")):
        try:
            env = json.loads(f.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        if ((env or {}).get("entities") or {}).get("gameId") == game_id:
            return f, env
    return None, None


def _bind_envelope(work: Path, dir_path, game_id: str) -> dict:
    f, env = _find_envelope_by_game_id(dir_path, game_id)
    if f is None:
        return {}
    rel = str(f.relative_to(work))
    return {rel: _content_and_payload(env)}


def bind(work: Path, template: dict, entity: str, today: str, *,
        repo_root: Path = DEFAULT_REPO_ROOT) -> dict:
    family = _template_family(template)
    sources = {}
    if family == "stats.all_time_records":
        sources.update(_read_rel(repo_root, "question-gen/config/all-time-records.yaml"))
    elif family == "stats.trending":
        sources.update(_read_rel(work, "wiki/stats/trending.md"))
    elif family and family.startswith("stats."):
        # stats.* 일반형(head_to_head·streaks·standings·home_away·monthly·
        # standings_trend·recent_scoring·season_leaders·team_history) — 수치는
        # json이 아니라 md 원문에서만 인용한다(환각 방지, 인터페이스 규칙).
        sources.update(_read_rel(work, "stats/season.md"))
        sources.update(_read_rel(work, "stats/kbo-official.md"))
    elif family in _SECTION_BY_WIKI_FAMILY:
        sources.update(_read_rel(work, f"wiki/players/{entity}.md"))
    elif family == "graph":
        a, b = entity.split("|")
        sources.update(_read_rel(work, f"wiki/players/{a}.md"))
        sources.update(_read_rel(work, f"wiki/players/{b}.md"))
    elif family and family.startswith("envelope.game_result"):
        sources.update(_bind_envelope(work, _latest_partition(work / "game_result"), entity))
    elif family == "schedule.today":
        sources.update(_bind_envelope(work, work / f"game_schedule/{today}", entity))
    return {"sources": sources}


# ── 최근 후보(C2 중복 검사 입력) ──────────────────────────────

def _recent_candidates(work: Path, days: int = 7) -> list:
    base = work / "quiz-candidates"
    if not base.is_dir():
        return []
    date_dirs = sorted(p for p in base.iterdir() if p.is_dir())
    out = []
    for d in date_dirs[-days:]:
        for f in sorted(d.glob("*.json")):
            try:
                data = json.loads(f.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError):
                continue
            if isinstance(data, dict):
                out.append(data)
    return out


def recent_template_counts(work: Path) -> dict:
    counts: dict = {}
    for c in _recent_candidates(work):
        tid = c.get("templateId")
        if tid:
            counts[tid] = counts.get(tid, 0) + 1
    return counts


def recent_summaries(work: Path) -> list:
    return [{"quizId": c.get("quizId"), "templateId": c.get("templateId"),
            "question": c.get("question")} for c in _recent_candidates(work)]
