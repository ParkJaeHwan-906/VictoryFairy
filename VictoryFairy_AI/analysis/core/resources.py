"""사전(JSON) 로더.

NER 후처리·집계에 쓰는 사전을 코드가 아닌 외부 JSON 파일에서 읽어온다.
파일만 수정하면 코드 변경 없이 사전을 갱신할 수 있다.

각 사전의 역할은 core/data/README.md 참고.
로더는 파일이 없거나 비어 있어도 안전하게 빈 값을 반환한다.
"""

import json
from pathlib import Path

# 이 파일(resources.py) 기준으로 data 디렉토리를 찾는다.
DATA_DIR = Path(__file__).resolve().parent / "data"


def _load_json(filename: str, default):
    """data 디렉토리의 JSON 파일을 읽어 파싱한다.

    파일이 없으면 default 를 반환한다(사전 미도입 상태에서도 동작하도록).
    """
    path = DATA_DIR / filename
    if not path.exists():
        return default
    with path.open(encoding="utf-8") as f:
        return json.load(f)


def _load_str_set(filename: str) -> set[str]:
    """문자열 목록(JSON list)을 집합으로 로드한다. 공백 항목은 제외."""
    data = _load_json(filename, [])
    if not isinstance(data, list):
        raise ValueError(f"{filename} 은 문자열 리스트여야 합니다. 예: [\"홍길동\", ...]")
    return {item.strip() for item in data if isinstance(item, str) and item.strip()}


def load_persons() -> set[str]:
    """등록 인명 whitelist (재현율↑·강제 인정)."""
    return _load_str_set("persons.json")


def load_surnames() -> set[str]:
    """한국 성씨 사전 (인명 후보 교차검증)."""
    return _load_str_set("surnames.json")


def load_person_stopwords() -> set[str]:
    """인명 오탐 blocklist (강제 제외)."""
    return _load_str_set("person_stopwords.json")


def load_organizations() -> set[str]:
    """등록 기관 whitelist (신규·도메인 기관 강제 추가)."""
    return _load_str_set("organizations.json")


def load_aliases() -> dict[str, str]:
    """표면형 → 정규 인명 매핑 사전 (동일 인물 통합)."""
    data = _load_json("aliases.json", {})
    if not isinstance(data, dict):
        raise ValueError('aliases.json 은 딕셔너리여야 합니다. 예: {"길동": "홍길동"}')
    return {str(k).strip(): str(v).strip() for k, v in data.items() if str(k).strip()}
