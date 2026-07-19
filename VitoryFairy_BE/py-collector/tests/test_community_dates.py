"""Post-date parsing (list cells) and the date-filtered pagination walk."""
from types import SimpleNamespace

import pytest

from kbo_collector import community, run


# --------------------------------------------------------------------------- date parse
@pytest.mark.parametrize(
    "text,today,expected",
    [
        ("14:02", "2026-07-14", "2026-07-14"),   # HH:MM -> recent, anchored to today
        ("07.11", "2026-07-14", "2026-07-11"),   # MM.DD -> current year
        ("07.11", "2026-01-05", "2025-07-11"),   # MM.DD in the future -> last year
        ("2025.07.11", "2026-07-14", "2025-07-11"),
        ("", "2026-07-14", None),
    ],
)
def test_fmkorea_date(text, today, expected):
    assert community._fmkorea_date(text, today) == expected


def test_fmkorea_list_reads_post_date():
    html = """
    <table class="bd_lst"><tbody>
      <tr><td class="title"><a href="/111">글1</a></td><td class="time">07.11</td></tr>
      <tr><td class="title"><a href="/222">글2</a></td><td class="time">14:02</td></tr>
    </tbody></table>
    """
    refs = community.parse_fmkorea_list(html, today="2026-07-14")
    assert [(r.post_id, r.post_date) for r in refs] == [
        ("111", "2026-07-11"),
        ("222", "2026-07-14"),
    ]


def test_fmkorea_list_without_today_leaves_date_none():
    html = '<table class="bd_lst"><tbody><tr><td class="title"><a href="/1">x</a></td><td class="time">07.11</td></tr></tbody></table>'
    assert community.parse_fmkorea_list(html)[0].post_date is None


def test_dcinside_list_reads_views_and_recommend():
    html = """
    <table><tbody>
      <tr class="ub-content"><td class="gall_num">10</td>
        <td class="gall_tit ub-word"><a href="/board/view/?id=g&no=10">글</a></td>
        <td class="gall_date" title="2026-07-11 22:15:03">07.11</td>
        <td class="gall_count">436</td><td class="gall_recommend">18</td></tr>
    </tbody></table>
    """
    ref = community.parse_dcinside_list(html)[0]
    assert ref.views == 436
    assert ref.recommend == 18


def test_fmkorea_list_reads_recommend_no_views():
    html = ('<table class="bd_lst"><tbody><tr>'
            '<td class="title"><a href="/1">x</a></td><td class="time">07.11</td>'
            '<td class="m_no">3</td><td class="m_no m_no_voted">27</td>'
            '</tr></tbody></table>')
    ref = community.parse_fmkorea_list(html, today="2026-07-14")[0]
    assert ref.recommend == 27   # td.m_no.m_no_voted, not the comment-count td.m_no
    assert ref.views is None


# --------------------------------------------------------------------------- webzine layout
def _webzine_li(srl, count, title, *, thumb=None, regdate=None):
    """A single FMKorea popularity ('webzine') card, matching the live markup."""
    href = f"/index.php?mid=baseball&sort_index=pop&document_srl={srl}&listStyle=webzine"
    img = (f'<a href="{href}"><img class="thumb" alt="{title}" '
           f'data-original="{thumb}" src="//image.fmkorea.com/classes/lazy/img/transparent.gif"/></a>'
           if thumb else "")
    rd = f'<div><span class="regdate">{regdate}<!-- {regdate} --></span></div>' if regdate else ""
    return f"""
    <li class="li li_best2_pop0">
      <div class="li">
        <a class="pc_voted_count pc_voted_count_plus" href="{href}">
          <span class="label">추천</span><span class="count">{count}</span></a>
        {img}
        <h3 class="title" data-title-ellipsis="true">
          <a class="hotdeal_var8" href="{href}">
            <span class="ellipsis-target">{title}</span>
            <span class="comment_count">[11]</span></a></h3>
        {rd}
      </div>
    </li>"""


def _webzine(*lis):
    return f'<div class="fm_best_widget"><ul>{"".join(lis)}</ul></div>'


def test_fmkorea_webzine_parses_card_from_thumb_cachebuster():
    # pop sort renders the .fm_best_widget card grid; the accurate authored date
    # comes from the thumbnail cache-buster (?c=YYYYMMDDHHMMSS), not the HH:MM cell.
    html = _webzine(_webzine_li(
        "10095740302", "38", "웰뱅톱랭킹 투수 순위",
        thumb="//image.fmkorea.com/filesn/cache/thumb/20260717/10095740302_70x50.crop.webp?c=20260717193332",
        regdate="06:52"))
    ref = community.parse_fmkorea_list(html, today="2026-07-18")[0]
    assert ref.post_id == "10095740302"
    assert ref.recommend == 38
    assert ref.title == "웰뱅톱랭킹 투수 순위"
    assert ref.post_date == "2026-07-17"        # thumb date, not the "06:52" cell (=today)
    assert ref.url.endswith("/10095740302")


def test_fmkorea_webzine_date_from_thumb_path_when_no_cachebuster():
    html = _webzine(_webzine_li(
        "555", "40", "t",
        thumb="//image.fmkorea.com/filesn/cache/thumb/20260401/555_70x50.crop.webp"))
    ref = community.parse_fmkorea_list(html, today="2026-07-18")[0]
    assert ref.post_date == "2026-04-01"


def test_fmkorea_webzine_date_falls_back_to_regdate_without_thumb():
    # a card with no thumbnail -> use the (less precise) regdate cell, ignoring the
    # duplicated HTML comment so MM.DD isn't corrupted into a double value.
    html = _webzine(_webzine_li("777", "31", "t", regdate="04.15"))
    ref = community.parse_fmkorea_list(html, today="2026-07-18")[0]
    assert ref.post_id == "777"
    assert ref.post_date == "2026-04-15"


def test_fmkorea_list_prefers_table_over_webzine_when_both_present():
    # The default recency list is a table; parser must use it, not any stray widget.
    html = ('<table class="bd_lst"><tbody><tr>'
            '<td class="title"><a href="/900">table글</a></td><td class="time">07.11</td>'
            '<td class="m_no m_no_voted">12</td></tr></tbody></table>'
            + _webzine(_webzine_li("999", "50", "webzine글", regdate="06:00")))
    refs = community.parse_fmkorea_list(html, today="2026-07-14")
    assert [r.post_id for r in refs] == ["900"]  # table layout wins


def _ref(post_id, views=None, recommend=None):
    return community.PostRef(post_id=post_id, url=f"/{post_id}", views=views, recommend=recommend)


def test_filter_popular_recommend_arm():
    refs = [_ref("a", views=100, recommend=15), _ref("b", views=100, recommend=3)]
    kept = run._filter_popular(refs, min_recommend=10, view_factor=0)
    assert [r.post_id for r in kept] == ["a"]  # only recommend>=10


def test_filter_popular_view_arm_uses_average():
    # avg views = (100+100+100+900)/4 = 300; factor 2 -> bar 600. Only 'd' passes.
    refs = [_ref("a", views=100, recommend=0), _ref("b", views=100, recommend=0),
            _ref("c", views=100, recommend=0), _ref("d", views=900, recommend=0)]
    kept = run._filter_popular(refs, min_recommend=0, view_factor=2.0)
    assert [r.post_id for r in kept] == ["d"]


def test_filter_popular_or_semantics():
    # low-view crowd keeps the average low so the high-view post clears 3*avg.
    crowd = [_ref(f"low{i}", views=10, recommend=1) for i in range(8)]
    refs = [_ref("hi_rec", views=10, recommend=50),
            _ref("hi_view", views=9999, recommend=0), *crowd]
    kept = run._filter_popular(refs, min_recommend=20, view_factor=3.0)
    assert set(r.post_id for r in kept) == {"hi_rec", "hi_view"}  # either arm qualifies


def test_filter_popular_keeps_signalless_and_disabled():
    # 배치 전체가 무신호(views/recommend 모두 None)면 판단 불가 -> 전부 keep
    signalless = [_ref("a"), _ref("b")]
    assert {r.post_id for r in run._filter_popular(signalless, 10, 3.0)} == {"a", "b"}
    # 임계 0/0이면 필터 비활성 -> 전부 keep
    refs = [_ref("nosig"), _ref("low", views=1, recommend=0)]
    assert run._filter_popular(refs, 0, 0) == refs


def test_filter_popular_blank_recommend_treated_as_zero():
    # FMKorea: 배치에 추천 신호가 있으면 추천란 빈칸(None)은 0으로 간주해 미달 drop.
    refs = [_ref("blank"), _ref("hot", recommend=50)]
    kept = run._filter_popular(refs, min_recommend=30, view_factor=0)
    assert [r.post_id for r in kept] == ["hot"]


def test_dcinside_list_reads_post_date_from_title():
    html = """
    <table><tbody>
      <tr class="ub-content"><td class="gall_num">10</td>
        <td class="gall_tit ub-word"><a href="/board/view/?id=g&no=10">글</a></td>
        <td class="gall_date" title="2026-07-11 22:15:03">07.11</td></tr>
    </tbody></table>
    """
    ref = community.parse_dcinside_list(html)[0]
    assert ref.post_id == "10"
    assert ref.post_date == "2026-07-11"


# --------------------------------------------------------------------------- pagination walk
def _dc_page(rows):
    trs = "".join(
        f'<tr class="ub-content"><td class="gall_num">{i}</td>'
        f'<td class="gall_tit ub-word"><a href="/board/view/?id=g&no={i}">t</a></td>'
        f'<td class="gall_date" title="{d} 10:00:00">x</td></tr>'
        for i, d in rows
    )
    return f"<table><tbody>{trs}</tbody></table>"


def test_collect_stops_after_passing_target_date(monkeypatch):
    # page1: all 07-13 (newer, skip); page2: 07-11 x2 then 07-10 (stop);
    # page3 must never be fetched.
    pages = {
        1: _dc_page([(1, "2026-07-13"), (2, "2026-07-13")]),
        2: _dc_page([(3, "2026-07-11"), (4, "2026-07-11"), (5, "2026-07-10")]),
    }
    fetched: list[int] = []

    def fake_fetch(client, url, *, settings, accept=None, referer=None):
        page = int(url.split("page=")[1])
        fetched.append(page)
        return SimpleNamespace(text=pages[page])

    monkeypatch.setattr(run.fetch, "fetch", fake_fetch)
    settings = SimpleNamespace(community_max_pages=10, fetch_delay_ms=0, community_list_retries=3, rate_limit_cooldown_s=0, retry_backoff_base=0)
    journal = SimpleNamespace(record=lambda **k: None)
    target = {"source": "DCINSIDE", "url": "https://x/?id=g", "team": "KIA"}

    refs = run._collect_refs_for_date(
        target, "2026-07-11", settings=settings, client=None,
        journal=journal, today="2026-07-14", sleep=lambda s: None,
    )
    assert [r.post_id for r in refs] == ["3", "4"]  # only 07-11 posts
    assert fetched == [1, 2]                          # stopped, page 3 untouched


def test_collect_respects_cap(monkeypatch):
    # 3 posts on the target date across one page, cap=2 -> keep the 2 newest.
    pages = {1: _dc_page([(1, "2026-07-11"), (2, "2026-07-11"), (3, "2026-07-11")])}
    fetched: list[int] = []

    def fake_fetch(client, url, *, settings, accept=None, referer=None):
        page = int(url.split("page=")[1])
        fetched.append(page)
        return SimpleNamespace(text=pages[page])

    monkeypatch.setattr(run.fetch, "fetch", fake_fetch)
    settings = SimpleNamespace(community_max_pages=10, fetch_delay_ms=0, community_list_retries=3, rate_limit_cooldown_s=0, retry_backoff_base=0)
    journal = SimpleNamespace(record=lambda **k: None)
    target = {"source": "DCINSIDE", "url": "https://x/?id=g"}
    refs = run._collect_refs_for_date(
        target, "2026-07-11", settings=settings, client=None,
        journal=journal, today="2026-07-14", sleep=lambda s: None, cap=2,
    )
    assert [r.post_id for r in refs] == ["1", "2"]  # newest two, capped
    assert fetched == [1]                            # stopped mid-page, no page 2


def test_collect_retries_intermittent_list_failure(monkeypatch):
    # page 1 fails twice (simulated 430) then succeeds -> walk must continue, not abort.
    pages = {1: _dc_page([(1, "2026-07-11"), (2, "2026-07-10")])}
    attempts = {"n": 0}

    def flaky_fetch(client, url, *, settings, accept=None, referer=None):
        if int(url.split("page=")[1]) == 1 and attempts["n"] < 2:
            attempts["n"] += 1
            raise RuntimeError("Client error '430 Unknown'")
        return SimpleNamespace(text=pages[int(url.split("page=")[1])])

    monkeypatch.setattr(run.fetch, "fetch", flaky_fetch)
    settings = SimpleNamespace(community_max_pages=10, fetch_delay_ms=0, community_list_retries=5, rate_limit_cooldown_s=0, retry_backoff_base=0)
    journal = SimpleNamespace(record=lambda **k: None)
    target = {"source": "DCINSIDE", "url": "https://x/?id=g"}
    refs = run._collect_refs_for_date(
        target, "2026-07-11", settings=settings, client=None,
        journal=journal, today="2026-07-14", sleep=lambda s: None,
    )
    assert [r.post_id for r in refs] == ["1"]  # recovered and collected the 07-11 post
    assert attempts["n"] == 2                   # retried twice before success


def test_collect_gives_up_after_list_retries(monkeypatch):
    calls = {"list_fail": 0}

    def always_fail(client, url, *, settings, accept=None, referer=None):
        raise RuntimeError("Client error '430 Unknown'")

    def record(**k):
        if k.get("status") == "list-fail":
            calls["list_fail"] += 1
            assert "430" in k.get("error", "")

    monkeypatch.setattr(run.fetch, "fetch", always_fail)
    settings = SimpleNamespace(community_max_pages=10, fetch_delay_ms=0, community_list_retries=3, rate_limit_cooldown_s=0, retry_backoff_base=0)
    journal = SimpleNamespace(record=record)
    target = {"source": "DCINSIDE", "url": "https://x/?id=g"}
    refs = run._collect_refs_for_date(
        target, "2026-07-11", settings=settings, client=None,
        journal=journal, today="2026-07-14", sleep=lambda s: None,
    )
    assert refs == []
    assert calls["list_fail"] == 1  # recorded once, with the HTTP code, then stopped


def test_collect_incremental_stops_at_first_seen(monkeypatch):
    # page1: posts 5,4,3 on target date. 3 is already in S3 -> stop there.
    pages = {1: _dc_page([(5, "2026-07-11"), (4, "2026-07-11"), (3, "2026-07-11")]),
             2: _dc_page([(2, "2026-07-11")])}
    fetched: list[int] = []

    def fake_fetch(client, url, *, settings, accept=None, referer=None):
        page = int(url.split("page=")[1])
        fetched.append(page)
        return SimpleNamespace(text=pages[page])

    class FakeSink:
        def exists(self, key):
            return key.endswith("/3.json")  # only post 3 already landed

    monkeypatch.setattr(run.fetch, "fetch", fake_fetch)
    settings = SimpleNamespace(community_max_pages=10, fetch_delay_ms=0, community_list_retries=3, rate_limit_cooldown_s=0, retry_backoff_base=0)
    journal = SimpleNamespace(record=lambda **k: None)
    target = {"source": "DCINSIDE", "url": "https://x/?id=g"}
    refs = run._collect_refs_for_date(
        target, "2026-07-11", settings=settings, client=None, journal=journal,
        today="2026-07-14", sleep=lambda s: None, sink=FakeSink(), incremental=True,
    )
    assert [r.post_id for r in refs] == ["5", "4"]  # new posts above the checkpoint only
    assert fetched == [1]                            # stopped on page 1, never fetched page 2


def test_collect_retries_empty_block_page_then_recovers(monkeypatch):
    # page 1 returns a 0-row block page twice, then the real listing -> must not
    # give up (0 rows != end of board).
    real = _dc_page([(1, "2026-07-11")])
    empty = "<table><tbody></tbody></table>"
    seq = {"n": 0}

    def fake_fetch(client, url, *, settings, accept=None, referer=None):
        if int(url.split("page=")[1]) == 1:
            seq["n"] += 1
            return SimpleNamespace(text=empty if seq["n"] <= 2 else real)
        return SimpleNamespace(text=empty)  # page 2 genuinely empty

    monkeypatch.setattr(run.fetch, "fetch", fake_fetch)
    settings = SimpleNamespace(community_max_pages=10, fetch_delay_ms=0, community_list_retries=5, rate_limit_cooldown_s=0, retry_backoff_base=0)
    journal = SimpleNamespace(record=lambda **k: None)
    target = {"source": "DCINSIDE", "url": "https://x/?id=g"}
    refs = run._collect_refs_for_date(
        target, "2026-07-11", settings=settings, client=None,
        journal=journal, today="2026-07-14", sleep=lambda s: None,
    )
    assert [r.post_id for r in refs] == ["1"]  # recovered the real listing


def test_collect_popular_order_scans_window_no_date_stop(monkeypatch):
    # order: popular -> not date-ordered. Older rows are interspersed and must NOT
    # end the walk; instead scan the bounded popular window collecting date matches.
    pages = {
        1: _dc_page([(1, "2026-07-11"), (2, "2026-07-09"), (3, "2026-07-11")]),  # older in middle
        2: _dc_page([(4, "2026-07-11")]),
        3: _dc_page([(5, "2026-07-08")]),
    }
    fetched: list[int] = []

    def fake_fetch(client, url, *, settings, accept=None, referer=None):
        page = int(url.split("page=")[1])
        fetched.append(page)
        return SimpleNamespace(text=pages[page])

    monkeypatch.setattr(run.fetch, "fetch", fake_fetch)
    settings = SimpleNamespace(community_max_pages=99, community_popular_scan_pages=2,
                               fetch_delay_ms=0, community_list_retries=3,
                               rate_limit_cooldown_s=0, retry_backoff_base=0)
    journal = SimpleNamespace(record=lambda **k: None)
    target = {"source": "DCINSIDE", "url": "https://x/?id=g", "order": "popular"}
    refs = run._collect_refs_for_date(
        target, "2026-07-11", settings=settings, client=None,
        journal=journal, today="2026-07-14", sleep=lambda s: None,
    )
    assert [r.post_id for r in refs] == ["1", "3", "4"]  # 07-09 skipped, not a stop
    assert fetched == [1, 2]                              # bounded to 2 popular-scan pages


def test_collect_stops_on_empty_page(monkeypatch):
    pages = {1: _dc_page([(1, "2026-07-13")]), 2: "<table><tbody></tbody></table>"}
    monkeypatch.setattr(
        run.fetch, "fetch",
        lambda client, url, *, settings, accept=None, referer=None: SimpleNamespace(
            text=pages[int(url.split("page=")[1])]),
    )
    settings = SimpleNamespace(community_max_pages=10, fetch_delay_ms=0, community_list_retries=3, rate_limit_cooldown_s=0, retry_backoff_base=0)
    journal = SimpleNamespace(record=lambda **k: None)
    target = {"source": "DCINSIDE", "url": "https://x/?id=g"}
    refs = run._collect_refs_for_date(
        target, "2026-07-11", settings=settings, client=None,
        journal=journal, today="2026-07-14", sleep=lambda s: None,
    )
    assert refs == []
