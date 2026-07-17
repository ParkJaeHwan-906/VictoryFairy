from pathlib import Path

from kbo_collector import community

FIX = Path(__file__).parent / "fixtures" / "community"


def test_fmkorea_list_extracts_posts_excluding_notice():
    html = (FIX / "fmkorea_list.html").read_text(encoding="utf-8")
    refs = community.parse_fmkorea_list(html)
    ids = [r.post_id for r in refs]
    assert ids == ["8523491", "8523492"]
    assert refs[0].url == "https://www.fmkorea.com/8523491"
    assert refs[0].title == "엘지 오늘 미쳤다"


def test_fmkorea_list_extracts_query_param_document_srl():
    # fmkorea switched post links from /<id> to /index.php?...&document_srl=<id>.
    # The list parser must pull the id from the query form too — otherwise the
    # id regex misses every row and the crawl silently lands 0 posts (seen 2026-07).
    html = (
        '<table class="bd_lst bd_tb_lst bd_tb"><tbody>'
        '<tr class="notice"><td class="title">'
        '<a href="/index.php?mid=baseball&document_srl=111">공지</a></td></tr>'
        '<tr><td class="title">'
        '<a href="/index.php?mid=baseball&sort_index=popular_docs&document_srl=10093853587">'
        '이틀 뒤까지 빠따 살려와라</a></td>'
        '<td class="time">09:55</td><td class="m_no m_no_voted">3</td></tr>'
        '</tbody></table>'
    )
    refs = community.parse_fmkorea_list(html)
    assert [r.post_id for r in refs] == ["10093853587"]
    assert refs[0].url == "https://www.fmkorea.com/10093853587"
    assert refs[0].title == "이틀 뒤까지 빠따 살려와라"


def test_fmkorea_detail_builds_rawpost():
    html = (FIX / "fmkorea_detail.html").read_text(encoding="utf-8")
    ref = community.PostRef(post_id="8523491", url="https://www.fmkorea.com/8523491",
                            title="엘지 오늘 미쳤다")
    post = community.parse_fmkorea_detail(html, ref, salt="pepper", top_n=20,
                                          crawled_at="2026-07-10T12:00:00")
    assert post["schemaVersion"] == 2
    assert post["source"] == "FMKOREA"
    assert post["postExternalId"] == "8523491"
    assert post["title"] == "엘지 오늘 미쳤다"
    assert "본문 첫줄" in post["body"]
    assert post["engagement"] == {
        "viewCount": 11228, "likeCount": 42, "dislikeCount": None, "commentCount": 17,
    }
    assert post["team"] is None
    assert post["crawlerVersion"] == "community-v3"
    # top comments sorted by likeCount desc, authors masked (no plaintext)
    assert [c["likeCount"] for c in post["topComments"]] == [12, 5]
    assert post["topComments"][0]["body"] == "댓글 본문 B"
    assert "엘지팬" not in post["topComments"][0]["author"]
    assert len(post["topComments"][0]["author"]) == 12


def test_fmkorea_detail_top_n_limits():
    html = (FIX / "fmkorea_detail.html").read_text(encoding="utf-8")
    ref = community.PostRef(post_id="1", url="u", title="t")
    post = community.parse_fmkorea_detail(html, ref, salt="p", top_n=1,
                                          crawled_at="2026-07-10T12:00:00")
    assert len(post["topComments"]) == 1
    assert post["topComments"][0]["likeCount"] == 12


def test_fmkorea_detail_graceful_on_missing_nodes():
    post = community.parse_fmkorea_detail("<html></html>",
                                          community.PostRef(post_id="1", url="u", title="t"),
                                          salt="p", top_n=5, crawled_at="2026-07-10T12:00:00")
    assert post["body"] == ""
    assert post["topComments"] == []
    assert post["engagement"]["viewCount"] is None
