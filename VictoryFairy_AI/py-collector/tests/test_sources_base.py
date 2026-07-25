import pytest

from kbo_collector.sources import base


@pytest.fixture(autouse=True)
def clean_registry():
    saved = dict(base.REGISTRY)
    base.REGISTRY.clear()
    yield
    base.REGISTRY.clear()
    base.REGISTRY.update(saved)


def test_register_and_get_source():
    @base.register
    class S:
        source_id = "s1"
        doc_types = ("d1",)

        def collect(self, ctx):
            return base.CollectResult()
    assert base.available() == ["s1"]
    src = base.get_source("s1")
    assert src.source_id == "s1"
    assert src.collect(base.CollectContext(settings=None)).loaded == 0


def test_register_rejects_duplicate_id():
    @base.register
    class A:
        source_id = "dup"
        doc_types = ("d",)

        def collect(self, ctx):
            return base.CollectResult()
    with pytest.raises(ValueError, match="dup"):
        @base.register
        class B:
            source_id = "dup"
            doc_types = ("d",)

            def collect(self, ctx):
                return base.CollectResult()


def test_register_rejects_missing_attrs():
    with pytest.raises(ValueError, match="source_id"):
        @base.register
        class Bad:
            def collect(self, ctx):
                return base.CollectResult()


def test_get_source_unknown_lists_available():
    @base.register
    class S:
        source_id = "known"
        doc_types = ("d",)

        def collect(self, ctx):
            return base.CollectResult()
    with pytest.raises(KeyError, match="known"):
        base.get_source("nope")


def test_sources_for_doc_type():
    @base.register
    class S:
        source_id = "s1"
        doc_types = ("player_meme",)

        def collect(self, ctx):
            return base.CollectResult()
    assert base.sources_for("player_meme")[0].source_id == "s1"
    assert base.sources_for("unknown") == []
