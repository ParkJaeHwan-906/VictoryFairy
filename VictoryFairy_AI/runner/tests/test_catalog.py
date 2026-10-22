from pathlib import Path
from runner.catalog import load_catalog, select_combos

CATALOG = Path(__file__).parents[2] / "question-gen/config/question-templates.yaml"


def test_load_catalog_fills_enabled_default():
    cat = load_catalog(CATALOG)
    by_id = {t["id"]: t for t in cat}
    assert by_id["H2H_SEASON_RECORD"]["enabled"] is True      # 키 없음 → True
    assert by_id["PRED_SP_WIN"]["enabled"] is False           # 명시 false 유지


def test_select_combos_filters_needs_and_orders_by_recent_count():
    cat = [
        {"id": "A", "enabled": True, "needs": ["stats.streaks"]},
        {"id": "B", "enabled": True, "needs": ["schedule.today"]},   # 데이터 없음 → 제외
        {"id": "C", "enabled": False, "needs": ["stats.streaks"]},   # 비활성 → 제외
        {"id": "D", "enabled": True, "needs": ["stats.streaks"]},
    ]
    combos = select_combos(
        cat, available={"stats.streaks"},
        entities_by_template={"A": ["OB", "LT"], "D": ["HH"]},
        recent_template_counts={"A": 5, "D": 0}, limit=15)
    ids = [(t["id"], e) for t, e in combos]
    # D(최근 0회)가 A(5회)보다 먼저, 라운드로빈 후 A의 2번째 엔티티
    assert ids == [("D", "HH"), ("A", "OB"), ("A", "LT")]


def test_select_combos_respects_limit_and_max_two_per_template():
    cat = [{"id": "A", "enabled": True, "needs": []}]
    combos = select_combos(cat, available=set(),
                           entities_by_template={"A": ["1", "2", "3"]},
                           recent_template_counts={}, limit=15)
    assert len(combos) == 2                                   # 템플릿당 최대 2


# perTeam의 PLAYER→TEAM scope 자동 전환(question-gen/ROUTINE.md §3, scoring.yaml
# volume.perTeam 2026-10-06 주석)은 "같은 난이도에 TEAM scope 대체 템플릿이
# 있다/없다"는 전제에 의존한다. 카탈로그가 바뀌어 이 전제가 조용히 깨지면
# ROUTINE.md·scoring.yaml 주석이 거짓말을 하게 되므로, 그 매핑을 실제
# question-templates.yaml 기준으로 고정해 회귀를 잡는다.
PLAYER_TO_TEAM_FALLBACK = {
    "EASY": ("MEME_ORIGIN", "STREAK_CURRENT"),
    "MEDIUM": ("RECORD_OX", "HOME_AWAY_SPLIT"),
    "HARD": ("CAREER_PATH", "RECENT_VS_EARLY"),
}


def test_player_scope_perteam_templates_have_enabled_team_scope_fallback():
    cat = load_catalog(CATALOG)
    by_id = {t["id"]: t for t in cat}
    for difficulty, (player_id, team_id) in PLAYER_TO_TEAM_FALLBACK.items():
        player_tmpl = by_id[player_id]
        team_tmpl = by_id[team_id]
        assert player_tmpl["subjectScope"] == "PLAYER"
        assert player_tmpl["difficulty"] == difficulty
        assert team_tmpl["subjectScope"] == "TEAM"
        assert team_tmpl["difficulty"] == difficulty
        assert team_tmpl.get("enabled", True) is True


def test_expert_perteam_has_no_team_scope_fallback_yet():
    # RELATION_LINK(PLAYER, EXPERT)에 대응하는 TEAM scope EXPERT 템플릿은 아직
    # 없다 — ROUTINE.md "자동 전환" 절·scoring.yaml perTeam.EXPERT 주석이 이 사실을
    # 전제로 "대체 없음, 있는 만큼만 채운다"고 적어 둔다. 누군가 TEAM scope EXPERT
    # 템플릿을 추가하면 이 테스트가 깨져서 그 문서들을 같이 고치라고 알려준다.
    cat = load_catalog(CATALOG)
    team_expert = [t for t in cat
                   if t.get("subjectScope") == "TEAM"
                   and t.get("difficulty") == "EXPERT"
                   and t.get("enabled", True) is True]
    assert team_expert == []
