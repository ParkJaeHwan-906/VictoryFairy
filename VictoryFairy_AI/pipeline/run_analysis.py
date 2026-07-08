"""② processed_data → ③ finished_data (형태소 추출).

data/processed_data.txt 의 통과 문장을 analysis_service(Kiwi) 로 분석해
'원본 문장 : [이름] ... | [명사] ... | [동사] ...' 형식으로
data/finished_data.txt 에 기록한다.

실행: (프로젝트 루트에서)
    python -m pipeline.run_analysis
"""

import json
from pathlib import Path

from analysis.schemas.analysis import AnalysisRequest, SentenceKeywords
from analysis.services.analysis import analysis_service

DATA_DIR = Path(__file__).resolve().parent.parent / "data"
PROCESSED = DATA_DIR / "processed_data.txt"
FINISHED = DATA_DIR / "finished_data.txt"          # 사람이 읽는 표시용
FINISHED_JSONL = DATA_DIR / "finished_data.jsonl"  # 집계용 구조화 데이터(문장_id 포함)


def read_lines(path: Path) -> list[str]:
    """파일에서 빈 줄을 제외한 문장 목록을 읽는다."""
    if not path.exists():
        return []
    with path.open(encoding="utf-8") as f:
        return [line.strip() for line in f if line.strip()]


def format_keywords(kw: SentenceKeywords) -> str:
    """'원본 문장 : [이름] ... | [지명] ... | [명사] ... | [동사] ...' 형식으로 변환한다."""
    # (라벨, 값 목록) 순서대로 비어 있지 않은 것만 출력한다.
    sections = [
        ("이름", kw.names),
        ("지명", kw.locations),
        ("기관", kw.organizations),
        ("날짜", kw.dates),
        ("명사", kw.nouns),
        ("동사", kw.verbs),
    ]
    parts = [f"[{label}] " + ", ".join(values) for label, values in sections if values]
    keywords = " | ".join(parts) if parts else "(추출된 키워드 없음)"
    return f"{kw.sent} : {keywords}"


def main() -> None:
    lines = read_lines(PROCESSED)
    response = analysis_service.analyze(AnalysisRequest(lines=lines))

    # 1) 사람이 읽는 표시용 텍스트
    formatted = [format_keywords(kw) for kw in response.results]
    with FINISHED.open("w", encoding="utf-8") as f:
        f.write("\n".join(formatted))
        if formatted:
            f.write("\n")

    # 2) 집계용 구조화 데이터(JSONL) — 문장마다 문장_id 를 부여한다.
    with FINISHED_JSONL.open("w", encoding="utf-8") as f:
        for sent_id, kw in enumerate(response.results):
            record = {"문장_id": sent_id, **kw.model_dump()}
            f.write(json.dumps(record, ensure_ascii=False) + "\n")

    print(f"통과 {len(lines)}줄 → 분석 {len(response.results)}문장")
    print(f"  결과(표시용): {FINISHED}")
    print(f"  결과(집계용): {FINISHED_JSONL}")


if __name__ == "__main__":
    main()
