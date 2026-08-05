from kbo_collector import dimensions as d


def test_ten_teams_with_codes():
    assert len(d.TEAMS) == 10
    assert set(d.TEAM_CODES) == {"OB","LG","SS","KT","WO","HT","HH","NC","LT","SK"}
    lg = next(t for t in d.TEAMS if t.team_code == "LG")
    assert lg.name == "LG" and lg.full_name == "LG 트윈스"


def test_player_sections_excludes_staff():
    assert d.PLAYER_SECTIONS == {"투수", "포수", "내야수", "외야수"}
    assert "감독" not in d.PLAYER_SECTIONS and "코치" not in d.PLAYER_SECTIONS


def test_parse_physique():
    assert d.parse_physique("184cm, 88kg") == (184, 88)
    assert d.parse_physique("178cm, 65kg") == (178, 65)
    assert d.parse_physique("") == (None, None)
    assert d.parse_physique("-") == (None, None)
