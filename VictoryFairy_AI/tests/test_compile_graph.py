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


# ── Finding 1 회귀: relations 스키마 이탈이 전체 컴파일을 죽이면 안 된다 ──

def test_relation_missing_target_does_not_crash():
    """target 없는 relation 항목 — 과거 `rel['target']`에서 KeyError로 전체
    컴파일이 죽었다. 이제는 해당 항목만 스킵하고 소속 엣지는 정상 생성된다."""
    doc = {
        "name": "A", "team": "HT", "kboPlayerId": "1",
        "relations": [{"type": "밈공유", "ref": "x"}],
    }
    g = cg.build_graph([doc])
    assert g["edges"] == [
        {"source": "player:1", "target": "team:HT", "type": "소속", "ref": None}
    ]


def test_relation_not_a_dict_does_not_crash():
    """relations 항목이 dict가 아닌 경우 — 과거 `rel['target']`/`rel.get`
    호출에서 TypeError로 전체 컴파일이 죽었다. 이제는 스킵된다."""
    doc = {
        "name": "A", "team": "HT", "kboPlayerId": "1",
        "relations": ["오타", 123, None],
    }
    g = cg.build_graph([doc])
    assert g["edges"] == [
        {"source": "player:1", "target": "team:HT", "type": "소속", "ref": None}
    ]


def test_relation_missing_type_does_not_crash_on_sort():
    """type 없는 relation 항목 — 과거 `type=None`인 엣지가 정렬 단계에서
    `None`과 `str`을 비교하며 TypeError로 죽었다. 이제는 스킵된다."""
    doc = {
        "name": "A", "team": "HT", "kboPlayerId": "1",
        "relations": [{"target": "2"}],
    }
    g = cg.build_graph([doc])
    assert g["edges"] == [
        {"source": "player:1", "target": "team:HT", "type": "소속", "ref": None}
    ]


def test_relations_malformed_items_are_isolated_per_item():
    """같은 문서의 relations 리스트에 불량 항목과 정상 항목이 섞여 있어도,
    불량 항목만 스킵되고 정상 항목은 그대로 엣지로 컴파일된다(문서 단위
    격리가 항목 단위까지 내려감을 확인)."""
    doc = {
        "name": "A", "team": "HT", "kboPlayerId": "1",
        "relations": [
            "not-a-dict",
            {"type": "밈공유"},
            {"target": "2"},
            {"type": "친분", "target": "3", "ref": None},
        ],
    }
    g = cg.build_graph([doc])
    types = {(e["source"], e["type"], e["target"]) for e in g["edges"]}
    assert ("player:1", "친분", "player:3") in types
    assert len(g["edges"]) == 2  # 소속 1개 + 정상 relation 1개


# ── Finding 2: 핵심 계약을 저장소에 회귀 테스트로 고정 ──

def test_parse_front_matter_broken_yaml_returns_none():
    """깨진 YAML(yaml.YAMLError)도 크래시 없이 None을 반환한다."""
    broken = "---\nname: [unterminated\n---\nbody\n"
    assert cg.parse_front_matter(broken) is None


def test_build_graph_order_independent():
    """결정성: 입력 docs 순서를 바꿔도 노드·엣지 출력이 완전히 동일하다."""
    doc1 = cg.parse_front_matter(DOC)
    doc2 = {
        "name": "정해영", "team": "KIA", "kboPlayerId": "60633",
        "relations": [],
    }
    g1 = cg.build_graph([doc1, doc2])
    g2 = cg.build_graph([doc2, doc1])
    assert g1["nodes"] == g2["nodes"]
    assert g1["edges"] == g2["edges"]


def test_incident_relation_type_is_compiled_not_filtered():
    """`사건연루` 타입도 컴파일 단계에서 배제하지 않는다(소비 배제는 퀴즈
    생성기 쪽 규칙이며 이 스크립트의 책임이 아니다)."""
    doc = {
        "name": "A", "team": "HT", "kboPlayerId": "1",
        "relations": [{"type": "사건연루", "target": "2", "ref": "news:1"}],
    }
    g = cg.build_graph([doc])
    types = {(e["source"], e["type"], e["target"]) for e in g["edges"]}
    assert ("player:1", "사건연루", "player:2") in types


def test_relation_target_without_own_doc_creates_placeholder_node():
    """relations의 target에 해당하는 문서가 없으면 name=None, team=None
    자리표시자 노드를 자동 생성한다."""
    doc = {
        "name": "A", "team": "HT", "kboPlayerId": "1",
        "relations": [{"type": "친분", "target": "999", "ref": None}],
    }
    g = cg.build_graph([doc])
    placeholder = next(n for n in g["nodes"] if n["id"] == "player:999")
    assert placeholder == {
        "id": "player:999", "type": "player", "name": None, "team": None,
    }


def test_actual_doc_node_wins_over_placeholder_regardless_of_order():
    """A가 B를 참조하는 relation이 있고 B도 자기 문서를 갖고 있다면, B의
    실제 name/team이 자리표시자(None)로 덮이면 안 된다 — 입력 순서 무관."""
    doc_a = {
        "name": "A", "team": "HT", "kboPlayerId": "1",
        "relations": [{"type": "친분", "target": "2", "ref": None}],
    }
    doc_b = {"name": "B", "team": "KIA", "kboPlayerId": "2", "relations": []}

    for docs in ([doc_a, doc_b], [doc_b, doc_a]):
        g = cg.build_graph(docs)
        node_b = next(n for n in g["nodes"] if n["id"] == "player:2")
        assert node_b == {
            "id": "player:2", "type": "player", "name": "B", "team": "KIA",
        }
