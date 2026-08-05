"""크롤러 아티팩트 제거 테스트 (PIPE-S3IO-39).

pytest 없이 stdlib 로 실행:  `.venv/bin/python tests/test_text_normalize.py`

이 결함은 실측으로 발견됐다 — dcinside 2026-07-22 표본 337건에서 본문이
`- dc official App` 서명뿐인 글이 15건(4.5%)이었고, Bedrock 폐기 60건 중 8건이
그 서명을 판정한 결과였다. `spam` 폐기 3건은 **전부** 이것이라 실제 spam 표본이
0건이 되어 축 B 폐기율 상한을 확정할 수 없었다(BRK-LLM-44).
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from pipeline.run_bedrock import _body_slot  # noqa: E402
from pipeline.text_normalize import is_blank_content, strip_crawler_artifacts  # noqa: E402

_results = []


def check(name, fn):
    try:
        fn()
        _results.append((name, None))
    except AssertionError as exc:
        _results.append((name, str(exc) or "assertion failed"))


# ---------------------------------------------------------------------------
# strip_crawler_artifacts / is_blank_content
# ---------------------------------------------------------------------------

def test_signature_only_body_is_blank():
    """실측 15건이 이 경우다 — 서명뿐인 본문은 '내용 없음'."""
    for text in ("- dc official App", "  - dc official App  ", "\n- dc official App\n"):
        assert is_blank_content(text), f"{text!r} 는 내용 없음이어야 한다"
        assert strip_crawler_artifacts(text) == "", f"{text!r} 는 빈 문자열이 돼야 한다"


def test_signature_is_stripped_but_content_survives():
    """실측 61건 중 46건은 내용 + 서명이다 — 내용은 남아야 한다."""
    text = "5회도 제대로 못 막으면 욕 개쳐먹어야함\n- dc official App"
    out = strip_crawler_artifacts(text)
    assert out == "5회도 제대로 못 막으면 욕 개쳐먹어야함", repr(out)
    assert not is_blank_content(text)


def test_case_and_spacing_variants():
    assert is_blank_content("- DC Official App")
    assert is_blank_content("-  dc  official  App")
    assert is_blank_content("— dc official App")  # em dash


def test_normal_text_untouched():
    """정상 본문을 건드리면 안 된다 — 이 함수는 판정 대상을 정하는 데 쓰인다."""
    for text in ("오늘 경기 진짜 재밌었다", "app 스토어에서 받았음", "dc 갤러리 눈팅만 함"):
        assert strip_crawler_artifacts(text) == text.strip(), repr(text)
        assert not is_blank_content(text)


def test_non_string_input_is_blank():
    """크롤 원본에 null 이 올 수 있다 — 크래시하지 않는 것이 계약이다."""
    for value in (None, 123, [], {}):
        assert is_blank_content(value)
        assert strip_crawler_artifacts(value) == ""


# ---------------------------------------------------------------------------
# 두 러너가 같은 판단을 하는가 — 이 모듈을 공유하는 이유
# ---------------------------------------------------------------------------

def test_both_runners_agree_on_slot_decision():
    """패턴 러너와 Bedrock 러너가 **같은 텍스트**를 판정해야 한다.

    각자 str.strip() 으로 빈 본문을 판정하던 것이 결함의 뿌리였다. 두 단계가
    어긋나면 1차에서 통과시킨 것과 2차가 보는 것이 달라진다.

    패턴 러너 쪽은 process_post 가 S3 객체 전체를 다루므로, 여기서는 같은 결정식
    (is_blank_content)을 직접 대조한다.
    """
    post = {"title": "결국 이게 답이다", "body": "- dc official App"}

    # Bedrock 러너의 결정
    slot_unit, slot_text = _body_slot(post)
    assert slot_unit == "title", f"서명뿐인 본문이면 title 로 넘어가야 한다(got {slot_unit})"
    assert slot_text == "결국 이게 답이다", repr(slot_text)

    # 패턴 러너가 쓰는 결정식
    assert is_blank_content(post["body"]), "패턴 러너도 같은 판단이어야 한다"

    # 내용이 있으면 양쪽 다 body 를 고른다
    post2 = {"title": "제목", "body": "실제 본문 내용\n- dc official App"}
    unit2, text2 = _body_slot(post2)
    assert unit2 == "body" and text2 == "실제 본문 내용", (unit2, repr(text2))
    assert not is_blank_content(post2["body"])


def test_body_and_title_both_signature_falls_through():
    """둘 다 서명뿐이면 title 슬롯으로 떨어지되 크래시하지 않는다(PIPE-S3IO-33 경로)."""
    unit, _ = _body_slot({"title": "- dc official App", "body": "- dc official App"})
    assert unit == "title"


for _name, _fn in sorted((k, v) for k, v in list(globals().items()) if k.startswith("test_")):
    check(_name, _fn)

_failed = [(n, e) for n, e in _results if e]
for _n, _e in _results:
    print(f"{'FAIL' if _e else 'PASS'}  {_n}" + (f": {_e}" if _e else ""))
print(f"\n{len(_results) - len(_failed)}/{len(_results)} passed")
sys.exit(1 if _failed else 0)
