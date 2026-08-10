"""KBO 공식 일정표 파서 — 취소 사유 추출.

실제 GetScheduleList 응답(2026-08 조회) 모양을 그대로 옮긴 픽스처를 쓴다. 이 표는
Class 가 앞 네 칸에만 있고 구장·비고는 위치로만 잡히며, 날짜 셀이 RowSpan 이라
그날 첫 경기 행에만 붙는다 — 그 두 성질이 파서의 유일한 위험이라 정면으로 건다.
"""
from kbo_collector import kbo_schedule


def _cell(text, cls=None):
    return {"Text": text, "Class": cls}


def _play(away, home, away_score=None, home_score=None):
    """경기 전이면 'LG vs 두산', 경기 후면 점수 span 이 <em> 안에 낀다."""
    if away_score is None:
        mid = "<em><span>vs</span></em>"
    else:
        mid = (f'<em><span class="same">{away_score}</span><span>vs</span>'
               f'<span class="same">{home_score}</span></em>')
    return f"<span>{away}</span>{mid}<span>{home}</span>"


def _row(cells):
    return {"row": cells}


def _finished_row_with_day(day, away, home, stadium, note="-"):
    return _row([
        _cell(day, "day"),
        _cell("<b>18:00</b>", "time"),
        _cell(_play(away, home, 2, 2), "play"),
        _cell("<a href='/Schedule/GameCenter/Main.aspx?gameDate=20260801"
              "&gameId=20260801LGOB0&section=REVIEW'>리뷰</a>", "relay"),
        _cell("<a href='#'>하이라이트</a>"),
        _cell("SPO-2T"),
        _cell(""),
        _cell(stadium),
        _cell(note),
    ])


def _row_without_day(away, home, stadium, note="-", scores=(2, 2)):
    """같은 날 두 번째 경기부터는 day 셀이 없어 열이 한 칸씩 당겨진다."""
    return _row([
        _cell("<b>18:00</b>", "time"),
        _cell(_play(away, home, *scores) if scores else _play(away, home), "play"),
        _cell("<a href='#'>리뷰</a>", "relay"),
        _cell("<a href='#'>하이라이트</a>"),
        _cell("SPO-2T"),
        _cell(""),
        _cell(stadium),
        _cell(note),
    ])


def _cancelled_row_without_day(away, home, stadium, note="폭염취소"):
    """취소 경기엔 리뷰·하이라이트 링크가 없어 relay 셀이 비고 gameId 도 없다."""
    return _row([
        _cell("<b>18:00</b>", "time"),
        _cell(_play(away, home), "play"),
        _cell("", "relay"),
        _cell(""),
        _cell("SPO-2T"),
        _cell(""),
        _cell(stadium),
        _cell(note),
    ])


def test_parse_rows_carries_day_across_rows_without_day_cell():
    payload = {"rows": [
        _finished_row_with_day("08.01(토)", "LG", "두산", "잠실"),
        _row_without_day("삼성", "롯데", "사직"),
        _finished_row_with_day("08.02(일)", "KIA", "NC", "창원"),
    ]}

    rows = kbo_schedule.parse_rows(payload, "2026")

    assert [r["date"] for r in rows] == ["2026-08-01", "2026-08-01", "2026-08-02"]
    # day 셀이 빠진 행도 구장·비고가 한 칸 밀리지 않고 그대로 잡혀야 한다
    assert [r["stadium"] for r in rows] == ["잠실", "사직", "창원"]


def test_parse_rows_resolves_korean_team_names_to_codes_away_first():
    payload = {"rows": [_finished_row_with_day("08.11(화)", "한화", "두산", "잠실")]}

    rows = kbo_schedule.parse_rows(payload, "2026")

    # play 셀은 '원정 vs 홈' 순서다 (구장이 홈팀 연고인 것으로 교차 확인된다)
    assert rows[0]["away_code"] == "HH"
    assert rows[0]["home_code"] == "OB"


def test_parse_rows_ignores_score_spans_between_team_names():
    """점수가 <em> 안 span 으로 끼어도 팀은 첫/마지막 span 이라 흔들리지 않는다."""
    payload = {"rows": [
        _finished_row_with_day("08.01(토)", "LG", "두산", "잠실"),          # 2 vs 2
        _row_without_day("SSG", "KT", "수원", scores=(11, 3)),             # 두 자리 점수
        _row_without_day("키움", "KIA", "광주", scores=None),              # 경기 전
    ]}

    rows = kbo_schedule.parse_rows(payload, "2026")

    assert [(r["away_code"], r["home_code"]) for r in rows] == [
        ("LG", "OB"), ("SK", "KT"), ("WO", "HT")]


def test_parse_rows_drops_rows_whose_teams_are_not_kbo_clubs():
    """올스타·이벤트 경기는 팀 표기가 구단명이 아니라 해소되지 않는다."""
    payload = {"rows": [
        _finished_row_with_day("07.12(토)", "나눔", "드림", "대전"),
        _row_without_day("LG", "두산", "잠실"),
    ]}

    rows = kbo_schedule.parse_rows(payload, "2026")

    assert len(rows) == 1
    assert rows[0]["home_code"] == "OB"


def test_parse_rows_skips_rows_before_any_day_cell():
    """헤더 등으로 day 없이 시작하는 행이 오면 날짜를 지어내지 않고 버린다."""
    payload = {"rows": [
        _row_without_day("LG", "두산", "잠실"),
        _finished_row_with_day("08.01(토)", "삼성", "롯데", "사직"),
    ]}

    rows = kbo_schedule.parse_rows(payload, "2026")

    assert len(rows) == 1
    assert rows[0]["date"] == "2026-08-01"


def test_cancelled_rows_keeps_only_rows_with_a_note():
    payload = {"rows": [
        _finished_row_with_day("08.09(일)", "KIA", "LG", "잠실"),
        _cancelled_row_without_day("롯데", "KT", "수원"),
        _cancelled_row_without_day("키움", "한화", "대전", note="우천취소"),
    ]}

    cancelled = kbo_schedule.cancelled_rows(kbo_schedule.parse_rows(payload, "2026"))

    assert [(r["away_code"], r["home_code"], r["note"]) for r in cancelled] == [
        ("LT", "KT", "폭염취소"), ("WO", "HH", "우천취소")]
    # 취소 행에 gameId 가 없어도 (날짜, 대진) 은 온전하다 — 매칭은 이걸로 한다
    assert all(r["date"] == "2026-08-09" for r in cancelled)
