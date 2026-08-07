import json
from collections import Counter

import pytest

from kbo_collector import stats


@pytest.mark.parametrize(
    "error,expected",
    [
        ("fetch failed after 3 attempts: https://x/1: Client error '403 Forbidden' for url 'https://x/1'", "403"),
        ("fetch failed after 3 attempts: https://x/2: Client error '429 Too Many Requests' for url '...'", "429"),
        ("fetch failed after 3 attempts: https://x/3: Server error '503 Service Unavailable' for url '...'", "503"),
        ("fetch failed after 3 attempts: https://x/4: ReadTimeout", "transport"),
        ("some parse error: 'NoneType' object has no attribute 'text'", "other"),
    ],
)
def test_http_code_classifies(error, expected):
    assert stats.http_code(error) == expected


def test_read_journal_counts(tmp_path):
    p = tmp_path / "2026-07-11.jsonl"
    p.write_text("\n".join(json.dumps(x) for x in [
        {"status": "page"}, {"status": "ok"}, {"status": "ok"},
        {"status": "dead-letter"}, {"status": "skip"},
    ]) + "\n", encoding="utf-8")
    counts = stats.read_journal_counts(p)
    assert counts == Counter({"ok": 2, "page": 1, "dead-letter": 1, "skip": 1})


def test_summarize_rate_and_codes():
    counts = Counter({"ok": 8, "dead-letter": 2, "skip": 3, "list-fail": 1, "page": 50})
    codes = Counter({"403": 2})
    out = stats.summarize(counts, codes)
    assert "detail attempted : 10" in out
    assert "success rate   : 80.0%" in out
    assert "403: 2" in out
    assert "block" in out  # 403 annotated as likely block


def test_summarize_no_attempts_is_zero_rate():
    assert "success rate   : 0.0%" in stats.summarize(Counter(), Counter())
