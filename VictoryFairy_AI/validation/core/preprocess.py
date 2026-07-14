"""
텍스트 전처리(정규화) 파이프라인.

처리 순서:
    1. 소문자화
    2. 다중 문자 치환 (여러 글자 → 한 글자)   ← 단일 치환보다 먼저
    3. 단일 문자 치환 (숫자/유사문자 → 표준 문자)
    4. 공백 제거
    5. 특수문자 제거
"""

import re

from validation.core.resources import load_normalization_maps

# 정규화 맵은 외부 JSON(core/data/normalization.json)에서 로드한다.
#   - SINGLE_CHAR_NORMALIZATION_MAP: 숫자/유사문자 → 표준 문자 (한 글자)
#   - MULTI_CHAR_REPLACEMENTS: 여러 글자 → 한 글자
# 기호(@ $ 등)는 치환 대상이 아니라 제거 대상이므로 맵에 넣지 않는다.
SINGLE_CHAR_NORMALIZATION_MAP, MULTI_CHAR_REPLACEMENTS = load_normalization_maps()

# str.translate 용으로 미리 변환 테이블을 만들어 둔다.
_NORMALIZATION_TABLE = str.maketrans(SINGLE_CHAR_NORMALIZATION_MAP)

# 공백 / 특수문자 제거용 정규식 (미리 컴파일)
_WHITESPACE_PATTERN = re.compile(r"\s+")
# 한글(자모 포함) / 영문 / 숫자 를 제외한 나머지 문자를 특수문자로 간주해 제거
_SPECIAL_CHAR_PATTERN = re.compile(r"[^0-9a-zㄱ-ㅎㅏ-ㅣ가-힣]")

# --- 다중 뷰 매칭용 보조 정규식 ---
_DIGIT_PATTERN = re.compile(r"[0-9]")  # 숫자 제거(압축 뷰)
_NON_HANGUL_PATTERN = re.compile(r"[^ㄱ-ㅎㅏ-ㅣ가-힣]")  # 한글(자모)만 남김
_NON_LATIN_PATTERN = re.compile(r"[^a-z]")  # 라틴 소문자만 남김


def preprocess(text: str) -> str:
    """입력 문장을 정규화한 문자열로 반환한다."""
    # 1) 소문자화
    text = text.lower()

    # 2) 다중 문자 치환 — 단일 치환보다 먼저 실행해야 구성 글자가 먼저 사라지지 않는다.
    #    (예: "77"→"ㄲ" 는 "7"→"t" 단일 치환보다 앞서야 한다.)
    for source, target in MULTI_CHAR_REPLACEMENTS.items():
        text = text.replace(source, target)

    # 3) 단일 문자 치환 (숫자/유사문자 → 표준 문자)
    text = text.translate(_NORMALIZATION_TABLE)

    # 4) 공백 제거
    text = _WHITESPACE_PATTERN.sub("", text)

    # 5) 특수문자 제거 (치환하지 않고 통째로 삭제)
    text = _SPECIAL_CHAR_PATTERN.sub("", text)
    return text


def build_match_views(text: str) -> list[tuple[str, str]]:
    """욕설 매칭을 위해 입력을 여러 형태(뷰)로 변환한 목록을 반환한다.

    단일 정규화만으로는 한글 사이에 낀 기호·숫자(예: '씨@발' → '씨a발')를 놓치므로,
    같은 문장을 여러 각도로 변형해 각각 매칭한다(다중 패스). 한 형태에서 놓친 우회
    표기를 다른 형태에서 잡는 것이 목적이다.

        1. 원문   : 정규화 이전(소문자화·공백 제거만) — 치환이 훼손할 수 있는 표기 방어
        2. 정규화 : 표준 정규화본(preprocess) — 치환·특수문자 제거까지 적용
        3. 압축   : 정규화본에서 숫자 제거 — 숫자 노이즈로 끊긴 표기 병합
        4. 한글   : 정규화본에서 한글(자모)만 — 한글 사이 라틴/숫자 노이즈 제거('씨@발' → '씨발')
        5. 영어   : 정규화본에서 라틴 문자만 — 로마자 표기('sibal') 격리

    반환 순서가 곧 매칭 우선순위이며, 값이 같은 뷰는 앞선 것만 남긴다(중복 제거).

    주의: 4(한글)·5(영어) 뷰는 단어 경계를 없애므로, 한글 사이에 라틴/숫자를 억지로
    끼운 비정상 표기에서 드물게 오탐이 날 수 있다. 정상 표현은 exceptions.json으로 보정한다.
    """
    lowered = text.lower()
    normalized = preprocess(text)
    candidates = [
        ("원문", _WHITESPACE_PATTERN.sub("", lowered)),
        ("정규화", normalized),
        ("압축", _DIGIT_PATTERN.sub("", normalized)),
        ("한글", _NON_HANGUL_PATTERN.sub("", normalized)),
        ("영어", _NON_LATIN_PATTERN.sub("", normalized)),
    ]
    seen: set[str] = set()
    views: list[tuple[str, str]] = []
    for name, value in candidates:
        if value and value not in seen:
            seen.add(value)
            views.append((name, value))
    return views
