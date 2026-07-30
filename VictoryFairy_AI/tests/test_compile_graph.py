import compile_graph as cg

DOC = """---
name: 김도영
team: HT
playerUid: 412
kboPlayerId: "60632"
updatedAt: 2026-07-28
relations:
  - { type: 밈공유, target: "60633", ref: "community_post:DCINSIDE:111" }
---
## 프로필 요약
본문
"""


def test_parse_front_matter():
    fm = cg.parse_front_matter(DOC)
    assert fm["name"] == "김도영" and fm["kboPlayerId"] == "60632"
    assert cg.parse_front_matter("# 그냥 마크다운") is None


def test_build_graph_nodes_and_edges():
    g = cg.build_graph([cg.parse_front_matter(DOC)])
    ids = {n["id"] for n in g["nodes"]}
    assert {"player:60632", "team:HT"} <= ids
    types = {(e["source"], e["type"], e["target"]) for e in g["edges"]}
    assert ("player:60632", "소속", "team:HT") in types
    assert ("player:60632", "밈공유", "player:60633") in types


def test_cli(tmp_path):
    (tmp_path / "60632.md").write_text(DOC, encoding="utf-8")
    out = tmp_path / "graph.json"
    cg.main(["--players-dir", str(tmp_path), "--out", str(out)])
    import json
    g = json.loads(out.read_text(encoding="utf-8"))
    assert g["nodes"] and g["edges"] and g["compiledAt"]
