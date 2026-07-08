import re
from typing import Iterable, Pattern

from validation.core.preprocess import preprocess
from validation.core.resources import load_banned_words, load_exceptions

# 필터링할 비속어 목록(카테고리별)은 외부 JSON(core/data/banned_words.json)에서 로드한다.
BANNED_WORDS: dict[str, list[str]] = load_banned_words()

# 오탐 방지용 예외 표현(core/data/exceptions.json)도 함께 로드한다.
EXCEPTIONS: list[str] = load_exceptions()


def _normalize_words(words: Iterable[str]) -> list[str]:
    """단어 목록을 preprocess()로 정규화하고 빈 문자열을 걸러낸다.

    입력 문장이 preprocess()로 정규화된 뒤 매칭되므로,
    비교 기준인 단어도 동일하게 정규화해야 매칭 기준이 일치한다.
    """
    normalized = [preprocess(word) for word in words]
    return [word for word in normalized if word]


def _compile_pattern(words: Iterable[str]) -> Pattern[str]:
    """단어 목록으로 정규식 패턴을 컴파일한다.

    목록이 비어 있으면 '아무것도 매칭하지 않는' 패턴을 반환한다.
    (공백/특수문자 제거는 preprocess() 단계가 담당하므로 패턴 자체는 단순하다.)
    """
    normalized = _normalize_words(words)
    if not normalized:
        return re.compile(r"(?!x)x")
    # 긴 단어부터 매칭하도록 길이 내림차순 정렬 (부분 매칭보다 전체 매칭 우선)
    normalized.sort(key=len, reverse=True)
    return re.compile("|".join(re.escape(word) for word in normalized))


# 카테고리별 패턴을 앱 시작 시 1회만 컴파일해 재사용한다.
CATEGORY_PATTERNS: dict[str, Pattern[str]] = {
    category: _compile_pattern(words) for category, words in BANNED_WORDS.items()
}

# 예외 표현 패턴: 검사 전에 정상 표현을 문장에서 제거해 오탐을 방지한다.
EXCEPTION_PATTERN: Pattern[str] = _compile_pattern(EXCEPTIONS)
