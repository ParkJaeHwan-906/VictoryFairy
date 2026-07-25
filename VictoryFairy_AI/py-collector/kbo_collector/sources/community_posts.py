"""커뮤니티 글 수집 소스 (기존 community job 위임)."""
import uuid

from .base import CollectResult, register


@register
class CommunityPosts:
    source_id = "community_posts"
    doc_types = ("community_post",)

    def collect(self, ctx) -> CollectResult:
        from .. import run
        from ..journal import Journal
        date = ctx.date or run._kst_today()
        journal = Journal("community", date, uuid.uuid4().hex[:8],
                          ctx.settings.journal_dir)
        landed = run.land_community(date, settings=ctx.settings, sink=ctx.sink,
                                    client=ctx.client, journal=journal)
        return CollectResult(loaded=landed)
