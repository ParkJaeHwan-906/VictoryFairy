"""데이터 파일(JSON) 로더.

비속어 목록·정규화 맵을 코드가 아닌 외부 JSON 파일에서 읽어온다.
파일만 수정하면 코드 변경 없이 목록을 갱신할 수 있다.
"""

import json
from pathlib import Path

# 이 파일(resources.py) 기준으로 data 디렉토리를 찾는다.
DATA_DIR = Path(__file__).resolve().parent / "data"


def _load_json(filename: str):
    """data 디렉토리의 JSON 파일을 읽어 파싱한다."""
    path = DATA_DIR / filename
    with path.open(encoding="utf-8") as f:
        return json.load(f)


def load_banned_words() -> dict[str, list[str]]:
    """비속어 단어 목록을 카테고리별로 로드한다.

    반환값: {"general": [...], "sexual": [...], "parent": [...]} 형태의 딕셔너리.
    (카테고리 키는 감지 시 어떤 유형인지 식별하는 데 쓰인다.)
    """
    data = _load_json("banned_words.json")
    if not isinstance(data, dict):
        raise ValueError(
            "banned_words.json 은 카테고리별 딕셔너리여야 합니다. "
            '예: {"general": [...], "sexual": [...]}'
        )
    return data


def load_exceptions() -> list[str]:
    """오탐(false positive) 방지용 예외 표현 목록을 로드한다.

    비속어 부분 문자열을 포함하지만 정상적인 표현(예: '보지도 못했다')을
    검사 전에 문장에서 제거하기 위한 whitelist 이다.
    """
    return _load_json("exceptions.json")


def load_normalization_maps() -> tuple[dict[str, str], dict[str, str]]:
    """정규화 맵을 로드한다.

    반환값: (단일 문자 치환 맵, 다중 문자 치환 맵)
    """
    data = _load_json("normalization.json")
    single_char = data.get("single_char", {})
    multi_char = data.get("multi_char", {})

    # str.maketrans 제약: 단일 문자 치환 맵의 key 는 반드시 한 글자여야 한다.
    invalid = [key for key in single_char if len(key) != 1]
    if invalid:
        raise ValueError(
            f"single_char 맵의 key 는 한 글자여야 합니다. 잘못된 항목: {invalid} "
            f"(여러 글자는 multi_char 로 옮기세요)"
        )

    return single_char, multi_char
