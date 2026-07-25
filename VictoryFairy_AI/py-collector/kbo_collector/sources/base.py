"""소스 플러그인 레지스트리.

새 소스 추가 = 이 패키지에 모듈 1개 + @register 1줄.
run.py는 REGISTRY 조회만 하므로 소스가 늘어도 변경 없음(스펙 성공 기준 1).
"""
from dataclasses import dataclass, field


@dataclass
class CollectContext:
    """소스 실행에 필요할 수 있는 것들. 소스는 필요한 것만 꺼내 쓴다."""
    settings: object
    client: object = None   # httpx.Client (크롤 소스용)
    db: object = None       # DbSink (MySQL 적재/조회용)
    sink: object = None     # S3RawSink (S3 적재용)
    date: str = None        # 'YYYY-MM-DD' (날짜 파라미터를 받는 소스용)


@dataclass
class CollectResult:
    loaded: int = 0
    failed: list = field(default_factory=list)


REGISTRY: dict = {}


def register(cls):
    """Source 클래스를 인스턴스화해 REGISTRY에 등록하는 데코레이터."""
    for attr in ("source_id", "doc_types", "collect"):
        if not hasattr(cls, attr):
            raise ValueError(f"source class {cls.__name__} missing '{attr}'"
                             " (need source_id, doc_types, collect)")
    instance = cls()
    if instance.source_id in REGISTRY:
        raise ValueError(f"duplicate source_id '{instance.source_id}'")
    REGISTRY[instance.source_id] = instance
    return cls


def available() -> list:
    return sorted(REGISTRY)


def get_source(source_id: str):
    if source_id not in REGISTRY:
        raise KeyError(f"unknown source '{source_id}' (available: {', '.join(available())})")
    return REGISTRY[source_id]


def sources_for(doc_type: str) -> list:
    """해당 docType을 방출하는 소스들 (export 위임용)."""
    return [s for s in REGISTRY.values() if doc_type in s.doc_types]
