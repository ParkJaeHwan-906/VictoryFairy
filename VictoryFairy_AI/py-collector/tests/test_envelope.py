import json

import pytest

from kbo_collector.exports.envelope import (
    Envelope, EnvelopeError, empty_entities, s3_key, safe_id,
)


def _env(**over):
    base = dict(
        doc_id="player_meme:HT:김도영:월관보음",
        doc_type="player_meme",
        source="seed_file",
        source_ref="config/memes.yaml",
        collected_at="2026-07-15T09:00:00Z",
        title="김도영 밈: 월관보음",
        content="KIA 김도영의 팬 별명 '월관보음'.",
        tags=["밈", "별명"],
        entities={"playerUids": [123], "teamCodes": ["HT"], "gameId": None, "unresolved": []},
        payload={"text": "월관보음"},
        pii={"masked": True},
    )
    base.update(over)
    return Envelope(**base)


def test_to_dict_has_version_and_camel_keys():
    d = _env().to_dict()
    assert d["envelopeVersion"] == 1
    assert d["docId"] == "player_meme:HT:김도영:월관보음"
    assert d["docType"] == "player_meme"
    assert d["sourceRef"] == "config/memes.yaml"
    assert d["collectedAt"] == "2026-07-15T09:00:00Z"
    assert d["entities"]["playerUids"] == [123]


def test_to_dict_json_roundtrip_keeps_korean():
    s = json.dumps(_env().to_dict(), ensure_ascii=False)
    assert "월관보음" in s and json.loads(s)["title"] == "김도영 밈: 월관보음"


def test_validate_rejects_empty_content():
    with pytest.raises(EnvelopeError):
        _env(content="").validate()
    with pytest.raises(EnvelopeError):
        _env(content="   ").validate()


def test_validate_rejects_blank_required_strings():
    for field in ("doc_id", "doc_type", "source", "title"):
        with pytest.raises(EnvelopeError):
            _env(**{field: ""}).validate()


def test_validate_ok_passes():
    _env().validate()  # no raise


def test_safe_id_replaces_unsafe_chars_keeps_korean():
    assert safe_id("player_meme:HT:김도영:월관보음") == "player_meme_HT_김도영_월관보음"
    assert safe_id("a/b c?d") == "a_b_c_d"


def test_s3_key_layout():
    key = s3_key("player_meme", "2026-07-15", "player_meme:HT:김도영:월관보음")
    assert key == "question-source/player_meme/2026-07-15/player_meme_HT_김도영_월관보음.json"


def test_empty_entities_shape():
    assert empty_entities() == {"playerUids": [], "teamCodes": [], "gameId": None, "unresolved": []}


def test_safe_id_no_collision_on_trailing_unsafe():
    assert safe_id("quote:HT:") != safe_id("quote:HT::")


def test_safe_id_never_empty_for_nonempty_input():
    assert safe_id(":::") != ""
