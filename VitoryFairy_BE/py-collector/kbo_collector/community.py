import re
from dataclasses import dataclass

from bs4 import BeautifulSoup

from .masking import mask_author


@dataclass
class PostRef:
    post_id: str
    url: str
    title: str = ""
    team: str | None = None
    post_date: str | None = None  # 'YYYY-MM-DD' authored date, when the list exposes it
    views: int | None = None      # list-row view count (None if the source omits it)
    recommend: int | None = None  # list-row recommend/vote count (None if omitted)


def _text(el) -> str:
    return el.get_text(" ", strip=True) if el is not None else ""


def _num(text: str):
    digits = re.sub(r"[^\d]", "", text or "")
    return int(digits) if digits else None


def _fmkorea_date(text: str, today: str) -> str | None:
    """FMKorea list time cell -> ISO date.

    The list shows `HH:MM` for the last ~24h (no date), `MM.DD` for older posts
    this year, and `YYYY.MM.DD` beyond that. `today` ('YYYY-MM-DD') anchors both
    the HH:MM case (treated as today) and the year for the MM.DD case.
    """
    t = (text or "").strip()
    if not t:
        return None
    if ":" in t:  # HH:MM -> within the last ~24h; date not shown, so it's recent
        return today
    parts = [p for p in t.split(".") if p]
    if len(parts) == 3:
        y, m, d = parts
        return f"{int(y):04d}-{int(m):02d}-{int(d):02d}"
    if len(parts) == 2:
        m, d = int(parts[0]), int(parts[1])
        year = int(today[:4])
        cand = f"{year:04d}-{m:02d}-{d:02d}"
        if cand > today:  # month/day in the future for this year -> it was last year
            cand = f"{year - 1:04d}-{m:02d}-{d:02d}"
        return cand
    return None


def raw_post(source, post_id, url, title, body, engagement, comments, team, crawled_at) -> dict:
    return {
        "schemaVersion": 2,
        "source": source,
        "postExternalId": post_id,
        "sourceUrl": url,
        "title": title,
        "body": body,
        "engagement": engagement,
        "topComments": comments,
        "team": team,
        "crawledAt": crawled_at,
        "crawlerVersion": "community-v3",
    }


# --------------------------------------------------------------------------- FMKorea
def parse_fmkorea_list(
    html: str, base: str = "https://www.fmkorea.com", today: str | None = None
) -> list[PostRef]:
    soup = BeautifulSoup(html, "lxml")
    refs: list[PostRef] = []
    for tr in soup.select("table.bd_lst tbody tr"):
        if "notice" in (tr.get("class") or []):
            continue
        a = tr.select_one("td.title a")
        if a is None:
            continue
        href = a.get("href", "")
        # fmkorea serves post links in two forms: the short path /<id> and the
        # query form /index.php?...&document_srl=<id>. Match either, else the id
        # regex misses the query form and every post is silently dropped.
        m = re.search(r"document_srl=(\d+)", href) or re.search(r"/(\d+)", href)
        if not m:
            continue
        post_id = m.group(1)
        time_el = tr.select_one("td.time")
        post_date = _fmkorea_date(_text(time_el), today) if (time_el is not None and today) else None
        # FMKorea list exposes the vote count (td.m_no.m_no_voted) but not views.
        recommend = _num(_text(tr.select_one("td.m_no.m_no_voted")))
        refs.append(
            PostRef(post_id=post_id, url=f"{base}/{post_id}", title=_text(a),
                    post_date=post_date, recommend=recommend)
        )
    return refs


def _fmkorea_engagement(soup) -> dict:
    view = like = comment = None
    for span in soup.select(".side.fr span"):
        t = _text(span)
        if "조회" in t:
            view = _num(t)
        elif "추천" in t:
            like = _num(t)
        elif "댓글" in t:
            comment = _num(t)
    return {"viewCount": view, "likeCount": like, "dislikeCount": None, "commentCount": comment}


def _fmkorea_comments(soup, salt: str, top_n: int) -> list[dict]:
    out: list[dict] = []
    for li in soup.select("ul.fdb_lst_ul li.fdb_itm"):
        body_el = li.select_one(".comment-content")
        if body_el is None:
            continue
        author = _text(li.select_one(".meta a.member_plate"))
        like = _num(_text(li.select_one(".voted_count"))) or 0
        out.append({"author": mask_author(author, salt), "body": _text(body_el), "likeCount": like})
    out.sort(key=lambda c: c["likeCount"], reverse=True)
    return out[:top_n]


def parse_fmkorea_detail(html, ref: PostRef, salt: str, top_n: int, crawled_at: str) -> dict:
    soup = BeautifulSoup(html, "lxml")
    body_el = soup.select_one(".rd_body div[class^=document_].xe_content")
    body = body_el.get_text("\n", strip=True) if body_el is not None else ""
    return raw_post(
        source="FMKOREA",
        post_id=ref.post_id,
        url=ref.url,
        title=ref.title,
        body=body,
        engagement=_fmkorea_engagement(soup),
        comments=_fmkorea_comments(soup, salt, top_n),
        team=None,
        crawled_at=crawled_at,
    )


# --------------------------------------------------------------------------- DCInside
def parse_dcinside_list(html: str) -> list[PostRef]:
    soup = BeautifulSoup(html, "lxml")
    refs: list[PostRef] = []
    for tr in soup.select("tr.ub-content"):
        num = tr.select_one("td.gall_num")
        if num is None or not num.get_text(strip=True).isdigit():
            continue  # notice / non-post rows
        a = tr.select_one("td.gall_tit.ub-word a")
        if a is None:
            continue
        href = a.get("href", "")
        m_no = re.search(r"no=(\d+)", href)
        m_id = re.search(r"id=([^&]+)", href)
        if not m_no or not m_id:
            continue
        post_id = m_no.group(1)
        gallery = m_id.group(1)
        url = f"https://gall.dcinside.com/board/view/?id={gallery}&no={post_id}"
        date_el = tr.select_one("td.gall_date")
        # The date cell's title holds a full 'YYYY-MM-DD HH:MM:SS' timestamp
        # (the visible text is only HH:MM for today / MM.DD for older).
        title_attr = date_el.get("title", "") if date_el is not None else ""
        post_date = title_attr[:10] if re.match(r"\d{4}-\d{2}-\d{2}", title_attr) else None
        # DCInside list carries view + recommend counts inline.
        views = _num(_text(tr.select_one("td.gall_count")))
        recommend = _num(_text(tr.select_one("td.gall_recommend")))
        refs.append(PostRef(post_id=post_id, url=url, title=_text(a), post_date=post_date,
                            views=views, recommend=recommend))
    return refs


def parse_dcinside_detail(html, ref: PostRef, team, crawled_at: str) -> dict:
    soup = BeautifulSoup(html, "lxml")
    body_el = soup.select_one(".write_div")
    if body_el is not None:
        for junk in body_el.select("#ad_nv_slot, #zzbang_div, script"):
            junk.decompose()
        body = body_el.get_text("\n", strip=True)
    else:
        body = ""
    engagement = {
        "viewCount": _num(_text(soup.select_one(".gallview_head .gall_count"))),
        "likeCount": _num(_text(soup.select_one(".up_num"))),
        "dislikeCount": _num(_text(soup.select_one(".down_num"))),
        "commentCount": _num(_text(soup.select_one(".gall_comment a"))),
    }
    # DCInside comments load via AJAX and are absent from static HTML -> always [].
    return raw_post(
        source="DCINSIDE",
        post_id=ref.post_id,
        url=ref.url,
        title=ref.title,
        body=body,
        engagement=engagement,
        comments=[],
        team=team,
        crawled_at=crawled_at,
    )
