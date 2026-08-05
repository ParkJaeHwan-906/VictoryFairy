"""③ finished_data.jsonl → ④ persons_aggregated.json (인명 정규화·집계).

문장별 분석 결과(JSONL)에서 인명을 모아 동일 인물로 통합하고 집계한다.
    출력: [{"정규화_인명", "표면형_리스트", "출현_문장_id", "빈도"}, ...]

실행: (프로젝트 루트에서)
    python -m pipeline.run_aggregate
"""

import json
from pathlib import Path

from analysis.services.normalize import aggregate_persons

DATA_DIR = Path(__file__).resolve().parent.parent / "data"
FINISHED_JSONL = DATA_DIR / "finished_data.jsonl"
PERSONS_AGG = DATA_DIR / "persons_aggregated.json"


def read_records(path: Path) -> list[dict]:
    """JSONL 파일에서 문장별 레코드를 읽는다."""
    if not path.exists():
        return []
    records: list[dict] = []
    with path.open(encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                records.append(json.loads(line))
    return records


def main() -> None:
    records = read_records(FINISHED_JSONL)
    aggregated = aggregate_persons(records)

    with PERSONS_AGG.open("w", encoding="utf-8") as f:
        json.dump(aggregated, f, ensure_ascii=False, indent=2)
        f.write("\n")

    total_mentions = sum(person["빈도"] for person in aggregated)
    print(f"문장 {len(records)}개 → 인물 {len(aggregated)}명 (총 언급 {total_mentions}회)")
    print(f"  결과: {PERSONS_AGG}")


if __name__ == "__main__":
    main()
