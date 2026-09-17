import re
from typing import Iterable, Pattern

from validation.core.preprocess import preprocess
from validation.core.resources import (
    load_banned_words,
    load_exceptions,
    load_strict_adjacent,
)

# 필터링할 비속어 목록(카테고리별)은 외부 JSON(core/data/banned_words.json)에서 로드한다.
BANNED_WORDS: dict[str, list[str]] = load_banned_words()

# 오탐 방지용 예외 표현(core/data/exceptions.json)도 함께 로드한다.
EXCEPTIONS: list[str] = load_exceptions()

# 공백 엄격 단어(core/data/strict_adjacent.json).
STRICT_ADJACENT: list[str] = load_strict_adjacent()


def _normalize_words(words: Iterable[str]) -> list[str]:
    """
    입력 문장이 preprocess()로 정규화된 뒤 매칭되므로,
    비교 기준인 단어도 동일하게 정규화해야 매칭 기준이 일치한다.
    """
    normalized = [preprocess(word) for word in words]
    return [word for word in normalized if word]


def _compile_pattern(words: Iterable[str]) -> Pattern[str]:
    """
    목록이 비어 있으면 '아무것도 매칭하지 않는' 패턴을 반환한다.
    (공백/특수문자 제거는 preprocess() 단계가 담당하므로 패턴 자체는 단순하다.)
    """
    normalized = _normalize_words(words)
    if not normalized:
        return re.compile(r"(?!x)x")
    # 긴 단어부터 매칭하도록 길이 내림차순 정렬 (부분 매칭보다 전체 매칭 우선)
    normalized.sort(key=len, reverse=True)
    return re.compile("|".join(re.escape(word) for word in normalized))


# --- 공백 엄격 단어 분리 ---
#
# '야발'처럼 짧은 변형어는 preprocess()가 공백을 지운 뒤 검사하면 야구 채팅의 정상
# 문장을 대량으로 잡는다("야 발표 준비하자" · "이야 발이 빠르네" · "대타 야 발 진짜").
# 그래서 이 단어들만 일반 뷰에서 빼고, 공백을 남긴 뷰(preprocess.build_adjacency_view)
# 에서만 검사한다. 기존 금지어의 공백 우회 탐지('시 발' · 'ㅅ ㅂ')는 그대로 둔다.
_STRICT_WORD_SET = frozenset(_normalize_words(STRICT_ADJACENT))


def _split_strict(words: Iterable[str]) -> tuple[list[str], list[str]]:
    strict, loose = [], []
    for word in words:
        (strict if preprocess(word) in _STRICT_WORD_SET else loose).append(word)
    return strict, loose


_SPLIT_WORDS: dict[str, tuple[list[str], list[str]]] = {
    category: _split_strict(words) for category, words in BANNED_WORDS.items()
}

# 카테고리별 패턴을 앱 시작 시 1회만 컴파일해 재사용한다.
CATEGORY_PATTERNS: dict[str, Pattern[str]] = {
    category: _compile_pattern(loose) for category, (_, loose) in _SPLIT_WORDS.items()
}

# 공백 보존 뷰 전용 패턴.
STRICT_ADJACENT_PATTERNS: dict[str, Pattern[str]] = {
    category: _compile_pattern(strict) for category, (strict, _) in _SPLIT_WORDS.items()
}

# 완성형 한글 음절(가-힣)로만 이루어진 욕설 정규식.
_SYLLABLE_ONLY = re.compile(r"^[가-힣]+$")


def _compile_syllable_pattern(words: Iterable[str]) -> Pattern[str]:
    """
    '키보드' 뷰 전용: 영단어를 자판 복원하면 낱자 초성(ㅁㅊ, ㅗ 등)이 남아
    초성 욕설과 대량 오탐을 낸다. 키보드 뷰는 '시발' 같은 완성 음절만 매칭한다.
    """
    normalized = [word for word in _normalize_words(words) if _SYLLABLE_ONLY.match(word)]
    if not normalized:
        return re.compile(r"(?!x)x")
    normalized.sort(key=len, reverse=True)
    return re.compile("|".join(re.escape(word) for word in normalized))


# 키보드 뷰 전용 패턴(완성형 음절 욕설만).
KEYBOARD_PATTERNS: dict[str, Pattern[str]] = {
    category: _compile_syllable_pattern(loose)
    for category, (_, loose) in _SPLIT_WORDS.items()
}

# 예외 표현 패턴: 검사 전에 정상 표현을 문장에서 제거해 오탐을 방지한다.
EXCEPTION_PATTERN: Pattern[str] = _compile_pattern(EXCEPTIONS)


def _compile_spaced_pattern(words: Iterable[str]) -> Pattern[str]:
    """
    공백 보존 뷰 전용 예외 패턴: 글자 사이에 공백이 끼어도 매칭된다.
    등록어는 공백이 지워진 형태('샤갈전')인데 이 뷰에는 공백이 남아 있어
    ('샤갈 전시회') 그대로는 만나지 못하고, 예외가 통째로 무력화된다.
    """
    normalized = _normalize_words(words)
    if not normalized:
        return re.compile(r"(?!x)x")
    normalized.sort(key=len, reverse=True)
    return re.compile(
        "|".join(r"\s*".join(re.escape(ch) for ch in word) for word in normalized)
    )


EXCEPTION_PATTERN_SPACED: Pattern[str] = _compile_spaced_pattern(EXCEPTIONS)
