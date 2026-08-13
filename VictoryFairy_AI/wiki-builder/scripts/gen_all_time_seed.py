"""KBO 역대 기록 시드 생성 — kbo-records `history-*` 스냅샷 → YAML 초안.

퀴즈 생성기(Task 10)는 `stats.all_time_records` needs를 이 파일이 만드는
`question-gen/config/all-time-records.yaml`에서 읽는다. **나무위키 복사
금지** 원칙(스펙 4.1)의 대체재로, 원천은 항상 KBO 공식 기록실
스냅샷(Task 2가 S3 `kbo-records/{page}/{date}.json`에 적재)이다.

이 스크립트는 반자동이다: `seed_from_snapshots`가 스냅샷 표를
`{"id","title","sourcePage","rankBasis","entries":[{"rank","name","value"}]}`
카테고리로 결정적으로 변환하지만, 산출물은 **v0 초안**이며 사람 검수(이름·
순위 표본 확인, 논란성 항목 제거, 파싱 잡음 카테고리 삭제) 전에는 신뢰할 수
없다. LLM은 이 파일 어디에서도 호출하지 않는다(stdlib + PyYAML만).

**`rankBasis`(리뷰 반영)**: `rank`의 의미가 카테고리마다 정반대일 수 있다 —
`"true-rank"`는 원본 표에 실제 "순위" 열이 있어 KBO가 매긴 진짜 순위(예:
history-top-hitter, rank=1은 "역대 1위"), `"chronological"`은 순위 열이
없어 표의 행 순서를 rank로 대신 쓴 것(예: history-player-hitter/pitcher,
rank=1은 "가장 오래된(=최초) 항목"일 뿐 "역대 1위"가 아님). 이 구분이
YAML/스키마에 없으면 ALL_TIME_LEADER 같은 템플릿이 chronological 카테고리의
rank=1을 "역대 1위"로 오인해 오답 문제를 만들 수 있다 — Task 10은 반드시
`rankBasis`로 소비 템플릿을 갈라야 한다(`"chronological"` → MILESTONE_FIRST류,
`"true-rank"` → ALL_TIME_LEADER류).

테이블 → 카테고리 추출 규칙(실제 KBO 페이지 4종 실측 기반):
- 헤더에서 "순위"를 포함한 열을 rank 열로, "선수명"/"선수"/"팀명"을 포함한
  열(순위 열 제외, 먼저 매치되는 첫 열)을 name 열로 찾는다.
- name 열을 못 찾으면 그 표는 스킵한다(stderr에 경고 1줄 출력하고 카테고리를
  만들지 않는다). 예: history-team 페이지의 표는 KBO 마크업의 rowspan/th
  구조 때문에 팀명이 행이 아니라 헤더 쪽에 밀려 들어가는 실측 파싱 버그가
  있어 — py-collector kbo_records.py parse_tables가 `tr th`를 표 전체에서
  긁어오는 탓 — name 열이 존재하지 않는다. 이 표는 "파싱 잡음"이므로 애초에
  카테고리를 만들지 않는다.
- value 열을 못 찾아도 마찬가지로 스킵 + stderr 경고.
- value 열은 rank/name 열과 "연도" 열을 제외한 나머지 중 첫 번째로 숫자로
  보이는 열이다. "연도"를 명시적으로 제외하는 이유: history-player-hitter/
  pitcher 페이지는 연도별 타이틀 홀더 목록이라 "연도"가 rank/name 다음
  첫 숫자열이 되어버려, 제외하지 않으면 "연도"가 통계값으로 잘못 뽑힌다.
  주의: "첫 번째로 숫자로 보이는 열"은 그 표의 헤드라인 통계와 다를 수 있다
  (예: history-player-hitter는 헤더 순서상 "타율"보다 "타수"가 먼저 나와
  타수가 선택됨) — value는 참고용이라 무해하지만, 사람 검수 시 더 적절한
  열로 바꾸고 싶다면 수동으로 교체한다.
- rank 열이 있으면(예: history-top-hitter) `rankBasis="true-rank"`, 없으면
  (예: history-player-hitter/pitcher — 순위표가 아니라 연도순 챔피언 목록)
  표에 나온 행 순서(1부터)를 rank로 대신 쓰고 `rankBasis="chronological"`.
  두 페이지 모두 실제 KBO 데이터가 연도 오름차순(1982년부터)이라 rank=1은
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
# rankBasis="chronological"인 카테고리는 MILESTONE_FIRST(최초 달성)용, "true-rank"는
#    ALL_TIME_LEADER(역대 1위)용 — 소비 시 반드시 구분할 것(정반대 의미).
# value 열은 "첫 번째로 숫자로 보이는 열" 규칙으로 자동 선택돼 헤드라인 통계가 아닐 수
#    있음(예: 통산 타자 — 타수; 타율이 더 적절할 수 있음) — 참고용이라 무해하나 검수 시 확인.
# 갱신: 시즌 종료 후 1회 + 마일스톤 이벤트 시 수동.
"""


# ── 내부 헬퍼 ────────────────────────────────────────────────

def _find_col(headers: list, keywords: tuple, exclude: set) -> "int | None":
    for i, h in enumerate(headers):
        if i in exclude:
            continue
        if any(k in h for k in keywords):
            return i
    return None


def _is_numeric(cell) -> bool:
    if cell is None:
        return False
    try:
        float(str(cell).replace(",", ""))
        return True
    except ValueError:
        return False


def _find_value_col(headers: list, first_row: list, exclude: set) -> "int | None":
    for i, h in enumerate(headers):
        if i in exclude or h in _VALUE_EXCLUDED_HEADERS:
            continue
        if i < len(first_row) and _is_numeric(first_row[i]):
            return i
    return None


def _extract_category_body(page: str, table: dict, top_n: int) -> "dict | None":
    """name 열 또는 value 열을 못 찾거나 유효한 행이 하나도 없으면 None — 그 표는
    카테고리로 만들지 않는다(파싱 잡음 억제, 모듈 docstring 참고)."""
    headers = table.get("headers") or []
    rows = table.get("rows") or []
    if not headers or not rows:
        return None

    rank_idx = _find_col(headers, _RANK_KEYWORDS, exclude=set())
    name_exclude = {rank_idx} if rank_idx is not None else set()
    name_idx = _find_col(headers, _NAME_KEYWORDS, exclude=name_exclude)
    if name_idx is None:
        print(f"경고: {page} 표에서 이름 열을 찾지 못해 스킵함(headers={headers!r})",
              file=sys.stderr)
        return None

    value_exclude = {i for i in (rank_idx, name_idx) if i is not None}
    value_idx = _find_value_col(headers, rows[0], exclude=value_exclude)
    if value_idx is None:
        print(f"경고: {page} 표에서 수치 열을 찾지 못해 스킵함(headers={headers!r})",
              file=sys.stderr)
        return None

    # rankBasis(리뷰 반영): 표에 실제 "순위" 열이 있으면 그 값이 진짜 KBO
    # 순위("true-rank"). 없으면 행 순서를 rank로 대신 쓴 것이므로
    # "chronological" — 두 의미는 정반대이며 소비자(Task 10)가 반드시
    # 구분해야 한다(모듈 docstring 참고).
    rank_basis = "true-rank" if rank_idx is not None else "chronological"

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
    return {"title": title, "sourcePage": page, "rankBasis": rank_basis, "entries": entries}


# ── 공개 API ─────────────────────────────────────────────────

def seed_from_snapshots(snapshots: dict, top_n: int = 10) -> dict:
    """카테고리는 페이지 슬러그 사전순으로 정렬해 결정적으로 나열한다(같은 입력이면
    항상 같은 순서).
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
                "rankBasis": body["rankBasis"],
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
    args = _build_arg_parser().parse_args(argv)

    snapshots = load_snapshots_dir(args.kbo_dir)
    seed = seed_from_snapshots(snapshots, top_n=args.top_n)

    body = yaml.safe_dump(seed, allow_unicode=True, sort_keys=False)
    Path(args.out).write_text(YAML_HEADER + body, encoding="utf-8")


if __name__ == "__main__":
    main()
