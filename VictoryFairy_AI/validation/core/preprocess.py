"""
텍스트 전처리(정규화) 파이프라인.

처리 순서:
    0. 유니코드 호환 문자 접기 (전각·수학기호·원문자·악센트 → 표준 문자)
    1. 소문자화
    2. 다중 문자 치환 (여러 글자 → 한 글자)   ← 단일 치환보다 먼저
    3. 단일 문자 치환 (숫자/유사문자 → 표준 문자)
    4. 공백 제거
    5. 특수문자 제거
"""

import re
import unicodedata

from validation.core.resources import load_normalization_maps

# --- 0단계: 유니코드 호환 문자 접기 ---
#
# 유니코드에는 겉보기가 같고 코드포인트만 다른 글자가 많다.
#   전각 'ｓｉｂａｌ' · 수학 볼드 '𝘀𝗶𝗯𝗮𝗹' · 프락투르 '𝖘𝖘𝖎𝖇𝖆𝖑' · 원문자 'ⓢⓘⓑⓐⓛ'
# 이런 표기를 손으로 맵에 나열하면 새 변형이 나올 때마다 뚫린다(실제로 프락투르
# '𝖘𝖘𝖎𝖇𝖆𝖑' 이 정규화 맵에 없어 통과했다). NFKC 는 유니코드 표준이 정의한
# 호환 문자 → 표준 문자 매핑이라, 아직 관측되지 않은 변형까지 함께 접는다.
#
# ⚠️ 그냥 적용하면 검열이 통째로 죽는다. 두 가지를 막아야 한다.
#   1) NFKC 는 호환 자모 'ㅅ'(U+3145)을 조합용 자모 'ᄉ'(U+1109)로 바꾼다.
#      U+1109 는 아래 _SPECIAL_CHAR_PATTERN 의 ㄱ-ㅎ 범위 밖이라 통째로 삭제되고,
#      초성 욕설(ㅅㅂ·ㅄ·ㅗ)이 전부 무력화된다. → 호환 자모는 접기 대상에서 뺀다.
#   2) 악센트 제거용 NFD 는 한글 음절까지 자모로 분해한다('시'→'ㅅㅣ').
#      → 제거 후 NFC 로 되돌려 완성형 음절을 복구한다.
#
# 숫자·기호 leet('s1b4l'·'씨@발')은 유니코드 호환 문자가 아니라 시각적 속임수다.
# NFKC 가 건드리지 않으므로 normalization.json 의 치환 맵이 계속 담당한다.
_COMPAT_JAMO = frozenset(chr(code) for code in range(0x3131, 0x3164))  # ㄱ-ㅎ, ㅏ-ㅣ


def fold_compat(text: str) -> str:
    folded = "".join(
        ch if ch in _COMPAT_JAMO else unicodedata.normalize("NFKC", ch) for ch in text
    )
    # 결합 분음부호 제거: 'é' → 'e'
    stripped = "".join(
        ch for ch in unicodedata.normalize("NFD", folded) if not unicodedata.combining(ch)
    )
    # NFD 가 분해한 한글 음절을 완성형으로 되돌린다.
    return unicodedata.normalize("NFC", stripped)

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
    # 0) 유니코드 호환 문자 접기 — 소문자화보다 먼저.
    #    'Ⓢ' 같은 호환 문자는 접어야 대문자 'S' 가 드러나 lower() 가 먹는다.
    text = fold_compat(text)

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


# --- 두벌식 키보드 역매핑: 한/영 키를 안 누르고 친 표기('tlqkf' → '시발') 복원 ---
# 소문자 QWERTY 자판 → 한글 자모. (Shift 조합 ㄲㅆㅃㅉㄸ·ㅒㅖ 는 소문자에서 구분 불가 → 미지원)
_KB_LAYOUT = {
    "q": "ㅂ", "w": "ㅈ", "e": "ㄷ", "r": "ㄱ", "t": "ㅅ", "y": "ㅛ", "u": "ㅕ",
    "i": "ㅑ", "o": "ㅐ", "p": "ㅔ", "a": "ㅁ", "s": "ㄴ", "d": "ㅇ", "f": "ㄹ",
    "g": "ㅎ", "h": "ㅗ", "j": "ㅓ", "k": "ㅏ", "l": "ㅣ", "z": "ㅋ", "x": "ㅌ",
    "c": "ㅊ", "v": "ㅍ", "b": "ㅠ", "n": "ㅜ", "m": "ㅡ",
}
_CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
_JUNG = "ㅏㅐㅑㅒㅓㅔㅕㅖㅗㅘㅙㅚㅛㅜㅝㅞㅟㅠㅡㅢㅣ"
_JONG = ["", "ㄱ", "ㄲ", "ㄳ", "ㄴ", "ㄵ", "ㄶ", "ㄷ", "ㄹ", "ㄺ", "ㄻ", "ㄼ", "ㄽ",
         "ㄾ", "ㄿ", "ㅀ", "ㅁ", "ㅂ", "ㅄ", "ㅅ", "ㅆ", "ㅇ", "ㅈ", "ㅊ", "ㅋ", "ㅌ", "ㅍ", "ㅎ"]
_CONS = set(_CHO)
_VOWEL = set(_JUNG)
_JONG_INDEX = {j: i for i, j in enumerate(_JONG)}
_VOWEL_COMBINE = {
    ("ㅗ", "ㅏ"): "ㅘ", ("ㅗ", "ㅐ"): "ㅙ", ("ㅗ", "ㅣ"): "ㅚ", ("ㅜ", "ㅓ"): "ㅝ",
    ("ㅜ", "ㅔ"): "ㅞ", ("ㅜ", "ㅣ"): "ㅟ", ("ㅡ", "ㅣ"): "ㅢ",
}
_JONG_COMBINE = {
    ("ㄱ", "ㅅ"): "ㄳ", ("ㄴ", "ㅈ"): "ㄵ", ("ㄴ", "ㅎ"): "ㄶ", ("ㄹ", "ㄱ"): "ㄺ",
    ("ㄹ", "ㅁ"): "ㄻ", ("ㄹ", "ㅂ"): "ㄼ", ("ㄹ", "ㅅ"): "ㄽ", ("ㄹ", "ㅌ"): "ㄾ",
    ("ㄹ", "ㅍ"): "ㄿ", ("ㄹ", "ㅎ"): "ㅀ", ("ㅂ", "ㅅ"): "ㅄ",
}
_JONG_SPLIT = {combined: parts for parts, combined in _JONG_COMBINE.items()}


def keyboard_to_hangul(latin: str) -> str:
    jamos = [_KB_LAYOUT[ch] for ch in latin if ch in _KB_LAYOUT]
    out: list[str] = []
    lead = vowel = tail = None

    def block() -> str:
        if lead is not None and vowel is not None:
            ci = _CHO.index(lead)
            ji = _JUNG.index(vowel)
            ki = _JONG_INDEX.get(tail or "", 0)
            return chr(0xAC00 + (ci * 21 + ji) * 28 + ki)
        return (lead or "") + (vowel or "") + (tail or "")

    for jamo in jamos:
        if jamo in _CONS:
            if lead is None and vowel is None:
                lead = jamo
            elif vowel is None:  # 자음+자음(모음 없음): 앞 자음 확정
                out.append(block()); lead, vowel, tail = jamo, None, None
            elif tail is None:  # 초+중 뒤 받침 자리
                if jamo in _JONG_INDEX:
                    tail = jamo
                else:
                    out.append(block()); lead, vowel, tail = jamo, None, None
            elif (tail, jamo) in _JONG_COMBINE:  # 겹받침
                tail = _JONG_COMBINE[(tail, jamo)]
            else:
                out.append(block()); lead, vowel, tail = jamo, None, None
        elif jamo in _VOWEL:
            if lead is None:  # 초성 없는 모음 → 낱자
                out.append(jamo)
            elif vowel is None:
                vowel = jamo
            elif tail is None:  # 초+중 뒤 모음 → 복합모음 시도
                if (vowel, jamo) in _VOWEL_COMBINE:
                    vowel = _VOWEL_COMBINE[(vowel, jamo)]
                else:
                    out.append(block()); lead, vowel, tail = None, jamo, None
            else:  # 받침 뒤 모음 → 받침이 다음 음절 초성으로 이동
                if tail in _JONG_SPLIT:
                    keep, move = _JONG_SPLIT[tail]
                else:
                    keep, move = None, tail
                tail = keep
                out.append(block())
                lead, vowel, tail = move, jamo, None
    out.append(block())
    return "".join(out)


def build_match_views(text: str) -> list[tuple[str, str]]:
    """
    주의:
    - 4(한글)·5(영어) 뷰는 단어 경계를 없애므로, 한글 사이에 라틴/숫자를 억지로 끼운
      비정상 표기에서 드물게 오탐이 날 수 있다. 정상 표현은 exceptions.json으로 보정.
    - 6(키보드) 뷰는 영단어가 우연히 낱자 초성 욕설로 조합되어 오탐이 크다. 따라서
      이 뷰는 **완성형 음절 욕설만** 매칭해야 한다(patterns.KEYBOARD_PATTERNS 사용).
    """
    lowered = text.lower()
    normalized = preprocess(text)
    english = _NON_LATIN_PATTERN.sub("", normalized)
    candidates = [
        ("원문", _WHITESPACE_PATTERN.sub("", lowered)),
        ("정규화", normalized),
        ("압축", _DIGIT_PATTERN.sub("", normalized)),
        ("한글", _NON_HANGUL_PATTERN.sub("", normalized)),
        ("영어", english),
        ("키보드", keyboard_to_hangul(english)),
    ]
    seen: set[str] = set()
    views: list[tuple[str, str]] = []
    for name, value in candidates:
        if value and value not in seen:
            seen.add(value)
            views.append((name, value))
    return views
