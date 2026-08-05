from kbo_collector.masking import mask_author


def test_empty_author_returns_empty():
    assert mask_author("", "salt") == ""
    assert mask_author(None, "salt") == ""


def test_deterministic_for_same_salt():
    assert mask_author("타이거즈팬", "s1") == mask_author("타이거즈팬", "s1")


def test_salt_changes_output():
    assert mask_author("타이거즈팬", "s1") != mask_author("타이거즈팬", "s2")


def test_no_plaintext_leak_and_fixed_length():
    out = mask_author("holyfield", "pepper")
    assert "holyfield" not in out
    assert len(out) == 12
    assert all(c in "0123456789abcdef" for c in out)
