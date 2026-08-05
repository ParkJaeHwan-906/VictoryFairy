"""C3 최종화 — evidence 대조·비율 선별·quizId/deadlineAt 부여·기록.

전부 결정적(현재 시각을 쓰는 createdAt만 예외) — LLM 없음.
"""
import json
from datetime import datetime, timedelta, timezone
from pathlib import Path

import yaml

#: 점수·비율의 정본. 이 모듈은 숫자를 직접 적지 않는다 — 값을 바꿀 때는
#: scoring.yaml 하나만 고치면 된다.
SCORING_PATH = (Path(__file__).resolve().parents[2]
                / "question-gen" / "config" / "scoring.yaml")


def load_scoring(path=SCORING_PATH):
    """`scoring.yaml` → `(points dict, ratio list)`.

    `dailyRatio`는 파일에 적힌 순서를 그대로 선별 우선순위로 쓴다. 값이 비면
    예외를 낸다 — 기본값으로 조용히 되돌아가면 정본과 실제 동작이 갈라진다."""
    with open(path, "r", encoding="utf-8") as f:
        doc = yaml.safe_load(f) or {}
    points = doc.get("points") or {}
    ratio = doc.get("dailyRatio") or {}
    if not points or not ratio:
        raise ValueError(f"scoring.yaml에 points/dailyRatio가 비어 있음: {path}")
    return ({str(k): int(v) for k, v in points.items()},
            [(str(k), int(v)) for k, v in ratio.items()])


POINTS, RATIO = load_scoring()
KST = timezone(timedelta(hours=9))


def _resolve(work: Path, repo_root: Path, source: str) -> Path:
    base = source.split(" (")[0].split("#")[0].strip()
    name = Path(base).name
    if base in ("wiki/stats/season.md", "wiki/stats/kbo-official.md"):
        return work / "stats" / name
    if base.startswith("wiki/"):
        return work / base
    if base.startswith("question-source/game_result/"):
        return work / "game_result" / "/".join(base.split("/")[2:])
    return repo_root / base


def check_evidence(work, repo_root, cand) -> bool:
    """KNOWLEDGE만 검사(PREDICTION은 evidence null이면 통과)."""
    if cand.get("kind") != "KNOWLEDGE":
        return cand.get("evidence") is None
    ev = cand.get("evidence") or {}
    quote, source = ev.get("quote") or "", ev.get("source") or ""
    if not quote or not source:
        return False
    p = _resolve(work, repo_root, source)
    if not p.exists():
        return False
    text = p.read_text(encoding="utf-8")
    if p.suffix == ".json":
        text = json.loads(text).get("content", "")
    return quote in text


def select_final(candidates, verdicts, entity_of):
    """verdict 순회로 폐기 사유 리스트를 만들고, 통과분의 difficulty·pointReward를
    재매핑한 뒤 RATIO 슬롯을 fun 내림차순으로 채운다. entity_of는 이 단계에서는
    쓰이지 않는다(assign_and_write의 정렬 키 조회용) — Interfaces 시그니처 유지."""
    reasons = []
    passed = []
    for c in candidates:
        qid = c["quizId"]
        v = verdicts.get(qid)
        if v is None:
            reasons.append(f"{qid}: verdict 누락 — 보수적 폐기")
            continue
        if v.get("duplicate"):
            reasons.append(f"{qid}: 중복(duplicate) 판정 — 폐기")
            continue
        if v.get("safety_violation"):
            reasons.append(f"{qid}: 안전 위반(safety_violation) 판정 — 폐기")
            continue
        fun = v.get("fun", 0)
        if fun < 4:
            reasons.append(f"{qid}: 재미 점수 부족(fun={fun}<4) — 폐기")
            continue
        new_diff = v.get("difficulty")
        if new_diff not in POINTS:
            reasons.append(f"{qid}: 난이도 재분류 값 인식 불가: {new_diff}")
            continue
        c["difficulty"] = new_diff
        c["pointReward"] = POINTS[new_diff]
        passed.append(c)

    final = []
    for diff, quota in RATIO:
        bucket = [c for c in passed if c["difficulty"] == diff]
        bucket.sort(key=lambda c: verdicts[c["quizId"]].get("fun", 0), reverse=True)
        final.extend(bucket[:quota])
        for c in bucket[quota:]:
            fun = verdicts[c["quizId"]].get("fun", 0)
            reasons.append(
                f"{c['quizId']}: 비율 슬롯 초과({diff}, fun={fun}) — fun 낮아 폐기")
    return final, reasons


def _schedule_payload(work: Path, today: str, game_id: str):
    """work/game_schedule/{today}/ 에서 entities.gameId가 일치하는 envelope의
    payload를 찾는다. 파티션·매치 envelope 없으면 None."""
    d = work / "game_schedule" / today
    if not game_id or not d.is_dir():
        return None
    for f in sorted(d.glob("*.json")):
        try:
            env = json.loads(f.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        if ((env or {}).get("entities") or {}).get("gameId") == game_id:
            return env.get("payload") or {}
    return None


def _kst_to_utc_iso(date_str: str, hhmm: str, minus_hours: float = 0) -> str:
    y, m, d = (int(x) for x in date_str.split("-"))
    hh, mm = (int(x) for x in hhmm.split(":")[:2])
    dt = datetime(y, m, d, hh, mm, tzinfo=KST) - timedelta(hours=minus_hours)
    return dt.astimezone(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def assign_and_write(final, entity_of, work: Path, today: str, reasons: "list | None" = None) -> list:
    """(templateId, entity) 사전순 정렬 → QZ-{YYYYMMDD}-{NNN} 부여 → 파일로 쓴다.

    PREDICTION인데 game_schedule에서 매치되는 startTime을 못 찾으면 그 후보는
    번호를 소비하지 않고 건너뛴다(쓰지 않음) — `reasons`가 주어지면 폐기 사유를
    한 줄 append한다(Task 7이 요약에 싣는 용도, 반환형은 list[Path] 그대로 유지)."""
    ordered = sorted(final, key=lambda c: (c["templateId"], entity_of.get(c["quizId"], "")))
    out_dir = work / "candidates" / today
    out_dir.mkdir(parents=True, exist_ok=True)
    created_at = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    ymd = today.replace("-", "")
    paths = []
    seq = 0
    for cand in ordered:
        if cand.get("kind") == "PREDICTION":
            game_id = (cand.get("settlement") or {}).get("gameId")
            payload = _schedule_payload(work, today, game_id)
            start_time = (payload or {}).get("startTime")
            if not start_time:
                if reasons is not None:
                    reasons.append(
                        f"{cand['quizId']}({cand['templateId']}): "
                        "game_schedule에서 startTime 미발견 — 폐기")
                continue
            deadline = _kst_to_utc_iso(today, start_time, minus_hours=2)
        else:
            deadline = f"{today}T14:59:00Z"
        seq += 1
        quiz_id = f"QZ-{ymd}-{seq:03d}"
        cand["quizId"] = quiz_id
        cand["createdAt"] = created_at
        cand["deadlineAt"] = deadline
        p = out_dir / f"{quiz_id}.json"
        p.write_text(json.dumps(cand, ensure_ascii=False, indent=2), encoding="utf-8")
        paths.append(p)
    return paths
