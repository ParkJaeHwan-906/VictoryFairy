# Bedrock 퀴즈 러너 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 퀴즈 생성기를 Claude 클라우드 루틴에서 "컨테이너 + Bedrock 구조화 호출 2번(C1 작문·C2 심사)"으로 옮겨, EKS CronJob으로 매일 돌 수 있는 러너를 만든다.

**Architecture:** entrypoint bash가 기존 ROUTINE.md 셸 블록(동기화→통계→게이트→업로드)을 그대로 수행하고, LLM이 필요한 ③문구 생성·④품질 심사만 `runner/` 파이썬 모듈이 Bedrock InvokeModel로 호출한다. 템플릿 선택·바인딩·evidence 대조·quizId 부여는 전부 결정적 코드다. 스펙: `../specs/2026-08-03-bedrock-quiz-runner-design.md` (위키 러너 C3·C4는 이 계획 범위 밖 — 후속 계획).

**Tech Stack:** Python 3.12, boto3(bedrock-runtime만), PyYAML, pytest / Docker(arm64) / 기존 `question-gen/` 스크립트·프롬프트 재사용.

## Global Constraints

- 기존 결정적 스크립트(`question-gen/scripts/aggregate_stats.py`·`validate_candidates.py`)와 프롬프트 문서·카탈로그는 **수정 금지** — 러너는 읽기만 한다.
- 러너 파이썬 의존성은 **boto3·PyYAML만** (그 외 stdlib). S3 접근은 entrypoint의 aws CLI 몫이고, boto3는 `bedrock-runtime` 호출에만 쓴다.
- 모델 기본값(2026-08-03 ap-northeast-2 실측, env로 오버라이드): C1 = `global.anthropic.claude-sonnet-5`, C2 = `global.anthropic.claude-haiku-4-5-20251001-v1:0`.
- LLM 금지사항(스펙 §4): 수치 계산 금지, evidence 창작 금지(원문 복사만), 카탈로그 수정 금지. evidence 원문 대조는 LLM 판단과 무관하게 항상 코드로 실행한다.
- 산출물 계약 불변: `quiz-candidates/{KST날짜}/{quizId}.json`, 필드는 스펙 §4.3(REQUIRED_FIELDS는 `validate_candidates.py` 기준). quizId는 `(templateId, entity)` 사전순 `QZ-{YYYYMMDD}-{NNN}` (generation-rules §7 멱등 규칙).
- deadlineAt: KNOWLEDGE = 출제일 23:59 KST(UTC `T14:59:00Z`), PREDICTION = 경기 시작(KST) 2시간 전 (generation-rules §10).
- Bedrock 응답은 JSON 강제. 파싱 실패 시 1회 재시도, 재실패 시 exit 1(그날 업로드 생략).
- 테스트에서 실제 Bedrock 호출 금지 — 클라이언트는 주입 가능한 인터페이스로 만들고 fake로 대체한다.
- 커밋 메시지는 기존 컨벤션(`feat(runner): ...` 등 한국어 요약).

## File Structure

```
VictoryFairy_AI/runner/
  Dockerfile                # python:3.12-slim + awscli + 리포 필요분 COPY
  entrypoint-quiz.sh        # ROUTINE.md 1·2·6게이트·7·8단계 이식 + runner 호출
  requirements.txt          # boto3, PyYAML
  runner/
    __init__.py
    config.py               # 환경변수 → RunnerConfig
    catalog.py              # 카탈로그 로드 + (템플릿×엔티티) 결정적 선택
    binding.py              # needs 가용성 판정 + 엔티티 열거 + 데이터 바인딩
    bedrock_client.py       # invoke_json (재시도·JSON 파싱)
    generate.py             # C1 프롬프트 조립·응답 파싱
    judge.py                # C2 프롬프트 조립·응답 파싱
    finalize.py             # evidence 대조·비율 선별·quizId·파일 쓰기
    main.py                 # run() 오케스트레이션 + CLI
  tests/
    conftest.py             # .work 픽스처 빌더
    test_catalog.py test_binding.py test_bedrock_client.py
    test_generate.py test_judge.py test_finalize.py test_main.py
VictoryFairy_AI/deploy/runner/
  cronjob-quiz.yaml         # EKS CronJob 매니페스트 초안 (apply는 dev_infra)
  irsa-policy-runner.json   # S3 최소권한 + bedrock:InvokeModel
  README.md                 # 배포·검증 절차
```

인터페이스 요약(모든 태스크 공통 어휘):

- `Work` = `.work` 디렉토리 경로(pathlib.Path). 레이아웃은 ROUTINE.md 1·2단계 산출과 동일: `game_result/{date}/*.json`, `game_schedule/{date}/*.json`, `kbo-records/`, `wiki/`, `stats/`, `quiz-candidates/{date}/*.json`.
- 후보(candidate) dict = 스펙 §4.3 계약 그대로 (가제 quizId `RAW-{NN}` 상태로 생성 → finalize에서 확정).

---

### Task 1: config + catalog — 카탈로그 로드와 결정적 템플릿 선택

**Files:**
- Create: `VictoryFairy_AI/runner/runner/__init__.py` (빈 파일), `VictoryFairy_AI/runner/runner/config.py`, `VictoryFairy_AI/runner/runner/catalog.py`
- Create: `VictoryFairy_AI/runner/tests/test_catalog.py`, `VictoryFairy_AI/runner/tests/__init__.py` (빈 파일)
- Create: `VictoryFairy_AI/runner/requirements.txt` (내용: `boto3\nPyYAML\n` — pytest는 dev 의존이라 넣지 않는다)

**Interfaces:**
- Produces: `RunnerConfig` (dataclass: `s3_bucket:str, region:str, model_c1:str, model_c2:str, repo_root:Path`), `RunnerConfig.from_env()`
- Produces: `load_catalog(path:Path) -> list[dict]` (enabled 기본 True 채움), `select_combos(catalog, available:set[str], entities_by_template:dict[str,list[str]], recent_template_counts:dict[str,int], limit:int=15) -> list[tuple[dict,str]]`
- 선택 규칙(결정적): ① `enabled: false` 제외 ② `needs`의 모든 어휘가 `available`에 있어야 함 ③ 템플릿을 `recent_template_counts` 오름차순→id 사전순으로 정렬 ④ 라운드로빈으로 템플릿당 엔티티 1개씩 뽑아 limit까지 (같은 템플릿은 엔티티 목록 순서대로 최대 2개까지)

- [ ] **Step 1: 실패하는 테스트 작성**

```python
# VictoryFairy_AI/runner/tests/test_catalog.py
from pathlib import Path
from runner.catalog import load_catalog, select_combos

CATALOG = Path(__file__).parents[2] / "question-gen/config/question-templates.yaml"


def test_load_catalog_fills_enabled_default():
    cat = load_catalog(CATALOG)
    by_id = {t["id"]: t for t in cat}
    assert by_id["H2H_SEASON_RECORD"]["enabled"] is True      # 키 없음 → True
    assert by_id["PRED_SP_WIN"]["enabled"] is False           # 명시 false 유지


def test_select_combos_filters_needs_and_orders_by_recent_count():
    cat = [
        {"id": "A", "enabled": True, "needs": ["stats.streaks"]},
        {"id": "B", "enabled": True, "needs": ["schedule.today"]},   # 데이터 없음 → 제외
        {"id": "C", "enabled": False, "needs": ["stats.streaks"]},   # 비활성 → 제외
        {"id": "D", "enabled": True, "needs": ["stats.streaks"]},
    ]
    combos = select_combos(
        cat, available={"stats.streaks"},
        entities_by_template={"A": ["OB", "LT"], "D": ["HH"]},
        recent_template_counts={"A": 5, "D": 0}, limit=15)
    ids = [(t["id"], e) for t, e in combos]
    # D(최근 0회)가 A(5회)보다 먼저, 라운드로빈 후 A의 2번째 엔티티
    assert ids == [("D", "HH"), ("A", "OB"), ("A", "LT")]


def test_select_combos_respects_limit_and_max_two_per_template():
    cat = [{"id": "A", "enabled": True, "needs": []}]
    combos = select_combos(cat, available=set(),
                           entities_by_template={"A": ["1", "2", "3"]},
                           recent_template_counts={}, limit=15)
    assert len(combos) == 2                                   # 템플릿당 최대 2
```

- [ ] **Step 2: 실패 확인**

Run: `cd VictoryFairy_AI/runner && ../py-collector/.venv/bin/python -m pytest tests/test_catalog.py -v`
Expected: FAIL (`ModuleNotFoundError: runner`)

- [ ] **Step 3: 구현**

```python
# VictoryFairy_AI/runner/runner/config.py
"""러너 설정 — 전부 환경변수에서. S3 자격증명은 다루지 않는다(IRSA/CLI 몫)."""
import os
from dataclasses import dataclass
from pathlib import Path

DEFAULT_MODEL_C1 = "global.anthropic.claude-sonnet-5"
DEFAULT_MODEL_C2 = "global.anthropic.claude-haiku-4-5-20251001-v1:0"


@dataclass(frozen=True)
class RunnerConfig:
    s3_bucket: str
    region: str
    model_c1: str
    model_c2: str
    repo_root: Path

    @classmethod
    def from_env(cls) -> "RunnerConfig":
        return cls(
            s3_bucket=os.environ["S3_BUCKET"],
            region=os.environ.get("AWS_DEFAULT_REGION", "ap-northeast-2"),
            model_c1=os.environ.get("MODEL_C1", DEFAULT_MODEL_C1),
            model_c2=os.environ.get("MODEL_C2", DEFAULT_MODEL_C2),
            repo_root=Path(os.environ.get("REPO_ROOT", "/app")),
        )
```

```python
# VictoryFairy_AI/runner/runner/catalog.py
"""카탈로그 로드 + (템플릿 × 엔티티) 결정적 선택 — LLM 없음.

원 ROUTINE.md 3단계의 판단("의외성 선호")은 버리고 규칙만 남긴다(스펙 §4):
enabled → needs 가용 → 최근 7일 편중 오름차순 → 라운드로빈.
"""
from pathlib import Path

import yaml

MAX_PER_TEMPLATE = 2


def load_catalog(path: Path) -> list:
    raw = yaml.safe_load(path.read_text(encoding="utf-8")) or []
    out = []
    for entry in raw:
        entry = dict(entry)
        entry.setdefault("enabled", True)
        out.append(entry)
    return out


def select_combos(catalog, available, entities_by_template,
                  recent_template_counts, limit: int = 15):
    eligible = [
        t for t in catalog
        if t["enabled"] and set(t.get("needs") or []) <= set(available)
        and entities_by_template.get(t["id"])
    ]
    eligible.sort(key=lambda t: (recent_template_counts.get(t["id"], 0), t["id"]))

    combos, cursor = [], {t["id"]: 0 for t in eligible}
    # 라운드로빈: 각 템플릿에서 1개씩, 다 돌면 2번째 엔티티
    for round_no in range(MAX_PER_TEMPLATE):
        for t in eligible:
            if len(combos) >= limit:
                return combos
            ents = entities_by_template[t["id"]]
            if cursor[t["id"]] < len(ents):
                combos.append((t, ents[cursor[t["id"]]]))
                cursor[t["id"]] += 1
    return combos
```

- [ ] **Step 4: 통과 확인**

Run: `cd VictoryFairy_AI/runner && ../py-collector/.venv/bin/python -m pytest tests/test_catalog.py -v`
Expected: PASS ×3

- [ ] **Step 5: Commit**

```bash
git add VictoryFairy_AI/runner
git commit -m "feat(runner): 카탈로그 로드·결정적 템플릿 선택 (러너 뼈대)"
```

---

### Task 2: binding — needs 가용성·엔티티 열거·데이터 바인딩

**Files:**
- Create: `VictoryFairy_AI/runner/runner/binding.py`
- Create: `VictoryFairy_AI/runner/tests/conftest.py`, `VictoryFairy_AI/runner/tests/test_binding.py`

**Interfaces:**
- Consumes: `Work` 레이아웃 (파일 구조 절 참고)
- Produces: `available_needs(work:Path, today:str) -> set[str]` — 파일 존재로만 판정
- Produces: `enumerate_entities(work:Path, template:dict, today:str) -> list[str]` — 템플릿의 첫 needs 어휘 패밀리로 결정:
  - `stats.head_to_head` → season.json `headToHead` 키(`"HH|LT"`꼴) 사전순
  - `stats.streaks|standings|home_away|monthly|standings_trend|recent_scoring` → season.json `standings`의 팀코드 사전순
  - `stats.season_leaders` → `["AVG", "ERA"]` 고정(공식 표가 rank 정렬을 보장하는 두 부문)
  - `stats.all_time_records` → all-time-records.yaml 카테고리 id 중 rankBasis가 템플릿에 맞는 것 (ALL_TIME_LEADER→true-rank, MILESTONE_FIRST→chronological, RECORD_OX→둘 다)
  - `stats.team_history` → kbo-official.json `teamHistory` 연도 내림차순 상위 3개
  - `stats.trending` → trending.md의 top10 표 1열 선수… 대신 **화제 1위 1건만** `["TOP1"]`
  - `envelope.game_result.*` → 최신 파티션 envelope의 gameId 내림차순(최근 경기 우선) 상위 5개
  - `wiki.별명밈`/`wiki.커리어이력` → 해당 섹션이 비어 있지 않은 위키 문서의 kboPlayerId 사전순
  - `graph` → graph.json에서 type이 `밈공유|커리어교차|라이벌`인 엣지의 `"{작은id}|{큰id}"` 중복 제거 사전순
  - `schedule.today` → 오늘 파티션 envelope의 gameId 사전순
- Produces: `bind(work:Path, template:dict, entity:str, today:str) -> dict` — C1 프롬프트에 넣을 원문 조각: `{"sources": {상대경로: 원문 문자열(파일 전체 또는 해당 envelope content)}}`. needs 패밀리별 대상 파일: stats.*→`stats/season.md`+`stats/kbo-official.md`(+json은 넣지 않는다 — quote는 md에서만), all_time→`question-gen/config/all-time-records.yaml`, wiki.*→`wiki/players/{id}.md`, graph→해당 두 선수 문서, trending→`wiki/stats/trending.md`, envelope/schedule→해당 envelope JSON의 `content`+`payload`
- Produces: `recent_template_counts(work:Path) -> dict[str,int]`, `recent_summaries(work:Path) -> list[dict]` (최근 7일 후보의 `{quizId, templateId, question}` — C2 중복 검사 입력)

- [ ] **Step 1: 픽스처 빌더 + 실패하는 테스트 작성**

```python
# VictoryFairy_AI/runner/tests/conftest.py
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
```

```python
# VictoryFairy_AI/runner/tests/test_binding.py
from runner.binding import (available_needs, bind, enumerate_entities,
                            recent_summaries, recent_template_counts)

TODAY = "2026-08-03"


def test_available_needs_reflects_files(work):
    av = available_needs(work, TODAY)
    assert "stats.head_to_head" in av and "wiki.별명밈" in av
    assert "schedule.today" not in av            # 오늘 스케줄 파티션 없음
    assert "stats.trending" not in av            # trending.md 없음


def test_enumerate_entities_by_family(work):
    h2h = {"id": "H2H_SEASON_RECORD", "needs": ["stats.head_to_head"]}
    meme = {"id": "MEME_OWNER", "needs": ["wiki.별명밈"]}
    assert enumerate_entities(work, h2h, TODAY) == ["HH|LT", "OB|SK"]
    assert enumerate_entities(work, meme, TODAY) == ["69238"]


def test_bind_wiki_returns_doc_source(work):
    meme = {"id": "MEME_OWNER", "needs": ["wiki.별명밈"]}
    b = bind(work, meme, "69238", TODAY)
    assert "야구의 신" in b["sources"]["wiki/players/69238.md"]


def test_recent_counts_and_summaries(work):
    assert recent_template_counts(work) == {"MEME_OWNER": 1}
    assert recent_summaries(work)[0]["quizId"] == "QZ-20260801-005"
```

- [ ] **Step 2: 실패 확인**

Run: `cd VictoryFairy_AI/runner && ../py-collector/.venv/bin/python -m pytest tests/test_binding.py -v`
Expected: FAIL (`ModuleNotFoundError: runner.binding`)

- [ ] **Step 3: 구현** — needs 패밀리 → (가용성 검사, 엔티티 열거, 바인딩 파일) 매핑을 모듈 상수 dict로 두고 구현한다. 핵심 골격:

```python
# VictoryFairy_AI/runner/runner/binding.py
"""needs 어휘 → 파일 가용성·엔티티·바인딩. 전부 결정적 — LLM 없음.

needs 어휘 사전은 question-templates.yaml 머리 주석이 원전이다.
"""
import json
import re
from pathlib import Path


def _latest_partition(base: Path):
    if not base.is_dir():
        return None
    parts = sorted(p.name for p in base.iterdir() if p.is_dir())
    return base / parts[-1] if parts else None


def _season(work: Path) -> dict:
    p = work / "stats/season.json"
    return json.loads(p.read_text(encoding="utf-8")) if p.exists() else {}


def _wiki_docs(work: Path) -> dict:
    out = {}
    for p in sorted((work / "wiki/players").glob("*.md")) if (work / "wiki/players").is_dir() else []:
        out[p.stem] = p.read_text(encoding="utf-8")
    return out


def _section(md: str, name: str) -> str:
    m = re.search(rf"^## {re.escape(name)}\n(.*?)(?=^## |\Z)", md, re.S | re.M)
    return (m.group(1) if m else "").strip()


def available_needs(work: Path, today: str) -> set:
    av = set()
    season = _season(work)
    stats_md = (work / "stats/season.md").exists() and (work / "stats/kbo-official.md").exists()
    if stats_md and season.get("headToHead"):
        av.add("stats.head_to_head")
    if stats_md and season.get("standings"):
        av.update({"stats.streaks", "stats.standings", "stats.home_away",
                   "stats.monthly", "stats.standings_trend", "stats.recent_scoring"})
    if (work / "stats/kbo-official.md").exists():
        av.update({"stats.season_leaders", "stats.team_history"})
    # 리포 파일(러너 이미지에 포함) — repo 루트는 work의 두 단계 위가 아니라 인자로
    # 받지 않고, 존재 검사는 bind에서 실패로 처리한다. 여기서는 항상 가용으로 본다.
    av.add("stats.all_time_records")
    if (work / "wiki/stats/trending.md").exists():
        av.add("stats.trending")
    docs = _wiki_docs(work)
    if any(_section(d, "별명·밈") for d in docs.values()):
        av.add("wiki.별명밈")
    if any(_section(d, "커리어 이력") for d in docs.values()):
        av.add("wiki.커리어이력")
    if (work / "wiki/graph.json").exists() and docs:
        av.update({"graph", "wiki"})
    if _latest_partition(work / "game_result"):
        av.update({"envelope.game_result.yesterday", "envelope.game_result.recent7d"})
    if (work / f"game_schedule/{today}").is_dir() and any((work / f"game_schedule/{today}").glob("*.json")):
        av.add("schedule.today")
    return av
```

(enumerate_entities·bind·recent_* 함수는 Interfaces 절의 규칙을 같은 스타일로 구현 — 각 needs 패밀리를 `if family == ...` 분기로. envelope 계열은 파티션 내 JSON을 열어 `entities.gameId`를 얻고, `bind`는 sources dict에 상대경로→원문을 담는다. all-time yaml 경로는 `bind`의 `repo_root` 키워드 인자(기본 `Path("/app/VictoryFairy_AI")`)에서 읽는다.)

- [ ] **Step 4: 통과 확인**

Run: `cd VictoryFairy_AI/runner && ../py-collector/.venv/bin/python -m pytest tests/test_binding.py -v`
Expected: PASS ×4

- [ ] **Step 5: Commit**

```bash
git add VictoryFairy_AI/runner
git commit -m "feat(runner): needs 가용성·엔티티 열거·데이터 바인딩"
```

---

### Task 3: bedrock_client — 재시도·JSON 강제 래퍼

**Files:**
- Create: `VictoryFairy_AI/runner/runner/bedrock_client.py`
- Create: `VictoryFairy_AI/runner/tests/test_bedrock_client.py`

**Interfaces:**
- Produces: `BedrockClient(region:str, transport=None)` — `transport`는 `(model_id, body_dict) -> response_body_dict` 콜러블(테스트 주입점). 미지정 시 boto3 `bedrock-runtime`의 `invoke_model`을 감싼 기본 transport 사용.
- Produces: `invoke_json(model_id:str, system:str, user:str, max_tokens:int=8000) -> object` — Anthropic Messages 포맷(`{"anthropic_version":"bedrock-2023-05-31", "system":..., "messages":[{"role":"user","content":...}], "max_tokens":...}`)으로 호출하고, 응답 text에서 JSON을 파싱해 반환. 파싱 실패 시 "JSON만 다시 출력하라" 지시를 덧붙여 **1회 재시도**, 재실패 시 `BedrockJsonError` raise. 스로틀(`ThrottlingException`) 시 지수 백오프(2초·4초) 2회 재시도.
- 응답 text에서 JSON 추출: 첫 `[` 또는 `{`부터 마지막 짝까지 슬라이스 후 `json.loads` (모델이 코드펜스를 둘러도 견딤).

- [ ] **Step 1: 실패하는 테스트 작성**

```python
# VictoryFairy_AI/runner/tests/test_bedrock_client.py
import pytest
from runner.bedrock_client import BedrockClient, BedrockJsonError


def _resp(text):
    return {"content": [{"type": "text", "text": text}]}


def test_invoke_json_parses_fenced_json():
    client = BedrockClient("ap-northeast-2",
                           transport=lambda m, b: _resp('```json\n[{"a": 1}]\n```'))
    assert client.invoke_json("model-x", "sys", "user") == [{"a": 1}]


def test_invoke_json_retries_once_then_raises():
    calls = []

    def transport(m, b):
        calls.append(b)
        return _resp("이건 JSON이 아님")

    client = BedrockClient("ap-northeast-2", transport=transport)
    with pytest.raises(BedrockJsonError):
        client.invoke_json("model-x", "sys", "user")
    assert len(calls) == 2                              # 원호출 + 재시도 1
    assert "JSON" in calls[1]["messages"][-1]["content"]  # 재시도에 교정 지시 포함
```

- [ ] **Step 2: 실패 확인**

Run: `cd VictoryFairy_AI/runner && ../py-collector/.venv/bin/python -m pytest tests/test_bedrock_client.py -v`
Expected: FAIL

- [ ] **Step 3: 구현**

```python
# VictoryFairy_AI/runner/runner/bedrock_client.py
"""Bedrock InvokeModel 래퍼 — JSON 강제·재시도. boto3는 여기서만 쓴다."""
import json
import time


class BedrockJsonError(RuntimeError):
    pass


def _extract_json(text: str):
    starts = [i for i in (text.find("["), text.find("{")) if i >= 0]
    if not starts:
        raise ValueError("no json start")
    s = min(starts)
    e = max(text.rfind("]"), text.rfind("}"))
    return json.loads(text[s:e + 1])


class BedrockClient:
    def __init__(self, region: str, transport=None):
        if transport is None:
            import boto3
            rt = boto3.client("bedrock-runtime", region_name=region)

            def transport(model_id, body):
                for attempt in range(3):
                    try:
                        resp = rt.invoke_model(modelId=model_id, body=json.dumps(body))
                        return json.loads(resp["body"].read())
                    except rt.exceptions.ThrottlingException:
                        if attempt == 2:
                            raise
                        time.sleep(2 ** (attempt + 1))
        self._transport = transport

    def invoke_json(self, model_id: str, system: str, user: str, max_tokens: int = 8000):
        messages = [{"role": "user", "content": user}]
        for attempt in range(2):
            body = {"anthropic_version": "bedrock-2023-05-31", "system": system,
                    "messages": messages, "max_tokens": max_tokens}
            resp = self._transport(model_id, body)
            text = "".join(c.get("text", "") for c in resp.get("content", []))
            try:
                return _extract_json(text)
            except (ValueError, json.JSONDecodeError):
                messages = messages + [
                    {"role": "assistant", "content": text},
                    {"role": "user", "content": "위 응답을 유효한 JSON만으로 다시 출력하라. 설명·코드펜스 금지."}]
        raise BedrockJsonError(f"{model_id}: JSON 파싱 2회 실패")
```

- [ ] **Step 4: 통과 확인** — Run: `... -m pytest tests/test_bedrock_client.py -v` → PASS ×2

- [ ] **Step 5: Commit**

```bash
git add VictoryFairy_AI/runner
git commit -m "feat(runner): Bedrock invoke_json 래퍼 — JSON 강제·스로틀 백오프"
```

---

### Task 4: generate — C1 작문 콜 (프롬프트 조립·후보 파싱)

**Files:**
- Create: `VictoryFairy_AI/runner/runner/generate.py`
- Create: `VictoryFairy_AI/runner/tests/test_generate.py`

**Interfaces:**
- Consumes: Task 2의 `bind` 결과, Task 3의 `BedrockClient`
- Produces: `build_prompt(repo_root:Path, combos:list[tuple[dict,str]], bindings:list[dict], today:str) -> tuple[str,str]` — system = `question-gen/prompts/generation-rules.md` 전문 + `question-gen/casebook/good.md`·`bad.md` 전문 + 출력 계약 지시. user = 오늘 날짜 + combo별 `{templateId, intent, format, difficulty, distractor, entity, sources}` JSON.
- Produces: `run_generate(client, model_id, repo_root, combos, bindings, today) -> list[dict]` — invoke_json 호출 후 후보 리스트 반환. 각 후보에 최소 필드(`templateId/format/question/options/kind`)가 없으면 그 후보만 버린다(전체 실패 아님). 후보의 `quizId`는 `RAW-{i:02d}`로 덮어쓴다(가제 — finalize가 확정).
- 출력 계약 지시(시스템 프롬프트 끝에 붙이는 고정 문구): 스펙 §4.3 필드 전부, `evidence.quote`는 입력 sources의 원문에서만 복사, PREDICTION은 answer/evidence null + settlement 필수, 출력은 JSON 배열만.

- [ ] **Step 1: 실패하는 테스트 작성**

```python
# VictoryFairy_AI/runner/tests/test_generate.py
from pathlib import Path
from runner.bedrock_client import BedrockClient
from runner.generate import build_prompt, run_generate

REPO = Path(__file__).parents[2]
COMBO = ({"id": "MEME_OWNER", "kind": "KNOWLEDGE", "format": "MULTI4",
          "needs": ["wiki.별명밈"], "intent": "별명 주인 맞히기",
          "difficulty": "EASY"}, "69238")
BINDING = {"sources": {"wiki/players/69238.md": "- **야구의 신**: 신격화 밈"}}


def test_build_prompt_carries_rules_and_sources():
    system, user = build_prompt(REPO, [COMBO], [BINDING], "2026-08-03")
    assert "문구 생성 규칙" in system            # generation-rules.md 포함
    assert "Casebook" in system                  # casebook 포함
    assert "야구의 신" in user and "MEME_OWNER" in user


def _fake_candidate(**over):
    c = {"quizId": "x", "kind": "KNOWLEDGE", "type": "MEME", "templateId": "MEME_OWNER",
         "format": "MULTI4", "question": "q?", "options": [], "answer": "A",
         "evidence": {"source": "s", "quote": "q"}, "settlement": None,
         "difficulty": "EASY", "pointReward": 30, "status": "PENDING",
         "createdAt": "", "deadlineAt": "", "createdBy": "AI_ENGINE"}
    c.update(over)
    return c


def test_run_generate_drops_malformed_and_renumbers():
    fake = [_fake_candidate(), {"garbage": True}]
    client = BedrockClient("r", transport=lambda m, b: {
        "content": [{"type": "text", "text": __import__("json").dumps(fake)}]})
    out = run_generate(client, "model", REPO, [COMBO], [BINDING], "2026-08-03")
    assert len(out) == 1 and out[0]["quizId"] == "RAW-01"
```

- [ ] **Step 2: 실패 확인** — Run: `... -m pytest tests/test_generate.py -v` → FAIL

- [ ] **Step 3: 구현**

```python
# VictoryFairy_AI/runner/runner/generate.py
"""C1 작문 콜 — 유일하게 '창작'이 허용되는 지점 (문구·오답 보기)."""
import json
from pathlib import Path

REQUIRED = ("templateId", "format", "question", "options", "kind")

CONTRACT = """
[출력 계약 — 반드시 지켜라]
- 출력은 JSON 배열만. 설명·코드펜스 금지.
- 각 항목 필드: quizId(가제), gameId, kind, type, templateId, format, question,
  options([{id,text}] — OX는 O/X 2개, BINARY 2개, MULTI4 4개), answer, evidence
  ({source, quote}), settlement, difficulty, pointReward(EASY30/MEDIUM50/HARD80/EXPERT120),
  status="PENDING", createdAt="", deadlineAt="", createdBy="AI_ENGINE".
- evidence.quote는 입력 sources의 원문에서 글자 그대로 복사한다. 요약·의역 금지.
  근거가 없으면 그 문제를 만들지 마라.
- PREDICTION은 answer=null, evidence=null, settlement={metric, gameId} 필수.
- 질문은 1문장 40자 이내. 정답 보기가 유난히 길지 않게.
"""


def _read(repo_root: Path, rel: str) -> str:
    return (repo_root / rel).read_text(encoding="utf-8")


def build_prompt(repo_root, combos, bindings, today):
    system = "\n\n".join([
        _read(repo_root, "question-gen/prompts/generation-rules.md"),
        _read(repo_root, "question-gen/casebook/good.md"),
        _read(repo_root, "question-gen/casebook/bad.md"),
        CONTRACT,
    ])
    items = []
    for (t, entity), b in zip(combos, bindings):
        items.append({"templateId": t["id"], "kind": t.get("kind"),
                      "format": t.get("format"), "intent": t.get("intent"),
                      "difficulty": t.get("difficulty"),
                      "distractor": t.get("distractor"), "entity": entity,
                      "sources": b["sources"]})
    user = json.dumps({"today": today, "requests": items}, ensure_ascii=False)
    return system, user


def run_generate(client, model_id, repo_root, combos, bindings, today):
    system, user = build_prompt(repo_root, combos, bindings, today)
    raw = client.invoke_json(model_id, system, user, max_tokens=16000)
    out = []
    for cand in raw if isinstance(raw, list) else []:
        if isinstance(cand, dict) and all(k in cand for k in REQUIRED):
            cand["quizId"] = f"RAW-{len(out) + 1:02d}"
            out.append(cand)
    return out
```

- [ ] **Step 4: 통과 확인** — PASS ×2. (참고: casebook 파일 첫 줄이 `# Casebook — 좋은 예 (Good)`이므로 `"Casebook" in system` 단언이 성립한다.)

- [ ] **Step 5: Commit**

```bash
git add VictoryFairy_AI/runner
git commit -m "feat(runner): C1 작문 콜 — 프롬프트 조립·후보 파싱"
```

---

### Task 5: judge — C2 심사 콜 (의미중복·안전·재미·난이도)

**Files:**
- Create: `VictoryFairy_AI/runner/runner/judge.py`
- Create: `VictoryFairy_AI/runner/tests/test_judge.py`

**Interfaces:**
- Produces: `build_prompt(repo_root, candidates:list[dict], recent:list[dict]) -> tuple[str,str]` — system = `question-gen/prompts/verification-pass.md` 전문 + `question-gen/config/banned-topics.txt` + 출력 계약(아래). user = `{candidates: [{quizId, templateId, question, options, difficulty}], recent: [...]}`.
- Produces: `run_judge(client, model_id, repo_root, candidates, recent) -> dict[str, dict]` — 가제 quizId → `{"duplicate": bool, "safety_violation": bool, "fun": int(1~5), "difficulty": str(재분류 결과), "reason": str}`. 응답에 없는 후보는 보수적으로 `duplicate=True` 취급(폐기 방향).
- 출력 계약: JSON 배열 `[{quizId, duplicate, safety_violation, fun, difficulty, reason}]`만.

- [ ] **Step 1: 실패하는 테스트 작성**

```python
# VictoryFairy_AI/runner/tests/test_judge.py
import json
from pathlib import Path
from runner.bedrock_client import BedrockClient
from runner.judge import build_prompt, run_judge

REPO = Path(__file__).parents[2]
CAND = {"quizId": "RAW-01", "templateId": "MEME_OWNER", "question": "q?",
        "options": [{"id": "A", "text": "김대한"}], "difficulty": "EASY"}


def test_build_prompt_includes_rules_banned_and_recent():
    system, user = build_prompt(REPO, [CAND], [{"quizId": "QZ-x", "question": "old"}])
    assert "검증 패스 규칙" in system
    assert "음주" in system                     # banned-topics 포함
    assert "RAW-01" in user and "old" in user


def test_run_judge_maps_by_id_and_defaults_missing_to_duplicate():
    verdicts = [{"quizId": "RAW-01", "duplicate": False, "safety_violation": False,
                 "fun": 5, "difficulty": "EASY", "reason": "ok"}]
    client = BedrockClient("r", transport=lambda m, b: {
        "content": [{"type": "text", "text": json.dumps(verdicts)}]})
    out = run_judge(client, "model", REPO, [CAND, dict(CAND, quizId="RAW-02")], [])
    assert out["RAW-01"]["fun"] == 5
    assert out["RAW-02"]["duplicate"] is True   # 응답 누락 → 보수적 폐기
```

- [ ] **Step 2: 실패 확인** — FAIL

- [ ] **Step 3: 구현**

```python
# VictoryFairy_AI/runner/runner/judge.py
"""C2 심사 콜 — 의미중복·안전 뉘앙스·재미·난이도. 분류 작업이라 Haiku급."""
import json
from pathlib import Path

CONTRACT = """
[출력 계약] JSON 배열만: [{quizId, duplicate(bool — recent 또는 후보끼리 같은
사실이면 true), safety_violation(bool — banned 소재·우회 표기·비하), fun(1~5),
difficulty(적정 난이도로 재분류: EASY|MEDIUM|HARD|EXPERT), reason(한 줄)}]
"""


def build_prompt(repo_root: Path, candidates, recent):
    system = "\n\n".join([
        (repo_root / "question-gen/prompts/verification-pass.md").read_text(encoding="utf-8"),
        "[banned-topics]\n" + (repo_root / "question-gen/config/banned-topics.txt").read_text(encoding="utf-8"),
        CONTRACT,
    ])
    slim = [{k: c.get(k) for k in ("quizId", "templateId", "question", "options", "difficulty")}
            for c in candidates]
    user = json.dumps({"candidates": slim, "recent": recent}, ensure_ascii=False)
    return system, user


def run_judge(client, model_id, repo_root, candidates, recent):
    system, user = build_prompt(repo_root, candidates, recent)
    raw = client.invoke_json(model_id, system, user, max_tokens=8000)
    by_id = {v.get("quizId"): v for v in raw if isinstance(v, dict)} if isinstance(raw, list) else {}
    out = {}
    for c in candidates:
        v = by_id.get(c["quizId"])
        out[c["quizId"]] = v if v else {
            "duplicate": True, "safety_violation": False, "fun": 0,
            "difficulty": c.get("difficulty"), "reason": "심사 응답 누락 — 보수적 폐기"}
    return out
```

- [ ] **Step 4: 통과 확인** — PASS ×2

- [ ] **Step 5: Commit**

```bash
git add VictoryFairy_AI/runner
git commit -m "feat(runner): C2 심사 콜 — 중복·안전·재미·난이도 채점"
```

---

### Task 6: finalize — evidence 대조·선별·quizId·deadlineAt·쓰기

**Files:**
- Create: `VictoryFairy_AI/runner/runner/finalize.py`
- Create: `VictoryFairy_AI/runner/tests/test_finalize.py`

**Interfaces:**
- Produces: `check_evidence(work:Path, repo_root:Path, cand:dict) -> bool` — KNOWLEDGE만 검사(PREDICTION은 evidence null이면 통과). source 경로 해석 규칙(2026-08-01 수동 가동에서 실증한 매핑):
  - `wiki/stats/season.md`·`wiki/stats/kbo-official.md` → `work/stats/{basename}`
  - 그 외 `wiki/...` → `work/wiki/...`
  - `question-source/game_result/...` → `work/game_result/{date}/{file}` (JSON이면 `content` 필드에서 검사)
  - `question-gen/config/...` → `repo_root/...`
  - `#섹션`·` (…)` 접미는 경로 해석 전에 제거. 파일 없거나 quote가 substring이 아니면 False.
- Produces: `select_final(candidates, verdicts, entity_of:dict[str,str]) -> tuple[list[dict], list[str]]` — 폐기 사유 로그와 함께: evidence 실패/duplicate/safety/fun<4 폐기 → difficulty를 verdict 재분류로 덮어쓰고 pointReward 재매핑(30/50/80/120) → 난이도 비율 EASY3/MEDIUM4/HARD2/EXPERT1(모자라면 있는 만큼, 남으면 fun 내림차순) → 최대 10개.
- Produces: `assign_and_write(final, entity_of, work, today) -> list[Path]` — `(templateId, entity)` 사전순 정렬 → `QZ-{YYYYMMDD}-{NNN}` 부여, `createdAt`=지금 UTC ISO, `deadlineAt`: KNOWLEDGE=`{today}T14:59:00Z`, PREDICTION=game_schedule envelope `payload.startTime`(KST) − 2h → UTC. `work/candidates/{today}/{quizId}.json`으로 쓴다.

- [ ] **Step 1: 실패하는 테스트 작성**

```python
# VictoryFairy_AI/runner/tests/test_finalize.py
import json
from pathlib import Path
from runner.finalize import assign_and_write, check_evidence, select_final

REPO = Path(__file__).parents[2]
TODAY = "2026-08-03"


def _cand(nn, template, diff="EASY", quote="- OB: 2연승", source="wiki/stats/season.md#연승·연패"):
    return {"quizId": f"RAW-{nn:02d}", "gameId": None, "kind": "KNOWLEDGE",
            "type": "STATS", "templateId": template, "format": "OX",
            "question": "q?", "options": [{"id": "A", "text": "O"}, {"id": "B", "text": "X"}],
            "answer": "A", "evidence": {"source": source, "quote": quote},
            "settlement": None, "difficulty": diff, "pointReward": 30,
            "status": "PENDING", "createdAt": "", "deadlineAt": "", "createdBy": "AI_ENGINE"}


def _ok(fun=5, diff="EASY"):
    return {"duplicate": False, "safety_violation": False, "fun": fun,
            "difficulty": diff, "reason": "ok"}


def test_check_evidence_resolves_stats_md(work):
    assert check_evidence(work, REPO, _cand(1, "STREAK_CURRENT")) is True
    assert check_evidence(work, REPO, _cand(2, "STREAK_CURRENT", quote="없는 문장")) is False


def test_select_final_drops_by_verdict_and_remaps_points():
    cands = [_cand(1, "A"), _cand(2, "B"), _cand(3, "C")]
    verdicts = {"RAW-01": _ok(diff="MEDIUM"), "RAW-02": _ok(fun=3),
                "RAW-03": dict(_ok(), duplicate=True)}
    final, reasons = select_final(cands, verdicts, {})
    assert [c["quizId"] for c in final] == ["RAW-01"]
    assert final[0]["difficulty"] == "MEDIUM" and final[0]["pointReward"] == 50
    assert len(reasons) == 2                       # fun<4, duplicate


def test_assign_and_write_orders_by_template_entity(work):
    final = [_cand(1, "ZZZ"), _cand(2, "AAA")]
    entity_of = {"RAW-01": "x", "RAW-02": "y"}
    paths = assign_and_write(final, entity_of, work, TODAY)
    names = [p.name for p in paths]
    assert names == ["QZ-20260803-001.json", "QZ-20260803-002.json"]
    first = json.loads(paths[0].read_text(encoding="utf-8"))
    assert first["templateId"] == "AAA"            # 사전순 → AAA가 001
    assert first["deadlineAt"] == "2026-08-03T14:59:00Z"
```

- [ ] **Step 2: 실패 확인** — FAIL

- [ ] **Step 3: 구현** — Interfaces 규칙대로. 핵심 조각:

```python
# VictoryFairy_AI/runner/runner/finalize.py (발췌 — 전체는 Interfaces 규칙 구현)
import json
from datetime import datetime, timedelta, timezone
from pathlib import Path

POINTS = {"EASY": 30, "MEDIUM": 50, "HARD": 80, "EXPERT": 120}
RATIO = [("EASY", 3), ("MEDIUM", 4), ("HARD", 2), ("EXPERT", 1)]


def _resolve(work: Path, repo_root: Path, source: str) -> Path:
    base = source.split(" (")[0].split("#")[0].strip()
    name = Path(base).name
    if base in ("wiki/stats/season.md", "wiki/stats/kbo-official.md"):
        return work / "stats" / name
    if base.startswith("wiki/"):
        return work / base
    if base.startswith("question-source/game_result/"):
        return work / "game_result" / "/".join(base.split("/")[2:])
    return repo_root / base


def check_evidence(work, repo_root, cand) -> bool:
    if cand.get("kind") != "KNOWLEDGE":
        return cand.get("evidence") is None
    ev = cand.get("evidence") or {}
    quote, source = ev.get("quote") or "", ev.get("source") or ""
    if not quote or not source:
        return False
    p = _resolve(work, repo_root, source)
    if not p.exists():
        return False
    text = p.read_text(encoding="utf-8")
    if p.suffix == ".json":
        text = json.loads(text).get("content", "")
    return quote in text
```

(select_final은 verdict 순회로 폐기 사유 리스트를 만들고, 통과분의 difficulty·pointReward를 재매핑한 뒤 RATIO 슬롯을 fun 내림차순으로 채운다. assign_and_write는 `(templateId, entity_of[가제id])` 키로 정렬해 번호를 붙이고 KST/UTC 변환은 `timezone(timedelta(hours=9))`로 계산한다. PREDICTION deadlineAt은 `work/game_schedule/{today}/`에서 settlement.gameId가 일치하는 envelope의 `payload.startTime`을 찾아 계산하고, 못 찾으면 그 후보를 폐기 사유와 함께 버린다.)

- [ ] **Step 4: 통과 확인** — PASS ×3

- [ ] **Step 5: Commit**

```bash
git add VictoryFairy_AI/runner
git commit -m "feat(runner): evidence 대조·비율 선별·quizId 결정 부여"
```

---

### Task 7: main — 파이프라인 오케스트레이션 + E2E(fake LLM)

**Files:**
- Create: `VictoryFairy_AI/runner/runner/main.py`
- Create: `VictoryFairy_AI/runner/tests/test_main.py`

**Interfaces:**
- Produces: `run(work:Path, repo_root:Path, today:str, client, model_c1:str, model_c2:str) -> dict` — 순서: available_needs → enumerate/select_combos → bind → run_generate → run_judge → check_evidence(전 후보) → select_final → assign_and_write. 반환 요약 `{"uploadedDir": str, "written": [quizId...], "discarded": [사유...], "combos": int}`. 후보 0개면 `written=[]`로 정상 종료(예측 0건 같은 정상 축소를 실패로 만들지 않는다 — 업로드 여부는 entrypoint가 결정).
- Produces: CLI `python -m runner.main --work .work --repo-root . --date 2026-08-03` — `RunnerConfig.from_env()`로 모델·리전을 얻고 실 BedrockClient로 run() 호출, 요약을 stdout JSON으로. exit 0/1(BedrockJsonError 등 예외 시 1).

- [ ] **Step 1: 실패하는 테스트 작성**

```python
# VictoryFairy_AI/runner/tests/test_main.py
import json
from pathlib import Path
from runner.bedrock_client import BedrockClient
from runner.main import run

REPO = Path(__file__).parents[2]


def _client_scripted():
    """C1 → 후보 1개(STREAK_CURRENT, 픽스처 원문 인용), C2 → 통과."""
    cand = {"quizId": "x", "gameId": None, "kind": "KNOWLEDGE", "type": "STATS",
            "templateId": "STREAK_CURRENT", "format": "OX", "question": "두산은 2연승 중이다?",
            "options": [{"id": "A", "text": "O"}, {"id": "B", "text": "X"}], "answer": "A",
            "evidence": {"source": "wiki/stats/season.md#연승·연패", "quote": "- OB: 2연승"},
            "settlement": None, "difficulty": "EASY", "pointReward": 30,
            "status": "PENDING", "createdAt": "", "deadlineAt": "", "createdBy": "AI_ENGINE"}
    verdict = [{"quizId": "RAW-01", "duplicate": False, "safety_violation": False,
                "fun": 5, "difficulty": "EASY", "reason": "ok"}]
    responses = iter([json.dumps([cand]), json.dumps(verdict)])
    return BedrockClient("r", transport=lambda m, b: {
        "content": [{"type": "text", "text": next(responses)}]})


def test_run_end_to_end_writes_final_candidate(work):
    summary = run(work, REPO, "2026-08-03", _client_scripted(), "m1", "m2")
    assert summary["written"] == ["QZ-20260803-001"]
    out = work / "candidates/2026-08-03/QZ-20260803-001.json"
    data = json.loads(out.read_text(encoding="utf-8"))
    assert data["pointReward"] == 30 and data["deadlineAt"] == "2026-08-03T14:59:00Z"
```

- [ ] **Step 2: 실패 확인** — FAIL

- [ ] **Step 3: 구현** — Interfaces 순서 그대로 조립. entity_of는 combos 순서와 run_generate의 renumber가 1:1이 아닐 수 있으므로, run_generate 반환 후보의 순번이 아니라 **후보의 templateId로 combos에서 첫 매칭 엔티티**를 찾아 매핑하고, 매칭 실패 시 그 후보는 폐기 사유에 남긴다.

- [ ] **Step 4: 통과 확인** — PASS. 이어서 전체 러너 테스트 일괄 확인: `cd VictoryFairy_AI/runner && ../py-collector/.venv/bin/python -m pytest tests -v` → 전부 PASS

- [ ] **Step 5: Commit**

```bash
git add VictoryFairy_AI/runner
git commit -m "feat(runner): 파이프라인 오케스트레이션 + fake-LLM E2E"
```

---

### Task 8: entrypoint + Dockerfile — ROUTINE.md 셸 이식·이미지화

**Files:**
- Create: `VictoryFairy_AI/runner/entrypoint-quiz.sh`, `VictoryFairy_AI/runner/Dockerfile`
- Modify: `VictoryFairy_AI/question-gen/ROUTINE.md:1-8` (머리에 지위 변경 안내 1단락 추가)

**Interfaces:**
- Consumes: Task 7의 `python -m runner.main` CLI
- Produces: 컨테이너 이미지 — `docker run -e S3_BUCKET=... victoryfairy-quiz-runner` 한 번이 ROUTINE.md 1→2→(3~6=runner)→검증 게이트→7→8 순서를 수행

- [ ] **Step 1: entrypoint 작성** — ROUTINE.md 1·2단계 bash를 그대로 옮기되(§ "S3_BUCKET 필수·KST TODAY·game_result 최신 파티션 1개·quiz-candidates 7일·md charset 업로드" 전부 유지), 3~6단계 자리에서 runner를 호출한다:

```bash
#!/bin/bash
# VictoryFairy_AI/runner/entrypoint-quiz.sh
# 퀴즈 러너 — question-gen/ROUTINE.md의 컨테이너 구현체. 문서와 어긋나면 문서가 정답.
set -euo pipefail
: "${S3_BUCKET:?S3_BUCKET 환경변수를 설정하라}"
cd /app/VictoryFairy_AI
TODAY=$(TZ=Asia/Seoul date +%Y-%m-%d)
WORK=.work
mkdir -p "$WORK"/{game_result,game_schedule,player_profile,kbo-records,wiki,quiz-candidates,stats}

# ── 1. 동기화 (ROUTINE.md 1단계 그대로) ──
LATEST_GR=$(aws s3 ls "s3://$S3_BUCKET/question-source/game_result/" 2>/dev/null | awk '{print $2}' | tr -d '/' | sort | tail -1)
[ -n "$LATEST_GR" ] && aws s3 sync "s3://$S3_BUCKET/question-source/game_result/$LATEST_GR/" "$WORK/game_result/$LATEST_GR/" --exclude "*" --include "*.json" --only-show-errors
for i in 0 1 2 3 4 5 6; do
  D=$(date -d "$TODAY -$i days" +%Y-%m-%d)
  aws s3 sync "s3://$S3_BUCKET/quiz-candidates/$D/" "$WORK/quiz-candidates/$D/" --exclude "*" --include "*.json" --only-show-errors 2>/dev/null || true
done
aws s3 sync "s3://$S3_BUCKET/question-source/game_schedule/$TODAY/" "$WORK/game_schedule/$TODAY/" --exclude "*" --include "*.json" --only-show-errors 2>/dev/null \
  || echo "경고: 오늘($TODAY) game_schedule 없음 — 예측 템플릿 제외" >&2
aws s3 sync "s3://$S3_BUCKET/wiki/" "$WORK/wiki/" --only-show-errors
aws s3 sync "s3://$S3_BUCKET/kbo-records/" "$WORK/kbo-records/" --only-show-errors

# ── 2. 통계 재집계 + 업로드 (md는 charset 명시 — ROUTINE.md 2단계) ──
python question-gen/scripts/aggregate_stats.py \
  --envelopes-dir "$WORK/game_result" --kbo-dir "$WORK/kbo-records" \
  --out-dir "$WORK/stats" --date "$TODAY"
aws s3 sync "$WORK/stats/" "s3://$S3_BUCKET/wiki/stats/" \
  --exclude "*" --include "season.md" --include "kbo-official.md" \
  --content-type "text/markdown; charset=utf-8" --only-show-errors
aws s3 sync "$WORK/stats/" "s3://$S3_BUCKET/wiki/stats/" \
  --exclude "*" --include "season.json" --include "kbo-official.json" --only-show-errors

# ── 3~6. 생성·심사·확정 (Bedrock 2콜) ──
python -m runner.main --work "$WORK" --repo-root /app/VictoryFairy_AI --date "$TODAY"

# ── 6b. 결정적 게이트 (검증 패스와 독립 — 항상 실행) ──
VALIDATE_DIR="$WORK/candidates/$TODAY"
if [ -d "$VALIDATE_DIR" ] && [ -n "$(ls -A "$VALIDATE_DIR" 2>/dev/null)" ]; then
  python question-gen/scripts/validate_candidates.py --dir "$VALIDATE_DIR"
  # ── 7. 업로드 (멱등) ──
  aws s3 cp --recursive "$VALIDATE_DIR/" "s3://$S3_BUCKET/quiz-candidates/$TODAY/" --only-show-errors
  echo "업로드 완료: $(ls "$VALIDATE_DIR" | wc -l)건 → quiz-candidates/$TODAY/"
else
  echo "오늘 채택 문항 0건 — 업로드 생략(정상 축소일 수 있음, 로그 확인)" >&2
fi
```

- [ ] **Step 2: Dockerfile 작성**

```dockerfile
# VictoryFairy_AI/runner/Dockerfile
# 빌드 컨텍스트 = VictoryFairy_AI/ :
#   docker build -f runner/Dockerfile -t victoryfairy-quiz-runner .
FROM python:3.12-slim
RUN apt-get update && apt-get install -y --no-install-recommends awscli \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app/VictoryFairy_AI
COPY runner/requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir -r /tmp/requirements.txt
COPY question-gen/ question-gen/
COPY runner/runner/ runner/
COPY runner/entrypoint-quiz.sh /usr/local/bin/entrypoint-quiz.sh
RUN chmod +x /usr/local/bin/entrypoint-quiz.sh
ENV PYTHONPATH=/app/VictoryFairy_AI
ENTRYPOINT ["/usr/local/bin/entrypoint-quiz.sh"]
```

- [ ] **Step 3: 빌드 확인** — Run: `cd VictoryFairy_AI && docker build -f runner/Dockerfile -t victoryfairy-quiz-runner .` → 성공. 이어서 `docker run --rm --entrypoint python victoryfairy-quiz-runner -c "import runner.main, yaml, boto3; print('ok')"` → `ok`

- [ ] **Step 4: ROUTINE.md 머리에 지위 변경 안내 추가** (스펙 §6 — 삭제·재작성 금지, 안내만):

```markdown
> **실행체 안내 (2026-08-03)**: 이 문서는 이제 Bedrock 러너
> (`runner/entrypoint-quiz.sh` + `runner/runner/`)의 스펙 문서다. 셸 블록은
> entrypoint가, "이 세션이 직접 수행" 단계는 러너의 C1(작문)·C2(심사) Bedrock
> 콜이 구현한다. 러너와 이 문서가 어긋나면 이 문서를 먼저 고치고 구현을 맞춘다
> (스펙: docs/superpowers/specs/2026-08-03-bedrock-quiz-runner-design.md).
```

- [ ] **Step 5: Commit**

```bash
git add VictoryFairy_AI/runner VictoryFairy_AI/question-gen/ROUTINE.md
git commit -m "feat(runner): entrypoint·Dockerfile — ROUTINE.md 셸 이식, 문서 지위 변경 안내"
```

---

### Task 9: 배포 산출물 — CronJob 매니페스트·IRSA 정책·가이드

**Files:**
- Create: `VictoryFairy_AI/deploy/runner/cronjob-quiz.yaml`, `VictoryFairy_AI/deploy/runner/irsa-policy-runner.json`, `VictoryFairy_AI/deploy/runner/README.md`

**Interfaces:**
- Consumes: Task 8 이미지. apply·역할 생성은 dev_infra 소관 — 이 태스크는 파일과 절차만 만든다.

- [ ] **Step 1: IRSA 정책** — 기존 `deploy/routines/iam-policy-routines.json`의 S3 Sid 3개를 그대로 복사하고(Resource의 `${BUCKET}` 치환 방식 동일) Sid 하나 추가:

```json
{
  "Sid": "BedrockInvoke",
  "Effect": "Allow",
  "Action": ["bedrock:InvokeModel"],
  "Resource": [
    "arn:aws:bedrock:*::foundation-model/anthropic.*",
    "arn:aws:bedrock:*:*:inference-profile/global.anthropic.*"
  ]
}
```

- [ ] **Step 2: CronJob 매니페스트**

```yaml
# VictoryFairy_AI/deploy/runner/cronjob-quiz.yaml
apiVersion: batch/v1
kind: CronJob
metadata:
  name: quiz-runner
  namespace: victoryfairy
spec:
  schedule: "50 23 * * *"          # 08:50 KST (UTC 전날 23:50)
  concurrencyPolicy: Forbid        # 겹침 방지 — Bedrock 호출 낭비 차단
  startingDeadlineSeconds: 600     # 컨트롤러 순단 시 10분 내면 만회 실행
  successfulJobsHistoryLimit: 3
  failedJobsHistoryLimit: 3
  jobTemplate:
    spec:
      backoffLimit: 1              # 실패 시 1회 재시도(업로드 멱등)
      activeDeadlineSeconds: 1800  # 30분 상한 (ROUTINE.md 소요 상한)
      template:
        spec:
          serviceAccountName: quiz-runner    # IRSA 어노테이션은 dev_infra가 부여
          restartPolicy: Never
          containers:
            - name: quiz-runner
              image: 555209622409.dkr.ecr.ap-northeast-2.amazonaws.com/victoryfairy-quiz-runner:latest
              env:
                - name: S3_BUCKET
                  value: victoryfairy-crawl-dev
                - name: AWS_DEFAULT_REGION
                  value: ap-northeast-2
              resources:
                requests: { cpu: 250m, memory: 512Mi }
                limits: { cpu: "1", memory: 1Gi }
```

- [ ] **Step 3: README** — 내용에 반드시 포함: ① ECR 리포 생성 + 이미지 푸시 명령(Task 8 빌드 명령 + `--platform`은 노드 아키텍처에 맞춤 — 확인 명령 `kubectl get nodes -o jsonpath='{.items[0].status.nodeInfo.architecture}'`) ② ServiceAccount `quiz-runner` + IRSA 역할 생성 절차(신뢰 대상 `system:serviceaccount:victoryfairy:quiz-runner`, 기존 IRSA 3건과 같은 패턴) ③ 1회 수동 실행 검증 `kubectl create job --from=cronjob/quiz-runner quiz-runner-manual` ④ 모니터링(quiz-candidates 오늘 파티션 건수 — `deploy/routines/README.md` §3의 명령 재사용, 러너 로그는 CloudWatch/`kubectl logs`) ⑤ 운영 전제 갭은 `deploy/routines/README.md` §5가 그대로 유효함을 링크로 명시

- [ ] **Step 4: 매니페스트 정적 검증** — Run: `python3 -c "import yaml; yaml.safe_load(open('VictoryFairy_AI/deploy/runner/cronjob-quiz.yaml'))" && python3 -m json.tool VictoryFairy_AI/deploy/runner/irsa-policy-runner.json > /dev/null && echo OK` → OK

- [ ] **Step 5: Commit**

```bash
git add VictoryFairy_AI/deploy/runner
git commit -m "feat(deploy): 퀴즈 러너 CronJob 매니페스트·IRSA 정책·배포 가이드"
```

---

### Task 10: 실검증 — Bedrock 스모크 + crawl-local E2E (비용·자격증명 주의)

**Files:**
- 없음(검증 전용). 결과는 커밋하지 않고 실행 기록만 대화/로그로 남긴다.

**주의:** 이 태스크만 실제 AWS 자격증명과 소액 Bedrock 비용(수백 원 미만)이 발생한다. 로컬 개발자 자격증명으로 실행한다.

- [ ] **Step 1: Bedrock 스모크 1콜** — Run:

```bash
cd VictoryFairy_AI/runner && AWS_DEFAULT_REGION=ap-northeast-2 \
  ../py-collector/.venv/bin/python - <<'PY'
from runner.bedrock_client import BedrockClient
c = BedrockClient("ap-northeast-2")
print(c.invoke_json("global.anthropic.claude-haiku-4-5-20251001-v1:0",
                    "JSON만 출력하라.", '{"echo": "pong"} 형태로 답하라.'))
PY
```

Expected: `{'echo': 'pong'}` (Haiku 스모크 — 모델 접근·리전·IAM 확인. 실패 시 Bedrock 모델 접근 권한(콘솔 Model access)부터 확인)

- [ ] **Step 2: crawl-local E2E** — Run:

```bash
cd VictoryFairy_AI && docker run --rm \
  -e S3_BUCKET=victoryfairy-crawl-local -e AWS_DEFAULT_REGION=ap-northeast-2 \
  -e AWS_ACCESS_KEY_ID -e AWS_SECRET_ACCESS_KEY -e AWS_SESSION_TOKEN \
  victoryfairy-quiz-runner
```

Expected: exit 0, 마지막 로그 `업로드 완료: N건 → quiz-candidates/{오늘}/` (N은 최근 7일 중복 폐기에 따라 0~10 — 0건이면 로그의 폐기 사유가 전부 "중복"인지 확인)

- [ ] **Step 3: 산출물 검사** — Run:

```bash
TODAY=$(TZ=Asia/Seoul date +%Y-%m-%d)
aws s3 ls "s3://victoryfairy-crawl-local/quiz-candidates/$TODAY/"
aws s3 cp "s3://victoryfairy-crawl-local/quiz-candidates/$TODAY/" /tmp/qc-check --recursive --only-show-errors
python3 VictoryFairy_AI/question-gen/scripts/validate_candidates.py --dir /tmp/qc-check
```

Expected: 파일 목록 출력 + validate exit 0 (`pip install pyyaml` 필요 시 py-collector venv 사용)

- [ ] **Step 4: 멱등성 확인** — Step 2를 한 번 더 실행 → 같은 quizId로 덮어쓰기(파일 수 불변, LastModified만 갱신) 확인

- [ ] **Step 5: 검증 요약 기록** — 실행 결과(문항 수·폐기 사유·소요 시간·Bedrock 토큰 사용량 추정)를 최종 보고에 포함. 커밋 없음.

---

## Self-Review 결과

- **Spec coverage**: §2 아키텍처(T8·T9), §3 C1/C2·모델 기본값(T4·T5·Global), §4 코드 이관 — 템플릿 선택(T1)·바인딩(T2)·evidence 대조(T6)·quizId(T6), §5 스케줄·CronJob 옵션(T9), §6 문서 지위(T8 Step 4), §7 실패 처리 — JSON 재시도(T3)·업로드 생략(T8 게이트)·백오프(T3)·멱등(T10 Step 4), §9 마이그레이션 1~2단계(T8·T10). §3의 C3·C4와 §9의 dev 전환·루틴 삭제는 이 계획 범위 밖(후속 계획·수동 절차)으로 명시됨.
- **Placeholder scan**: 통과 — 모든 코드 스텝에 실제 코드/명령 포함. T2·T6의 "같은 스타일로 구현" 부분은 Interfaces 절에 완전한 규칙(패밀리별 매핑·경로 해석 표)이 명시돼 있어 구현자가 참조할 원전이 있다.
- **Type consistency**: `select_combos`(T1) → `entities_by_template`/`recent_template_counts`는 T2의 `enumerate_entities`/`recent_template_counts` 반환형과 일치. `run_generate` 반환 후보 dict를 T5 `run_judge`·T6 `select_final`이 그대로 소비. `BedrockClient.invoke_json` 시그니처 T3=T4=T5 일치. `run()`(T7)의 인자 순서는 test_main과 일치.
