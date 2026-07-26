from pathlib import Path

from kbo_collector import kbo_register

FIX = Path(__file__).parent / "fixtures" / "kbo"


def test_parse_register_extracts_players_excluding_staff():
    html = (FIX / "register_lg.html").read_text(encoding="utf-8")
    players = kbo_register.parse_register(html)
    ids = {p.player_id for p in players}
    # 감독(91350)·코치(60001) 제외, 선수 3명만
    assert ids == {"60123", "67890", "79192"}
    son = next(p for p in players if p.player_id == "60123")
    assert son.name == "손주영"
    assert son.back_number == "1"
    assert son.position == "투수"
    assert son.throw_bat == "좌투좌타"
    assert son.birth_date == "1998-05-12"
    assert son.height_cm == 184 and son.weight_kg == 88
    oh = next(p for p in players if p.player_id == "79192")
    assert oh.position == "내야수"
