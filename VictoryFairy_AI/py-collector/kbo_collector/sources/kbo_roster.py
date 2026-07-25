"""KBO 공식 1군 등록명단 수집 소스 (기존 registrations job 위임)."""
from .base import CollectResult, register


@register
class KboRoster:
    source_id = "kbo_roster"
    doc_types = ("player_profile",)

    def collect(self, ctx) -> CollectResult:
        from .. import run
        synced = run.land_registrations(ctx.date, settings=ctx.settings,
                                        db=ctx.db, client=ctx.client)
        return CollectResult(loaded=len(synced))
