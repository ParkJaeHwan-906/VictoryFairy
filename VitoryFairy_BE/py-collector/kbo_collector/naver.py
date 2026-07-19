def schedule_url(settings, date: str) -> str:
    return settings.schedule_url_template.format(base=settings.naver_base_url, date=date)


def result_url(settings, game_id: str) -> str:
    return settings.result_url_template.format(base=settings.naver_base_url, gameId=game_id)


def relay_url(settings, game_id: str, inning: int) -> str:
    return settings.relay_url_template.format(
        base=settings.naver_base_url, gameId=game_id, inning=inning
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
    is absent/empty. VERIFY this path against a live capture in the notebook
    smoke test (Task 15) and adjust if Naver's schema differs.
    """
    result = relay_json.get("result")
    if not result:
        return True
    data = result.get("textRelayData") or {}
    relays = data.get("textRelays") if isinstance(data, dict) else None
    return not relays
