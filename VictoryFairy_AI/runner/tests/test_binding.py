from runner.binding import (available_needs, bind, enumerate_entities,
                            recent_summaries, recent_template_counts)

TODAY = "2026-08-03"


def test_available_needs_reflects_files(work):
    av = available_needs(work, TODAY)
    assert "stats.head_to_head" in av and "wiki.별명밈" in av
    assert "schedule.today" not in av            # 오늘 스케줄 파티션 없음
    assert "stats.trending" not in av            # trending.md 없음


def test_enumerate_entities_by_family(work):
    h2h = {"id": "H2H_SEASON_RECORD", "needs": ["stats.head_to_head"]}
    meme = {"id": "MEME_OWNER", "needs": ["wiki.별명밈"]}
    assert enumerate_entities(work, h2h, TODAY) == ["HH|LT", "OB|SK"]
    assert enumerate_entities(work, meme, TODAY) == ["69238"]


def test_bind_wiki_returns_doc_source(work):
    meme = {"id": "MEME_OWNER", "needs": ["wiki.별명밈"]}
    b = bind(work, meme, "69238", TODAY)
    assert "야구의 신" in b["sources"]["wiki/players/69238.md"]


def test_recent_counts_and_summaries(work):
    assert recent_template_counts(work) == {"MEME_OWNER": 1}
    assert recent_summaries(work)[0]["quizId"] == "QZ-20260801-005"


def test_enumerate_entities_game_result_filters_by_date(work):
    # conftest의 game_result 경기는 gameId 20260728... (2026-07-28 경기).
    yesterday = {"id": "YESTERDAY_WINNER", "needs": ["envelope.game_result.yesterday"]}
    recent7d = {"id": "RECENT7D_TPL", "needs": ["envelope.game_result.recent7d"]}

    # today가 그 경기의 다음날이면 "어제 경기"로 잡힌다.
    assert enumerate_entities(work, yesterday, "2026-07-29") == ["20260728OBSK02026"]
    # today가 훨씬 뒤라 그 경기가 "어제"가 아니면 제외된다(그저께 경기 오분류 방지).
    assert enumerate_entities(work, yesterday, "2026-08-03") == []

    # recent7d: today-7일 ~ today 창에 들어오면 포함.
    assert enumerate_entities(work, recent7d, "2026-08-03") == ["20260728OBSK02026"]
    # 창 밖(8일 이상 지남)이면 제외.
    assert enumerate_entities(work, recent7d, "2026-08-05") == []
