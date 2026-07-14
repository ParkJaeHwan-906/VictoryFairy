"""validation 모듈 검열 로직 테스트 (다중 뷰 매칭).

pytest 없이도 실행 가능:  `python3 tests/test_validation.py`
(pydantic 불필요 — 순수 정규화·매칭 로직만 검증한다. 서비스는 이 로직을 그대로 배선한다.)
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from validation.core.patterns import CATEGORY_PATTERNS, EXCEPTION_PATTERN
from validation.core.preprocess import build_match_views


def _detect(line: str):
    """ValidationService.validation() 과 동일한 절차: 여러 뷰를 순서대로 검사."""
    for _name, view in build_match_views(line):
        cleaned = EXCEPTION_PATTERN.sub("", view)
        for category, pattern in CATEGORY_PATTERNS.items():
            match = pattern.search(cleaned)
            if match:
                return category, match.group()
    return None


# 차단되어야 하는 입력: 직접 욕설 + 우회(기호·숫자·공백) + 로마자·초성
BLOCK = [
    "시발", "씨발", "병신", "개새끼", "존나", "섹스",
    "씨@발", "시8발", "시$발", "병1신", "지⁴랄", "개  새끼",
    "sibal", "s1b4l", "ㅅㅂ",
]
# 통과되어야 하는 정상 문장 (whitelist 포함)
ALLOW = [
    "안녕하세요", "오늘 날씨 좋네요", "회사 발표 자료 정리",
    "보지도 못했다", "basic algorithm class", "자세히 보자", "발표 준비 잘 하자",
]


def test_blocks_profanity_and_evasions():
    for line in BLOCK:
        assert _detect(line) is not None, f"차단 실패: {line!r}"


def test_allows_normal_text():
    for line in ALLOW:
        assert _detect(line) is None, f"오탐(차단됨): {line!r} -> {_detect(line)}"


def test_hangul_view_reconstructs_evasion():
    # '씨@발' → 단일 치환으로 '씨a발' 이 되지만, '한글' 뷰에서 '씨발' 로 복원된다.
    views = dict(build_match_views("씨@발"))
    assert views.get("한글") == "씨발", views


def test_english_view_isolates_romanization():
    # 'ㅋsibalㅋ' 같은 혼용 표기에서 '영어' 뷰가 라틴만 격리한다.
    views = dict(build_match_views("ㅋsibalㅋ"))
    assert views.get("영어") == "sibal", views


def test_exception_whitelist_prevents_false_positive():
    assert _detect("보지도 못했다") is None


if __name__ == "__main__":
    tests = [fn for name, fn in sorted(globals().items())
             if name.startswith("test_") and callable(fn)]
    failed = 0
    for fn in tests:
        try:
            fn()
            print(f"PASS  {fn.__name__}")
        except AssertionError as exc:
            failed += 1
            print(f"FAIL  {fn.__name__}: {exc}")
    print(f"\n{len(tests) - failed}/{len(tests)} passed")
    sys.exit(1 if failed else 0)
