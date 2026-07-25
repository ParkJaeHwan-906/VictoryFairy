from pathlib import Path

import yaml


def load_targets(path: str) -> list[dict]:
    text = Path(path).read_text(encoding="utf-8")
    data = yaml.safe_load(text) or {}
    return list(data.get("targets") or [])
