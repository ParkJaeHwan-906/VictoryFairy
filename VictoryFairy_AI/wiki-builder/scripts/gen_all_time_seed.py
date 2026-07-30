"""KBO 역대 기록 시드 생성 — kbo-records `history-*` 스냅샷 → YAML 초안.

퀴즈 생성기(Task 10)는 `stats.all_time_records` needs를 이 파일이 만드는
`question-gen/config/all-time-records.yaml`에서 읽는다. **나무위키 복사
금지** 원칙(스펙 4.1)의 대체재로, 원천은 항상 KBO 공식 기록실
스냅샷(Task 2가 S3 `kbo-records/{page}/{date}.json`에 적재)이다.

이 스크립트는 반자동이다: `seed_from_snapshots`가 스냅샷 표를
`{"id","title","sourcePage","entries":[{"rank","name","value"}]}` 카테고리로
결정적으로 변환하지만, 산출물은 **v0 초안**이며 사람 검수(이름·순위 표본
확인, 논란성 항목 제거, 파싱 잡음 카테고리 삭제) 전에는 신뢰할 수 없다.
LLM은 이 파일 어디에서도 호출하지 않는다(stdlib + PyYAML만).

테이블 → 카테고리 추출 규칙(실제 KBO 페이지 4종 실측 기반):
- 헤더에서 "순위"를 포함한 열을 rank 열로, "선수명"/"선수"/"팀명"을 포함한
  열(순위 열 제외, 먼저 매치되는 첫 열)을 name 열로 찾는다.
- name 열을 못 찾으면 그 표는 스킵한다(예: history-team 페이지의 표는 KBO
  마크업의 rowspan/th 구조 때문에 팀명이 행이 아니라 헤더 쪽에 밀려 들어가는
  실측 파싱 버그가 있어 — py-collector kbo_records.py parse_tables가
  `tr th`를 표 전체에서 긁어오는 탓 — name 열이 존재하지 않는다. 이 표는
  "파싱 잡음"이므로 애초에 카테고리를 만들지 않는다).
- value 열은 rank/name 열과 "연도" 열을 제외한 나머지 중 첫 번째로 숫자로
  보이는 열이다. "연도"를 명시적으로 제외하는 이유: history-player-hitter/
  pitcher 페이지는 연도별 타이틀 홀더 목록이라 "연도"가 rank/name 다음
  첫 숫자열이 되어버려, 제외하지 않으면 "연도"가 통계값으로 잘못 뽑힌다.
- rank 열이 없는 표(예: history-player-hitter/pitcher — 순위표가 아니라
  연도순 챔피언 목록)는 표에 나온 행 순서(1부터)를 rank로 대신 쓴다. 두
  페이지 모두 실제 KBO 데이터가 연도 오름차순(1982년부터)이라 rank=1은
  "최초" 달성자에 대응한다 — MILESTONE_FIRST 템플릿과 자연스럽게 맞는다.

value는 참고용 수치일 뿐이며 퀴즈 정답으로 쓰지 않는다(스펙 4.1) — YAML
헤더 주석에 이 규칙을 반드시 적어 둔다.
"""

import argparse
import sys
from pathlib import Path

# question-gen/scripts의 aggregate_stats(Task 5)를 재사용한다. 테스트는
# tests/conftest.py가 question-gen/scripts·wiki-builder/scripts를 모두
# sys.path에 넣어 두므로 이 삽입이 없어도 동작하지만, 스크립트를 직접
# 실행할 때는(`python wiki-builder/scripts/gen_all_time_seed.py ...`) 이
# 삽입이 없으면 aggregate_stats를 찾지 못한다.
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "question-gen" / "scripts"))

from aggregate_stats import load_snapshots_dir  # noqa: E402

import yaml  # noqa: E402

# ── 상수 ─────────────────────────────────────────────────

#: 처리 대상 페이지(kbo-records history-* 슬러그)의 한글명. 이 dict에 없는
#: 페이지(hitter-basic, team-rank-daily 등)는 이 스크립트의 대상이 아니므로
#: --kbo-dir에 같이 있어도 무시한다.
PAGE_KO = {
    "history-player-hitter": "통산 타자",
    "history-player-pitcher": "통산 투수",
    "history-top-hitter": "역대 TOP",
    "history-team": "역대 팀 기록",
}

_RANK_KEYWORDS = ("순위",)
_NAME_KEYWORDS = ("선수명", "선수", "팀명")
#: value 열 탐색에서 항상 제외하는 헤더(연도는 통계값이 아니라 메타데이터).
_VALUE_EXCLUDED_HEADERS = ("연도",)

#: 검수 지침 헤더. 첫 줄은 v0 초안 경고(컨트롤러 지시 — 사람 검수 완료 후
#: 수동으로 제거). 나머지는 브리프가 지정한 규칙 주석 그대로다. 재생성될
#: 때마다 이 헤더가 다시 붙는다 — 규칙 자체는 "매 갱신 때마다 재검수 필요"
#: 이므로 의도된 동작이다.
YAML_HEADER = """\
# ⚠️ 초안 v0 — 사람 검수 전(검수 완료 시 이 줄 제거). routine 등록 전 검수 필수.
# KBO 역대 기록 시드 — gen_all_time_seed.py 초안 + 사람 검수 완료본.
# ⚠️ 규칙(스펙 4.1): 수치(value)는 참고용 — 퀴즈 정답으로 사용 금지(순위·최초달성형만).
#    논란성 항목(약물 등)은 검수 시 삭제. 나무위키 복사 금지 — 원천은 KBO 공식 기록실.
# 갱신: 시즌 종료 후 1회 + 마일스톤 이벤트 시 수동.
"""


# ── 내부 헬퍼 ────────────────────────────────────────────────

def _find_col(headers: list, keywords: tuple, exclude: set) -> "int | None":
    """headers에서 exclude 인덱스를 건너뛰고, keywords 중 하나라도 부분
    문자열로 포함하는 첫 열의 인덱스를 반환한다. 없으면 None."""
    for i, h in enumerate(headers):
        if i in exclude:
            continue
        if any(k in h for k in keywords):
            return i
    return None


def _is_numeric(cell) -> bool:
    """셀 값이 숫자로 보이는지(쉼표 제거 후 float 변환 가능 여부). 빈 문자열/
    None은 숫자가 아니다."""
    if cell is None:
        return False
    try:
        float(str(cell).replace(",", ""))
        return True
    except ValueError:
        return False


def _find_value_col(headers: list, first_row: list, exclude: set) -> "int | None":
    """rank/name 열과 `_VALUE_EXCLUDED_HEADERS`를 제외한 나머지 중, 첫 행
    기준으로 숫자로 보이는 첫 열의 인덱스. 없으면 None."""
    for i, h in enumerate(headers):
        if i in exclude or h in _VALUE_EXCLUDED_HEADERS:
            continue
        if i < len(first_row) and _is_numeric(first_row[i]):
            return i
    return None


def _extract_category_body(page: str, table: dict, top_n: int) -> "dict | None":
    """표 하나에서 카테고리 본문(`{"title","sourcePage","entries"}`)을
    추출한다. name 열 또는 value 열을 못 찾거나 유효한 행이 하나도 없으면
    None(그 표는 카테고리로 만들지 않는다 — 파싱 잡음 억제)."""
    headers = table.get("headers") or []
    rows = table.get("rows") or []
    if not headers or not rows:
        return None

    rank_idx = _find_col(headers, _RANK_KEYWORDS, exclude=set())
    name_exclude = {rank_idx} if rank_idx is not None else set()
    name_idx = _find_col(headers, _NAME_KEYWORDS, exclude=name_exclude)
    if name_idx is None:
        return None

    value_exclude = {i for i in (rank_idx, name_idx) if i is not None}
    value_idx = _find_value_col(headers, rows[0], exclude=value_exclude)
    if value_idx is None:
        return None

    entries = []
    for pos, row in enumerate(rows[:top_n], start=1):
        if name_idx >= len(row) or value_idx >= len(row):
            continue
        rank = pos
        if rank_idx is not None and rank_idx < len(row):
            try:
                rank = int(row[rank_idx])
            except ValueError:
                rank = pos
        entries.append({"rank": rank, "name": row[name_idx], "value": row[value_idx]})

    if not entries:
        return None

    title = f"{PAGE_KO.get(page, page)} — {headers[value_idx]}"
    return {"title": title, "sourcePage": page, "entries": entries}


# ── 공개 API ─────────────────────────────────────────────────

def seed_from_snapshots(snapshots: dict, top_n: int = 10) -> dict:
    """kbo-records 스냅샷 dict(`{page_slug: snapshot_dict}`)를 역대 기록
    시드로 변환한다. `PAGE_KO`에 없는 페이지는 무시한다(history-* 외
    페이지는 이 스크립트의 대상이 아님).

    `asOf`는 처리 대상이 된 스냅샷들의 `date` 중 최댓값(가장 최근 수집일).
    스냅샷이 하나도 없으면 None. 카테고리는 페이지 슬러그 사전순으로
    정렬해 결정적으로 나열한다(같은 입력이면 항상 같은 순서).
    """
    relevant = {page: snap for page, snap in snapshots.items() if page in PAGE_KO and snap}
    dates = [snap.get("date") for snap in relevant.values() if snap.get("date")]
    as_of = max(dates) if dates else None

    categories = []
    for page in sorted(relevant):
        tables = relevant[page].get("tables") or []
        seq = 0
        for table in tables:
            body = _extract_category_body(page, table, top_n)
            if body is None:
                continue
            seq += 1
            cat_id = page if seq == 1 else f"{page}__{seq}"
            categories.append({
                "id": cat_id,
                "title": body["title"],
                "sourcePage": body["sourcePage"],
                "entries": body["entries"],
            })

    return {"asOf": as_of, "source": "KBO 공식 기록실", "categories": categories}


# ── CLI ──────────────────────────────────────────────────

def _build_arg_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        description="kbo-records history-* 스냅샷에서 역대 기록 시드 YAML 초안을 생성한다"
                    "(LLM 미사용, 결정적). 산출물은 v0 초안 — 사람 검수 전 routine 등록 금지.")
    p.add_argument("--kbo-dir", required=True, help="kbo-records 스냅샷 디렉토리({page}/{date}.json)")
    p.add_argument("--out", required=True, help="YAML 출력 경로")
    p.add_argument("--top-n", type=int, default=10, help="카테고리당 상위 N개 항목(기본 10)")
    return p


def main(argv: "list | None" = None) -> None:
    """CLI 진입점. `--kbo-dir`를 `load_snapshots_dir`(Task 5)로 읽어
    `seed_from_snapshots`를 호출하고, 결과를 YAML로 `--out`에 쓴다.
    출력 앞에는 항상 `YAML_HEADER`(v0 초안 경고 + 검수 규칙 주석)를 붙인다."""
    args = _build_arg_parser().parse_args(argv)

    snapshots = load_snapshots_dir(args.kbo_dir)
    seed = seed_from_snapshots(snapshots, top_n=args.top_n)

    body = yaml.safe_dump(seed, allow_unicode=True, sort_keys=False)
    Path(args.out).write_text(YAML_HEADER + body, encoding="utf-8")


if __name__ == "__main__":
    main()
