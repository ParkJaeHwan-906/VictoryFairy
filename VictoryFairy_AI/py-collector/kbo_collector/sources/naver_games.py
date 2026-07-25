"""네이버 record API 박스스코어 수집 소스 (기존 records job 위임)."""
from .base import CollectResult, register


@register
class NaverGames:
    source_id = "naver_games"
    doc_types = ("game_result",)

    def collect(self, ctx) -> CollectResult:
        from .. import run  # 지연 임포트: run→sources 순환 방지
        date = ctx.date or run._today()
        out = run.land_game_records_range(date, date, settings=ctx.settings,
                                          db=ctx.db, client=ctx.client)
        return CollectResult(loaded=len(out["loaded"]), failed=out["failed"])
