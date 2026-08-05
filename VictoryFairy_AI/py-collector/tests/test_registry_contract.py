"""REGISTRY의 모든 소스가 프로토콜을 준수하는지 전수 검사 (스펙 §5-1)."""
import kbo_collector.sources  # noqa: F401
from kbo_collector.exports import exporter
from kbo_collector.sources import base


def test_every_source_satisfies_protocol():
    assert base.REGISTRY, "no sources registered"
    for sid, src in base.REGISTRY.items():
        assert isinstance(src.source_id, str) and src.source_id == sid
        assert isinstance(src.doc_types, tuple) and src.doc_types
        assert all(isinstance(d, str) for d in src.doc_types)
        assert callable(src.collect)


def test_every_doc_type_is_exportable():
    """모든 docType이 reader 또는 소유 소스(collect=export)를 가진다."""
    for src in base.REGISTRY.values():
        for doc_type in src.doc_types:
            has_reader = doc_type in exporter.READERS
            has_owner = bool(base.sources_for(doc_type))
            assert has_reader or has_owner, f"{doc_type} not exportable"


def test_expected_initial_sources():
    assert set(base.REGISTRY) == {
        "naver_games", "kbo_roster", "community_posts", "meme_dict"}
