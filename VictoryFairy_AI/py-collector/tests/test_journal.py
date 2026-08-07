import json

from kbo_collector.journal import Journal, setup_logging


def test_journal_appends_valid_jsonl(tmp_path):
    j = Journal("community", "2026-07-10", "run-1", str(tmp_path))
    j.record(status="ok", source="FMKOREA", item_id="1", s3_key="k1", bytes=10)
    j.record(status="skip", source="FMKOREA", item_id="2", s3_key="k2")
    path = tmp_path / "community" / "2026-07-10.jsonl"
    lines = path.read_text(encoding="utf-8").strip().splitlines()
    assert len(lines) == 2
    first = json.loads(lines[0])
    assert first["job"] == "community"
    assert first["run_id"] == "run-1"
    assert first["status"] == "ok"
    assert first["item_id"] == "1"


def test_setup_logging_emits_json(capsys):
    logger = setup_logging("INFO")
    logger.info("landed", extra={"job": "result", "item_id": "gid1", "s3_key": "k"})
    out = capsys.readouterr().out.strip().splitlines()[-1]
    parsed = json.loads(out)
    assert parsed["msg"] == "landed"
    assert parsed["job"] == "result"
    assert parsed["item_id"] == "gid1"
