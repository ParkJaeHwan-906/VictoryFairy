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
