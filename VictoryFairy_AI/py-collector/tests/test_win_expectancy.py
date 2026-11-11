"""승리 기대값 — 경계 규칙과 KBO 경기 목록 입력 해석."""
from decimal import Decimal

from kbo_collector import win_expectancy as we


def test_rates_are_percentages_summing_to_100():
    h, a = we.win_rate(5, 1, 1, 3, 2, 4)
    assert h + a == Decimal("100.0") and h.as_tuple().exponent == -1


def test_game_start_is_even_and_leads_move_it():
    assert we.win_rate(1, 0, 0, 0, 0, 0) == (Decimal("50.0"), Decimal("50.0"))
    home_up, _ = we.win_rate(5, 0, 0, 0, 3, 0)
    away_up, _ = we.win_rate(5, 0, 0, 0, 0, 3)
    assert home_up > 80 and away_up < 20


def test_runners_and_outs_matter_in_the_expected_direction():
    loaded_no_out, _ = we.win_rate(7, 1, 0, 7, 2, 3)
    empty_two_out, _ = we.win_rate(7, 1, 2, 0, 2, 3)
    assert loaded_no_out > empty_two_out


def test_walk_off_and_skipped_bottom_half_are_certain():
    assert we.probabilities(9, 1, 1, 0, 5, 4) == (1.0, 0.0)   # 9회말 홈 리드 = 끝내기
    assert we.probabilities(9, 0, 3, 0, 5, 4) == (1.0, 0.0)   # 9회초 종료 홈 리드 = 말 없음
    assert we.probabilities(10, 1, 3, 0, 3, 5) == (0.0, 0.0)  # 연장 말 종료 원정 리드


def test_regular_season_ties_after_11_but_postseason_plays_on():
    # 정규시즌 11회말 2사 동점: 이번 말에 못 내면 무승부다.
    ph, pd = we.probabilities(11, 1, 3, 0, 2, 2, we.REGULAR_MAX_INNING)
    assert (ph, pd) == (0.0, 1.0)
    ph, pd = we.probabilities(11, 1, 3, 0, 2, 2, we.POSTSEASON_MAX_INNING)
    assert pd < 0.5 and ph > 0.2


def test_from_kbo_game_reads_live_state():
    kg = {"GAME_INN_NO": 7, "GAME_TB_SC": "B", "OUT_CN": 1, "B1_BAT_ORDER_NO": 4,
          "B2_BAT_ORDER_NO": 0, "B3_BAT_ORDER_NO": 9, "B_SCORE_CN": "2", "T_SCORE_CN": "3",
          "SR_ID": 7}
    assert we.from_kbo_game(kg) == we.win_rate(7, 1, 1, 5, 2, 3, we.POSTSEASON_MAX_INNING)


def test_from_kbo_game_returns_none_when_state_is_unreadable():
    assert we.from_kbo_game({"GAME_INN_NO": None, "GAME_TB_SC": "T"}) is None
    assert we.from_kbo_game({"GAME_INN_NO": 3, "GAME_TB_SC": "", "B_SCORE_CN": "0",
                             "T_SCORE_CN": "0"}) is None
