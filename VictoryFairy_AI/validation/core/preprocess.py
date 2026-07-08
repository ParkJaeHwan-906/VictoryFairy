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
