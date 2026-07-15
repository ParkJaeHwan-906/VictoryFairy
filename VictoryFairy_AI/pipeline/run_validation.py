"""① crawled_data → ② processed_data (검열 1차 필터링).

data/crawled_data.txt 의 각 문장을 validation_service 로 검열해
    - 통과 문장 → data/processed_data.txt
    - 폐기 문장 → data/discarded_data.txt (폐기 사유 포함, 설계 §159)
에 각각 기록한다.

실행: (프로젝트 루트에서)
    python -m pipeline.run_validation
"""

from pathlib import Path

from validation.schemas.validation import ValidationRequest
from validation.services.validation import validation_service

DATA_DIR = Path(__file__).resolve().parent.parent / "data"
CRAWLED = DATA_DIR / "crawled_data.txt"
PROCESSED = DATA_DIR / "processed_data.txt"
DISCARDED = DATA_DIR / "discarded_data.txt"


def read_lines(path: Path) -> list[str]:
    """파일에서 빈 줄을 제외한 문장 목록을 읽는다."""
    if not path.exists():
        return []
    with path.open(encoding="utf-8") as f:
        return [line.strip() for line in f if line.strip()]


def write_lines(path: Path, lines: list[str]) -> None:
    """문장 목록을 파일에 기록한다(줄바꿈 구분)."""
    with path.open("w", encoding="utf-8") as f:
        f.write("\n".join(lines))
        if lines:
            f.write("\n")


def main() -> None:
    lines = read_lines(CRAWLED)
    passed: list[str] = []
    discarded: list[str] = []

    for line in lines:
        result = validation_service.validation(ValidationRequest(line=line))
        if result.is_valid:
            passed.append(line)
        else:
            # 원본 문장 + 폐기 사유를 함께 남긴다(플라이휠용 로그).
            discarded.append(f"{line}\t# {result.message}")

    write_lines(PROCESSED, passed)
    write_lines(DISCARDED, discarded)

    print(f"입력 {len(lines)}문장 → 통과 {len(passed)} / 폐기 {len(discarded)}")
    print(f"  통과: {PROCESSED}")
    print(f"  폐기: {DISCARDED}")


if __name__ == "__main__":
    main()
