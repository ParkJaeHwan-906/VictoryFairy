def schedule_url(settings, date: str) -> str:
    return settings.schedule_url_template.format(base=settings.naver_base_url, date=date)


def result_url(settings, game_id: str) -> str:
    return settings.result_url_template.format(base=settings.naver_base_url, gameId=game_id)


def relay_url(settings, game_id: str, inning: int) -> str:
    return settings.relay_url_template.format(
        base=settings.naver_base_url, gameId=game_id, inning=inning
    )


def preview_url(settings, game_id: str) -> str:
    return settings.preview_url_template.format(
        base=settings.naver_base_url, gameId=game_id
    )


def extract_game_ids(schedule_json: dict) -> list[str]:
    """gameIds of finished, non-cancelled KBO first-team games.

    Delegates to game_records.list_finished_games so the S3 landing path
    (land_schedule) and the DB path agree on exactly which games count:
    categoryId=='kbo', statusCode=='RESULT', not cancelled, real team codes.
    A cancelled game is BEFORE/cancel=true with a 0-0 skeleton; collecting it
    freezes an empty snapshot into S3 that the existence checkpoint never heals.
    """
    from .game_records import list_finished_games

    games = list_finished_games(schedule_json)
    return [g["gameId"] for g in games if g.get("gameId")]


def relay_is_empty(relay_json: dict) -> bool:
    """True when this inning is out of the game's range (no at-bats).

    Heuristic: empty when `result` is falsy or `result.textRelayData.textRelays`
    is absent/empty.
    """
    result = relay_json.get("result")
    if not result:
        return True
    data = result.get("textRelayData") or {}
    relays = data.get("textRelays") if isinstance(data, dict) else None
    return not relays


# 한 타석(`textRelays[]`의 한 항목)의 "이 타석 최종 결과" 텍스트가 실리는
# textOption.type. 실측(2026-09-20 한화-LG전 9이닝 relay 전수, 89타석)상
#   13 = 표준 종료(아웃/볼넷/몸에 맞는 볼/야수선택·에러성 출루 등)
#   23 = 13과 같은 자리이지만 안타·홈런·희생플라이 아웃이 나오는 타석에 쓰인다
# 둘 다 "이름 : 결과텍스트" 형태로 **타자 본인**의 타석 결과를 담는다. (14/24는
# 그 결과로 생긴 **주자**의 진루/아웃 안내라 타자 본인 결과가 아니라서 보지 않는다.)
_PA_RESULT_TYPES = (13, 23)

# 안타를 직접 말해주는 결과 텍스트 키워드. 실측 89타석 전수 확인상 안타인 경우에만
# 등장하고 아웃·병살타·희생플라이 아웃·야수선택/에러성 출루 등 비안타 결과와는
# 전혀 겹치지 않는다 — 예: "내야안타"/"번트안타" → "안타", "좌익수 앞 1루타"/
# "우익수 오른쪽 2루타"/"우익수 뒤 3루타" → "루타", "좌익수 뒤 홈런" → "홈런".
# "병살타"는 "안타"를 부분 문자열로 포함하지 않아 오탐이 없다.
_HIT_TEXT_MARKERS = ("안타", "루타", "홈런")


def _is_hit_result_text(text: str) -> bool:
    return any(marker in text for marker in _HIT_TEXT_MARKERS)


def _play_batter_pcode(play: dict) -> str | None:
    """이 타석(play)의 타자 pcode.

    1차 소스는 `textOptions[*].batterRecord.pcode`(보통 textOptions[0], "N번타자
    OOO" 소개 textOption 에 실린다). 실측 89타석 중 순수 교체 공시 1건만
    batterRecord 가 전혀 없었고, 그 play 엔 안타 결과 텍스트 자체가 없었다 — 그래도
    안전하게 `currentGameState.batter`(그 시점 타자 pcode)로 한 번 더 떨어진다.
    """
    for opt in play.get("textOptions") or []:
        br = opt.get("batterRecord")
        if br and br.get("pcode"):
            return br["pcode"]
    for opt in play.get("textOptions") or []:
        pcode = (opt.get("currentGameState") or {}).get("batter")
        if pcode:
            return pcode
    return None


def extract_inning_events(relay_json: dict, inning: int, half: int) -> dict:
    """그 이닝·그 공수(half)에서 안타가 발생한 타자들의 pcode 를 모아
    `{"<pcode>": {"hit": True}, ...}` 로 반환한다(안타가 없으면 `{}`).

    `half` 는 domain `InningHalf` ordinal(TOP=0/BOTTOM=1)과 같은 값 — 네이버
    원본의 `homeOrAway`("0"=원정 공격=TOP, "1"=홈 공격=BOTTOM)와 그대로 대응한다.

    ## 판정 방식과 그 근거(실측, 2026-09-20 한화-LG전 S3 raw-json/relay 9이닝 전수)
    처음 가정("batterRecord.hit/ab 는 그 타석 **이전까지의** 누적치라 직전 타석과
    비교해 증가분을 봐야 한다")은 **데이터로 확인해보니 틀렸다** — 실측상
    `textOptions[0]`(소개, type=8)의 `batterRecord.hit`/`ab`/`pa` 는 **그 타석이
    끝난 뒤** 값이다. 예: 1번타자 박해민의 그 경기 첫 타석이 내야안타로 끝나면,
    바로 그 타석의 소개 textOption 에서 이미 `ab=1, hit=1`이 찍힌다 — 타석 시작
    전 상태가 아니라 결과가 반영된 스냅샷이다. 그래서 "직전 등장과 비교"는 이
    데이터 구조상 불필요하게 복잡하고(같은 이닝에 같은 타자가 보통 한 번만
    나오므로 직전 **이닝**까지의 값을 추가로 알아야 함), 더 위험하다.

    대신 그 타석의 **결과 텍스트**(`type` 13/23 textOption 의 `text`, 예:
    "박해민 : 유격수 뒤 내야안타", "이재원 : 좌익수 뒤 홈런 (홈런거리:140M)")가
    안타 여부를 직접 말해준다 — 그래서 누적값 비교 대신 **이 결과 텍스트를 직접
    판정**하는 방식을 택했다(`_HIT_TEXT_MARKERS` 설명 참고).
    """
    if not relay_json:
        return {}
    result = relay_json.get("result") or {}
    data = result.get("textRelayData") or {}
    plays = data.get("textRelays") or []
    events: dict = {}
    for play in plays:
        if play.get("inn") != inning or play.get("homeOrAway") != str(half):
            continue
        for opt in play.get("textOptions") or []:
            if opt.get("type") not in _PA_RESULT_TYPES:
                continue
            if not _is_hit_result_text(opt.get("text") or ""):
                continue
            pcode = _play_batter_pcode(play)
            if pcode is None:
                # pcode 를 못 구하면 조용히 건너뛴다 — 미탐지가 오탐지보다 안전하다.
                continue
            events[pcode] = {"hit": True}
            break  # 이 타석은 끝 — 같은 play 의 다른 textOption으로 중복 집계 방지
    return events
