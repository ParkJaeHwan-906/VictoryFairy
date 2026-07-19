from pathlib import Path

from kbo_collector import community

FIX = Path(__file__).parent / "fixtures" / "community"


def test_dcinside_list_extracts_real_posts_only():
    html = (FIX / "dcinside_list.html").read_text(encoding="utf-8")
    refs = community.parse_dcinside_list(html)
    ids = [r.post_id for r in refs]
    assert ids == ["41521", "41520"]  # notice (gall_num='공지') excluded
    assert refs[0].url == "https://gall.dcinside.com/board/view/?id=tigers_new2&no=41521"
    assert refs[0].title == "기아 오늘 경기 어땠음"


def test_dcinside_detail_builds_rawpost_with_team_and_no_comments():
    html = (FIX / "dcinside_detail.html").read_text(encoding="utf-8")
    ref = community.PostRef(post_id="41521",
                            url="https://gall.dcinside.com/board/view/?id=tigers_new2&no=41521",
                            title="기아 오늘 경기 어땠음")
    post = community.parse_dcinside_detail(html, ref, team="KIA",
                                           crawled_at="2026-07-10T12:00:00")
    assert post["source"] == "DCINSIDE"
    assert post["team"] == "KIA"
    assert "본문 첫째줄" in post["body"]
    assert "본문 둘째줄" in post["body"]
    assert "광고" not in post["body"]        # #ad_nv_slot removed
    assert "짤방광고" not in post["body"]     # #zzbang_div removed
    assert "evil" not in post["body"]         # script removed
    assert post["engagement"] == {
        "viewCount": 3072, "likeCount": 124, "dislikeCount": 7, "commentCount": 88,
    }
    assert post["topComments"] == []          # DCInside comments are AJAX -> always empty


def test_dcinside_detail_graceful_on_missing_nodes():
    post = community.parse_dcinside_detail("<html></html>",
                                           community.PostRef(post_id="1", url="u", title="t"),
                                           team="LG", crawled_at="2026-07-10T12:00:00")
    assert post["body"] == ""
    assert post["engagement"]["viewCount"] is None
    assert post["topComments"] == []
