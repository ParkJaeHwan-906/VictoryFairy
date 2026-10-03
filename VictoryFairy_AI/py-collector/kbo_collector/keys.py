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
