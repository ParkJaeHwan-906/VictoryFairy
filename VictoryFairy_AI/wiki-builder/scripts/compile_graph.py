"""위키 선수 문서 front-matter → graph.json 컴파일 스크립트.

위키 빌더 routine이 선수별 마크다운 문서(front-matter에 relations)를 갱신한
뒤, 이 스크립트가 전체 문서를 훑어 `wiki/graph.json`을 재컴파일한다.

**문서(마크다운 front-matter)가 진실의 원천이고, graph.json은 언제든 문서를
다시 훑어 재컴파일할 수 있는 파생물이다.** graph.json 자체를 직접 수정하거나
graph.json만 보고 문서 상태를 역추정해서는 안 된다 — 항상 문서를 고치고 이
스크립트를 재실행한다.

`사건연루` 등 소재 민감도가 높은 relation 타입도 그래프 컴파일 대상에서
배제하지 않는다(기록 유지 목적). 퀴즈 생성 시 특정 relation 타입을 소재로
쓰지 않는 것은 퀴즈 생성기 쪽의 소비 규칙이며, 이 컴파일 단계의 책임이
아니다.

파일 구성: parse_front_matter(front-matter 파서) → build_graph(그래프 조립,
결정적) → main(CLI, 디렉토리 순회 + compiledAt 주입). stdlib + PyYAML만
사용한다(boto3 금지 — 입력은 로컬 디렉토리이며, 원격 데이터 준비는 routine이
`aws s3 sync`로 미리 해 둔다).
"""

import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

import yaml

# ── front-matter 파서 ────────────────────────────────────

def parse_front_matter(md_text: str) -> dict | None:
    """마크다운 문서 맨 앞 `---\\n ... \\n---` YAML 블록을 파싱한다.

    텍스트가 `---\\n`으로 시작하지 않거나 닫는 `\\n---`가 없으면 front-matter
    없음으로 보고 None을 반환한다. YAML이 깨져 있거나(yaml.YAMLError) 최상위
    구조가 dict가 아니면(예: 리스트·스칼라) 역시 None을 반환한다 — 이
    스크립트는 문서 하나가 깨졌다고 전체 컴파일을 죽여서는 안 되므로, 호출부
    (main)가 크래시 없이 스킵할 수 있도록 예외를 여기서 흡수한다.
    """
    if not md_text.startswith("---\n"):
        return None

    end_idx = md_text.find("\n---", 4)
    if end_idx == -1:
        return None

    yaml_block = md_text[4:end_idx]
    try:
        data = yaml.safe_load(yaml_block)
    except yaml.YAMLError:
        return None

    if not isinstance(data, dict):
        return None
    return data


# ── 그래프 조립 ──────────────────────────────────────────

def build_graph(docs: list, compiled_at: str | None = None) -> dict:
    """front-matter dict 목록을 노드·엣지 그래프로 조립한다(결정적).

    `kboPlayerId`가 없는 문서는 스킵한다(대상 밖 문서 — 예: 팀 개요 문서 등을
    나중에 추가하더라도 이 스크립트가 실수로 player 노드로 취급하지 않도록).

    노드는 두 종류다.
    - player 노드: id `player:{kboPlayerId}`. 문서가 실재하면 name/team을
      문서 값으로 채우고, relations의 target으로만 언급되고 그 문서가 아직
      없는 경우에는 자리표시자 노드(name=None, team=None)를 자동 생성한다.
    - team 노드: id `team:{code}`. 여러 선수가 같은 팀이면 1개로 중복
      제거(dedup)한다.

    team 노드의 `name`은 항상 None이다 — front-matter에는 팀 코드(예: `HT`)만
    있고 팀 전체 이름(예: "KIA 타이거즈")은 이 계약에 없어 채울 데이터가
    없기 때문이다(추후 팀 이름 매핑이 추가되면 여기만 바뀐다).

    엣지는 두 종류다.
    - 소속 엣지: 선수 문서마다 `{"source": player, "target": team,
      "type": "소속", "ref": None}` 1개.
    - relations 엣지: 문서의 `relations` 항목을 그대로 옮기되 target만
      `player:{target}`으로 정규화한다. `type`·`ref`는 그대로 보존한다 —
      `사건연루`처럼 민감한 타입도 여기서 걸러내지 않는다(모듈 docstring 참고).

    relations 항목 하나가 스키마를 벗어나도(dict가 아님, `target` 없음,
    `type` 없음) 전체 컴파일이 죽어서는 안 된다 — "문서가 진실의 원천"
    원칙은 문서 단위 격리를 전제로 한다. 그런 불량 항목은 경고를 stderr에
    남기고 그 항목만 스킵한다(같은 문서의 나머지 relations, 다른 문서에는
    영향 없음).

    최종 반환 전에 노드는 `id` 기준, 엣지는 `(source, type, target)` 기준으로
    정렬한다 — 입력 docs 순서와 무관하게 실행마다 같은 결과가 나오게 하기
    위한 결정성 보장이다(불량 relation을 스킵으로 처리하는 것도 이 정렬이
    `None`과 `str`을 비교하다 죽지 않게 하는 전제 조건이다). `compiled_at`은
    이 함수 자체와 무관한 부수 값이라 인자로 주입받는다(기본값 None) —
    그래프 조립 로직 자체는 순수하게 유지한다.
    """
    nodes: dict[str, dict] = {}
    edges: list[dict] = []

    valid_docs = [doc for doc in docs if doc and doc.get("kboPlayerId")]

    # 1단계: 실제 문서로부터 player/team 노드를 먼저 확정한다. relations가
    # 참조하는 자리표시자 노드를 나중 단계에서 만들 때, 이미 실제 문서가
    # 채워둔 노드를 덮어쓰지 않도록 순서를 나눈다(입력 리스트 순서에 무관하게
    # 결과가 같아야 하므로).
    for doc in valid_docs:
        player_id = f"player:{doc['kboPlayerId']}"
        team_code = doc.get("team")
        nodes[player_id] = {
            "id": player_id,
            "type": "player",
            "name": doc.get("name"),
            "team": team_code,
        }
        if team_code:
            team_id = f"team:{team_code}"
            nodes.setdefault(team_id, {
                "id": team_id,
                "type": "team",
                "name": None,
                "team": None,
            })

    # 2단계: 소속 엣지 + relations 엣지. relations의 target이 아직 노드에
    # 없으면(상대 선수의 문서가 없는 경우) 자리표시자 노드를 만든다.
    for doc in valid_docs:
        player_id = f"player:{doc['kboPlayerId']}"
        team_code = doc.get("team")
        if team_code:
            edges.append({
                "source": player_id,
                "target": f"team:{team_code}",
                "type": "소속",
                "ref": None,
            })

        for rel in doc.get("relations") or []:
            # relations 항목별 방어: dict가 아니거나 target/type이 없으면
            # 이 항목만 스킵한다(문서 단위 격리 — 크래시 금지, 위 docstring
            # 참고). target 누락은 과거 KeyError, dict가 아닌 항목은 과거
            # TypeError, type 누락은 과거 정렬 단계의 NoneType/str 비교
            # TypeError로 전체 컴파일을 죽였다.
            if not isinstance(rel, dict):
                print(
                    f"경고: {player_id} relations 항목이 dict가 아님, 스킵: {rel!r}",
                    file=sys.stderr,
                )
                continue
            target = rel.get("target")
            if not target:
                print(
                    f"경고: {player_id} relations 항목에 target 없음, 스킵: {rel!r}",
                    file=sys.stderr,
                )
                continue
            rel_type = rel.get("type")
            if not rel_type:
                print(
                    f"경고: {player_id} relations 항목에 type 없음, 스킵: {rel!r}",
                    file=sys.stderr,
                )
                continue

            target_id = f"player:{target}"
            nodes.setdefault(target_id, {
                "id": target_id,
                "type": "player",
                "name": None,
                "team": None,
            })
            edges.append({
                "source": player_id,
                "target": target_id,
                "type": rel_type,
                "ref": rel.get("ref"),
            })

    sorted_nodes = sorted(nodes.values(), key=lambda n: n["id"])
    sorted_edges = sorted(edges, key=lambda e: (e["source"], e["type"], e["target"]))

    return {
        "compiledAt": compiled_at,
        "nodes": sorted_nodes,
        "edges": sorted_edges,
    }


# ── CLI ──────────────────────────────────────────────────

def _build_arg_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="선수 위키 문서 front-matter를 훑어 graph.json을 재컴파일한다.",
    )
    parser.add_argument("--players-dir", required=True, help="선수 마크다운 문서(*.md) 디렉토리")
    parser.add_argument("--out", required=True, help="graph.json 출력 경로")
    return parser


def main(argv: list | None = None) -> None:
    """CLI 진입점.

    `--players-dir`의 `*.md`를 파일명순으로 순회하며 front-matter를 읽는다.
    front-matter가 없거나(형식 미준수) 깨진 문서는 경고를 출력하고 스킵한다
    — 문서 하나 때문에 전체 컴파일이 죽으면 안 된다. 나머지 문서로
    `build_graph`를 호출하고, `compiledAt`(ISO 8601 UTC 타임스탬프)을 이
    함수가 주입해 `--out`에 JSON으로 기록한다(`ensure_ascii=False,
    indent=2` — 한글 이름을 이스케이프 없이 그대로 저장).
    """
    args = _build_arg_parser().parse_args(argv)

    docs = []
    for path in sorted(Path(args.players_dir).glob("*.md")):
        text = path.read_text(encoding="utf-8")
        fm = parse_front_matter(text)
        if fm is None:
            print(f"경고: {path.name} — front-matter 없음/파싱 실패, 스킵", file=sys.stderr)
            continue
        docs.append(fm)

    compiled_at = datetime.now(timezone.utc).isoformat()
    graph = build_graph(docs, compiled_at=compiled_at)

    Path(args.out).write_text(
        json.dumps(graph, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
