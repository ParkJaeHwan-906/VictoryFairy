"""validate_candidates check 9 — subject(주제 축, 스펙 4.3 v2) pytest.

이행기 계약을 고정한다: subject '부재'는 위반이 아니라 경고(구계약 v1 후보
공존 — candidate_warnings, exit code 미반영)이고, subject가 '있는데 틀린' 것
(scope·카탈로그 선언 불일치·카디널리티·팀코드 화이트리스트·정답 유출)만
하드 실패다. 기본 8항목 검사는 리포 루트 tests/test_validate_candidates.py 소관.
"""
import json

import pytest

import validate_candidates as vc

CATALOG = {
    "CAREER_PATH": {"id": "CAREER_PATH", "kind": "KNOWLEDGE", "format": "MULTI4",
                    "enabled": True, "subjectScope": "PLAYER"},
    "H2H_SEASON_RECORD": {"id": "H2H_SEASON_RECORD", "kind": "KNOWLEDGE",
                          "format": "BINARY", "enabled": True,
                          "subjectScope": "MATCHUP"},
    "STREAK_CURRENT": {"id": "STREAK_CURRENT", "kind": "KNOWLEDGE", "format": "OX",
                       "enabled": True, "subjectScope": "TEAM"},
    "TRENDING_WHO": {"id": "TRENDING_WHO", "kind": "KNOWLEDGE", "format": "MULTI4",
                     "enabled": True, "subjectScope": "LEAGUE"},
    "WINNING_PITCHER": {"id": "WINNING_PITCHER", "kind": "KNOWLEDGE",
                        "format": "MULTI4", "enabled": True, "subjectScope": "GAME"},
    "PRED_WIN_LOSE": {"id": "PRED_WIN_LOSE", "kind": "PREDICTION", "format": "BINARY",
                      "enabled": True, "subjectScope": "GAME"},
}
BANNED = ["음주"]


def career_path():
    """스펙 4.3 v2 대표 예시 — 강백호(68050)는 전제(playerIds), 한화는 정답이라
    subject.teamCodes는 비운다(scope=PLAYER). 실측 후보 QZ-20260807-001 기반."""
    return {"quizId": "QZ-20260807-001", "gameId": None, "teamCodes": [],
            "kind": "KNOWLEDGE", "type": "HISTORY", "templateId": "CAREER_PATH",
            "format": "MULTI4", "question": "강백호가 FA로 새로 합류한 팀은?",
            "options": [{"id": "A", "text": "한화"}, {"id": "B", "text": "KT"},
                        {"id": "C", "text": "SSG"}, {"id": "D", "text": "키움"}],
            "answer": "A",
            "evidence": {"source": "wiki/players/68050.md#커리어 이력",
                         "quote": "KT 소속이었다가 FA로 한화에 이적"},
            "settlement": None, "difficulty": "HARD", "pointReward": 80,
            "status": "PENDING", "createdAt": "2026-08-07T00:00:00Z",
            "deadlineAt": "2026-08-07T14:59:00Z", "createdBy": "AI_ENGINE",
            "subject": {"scope": "PLAYER", "playerIds": [68050],
                        "teamCodes": [], "gameId": None}}


def h2h(options, answer, subject):
    """MATCHUP 검사용 H2H 후보 — 보기·정답·subject만 바꿔 쓴다."""
    c = career_path()
    c.update(quizId="QZ-20260807-002", templateId="H2H_SEASON_RECORD",
             format="BINARY", question="올해 한화는 KT와의 상대전적에서 우위다?",
             options=options, answer=answer, difficulty="MEDIUM", pointReward=50,
             evidence={"source": "wiki/stats/season.md#상대전적",
                       "quote": "HH 4승 KT 7승"},
             subject=subject)
    return c


# ── subject 부재 = 경고만(구계약 v1 하위호환), 존재+정상 = 통과 ──────────

def test_v2_subject_player_passes():
    assert vc.validate_candidate(career_path(), CATALOG, BANNED) == []


def test_v1_without_subject_no_violation_but_warns():
    c = career_path()
    del c["subject"]
    assert vc.validate_candidate(c, CATALOG, BANNED) == []      # 하드 실패 아님
    warnings = vc.candidate_warnings(c, CATALOG)
    assert len(warnings) == 1
    assert "subject 부재" in warnings[0] and "PLAYER" in warnings[0]


def test_with_subject_no_warning():
    assert vc.candidate_warnings(career_path(), CATALOG) == []


# ── scope: 허용값 + 카탈로그 subjectScope 선언 일치 ─────────────────────

def test_subject_scope_must_match_catalog_declaration():
    c = career_path()
    # LEAGUE 카디널리티 자체는 만족시켜 선언 불일치만 잡히게 한다.
    c["subject"] = {"scope": "LEAGUE", "playerIds": [], "teamCodes": [],
                    "gameId": None}
    violations = vc.validate_candidate(c, CATALOG, BANNED)
    assert any("subjectScope" in v and "불일치" in v for v in violations)


def test_subject_scope_invalid_value_rejected():
    c = career_path()
    c["subject"]["scope"] = "GALAXY"
    violations = vc.validate_candidate(c, CATALOG, BANNED)
    assert any("subject.scope" in v for v in violations)


# ── scope별 카디널리티 ──────────────────────────────────────────────────

def test_player_scope_requires_player_ids():
    c = career_path()
    c["subject"]["playerIds"] = []
    violations = vc.validate_candidate(c, CATALOG, BANNED)
    assert any("PLAYER" in v and "playerIds" in v for v in violations)


def test_player_ids_must_be_integers():
    c = career_path()
    c["subject"]["playerIds"] = ["68050"]      # 문자열 — 축(정수 playerId) 위반
    violations = vc.validate_candidate(c, CATALOG, BANNED)
    assert any("정수 배열" in v for v in violations)


def test_team_scope_exactly_one_team_and_no_players():
    def streak(subject):
        c = career_path()
        c.update(quizId="QZ-20260807-003", templateId="STREAK_CURRENT", format="OX",
                 question="KT는 현재 6연승 중이다",
                 options=[{"id": "A", "text": "O"}, {"id": "B", "text": "X"}],
                 answer="A", difficulty="EASY", pointReward=30,
                 evidence={"source": "wiki/stats/season.md#연승·연패",
                           "quote": "KT 6연승"},
                 subject=subject)
        return c

    ok = streak({"scope": "TEAM", "playerIds": [], "teamCodes": ["KT"],
                 "gameId": None})
    assert vc.validate_candidate(ok, CATALOG, BANNED) == []
    two_teams = streak({"scope": "TEAM", "playerIds": [], "teamCodes": ["KT", "HH"],
                        "gameId": None})
    assert any("TEAM" in v for v in vc.validate_candidate(two_teams, CATALOG, BANNED))
    with_player = streak({"scope": "TEAM", "playerIds": [68050], "teamCodes": ["KT"],
                          "gameId": None})
    assert any("TEAM" in v for v in vc.validate_candidate(with_player, CATALOG, BANNED))


def test_matchup_scope_requires_exactly_two_teams():
    c = h2h([{"id": "A", "text": "우위다"}, {"id": "B", "text": "열세다"}], "B",
            {"scope": "MATCHUP", "playerIds": [], "teamCodes": ["HH"], "gameId": None})
    violations = vc.validate_candidate(c, CATALOG, BANNED)
    assert any("MATCHUP" in v and "2개" in v for v in violations)


def test_league_scope_must_be_all_empty():
    c = career_path()
    c.update(quizId="QZ-20260807-004", templateId="TRENDING_WHO",
             question="이번 주 커뮤니티 최다 화제 선수는?",
             options=[{"id": "A", "text": "김대한"}, {"id": "B", "text": "김도영"},
                      {"id": "C", "text": "구자욱"}, {"id": "D", "text": "오스틴"}],
             answer="A", difficulty="MEDIUM", pointReward=50,
             evidence={"source": "wiki/stats/trending.md", "quote": "1위 김대한"},
             subject={"scope": "LEAGUE", "playerIds": [], "teamCodes": ["OB"],
                      "gameId": None})
    violations = vc.validate_candidate(c, CATALOG, BANNED)
    assert any("LEAGUE" in v for v in violations)


def test_game_scope_requires_game_id_and_others_must_not_carry_it():
    def pitcher(subject):
        c = career_path()
        c.update(quizId="QZ-20260807-005", templateId="WINNING_PITCHER",
                 question="8/2 SSG-키움 경기 승리투수는?",
                 options=[{"id": "A", "text": "김성민"}, {"id": "B", "text": "노경은"},
                          {"id": "C", "text": "유토"}, {"id": "D", "text": "알칸타라"}],
                 answer="A", difficulty="MEDIUM", pointReward=50,
                 evidence={"source": "question-source/game_result/2026-08-07/x.json",
                           "quote": "승리투수 김성민"},
                 subject=subject)
        return c

    ok = pitcher({"scope": "GAME", "playerIds": [], "teamCodes": ["SK", "WO"],
                  "gameId": "20260802SKWO02026"})
    assert vc.validate_candidate(ok, CATALOG, BANNED) == []
    no_gid = pitcher({"scope": "GAME", "playerIds": [], "teamCodes": ["SK", "WO"],
                      "gameId": None})
    assert any("GAME" in v and "gameId" in v
               for v in vc.validate_candidate(no_gid, CATALOG, BANNED))
    # scope=GAME이 아닌데 subject.gameId를 채우면 위반(스펙: 그 외 null).
    stray = career_path()
    stray["subject"]["gameId"] = "20260802SKWO02026"
    assert any("gameId" in v and "GAME" in v
               for v in vc.validate_candidate(stray, CATALOG, BANNED))


# ── 팀코드 화이트리스트 (귀속 축 top-level + 주제 축 subject) ────────────

def test_subject_team_code_whitelist():
    c = h2h([{"id": "A", "text": "우위다"}, {"id": "B", "text": "열세다"}], "B",
            {"scope": "MATCHUP", "playerIds": [], "teamCodes": ["HH", "ZZ"],
             "gameId": None})
    violations = vc.validate_candidate(c, CATALOG, BANNED)
    assert any("subject.teamCodes" in v and "'ZZ'" in v for v in violations)


def test_top_level_team_code_whitelist_checked_even_without_subject():
    c = career_path()
    del c["subject"]
    c["teamCodes"] = ["HH", "HANWHA"]      # 실존 코드는 HH — 팀명 오기입 검출
    violations = vc.validate_candidate(c, CATALOG, BANNED)
    assert any("귀속 축" in v and "'HANWHA'" in v for v in violations)


# ── 정답 유출 결정적 검사(팀 한정) ──────────────────────────────────────

def test_subject_team_in_answer_option_is_leak():
    # 정답 보기 "KT 우위"에 subject 팀(KT) 이름이 그대로 등장 — 유출 위반.
    c = h2h([{"id": "A", "text": "한화 우위"}, {"id": "B", "text": "KT 우위"}], "B",
            {"scope": "MATCHUP", "playerIds": [], "teamCodes": ["HH", "KT"],
             "gameId": None})
    violations = vc.validate_candidate(c, CATALOG, BANNED)
    assert any("정답 유출" in v and "KT" in v for v in violations)


def test_subject_team_in_wrong_option_is_allowed():
    # 팀명 없는 보기("우위다/열세다")면 두 팀을 전제로 담아도 유출이 아니다.
    c = h2h([{"id": "A", "text": "우위다"}, {"id": "B", "text": "열세다"}], "B",
            {"scope": "MATCHUP", "playerIds": [], "teamCodes": ["HH", "KT"],
             "gameId": None})
    assert vc.validate_candidate(c, CATALOG, BANNED) == []


def test_prediction_with_team_options_not_leak_checked():
    # PREDICTION은 answer가 None — 보기가 팀명이어도 유출 검사 비대상.
    c = career_path()
    c.update(quizId="QZ-20260807-006", kind="PREDICTION", templateId="PRED_WIN_LOSE",
             format="BINARY", question="오늘 LG-두산전 승자는?",
             options=[{"id": "A", "text": "LG"}, {"id": "B", "text": "두산"}],
             answer=None, evidence=None,
             settlement={"gameId": "20260807LGOB02026", "metric": "WIN_TEAM"},
             gameId="20260807LGOB02026", type="WIN_LOSE",
             difficulty="MEDIUM", pointReward=50,
             deadlineAt="2026-08-07T07:30:00Z",
             subject={"scope": "GAME", "playerIds": [], "teamCodes": ["LG", "OB"],
                      "gameId": "20260807LGOB02026"})
    assert vc.validate_candidate(c, CATALOG, BANNED) == []


# ── main() CLI: 경고는 exit 0, subject가 있는데 틀리면 exit 1 ───────────

def _write(path, candidate):
    path.write_text(json.dumps(candidate, ensure_ascii=False), encoding="utf-8")


def _catalog_yaml(tmp_path):
    """실전 카탈로그 대신 최소 카탈로그 파일 — subjectScope 선언 포함."""
    p = tmp_path / "catalog.yaml"
    p.write_text(
        "- id: CAREER_PATH\n  kind: KNOWLEDGE\n  format: MULTI4\n"
        "  subjectScope: PLAYER\n", encoding="utf-8")
    return str(p)


def test_main_v1_candidate_warns_but_exits_zero(tmp_path, capsys):
    c = career_path()
    del c["subject"]
    _write(tmp_path / "QZ-20260807-001.json", c)
    with pytest.raises(SystemExit) as exc_info:
        vc.main(["--dir", str(tmp_path), "--catalog", _catalog_yaml(tmp_path)])
    assert exc_info.value.code == 0
    out = capsys.readouterr().out
    assert "! 경고: subject 부재" in out and "경고 1건" in out


def test_main_wrong_subject_exits_one(tmp_path):
    c = career_path()
    c["subject"]["playerIds"] = []             # PLAYER인데 playerIds 빔 — 하드 실패
    _write(tmp_path / "QZ-20260807-001.json", c)
    with pytest.raises(SystemExit) as exc_info:
        vc.main(["--dir", str(tmp_path), "--catalog", _catalog_yaml(tmp_path)])
    assert exc_info.value.code == 1
