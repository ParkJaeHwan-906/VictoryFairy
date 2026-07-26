from pathlib import Path

from kbo_collector.targets import load_targets

REPO_TARGETS = Path(__file__).resolve().parents[1] / "config" / "targets.yaml"
FMKOREA_TARGETS = Path(__file__).resolve().parents[1] / "config" / "targets.fmkorea.yaml"


def test_load_repo_targets_is_dcinside_only():
    # targets.yaml is the LAMBDA target list: DCInside only. FMKorea lives in
    # targets.fmkorea.yaml (residential-only; it 430s AWS IPs).
    targets = load_targets(str(REPO_TARGETS))
    assert len(targets) == 10
    sources = [t["source"] for t in targets]
    assert sources.count("DCINSIDE") == 10
    assert sources.count("FMKOREA") == 0


def test_dcinside_targets_carry_team():
    targets = load_targets(str(REPO_TARGETS))
    dc = [t for t in targets if t["source"] == "DCINSIDE"]
    assert all(t.get("team") for t in dc)
    kia = next(t for t in dc if t["team"] == "KIA")
    assert "tigers_new2" in kia["url"]


def test_fmkorea_target_file_is_kbo_popular():
    # The residential FMKorea target file: one popular-ordered KBO target, no team.
    targets = load_targets(str(FMKOREA_TARGETS))
    assert len(targets) == 1
    fm = targets[0]
    assert fm["source"] == "FMKOREA"
    assert fm.get("team") is None
    assert fm.get("order") == "popular"
    assert "category=4332282" in fm["url"]


def test_load_custom_file(tmp_path):
    p = tmp_path / "t.yaml"
    p.write_text('targets:\n  - { source: FMKOREA, url: "https://x" }\n', encoding="utf-8")
    assert load_targets(str(p)) == [{"source": "FMKOREA", "url": "https://x"}]
