import json
from pathlib import Path

import pytest

SEASON_JSON = {
    "headToHead": {"HH|LT": {}, "OB|SK": {}},
    "standings": [{"team": "HH"}, {"team": "LT"}, {"team": "OB"}],
}
GAME_ENV = {
    "docId": "game_result:20260728OBSK02026", "docType": "game_result",
    "content": "2026-07-28 문학에서 열린 두산 대 SSG 경기는 2:1, 두산의 승리로 끝났다. 승리투수 이영하.",
    "entities": {"gameId": "20260728OBSK02026"}, "payload": None,
}
WIKI_DOC = """---
name: 김대한
team: OB
kboPlayerId: "69238"
relations: []
---
## 별명·밈
- **야구의 신**: 신격화 밈[^ref1]

## 커리어 이력

[^ref1]: community_post:FMKOREA:2026-07-31:10155618461
"""


@pytest.fixture
def work(tmp_path: Path) -> Path:
    w = tmp_path / ".work"
    (w / "stats").mkdir(parents=True)
    (w / "stats/season.json").write_text(json.dumps(SEASON_JSON), encoding="utf-8")
    (w / "stats/season.md").write_text("- OB: 2연승\n", encoding="utf-8")
    (w / "stats/kbo-official.md").write_text("| 1 | 레이예스 | 롯데 | 0.351 |\n", encoding="utf-8")
    (w / "game_result/2026-08-01").mkdir(parents=True)
    (w / "game_result/2026-08-01/game_result_20260728OBSK02026.json").write_text(
        json.dumps(GAME_ENV, ensure_ascii=False), encoding="utf-8")
    (w / "wiki/players").mkdir(parents=True)
    (w / "wiki/players/69238.md").write_text(WIKI_DOC, encoding="utf-8")
    (w / "quiz-candidates/2026-08-01").mkdir(parents=True)
    (w / "quiz-candidates/2026-08-01/QZ-20260801-005.json").write_text(json.dumps(
        {"quizId": "QZ-20260801-005", "templateId": "MEME_OWNER",
         "question": "요즘 '야구의 신'으로 불리는 두산 타자는?"}, ensure_ascii=False),
        encoding="utf-8")
    return w
