def schedule_key(date: str) -> str:
    # One schedule per date; a fixed, self-describing name (no opaque hash).
    return f"raw-json/schedule/{date}/schedule.json"


def result_key(date: str, game_id: str) -> str:
    # gameId is the natural, unique identifier for a game's result.
    return f"raw-json/result/{date}/{game_id}.json"


def relay_key(game_id: str, inning: int) -> str:
    return f"raw-json/relay/{game_id}/{inning}.json"


def community_key(source: str, date: str, post_external_id: str) -> str:
    return f"community/{source.lower()}/{date}/{post_external_id}.json"


def dead_letter_key(job: str, date: str, item_id: str) -> str:
    return f"dead-letter/{job}/{date}/{item_id}.json"


def manifest_key(job: str, date: str, run_id: str) -> str:
    return f"manifests/{job}/{date}/{run_id}.json"


def kbo_records_key(page: str, date: str) -> str:
    return f"kbo-records/{page}/{date}.json"


def inning_event_key(date: str, game_id: str, inning: int, half: int) -> str:
    # games_sync 라이브 폴링의 이닝 전환 감지가 "막 끝난 이닝"에 대해 적재한다.
    # half 는 domain InningHalf ordinal(TOP=0/BOTTOM=1)과 동일한 값.
    return f"inning-events/{date}/{game_id}/{inning}-{half}.json"


def game_state_event_key(date: str, game_id: str, observed_at: str) -> str:
    # games_sync 라이브 폴링이 이닝·점수·상태 중 하나라도 바뀐 경기의 스냅샷을 적재한다
    # (run._land_game_state_event). 이닝 이벤트(inning_event_key)와 달리 "막 끝난 이닝"이
    # 아니라 "지금 상태" 라서 키에 이닝 대신 관측 시각이 들어간다 — 같은 이닝 안에서도
    # 득점이 여러 번 나면 그 수만큼 문서가 생긴다. observed_at 은 UTC
    # "YYYYMMDDTHHMMSSffffffZ" — 폴링 1회당 경기 하나에 최대 1건이라 충돌하지 않는다.
    return f"game-state-events/{date}/{game_id}/{observed_at}.json"
