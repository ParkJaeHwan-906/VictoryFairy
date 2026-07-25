"""선수-밈 사전 소스: config/memes.yaml → player_meme envelope → S3.

크롤 없는 파일 소스. 중간 저장소가 없으므로 collect가 곧 export다(스펙 3-6).
"""
from datetime import datetime, timezone
from pathlib import Path

import yaml

from ..exports.envelope import Envelope, empty_entities, s3_key
from .base import CollectResult, register

_UID_SQL = "SELECT player_uid FROM game_players WHERE name=%s AND team_code=%s"


def resolve_player_uid(db, name: str, team_code: str):
    """이름+팀 유일매칭. (uid, None) 또는 (None, 실패사유)."""
    rows = db.fetch_all(_UID_SQL, (name, team_code))
    if not rows:
        return None, "not-found"
    if len(rows) > 1:
        return None, "duplicate-name"
    return rows[0][0], None


@register
class MemeDict:
    source_id = "meme_dict"
    doc_types = ("player_meme",)

    def collect(self, ctx) -> CollectResult:
        path = Path(ctx.settings.memes_file)
        entries = yaml.safe_load(path.read_text(encoding="utf-8")) or []
        now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        today = now[:10]
        loaded, failed = 0, []
        for i, entry in enumerate(entries):
            try:
                name = entry["player"]["name"]
                team = entry["player"]["team"]
                uid, reason = resolve_player_uid(ctx.db, name, team)
                entities = empty_entities()
                entities["teamCodes"] = [team]
                if uid is not None:
                    entities["playerUids"] = [uid]
                else:
                    entities["unresolved"] = [
                        {"kind": "player", "name": name, "reason": reason}]
                for meme in entry.get("memes") or []:
                    text = meme["text"]
                    env = Envelope(
                        doc_id=f"player_meme:{team}:{name}:{text}",
                        doc_type="player_meme",
                        source="seed_file",
                        source_ref=str(path),
                        collected_at=now,
                        title=f"{name} 밈: {text}",
                        content=f"{team} {name}의 밈 '{text}': {meme.get('origin', '')}".strip(),
                        tags=["밈", *(meme.get("tags") or [])],
                        entities=dict(entities),
                        payload={"text": text, "origin": meme.get("origin")},
                        pii={"masked": True},
                    )
                    try:
                        env.validate()
                        ctx.sink.put_json(s3_key(env.doc_type, today, env.doc_id),
                                          env.to_dict())
                        loaded += 1
                    except Exception as exc:  # 항목 격리: 한 밈 실패가 전체를 막지 않음
                        failed.append(f"{env.doc_id}: {exc}")
            except Exception as exc:  # 진입 격리: 한 선수 항목 실패가 전체를 막지 않음
                entry_label = name if 'name' in locals() else f"entry[{i}]"
                failed.append(f"{entry_label}: {exc}")
        return CollectResult(loaded=loaded, failed=failed)
