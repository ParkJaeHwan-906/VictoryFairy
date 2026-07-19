"""질문 생성 인계용 통일 envelope(v1).

분석자·질문 생성기는 envelope의 공통 필드(title/content/tags/entities)만
소비한다. payload는 docType별 구조 데이터(선택). 스펙:
docs/superpowers/specs/2026-07-15-question-source-architecture-design.md
"""
import re
from dataclasses import dataclass, field

ENVELOPE_VERSION = 1

_UNSAFE = re.compile(r"[^0-9A-Za-z가-힣._-]")


class EnvelopeError(ValueError):
    pass


def empty_entities() -> dict:
    return {"playerUids": [], "teamCodes": [], "gameId": None, "unresolved": []}


def safe_id(doc_id: str) -> str:
    """docId를 S3 키 조각으로 안전화(한글 유지, 특수문자 → '_')."""
    return _UNSAFE.sub("_", doc_id)


def s3_key(doc_type: str, date: str, doc_id: str) -> str:
    return f"question-source/{doc_type}/{date}/{safe_id(doc_id)}.json"


@dataclass
class Envelope:
    doc_id: str
    doc_type: str
    source: str
    source_ref: str
    collected_at: str
    title: str
    content: str
    tags: list = field(default_factory=list)
    entities: dict = field(default_factory=empty_entities)
    payload: dict = field(default_factory=dict)
    pii: dict = field(default_factory=lambda: {"masked": True})

    def validate(self) -> None:
        for name in ("doc_id", "doc_type", "source", "title", "content"):
            if not (getattr(self, name) or "").strip():
                raise EnvelopeError(f"envelope field '{name}' must be non-empty")

    def to_dict(self) -> dict:
        return {
            "envelopeVersion": ENVELOPE_VERSION,
            "docId": self.doc_id,
            "docType": self.doc_type,
            "source": self.source,
            "sourceRef": self.source_ref,
            "collectedAt": self.collected_at,
            "title": self.title,
            "content": self.content,
            "tags": self.tags,
            "entities": self.entities,
            "payload": self.payload,
            "pii": self.pii,
        }
