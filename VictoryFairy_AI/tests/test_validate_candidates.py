import json

import pytest

import validate_candidates as vc

CATALOG = {"H2H_SEASON_RECORD": {"id": "H2H_SEASON_RECORD", "kind": "KNOWLEDGE",
                                 "format": "BINARY", "enabled": True},
           "YOY_TEAM": {"id": "YOY_TEAM", "kind": "KNOWLEDGE",
                        "format": "MULTI4", "enabled": False},
           "PRED_WIN_LOSE": {"id": "PRED_WIN_LOSE", "kind": "PREDICTION",
                             "format": "BINARY", "enabled": True},
           "PRED_BATTER_HIT_INNING": {"id": "PRED_BATTER_HIT_INNING",
                                      "kind": "PREDICTION", "format": "BINARY",
                                      "subjectScope": "PLAYER", "enabled": True},
           "CAREER_PATH": {"id": "CAREER_PATH", "kind": "KNOWLEDGE",
                          "format": "MULTI4", "enabled": True},
           "TEAMMATE_STAT_COMPARE": {"id": "TEAMMATE_STAT_COMPARE", "kind": "KNOWLEDGE",
                                     "format": "BINARY", "subjectScope": "PLAYER",
                                     "enabled": True}}
BANNED = ["음주", "폭행"]


def ok_batter_hit_inning():
    # 실제 존재하는 경기(2026-10-03 KIA@LG, naverGameId 20261003HTLG02026)와 실제
    # 선수(KIA 김도영, kboPlayerId 52605)로 만든 샘플 — away팀(KIA) 소속이라
    # half="TOP"(초). deadlineAt은 그 경기 startTime(14:00 KST) - 2시간 = 03:00Z.
    return {"quizId": "QZ-20261003-901", "gameId": "20261003HTLG02026",
            "kind": "PREDICTION", "type": "PRED_BATTER_HIT_INNING",
            "templateId": "PRED_BATTER_HIT_INNING", "format": "BINARY",
            "question": "오늘 KIA-LG 3회 초, 김도영은 안타를 칠까?",
            "options": [{"id": "A", "text": "안타를 친다"},
                        {"id": "B", "text": "안타를 치지 못한다"}],
            "answer": None, "evidence": None,
            "settlement": {"metric": "BATTER_HIT_IN_INNING",
                           "gameId": "20261003HTLG02026", "inning": 3, "half": "TOP"},
            "difficulty": "MEDIUM", "pointReward": 50, "bqReward": 2,
            "status": "PENDING", "createdAt": "2026-10-02T23:50:00Z",
            "deadlineAt": "2026-10-03T03:00:00Z", "createdBy": "AI_ENGINE",
            "teamCodes": ["HT", "LG"],
            "subject": {"scope": "PLAYER", "playerIds": [52605], "teamCodes": [],
                        "gameId": None}}


def test_batter_hit_inning_valid_passes():
    assert vc.validate_candidate(ok_batter_hit_inning(), CATALOG, BANNED) == []


def test_batter_hit_inning_requires_inning_in_range():
    c = ok_batter_hit_inning(); c["settlement"]["inning"] = 12   # INNING_MAX=11 초과
    violations = vc.validate_candidate(c, CATALOG, BANNED)
    assert any("settlement.inning" in v for v in violations)

    c = ok_batter_hit_inning(); c["settlement"]["inning"] = 0   # 1 미만
    assert any("settlement.inning" in v for v in vc.validate_candidate(c, CATALOG, BANNED))

    c = ok_batter_hit_inning(); del c["settlement"]["inning"]   # 부재
    assert any("settlement.inning" in v for v in vc.validate_candidate(c, CATALOG, BANNED))


def test_batter_hit_inning_half_must_be_top_or_bottom_string():
    # half는 BE InningHalf#name() 문자열("TOP"/"BOTTOM")이어야 한다 — 흔히
    # 오해하는 0/1 정수 표기는 거부된다(BE 역직렬화 계약, generation-rules.md §3).
    c = ok_batter_hit_inning(); c["settlement"]["half"] = 0
    violations = vc.validate_candidate(c, CATALOG, BANNED)
    assert any("settlement.half" in v for v in violations)

    c = ok_batter_hit_inning(); c["settlement"]["half"] = "1"
    assert any("settlement.half" in v for v in vc.validate_candidate(c, CATALOG, BANNED))

    c = ok_batter_hit_inning(); c["settlement"]["half"] = "MIDDLE"
    assert any("settlement.half" in v for v in vc.validate_candidate(c, CATALOG, BANNED))


def test_other_prediction_metrics_unaffected_by_inning_half_check():
    # check 11은 metric==BATTER_HIT_IN_INNING일 때만 적용된다 — WIN_TEAM 등
    # 기존 PRED_* 후보는 inning/half가 없어도(기존 동작) 그대로 통과해야 한다.
    c = ok_knowledge()
    c.update(kind="PREDICTION", templateId="PRED_WIN_LOSE", answer=None, evidence=None,
             settlement={"gameId": "20260730LGOB02026", "metric": "WIN_TEAM"},
             gameId="20260730LGOB02026")
    assert vc.validate_candidate(c, CATALOG, BANNED) == []


def ok_knowledge():
    # deadlineAt은 gameId 없는 KNOWLEDGE 문항 기준이라 값 자체는 임의였지만,
    # PREDICTION 테스트들이 이 fixture를 c.update()로 재사용하며 gameId만 덧붙이므로
    # (아래 test_prediction_*) deadlineAt도 gameId(20260730...) 날짜의 KST 유효 범위
    # 안에 들도록 맞춰둔다 — 아니면 새 deadlineAt sanity 검사(check 8)에 걸린다.
    return {"quizId": "QZ-20260730-001", "gameId": None, "kind": "KNOWLEDGE",
            "type": "HISTORY", "templateId": "H2H_SEASON_RECORD", "format": "BINARY",
            "question": "올 시즌 잠실 라이벌전 우위 팀은?",
            "options": [{"id": "A", "text": "LG"}, {"id": "B", "text": "두산"}],
            "answer": "A",
            "evidence": {"source": "wiki/stats/season.md#상대전적", "quote": "LG 7-4 두산"},
            "settlement": None, "difficulty": "MEDIUM", "pointReward": 50,
            "bqReward": 2,
            "status": "PENDING", "createdAt": "2026-07-30T00:00:00Z",
            "deadlineAt": "2026-07-30T07:30:00Z", "createdBy": "AI_ENGINE"}


def test_valid_knowledge_passes():
    assert vc.validate_candidate(ok_knowledge(), CATALOG, BANNED) == []


def test_option_count_must_match_format():
    c = ok_knowledge()
    c["options"].append({"id": "C", "text": "무승부"})
    assert any("options" in v for v in vc.validate_candidate(c, CATALOG, BANNED))


def test_knowledge_requires_evidence_and_answer():
    c = ok_knowledge(); c["evidence"] = None
    assert vc.validate_candidate(c, CATALOG, BANNED)
    c = ok_knowledge(); c["answer"] = "Z"
    assert vc.validate_candidate(c, CATALOG, BANNED)


def test_prediction_requires_settlement_metric():
    c = ok_knowledge()
    c.update(kind="PREDICTION", templateId="PRED_WIN_LOSE", answer=None, evidence=None,
             settlement={"gameId": "20260730LGOB02026", "metric": "WIN_TEAM"},
             gameId="20260730LGOB02026")
    assert vc.validate_candidate(c, CATALOG, BANNED) == []
    c["settlement"]["metric"] = "INNINGS_PITCHED"   # 정산 불가 지표
    assert vc.validate_candidate(c, CATALOG, BANNED)


def test_prediction_gameid_mismatch_rejected():
    # I4: top-level gameId와 settlement.gameId는 같은 경기를 가리켜야 한다.
    c = ok_knowledge()
    c.update(kind="PREDICTION", templateId="PRED_WIN_LOSE", answer=None, evidence=None,
             settlement={"gameId": "20260730LGOB02026", "metric": "WIN_TEAM"},
             gameId="20260731LGOB02026",   # settlement.gameId와 불일치
             deadlineAt="2026-07-30T07:30:00Z")
    violations = vc.validate_candidate(c, CATALOG, BANNED)
    assert any("gameId" in v and "불일치" in v for v in violations)


def test_prediction_deadline_outside_gameid_date_rejected():
    # I4: deadlineAt이 gameId 날짜(KST)와 전혀 무관한 값이면 보수적 sanity 검사로 거부한다.
    # (정확한 경기 시작시각 대조는 candidate에 시작시각 필드가 없어 LLM 검증 패스 몫 —
    # 여기서는 KST 날짜 경계만 결정적으로 검사한다.)
    c = ok_knowledge()
    c.update(kind="PREDICTION", templateId="PRED_WIN_LOSE", answer=None, evidence=None,
             settlement={"gameId": "20260730LGOB02026", "metric": "WIN_TEAM"},
             gameId="20260730LGOB02026",
             deadlineAt="2026-08-15T00:00:00Z")   # gameId 날짜와 무관한 마감
    violations = vc.validate_candidate(c, CATALOG, BANNED)
    assert any("deadlineAt" in v for v in violations)


def test_disabled_or_unknown_template_rejected():
    c = ok_knowledge(); c["templateId"] = "YOY_TEAM"; c["format"] = "MULTI4"
    assert vc.validate_candidate(c, CATALOG, BANNED)
    c = ok_knowledge(); c["templateId"] = "NOPE"
    assert vc.validate_candidate(c, CATALOG, BANNED)


def test_point_must_match_difficulty():
    c = ok_knowledge(); c["pointReward"] = 999
    assert vc.validate_candidate(c, CATALOG, BANNED)


def test_bq_must_match_difficulty():
    # 보상 두 축은 각각 검사된다 — pointReward가 맞아도 bqReward만 틀리면 실패.
    c = ok_knowledge(); c["bqReward"] = 999
    violations = vc.validate_candidate(c, CATALOG, BANNED)
    assert any("bqReward" in v for v in violations)
    assert not any("pointReward" in v for v in violations)


def test_bq_reward_is_required():
    c = ok_knowledge(); del c["bqReward"]
    violations = vc.validate_candidate(c, CATALOG, BANNED)
    assert any("필수 필드 누락" in v and "bqReward" in v for v in violations)


def test_banned_topic_rejected():
    c = ok_knowledge(); c["question"] = "음주운전 사건의 주인공은?"
    assert vc.validate_candidate(c, CATALOG, BANNED)


def test_real_catalog_loads():
    from pathlib import Path
    path = Path(__file__).resolve().parents[1] / "question-gen/config/question-templates.yaml"
    cat = vc.load_catalog(str(path))
    assert "H2H_SEASON_RECORD" in cat and cat["YOY_TEAM"]["enabled"] is False


def test_pitch_velocity_passes_but_arrest_warrant_is_banned():
    # 회귀 테스트: '구속'(투구 속도) 오탐 수정 확인.
    # 실제 banned-topics.txt는 '구속' 단독 항목을 '구속영장'/'구속기소'로 대체했다 —
    # 야구 스탯 용어 '구속'은 통과해야 하고, 법적 맥락 '구속영장'은 여전히 걸려야 한다.
    from pathlib import Path
    path = Path(__file__).resolve().parents[1] / "question-gen/config/banned-topics.txt"
    banned = vc.load_banned(str(path))

    ok = ok_knowledge(); ok["question"] = "최고 구속 155km/h를 던지는 투수는?"
    assert vc.validate_candidate(ok, CATALOG, banned) == []

    bad = ok_knowledge(); bad["question"] = "구속영장이 청구된 사건의 당사자는?"
    assert vc.validate_candidate(bad, CATALOG, banned)


# ── Finding 2: check 5 kind/format 불일치 경로 + check 2 세부 분기 ──────

def test_template_format_mismatch_rejected():
    # H2H_SEASON_RECORD는 카탈로그상 format=BINARY. candidate가 kind는 그대로
    # KNOWLEDGE로 맞추면서 format만 MULTI4로 선언(+보기 4개)하면 옵션 개수·id
    # 규칙(check 2)은 통과하지만 카탈로그와의 format 불일치(check 5)로 걸려야 한다.
    c = ok_knowledge()
    c["format"] = "MULTI4"
    c["options"] = [{"id": "A", "text": "LG"}, {"id": "B", "text": "두산"},
                    {"id": "C", "text": "KT"}, {"id": "D", "text": "SSG"}]
    violations = vc.validate_candidate(c, CATALOG, BANNED)
    assert any("format" in v and "카탈로그" in v for v in violations)


def test_option_id_duplicate_rejected():
    # option id가 [A, A]로 중복되면(순서·유니크 규칙 위반) check 2에서 걸려야 한다.
    c = ok_knowledge()
    c["options"] = [{"id": "A", "text": "LG"}, {"id": "A", "text": "두산"}]
    violations = vc.validate_candidate(c, CATALOG, BANNED)
    assert any("id" in v and "유니크" in v for v in violations)


def test_option_text_blank_rejected():
    # option text가 공백만 있으면(비어있음과 동치) check 2에서 걸려야 한다.
    c = ok_knowledge()
    c["options"] = [{"id": "A", "text": "   "}, {"id": "B", "text": "두산"}]
    violations = vc.validate_candidate(c, CATALOG, BANNED)
    assert any("text" in v and "비어" in v for v in violations)


# ── Finding 1: main() CLI 계약 (--dir 필수·파싱 실패·quizId 중복·exit code) ──

def _write_candidate(path, candidate):
    path.write_text(json.dumps(candidate, ensure_ascii=False), encoding="utf-8")


def test_main_requires_dir_arg():
    # --dir 없이 호출하면 argparse가 필수 인자 누락으로 SystemExit(2)를 던진다.
    with pytest.raises(SystemExit) as exc_info:
        vc.main([])
    assert exc_info.value.code == 2


def test_main_all_valid_exits_zero(tmp_path):
    _write_candidate(tmp_path / "QZ-1.json", ok_knowledge())
    with pytest.raises(SystemExit) as exc_info:
        vc.main(["--dir", str(tmp_path)])
    assert exc_info.value.code == 0


def test_main_json_parse_failure_exits_one(tmp_path):
    _write_candidate(tmp_path / "QZ-1.json", ok_knowledge())
    (tmp_path / "QZ-broken.json").write_text("not json {{{", encoding="utf-8")
    with pytest.raises(SystemExit) as exc_info:
        vc.main(["--dir", str(tmp_path)])
    assert exc_info.value.code == 1


def test_main_duplicate_quiz_id_exits_one(tmp_path):
    c1 = ok_knowledge()
    c2 = ok_knowledge(); c2["question"] = "다른 문항이지만 quizId가 같음"
    _write_candidate(tmp_path / "a.json", c1)
    _write_candidate(tmp_path / "b.json", c2)
    with pytest.raises(SystemExit) as exc_info:
        vc.main(["--dir", str(tmp_path)])
    assert exc_info.value.code == 1


def test_roster_template_forbidden_in_game_unit():
    """2026-10-03 신설(check 10) — CAREER_PATH·MEME_ORIGIN·RELATION_LINK는
    gameId가 있으면(경기 유닛) 금지된다. 교차 중복(경기 유닛 vs 팀 특화 유닛이
    같은 선수 소재를 독립적으로 중복 생성) 방지용."""
    c = ok_knowledge()
    c.update(templateId="CAREER_PATH", format="MULTI4", gameId="20261003LTKT02026",
             options=[{"id": "A", "text": "KT"}, {"id": "B", "text": "LG"},
                      {"id": "C", "text": "SSG"}, {"id": "D", "text": "키움"}])
    assert any("경기 유닛" in v and "CAREER_PATH" in v
               for v in vc.validate_candidate(c, CATALOG, BANNED))


def test_roster_template_allowed_outside_game_unit():
    """같은 템플릿이라도 gameId가 없으면(팀 특화·공통 유닛) 정상이다."""
    c = ok_knowledge()
    c.update(templateId="CAREER_PATH", format="MULTI4", gameId=None,
             options=[{"id": "A", "text": "KT"}, {"id": "B", "text": "LG"},
                      {"id": "C", "text": "SSG"}, {"id": "D", "text": "키움"}])
    assert vc.validate_candidate(c, CATALOG, BANNED) == []


# ── TEAMMATE_STAT_COMPARE (check 12, 2026-10-06 신설) ────────────

def ok_teammate_stat_compare():
    # 실제 player_season_stat 집계치를 흉내낸 샘플 — 보기엔 선수 이름만,
    # evidence는 기존 계약대로 정답(더 우수한 쪽) 선수 한 명의 envelope만
    # 가리킨다(source 1개·quote 1개 — runner/finalize.py check_evidence와
    # 같은 단일 소스 계약, ROUTINE.md §3-2).
    return {"quizId": "QZ-20261006-901", "gameId": None, "kind": "KNOWLEDGE",
            "type": "STAT", "templateId": "TEAMMATE_STAT_COMPARE", "format": "BINARY",
            "question": "롯데 두 선수 중 올 시즌 타율이 더 높은 쪽은?",
            "options": [{"id": "A", "text": "김민석"}, {"id": "B", "text": "전준우"}],
            "answer": "A",
            "evidence": {
                "source": "question-source/player_season_stat/2026-10-06/player_season_stat_53554.json",
                "quote": "롯데 김민석은(는) 2026시즌 90경기 300타수 102안타(타율 0.340) 8홈런 "
                         "40타점 10도루를 기록했다."},
            "settlement": None, "difficulty": "MEDIUM", "pointReward": 50,
            "bqReward": 2,
            "status": "PENDING", "createdAt": "2026-10-06T00:00:00Z",
            "deadlineAt": "2026-10-06T07:30:00Z", "createdBy": "AI_ENGINE",
            "teamCodes": ["LT"],
            "subject": {"scope": "PLAYER", "playerIds": [53554, 50129],
                        "teamCodes": [], "gameId": None}}


def test_teammate_stat_compare_valid_passes():
    assert vc.validate_candidate(ok_teammate_stat_compare(), CATALOG, BANNED) == []


def test_teammate_stat_compare_rejects_numbers_in_options():
    c = ok_teammate_stat_compare()
    c["options"] = [{"id": "A", "text": "김민석(타율 0.340)"}, {"id": "B", "text": "전준우"}]
    violations = vc.validate_candidate(c, CATALOG, BANNED)
    assert any("보기에 수치를 노출" in v for v in violations)


def test_teammate_stat_compare_requires_exactly_two_player_ids():
    c = ok_teammate_stat_compare()
    c["subject"]["playerIds"] = [53554]
    violations = vc.validate_candidate(c, CATALOG, BANNED)
    assert any("정확히 2명" in v for v in violations)

    c = ok_teammate_stat_compare()
    c["subject"]["playerIds"] = [53554, 50129, 12345]
    assert any("정확히 2명" in v for v in vc.validate_candidate(c, CATALOG, BANNED))


def test_teammate_stat_compare_requires_single_team_code():
    c = ok_teammate_stat_compare()
    c["teamCodes"] = ["LT", "KT"]
    violations = vc.validate_candidate(c, CATALOG, BANNED)
    assert any("teamCodes가 정확히 1개" in v for v in violations)


def test_teammate_stat_compare_requires_evidence_with_two_numbers():
    c = ok_teammate_stat_compare()
    c["evidence"]["quote"] = "김민석이 전준우보다 타율이 더 높다."
    violations = vc.validate_candidate(c, CATALOG, BANNED)
    assert any("evidence.quote가 실제 집계 수치" in v for v in violations)


def test_teammate_stat_compare_valid_passes_for_easy_hits_metric():
    # 2026-10-07 난이도 다변화 — 타자 안타(hits, raw count) 비교는 EASY다.
    # check 12는 지표에 무관하게 구조만 검사하므로(보기 숫자 금지·playerIds
    # 2명·teamCodes 1개·evidence.quote 숫자 2개 이상) EASY/HARD 지표에도 그대로
    # 통과해야 한다.
    c = ok_teammate_stat_compare()
    c.update(
        question="롯데 두 선수 중 올 시즌 안타가 더 많은 쪽은?",
        difficulty="EASY", pointReward=30, bqReward=1)
    assert vc.validate_candidate(c, CATALOG, BANNED) == []


def test_teammate_stat_compare_valid_passes_for_hard_rbi_metric():
    # 타자 타점(rbi) 비교는 HARD다.
    c = ok_teammate_stat_compare()
    c.update(
        question="롯데 두 선수 중 올 시즌 타점이 더 많은 쪽은?",
        difficulty="HARD", pointReward=80, bqReward=3)
    assert vc.validate_candidate(c, CATALOG, BANNED) == []


def test_teammate_stat_compare_valid_passes_for_hard_whip_metric():
    # 투수 WHIP(2026-10-07 exporter 신규 필드) 비교는 HARD다 — evidence.quote는
    # exporter가 렌더한 투수 기록 문장(경기수·이닝·평균자책점·탈삼진·WHIP 등
    # 숫자 여러 개 포함)을 그대로 쓰므로 check 12(숫자 2개 이상)를 자연히
    # 만족한다.
    c = ok_teammate_stat_compare()
    c.update(
        question="롯데 두 투수 중 올 시즌 WHIP이 더 낮은 쪽은?",
        difficulty="HARD", pointReward=80, bqReward=3,
        options=[{"id": "A", "text": "박세웅"}, {"id": "B", "text": "다른투수"}],
        evidence={
            "source": "question-source/player_season_stat/2026-10-07/player_season_stat_60100.json",
            "quote": "롯데 박세웅은(는) 2026시즌 20경기 33⅓이닝 평균자책점 8.10 "
                     "80탈삼진 WHIP 2.85를 기록했다."})
    assert vc.validate_candidate(c, CATALOG, BANNED) == []


def test_other_templates_unaffected_by_teammate_stat_compare_check():
    # check 12는 templateId==TEAMMATE_STAT_COMPARE일 때만 적용된다 — 다른
    # 템플릿은 보기에 숫자가 있어도(예: YESTERDAY_SCORE류) 영향받지 않는다.
    c = ok_knowledge()
    c.update(templateId="H2H_SEASON_RECORD", options=[
        {"id": "A", "text": "LG가 8승 2패로 우위"}, {"id": "B", "text": "두산이 우위"}])
    assert vc.validate_candidate(c, CATALOG, BANNED) == []
