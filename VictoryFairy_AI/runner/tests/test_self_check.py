import json

import pytest

import self_check as sc


def _catalog_yaml(tmp_path):
    p = tmp_path / "catalog.yaml"
    p.write_text(
        "- id: MEME_ORIGIN\n  kind: KNOWLEDGE\n  format: MULTI4\n"
        "  subjectScope: PLAYER\n", encoding="utf-8")
    return str(p)


def _cand(quiz_id, quote):
    """work 픽스처의 wiki/players/69238.md(별명·밈 섹션 "야구의 신")를 겨냥한다."""
    return {"quizId": quiz_id, "gameId": None, "teamCodes": ["OB"],
            "kind": "KNOWLEDGE", "type": "MEME", "templateId": "MEME_ORIGIN",
            "format": "MULTI4", "question": "김대한의 별명은?",
            "options": [{"id": "A", "text": "야구의 신"}, {"id": "B", "text": "불의 신"},
                        {"id": "C", "text": "물의 신"}, {"id": "D", "text": "흙의 신"}],
            "answer": "A",
            "evidence": {"source": "wiki/players/69238.md#별명·밈", "quote": quote},
            "settlement": None, "difficulty": "EASY", "pointReward": 30, "bqReward": 1,
            "status": "PENDING", "createdAt": "2026-08-01T00:00:00Z",
            "deadlineAt": "2026-08-01T14:59:00Z", "createdBy": "AI_ENGINE"}


def test_main_passes_with_real_evidence(work, tmp_path, capsys):
    cdir = tmp_path / "raw"
    cdir.mkdir()
    (cdir / "team-OB-001.json").write_text(
        json.dumps(_cand("team-OB-001", "야구의 신"), ensure_ascii=False), encoding="utf-8")

    with pytest.raises(SystemExit) as exc:
        sc.main([str(cdir), "--work", str(work), "--repo-root", ".",
                 "--catalog", _catalog_yaml(tmp_path)])
    assert exc.value.code == 0
    assert "[OK] team-OB-001.json" in capsys.readouterr().out


def test_main_fails_when_evidence_quote_not_found(work, tmp_path, capsys):
    cdir = tmp_path / "raw"
    cdir.mkdir()
    (cdir / "team-OB-002.json").write_text(
        json.dumps(_cand("team-OB-002", "존재하지 않는 문장"), ensure_ascii=False),
        encoding="utf-8")

    with pytest.raises(SystemExit) as exc:
        sc.main([str(cdir), "--work", str(work), "--repo-root", ".",
                 "--catalog", _catalog_yaml(tmp_path)])
    assert exc.value.code == 1
    out = capsys.readouterr().out
    assert "[FAIL] team-OB-002.json" in out and "EVIDENCE-FAIL" in out


def test_main_accepts_placeholder_quiz_id(work, tmp_path):
    """최종 QZ-{date}-NNN 형식이 아닌 임시 quizId(team-OB-001)도 그대로 통과한다 —
    quizId 값 자체는 결정적 검사에 쓰이지 않는다."""
    cdir = tmp_path / "raw"
    cdir.mkdir()
    (cdir / "placeholder.json").write_text(
        json.dumps(_cand("team-OB-999-placeholder", "야구의 신"), ensure_ascii=False),
        encoding="utf-8")

    with pytest.raises(SystemExit) as exc:
        sc.main([str(cdir), "--work", str(work), "--repo-root", ".",
                 "--catalog", _catalog_yaml(tmp_path)])
    assert exc.value.code == 0
