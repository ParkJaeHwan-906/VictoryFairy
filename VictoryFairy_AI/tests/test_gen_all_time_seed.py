import gen_all_time_seed as gas

SNAP = {"page": "history-player-hitter", "date": "2026-07-30",
        "tables": [{"headers": ["순위", "선수명", "홈런"],
                    "rows": [["1", "최정", "500"], ["2", "이승엽", "467"]]}]}

# 순위 열이 없는 실측 구조(history-player-hitter/pitcher — 연도별 챔피언 목록).
CHRONO_SNAP = {"page": "history-player-hitter", "date": "2026-07-30",
               "tables": [{"headers": ["연도", "선수", "소속", "타율"],
                           "rows": [["1982", "백인천", "MBC", "0.412"],
                                    ["1983", "장효조", "삼성", "0.369"]]}]}


def test_seed_from_snapshots():
    seed = gas.seed_from_snapshots({"history-player-hitter": SNAP}, top_n=1)
    assert seed["asOf"] == "2026-07-30"
    cat = seed["categories"][0]
    assert cat["sourcePage"] == "history-player-hitter"
    assert cat["entries"] == [{"rank": 1, "name": "최정", "value": "500"}]
    # 표에 실제 "순위" 열이 있으므로 rank는 진짜 KBO 순위다.
    assert cat["rankBasis"] == "true-rank"


def test_empty_snapshot_yields_no_category():
    assert gas.seed_from_snapshots({})["categories"] == []


def test_rank_basis_is_chronological_when_no_rank_column():
    """순위 열이 없는 표(연도별 챔피언 목록 등)는 rankBasis="chronological" —
    rank는 KBO가 매긴 진짜 순위가 아니라 표의 행 순서(1=최초 달성)일 뿐이다.
    이 구분이 없으면 소비자(퀴즈 생성기)가 rank==1을 "역대 1위"로 오인해
    최초 타율왕(백인천, 1982)을 "통산 타자 역대 1위"로 잘못 출제할 수 있다
    (리뷰 Important 지적 — Task 10은 rankBasis로 소비 템플릿을 갈라야 함)."""
    seed = gas.seed_from_snapshots({"history-player-hitter": CHRONO_SNAP}, top_n=2)
    cat = seed["categories"][0]
    assert cat["rankBasis"] == "chronological"
    assert cat["entries"][0] == {"rank": 1, "name": "백인천", "value": "0.412"}
    assert cat["entries"][1] == {"rank": 2, "name": "장효조", "value": "0.369"}
