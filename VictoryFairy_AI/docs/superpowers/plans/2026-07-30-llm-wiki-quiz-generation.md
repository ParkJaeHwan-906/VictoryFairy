# LLM 위키 + 퀴즈 생성 파이프라인 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 승인된 스펙([2026-07-28-llm-wiki-quiz-generation-design.md](../specs/2026-07-28-llm-wiki-quiz-generation-design.md))대로 py-collector 변경 2건 + 위키 빌더/퀴즈 생성기 routine 자산 일체를 구현한다.

**Architecture:** py-collector가 `game_schedule` envelope과 `kbo-records/` 브론즈 스냅샷을 S3에 공급하고, Claude Code 클라우드 routine 2개(위키 빌더 주 1~2회 · 퀴즈 생성기 매일)가 S3만 읽고 써서 `wiki/`와 `quiz-candidates/`를 만든다. 통계 집계·그래프 컴파일·후보 검증은 결정적 파이썬 스크립트(LLM 미사용), 서사 병합·문구 생성만 LLM.

**Tech Stack:** Python 3.11+ (py-collector: httpx/bs4/lxml/boto3/pytest — 기존 그대로), routine 스크립트는 **stdlib + PyYAML만**(S3 동기화는 routine이 aws CLI로 수행 → 스크립트는 로컬 디렉토리 in/out), Claude Code cloud routine (Sonnet 5 권장), Terraform (기존 py-collector/deploy/lambda).

## Global Constraints

스펙 전체에서 오는 프로젝트 공통 제약. 모든 작업의 요구사항에 암묵 포함된다.

- 출제 형식은 **OX | BINARY | MULTI4만** — `options`는 항상 2개 또는 4개, 주관식 금지
- **안전 규칙**: 사건사고·법적 논란·사생활·건강 소재 출제 금지. graph의 `사건연루` 엣지 출제 사용 금지 (기록은 유지). 나무위키 원문 복사 금지
- 위키 빌더 입력은 **`validation/bedrock/success/{source}/{date}/*.json`만** (1차 정규식 + 2차 Bedrock(abuse/spam/offtopic) 통과분). 커뮤니티 원문(`community/`)·1차만 통과분(`validation/pattern/success/`) 직접 소비 금지
- **사실 통계의 자연어화에 LLM 금지** — 집계·렌더는 결정적 스크립트
- routine은 **S3 전용** (DB 자격증명을 클라우드에 열지 않음), 최소 권한 IAM
- 운영 DB 스키마 변경 없음, 이 리포에 **DDL 사본 금지** (py-collector/CLAUDE.md 규칙)
- py-collector 소스 추가 = `sources/` 모듈 1개 + `sources/__init__.py` import 1줄 (레지스트리 계약)
- S3 적재는 전부 **멱등** (같은 키 재실행 = 덮어쓰기)
- 포인트 기준표(기능명세서 §3.1.2): EASY 30P / MEDIUM 50P / HARD 80P / EXPERT 120P, 일일 난이도 비율 30/40/20/10
- 예측 퀴즈 정산 지표는 `WIN_TEAM | TOTAL_RUNS | SCORE_GAP | PITCHER_DECISION`만 (운영 DB로 판정 가능한 것만)
- **작업 위치**: worktree `~/PycharmProjects/VictoryFairy-wiki-quiz` (브랜치 `sotaeho/ai/feat-llm-wiki-quiz`). 메인 체크아웃(`~/PycharmProjects/VictoryFairy`)에서 커밋·브랜치 전환 금지
- py-collector 테스트 실행: `cd VictoryFairy_AI/py-collector && pip install -e ".[dev]"`(worktree 첫 1회) 후 `python -m pytest tests/ -v`. routine 스크립트 테스트: `cd VictoryFairy_AI && python -m pytest tests/test_<이름>.py -v`
- 커밋 메시지는 기존 컨벤션(`feat(py-collector): …`, `feat(question-gen): …`, `feat(wiki-builder): …` + 한글 요약). PR 분리(py-collector 2건은 dev_ai 대상 별도 PR)는 브랜치 마무리 단계에서 cherry-pick으로 처리 — 작업 중에는 전부 이 브랜치에 커밋

## 산출물 파일 구조 (전체 지도)

```
VictoryFairy_AI/
├── py-collector/
│   ├── kbo_collector/
│   │   ├── exports/exporter.py          [수정] game_schedule reader 추가
│   │   ├── sources/kbo_records.py       [신규] KBO 기록실 스냅샷 소스
│   │   ├── sources/__init__.py          [수정] import 1줄
│   │   ├── keys.py                      [수정] kbo_records_key()
│   │   └── run.py                       [수정] collect의 DbSink 지연 생성, export의 DB-free 처리
│   ├── deploy/lambda/handler.py         [수정] kbo_records·game_schedule·export 잡
│   ├── deploy/lambda/terraform/*.tf     [수정] EventBridge 규칙 3개 + -db 함수 S3 권한
│   └── tests/                           [수정/신규] test_exporter.py, test_kbo_records_source.py, test_lambda_handler.py, fixtures/
├── question-gen/
│   ├── config/question-templates.yaml   [수정] needs 어휘 정렬·라인업 템플릿 비활성
│   ├── config/all-time-records.yaml     [신규] 역대 기록 시드 (반자동 생성 + 사람 검수)
│   ├── config/banned-topics.txt         [신규] 출제 금지 키워드
│   ├── scripts/aggregate_stats.py       [신규] ⓪ 통계 재집계 (결정적)
│   ├── scripts/validate_candidates.py   [신규] quiz-candidates 검증 (결정적)
│   ├── prompts/generation-rules.md      [신규] 문구 생성 규칙
│   ├── prompts/verification-pass.md     [신규] 검증 패스 규칙
│   ├── casebook/good.md · bad.md        [신규] 사례집 (자동 갱신 대상)
│   ├── requirements.txt                 [신규] routine 최소 의존성
│   └── ROUTINE.md                       [신규] 퀴즈 생성기 루틴 지침
├── wiki-builder/
│   ├── ROUTINE.md                       [신규] 위키 빌더 루틴 지침
│   ├── prompts/merge-rules.md           [신규] 병합·환각 방지 규칙
│   ├── templates/player-doc.md          [신규] 선수 문서 골격
│   └── scripts/compile_graph.py         [신규] front-matter → graph.json
│   └── scripts/gen_all_time_seed.py     [신규] 기록실 스냅샷 → 시드 YAML 초안
├── deploy/routines/                     [신규] routine IAM 정책 + 등록 가이드
└── tests/                               [수정/신규] conftest.py + routine 스크립트 테스트 4개
```

S3 레이아웃(신규 prefix): `question-source/game_schedule/{date}/…`, `kbo-records/{page}/{date}.json`, `wiki/players/{kboPlayerId}.md`, `wiki/graph.json`, `wiki/stats/{season.json,season.md,kbo-official.json,kbo-official.md,trending.md,all-time-records.md}`, `quiz-candidates/{date}/{quizId}.json`.

---

### Task 1: py-collector — `game_schedule` export reader

**Files:**
- Modify: `VictoryFairy_AI/py-collector/kbo_collector/exports/exporter.py` (파일 끝에 reader 추가)
- Create: `VictoryFairy_AI/py-collector/tests/fixtures/schedule_before.json`
- Modify: `VictoryFairy_AI/py-collector/tests/test_exporter.py`

**Interfaces:**
- Consumes: `S3RawSink.get_json(key)` / `exists(key)` (kbo_collector/sink.py 기존), `keys.schedule_key(date)` = `raw-json/schedule/{date}/schedule.json` (schedule 잡이 적재)
- Produces: `read_game_schedules(db, date=None, sink=None)` — `@reader("game_schedule")` 등록 제너레이터. envelope: `doc_id="game_schedule:{gameId}"`, `doc_type="game_schedule"`, `payload={"gameId","startTime","stadium","awayStarter","homeStarter"}` (starter는 없으면 None). Task 3·10이 이 payload 키에 의존

- [ ] **Step 1: 실제 스케줄 JSON 픽스처 캡처**

worktree의 py-collector에서 (`.env`는 메인 체크아웃 것을 복사: `cp ~/PycharmProjects/VictoryFairy/VictoryFairy_AI/py-collector/.env .`):

```bash
cd VictoryFairy_AI/py-collector
URL=$(python -c "
from kbo_collector.config import get_settings
from kbo_collector import naver
import datetime
d = (datetime.datetime.utcnow() + datetime.timedelta(hours=9)).strftime('%Y-%m-%d')
print(naver.schedule_url(get_settings(), d))")
curl -s -H 'User-Agent: Mozilla/5.0' "$URL" -o tests/fixtures/schedule_before.json
python -m json.tool tests/fixtures/schedule_before.json | head -60
```

캡처한 JSON에서 확인·기록할 것: BEFORE 상태 경기 객체의 **실제 필드명** — 시작 시각(`gameDateTime` 류), 구장(`stadium`), 팀 이름(`awayTeamName`/`homeTeamName`), **선발투수(`awayStarterName`/`homeStarterName` 존재 여부)**. Step 3 코드의 `g.get(...)` 키를 실측 필드명으로 맞춘다. 몸집이 크면 KBO(`categoryId=="kbo"`) 경기 2~3개만 남기고 잘라서 저장.

> ⚠️ **선발투수 필드가 응답에 없으면**: envelope은 일정만 담고 `awayStarter/homeStarter=None`으로 두되, Task 10에서 `PRED_SP_WIN` 템플릿을 `enabled: false`(사유 주석) 처리한다. 이 결과를 커밋 메시지에 남길 것.

- [ ] **Step 2: 실패하는 테스트 작성** — `tests/test_exporter.py`에 추가

```python
import json
from pathlib import Path

FIXTURES = Path(__file__).parent / "fixtures"


class FakeScheduleSink(FakeSink):
    def __init__(self, sched, key):
        super().__init__()
        self.sched, self.key = sched, key

    def exists(self, key):
        return key == self.key

    def get_json(self, key):
        assert key == self.key
        return self.sched


def _sched_sink(date="2026-07-31"):
    sched = json.loads((FIXTURES / "schedule_before.json").read_text(encoding="utf-8"))
    return FakeScheduleSink(sched, f"raw-json/schedule/{date}/schedule.json")


def test_read_game_schedules_emits_before_games_only():
    envs = list(exporter.read_game_schedules(None, date="2026-07-31", sink=_sched_sink()))
    assert envs, "픽스처에 BEFORE 상태 KBO 경기가 최소 1개 필요"
    e = envs[0]
    assert e.doc_type == "game_schedule"
    assert e.doc_id == f"game_schedule:{e.entities['gameId']}"
    assert len(e.entities["teamCodes"]) == 2
    assert "예정" in e.content
    assert set(e.payload) == {"gameId", "startTime", "stadium", "awayStarter", "homeStarter"}


def test_read_game_schedules_requires_date():
    with pytest.raises(ValueError, match="--date"):
        list(exporter.read_game_schedules(None, date=None, sink=FakeSink()))


def test_read_game_schedules_missing_raw_raises():
    with pytest.raises(ValueError, match="schedule"):
        list(exporter.read_game_schedules(None, date="1999-01-01", sink=_sched_sink()))


def test_export_game_schedule_writes_to_s3():
    sink = _sched_sink()
    n = exporter.export("game_schedule", settings=SimpleNamespace(), db=None,
                        sink=sink, date="2026-07-31")
    assert n >= 1
    key, obj = sink.puts[0]
    assert key.startswith("question-source/game_schedule/")
    assert obj["envelopeVersion"] == 1
```

(픽스처가 당일 경기 없는 월요일이라 BEFORE 경기가 0개면 다른 날짜로 다시 캡처.)

- [ ] **Step 3: 테스트가 실패하는지 실행**

`python -m pytest tests/test_exporter.py -v` — 기대: `AttributeError: ... 'read_game_schedules'` 로 FAIL

- [ ] **Step 4: reader 구현** — `exporter.py` 끝에 추가 (필드명은 Step 1 실측으로 교체)

```python
@reader("game_schedule")
def read_game_schedules(db, date=None, sink=None):
    """raw-json/schedule/{date} → 예정(BEFORE) 경기 envelope. date 필수, db 미사용.

    선발 라인업(타자)은 경기 전 데이터 소스가 없어 v1은 일정+선발투수만 담는다.
    """
    if not date:
        raise ValueError("game_schedule export requires --date")
    from .. import keys as raw_keys
    from ..dimensions import TEAM_CODES
    key = raw_keys.schedule_key(date)
    if not sink.exists(key):
        raise ValueError(f"raw schedule 없음: {key} — 'schedule' 잡을 먼저 실행하세요")
    now = _now()
    games = ((sink.get_json(key) or {}).get("result") or {}).get("games") or []
    for g in games:
        if g.get("categoryId") != "kbo" or g.get("cancel"):
            continue
        if g.get("statusCode") != "BEFORE":
            continue
        if g.get("awayTeamCode") not in TEAM_CODES or g.get("homeTeamCode") not in TEAM_CODES:
            continue
        gid = g["gameId"]
        a_name = g.get("awayTeamName") or g["awayTeamCode"]
        h_name = g.get("homeTeamName") or g["homeTeamCode"]
        gtime = (g.get("gameDateTime") or "")[11:16]   # 실측 필드명·포맷으로 교체
        stadium = g.get("stadium") or ""
        a_sp, h_sp = g.get("awayStarterName"), g.get("homeStarterName")
        parts = [f"{date} {gtime} {stadium}에서 {a_name} 대 {h_name} 경기가 예정되어 있다."]
        if a_sp or h_sp:
            parts.append(f"선발투수는 {a_name} {a_sp or '미정'}, {h_name} {h_sp or '미정'}.")
        entities = empty_entities()
        entities["gameId"] = gid
        entities["teamCodes"] = [g["awayTeamCode"], g["homeTeamCode"]]
        yield Envelope(
            doc_id=f"game_schedule:{gid}", doc_type="game_schedule", source="naver",
            source_ref=key, collected_at=now,
            title=f"{date} {a_name} vs {h_name} 경기 예정",
            content=" ".join(parts), tags=["일정", "예정경기"],
            entities=entities,
            payload={"gameId": gid, "startTime": gtime, "stadium": stadium,
                     "awayStarter": a_sp, "homeStarter": h_sp},
        )
```

- [ ] **Step 5: 테스트 통과 확인**

`python -m pytest tests/test_exporter.py -v` — 기대: 전체 PASS (기존 테스트 포함)

- [ ] **Step 6: 커밋**

```bash
git add kbo_collector/exports/exporter.py tests/test_exporter.py tests/fixtures/schedule_before.json
git commit -m "feat(py-collector): game_schedule docType export 추가"
```

---

### Task 2: py-collector — `kbo_records` 수집 소스

**Files:**
- Create: `VictoryFairy_AI/py-collector/kbo_collector/sources/kbo_records.py`
- Modify: `VictoryFairy_AI/py-collector/kbo_collector/sources/__init__.py` (import 1줄)
- Modify: `VictoryFairy_AI/py-collector/kbo_collector/keys.py` (`kbo_records_key`)
- Modify: `VictoryFairy_AI/py-collector/kbo_collector/run.py` (collect 잡의 DbSink 지연 생성)
- Create: `VictoryFairy_AI/py-collector/tests/fixtures/kbo_team_rank_daily.html`
- Create: `VictoryFairy_AI/py-collector/tests/test_kbo_records_source.py`

**Interfaces:**
- Consumes: `sources.base.register/CollectContext/CollectResult`, `ctx.client`(httpx), `ctx.sink.put_json`, `settings.kbo_base_url`
- Produces: S3 `kbo-records/{page}/{date}.json` = `{"page","url","date","fetchedAt","tables":[{"headers":[...],"rows":[[...]]}]}` — Task 5·9의 스냅샷 입력 계약. `PAGES` dict 키(페이지 슬러그): `team-rank-daily, hitter-basic, pitcher-basic, top5, history-top-hitter, history-player-hitter, history-player-pitcher, history-team, expectation-week, record-correct`. 클래스 속성 `needs_db = False` (run.py가 참조)

- [ ] **Step 1: 픽스처 캡처**

```bash
cd VictoryFairy_AI/py-collector
curl -s -H 'User-Agent: Mozilla/5.0' \
  "https://www.koreabaseball.com/Record/TeamRank/TeamRankDaily.aspx" \
  -o tests/fixtures/kbo_team_rank_daily.html
grep -c "<table" tests/fixtures/kbo_team_rank_daily.html   # 1 이상이어야 함
```

- [ ] **Step 2: 실패하는 테스트 작성** — `tests/test_kbo_records_source.py`

```python
from pathlib import Path
from types import SimpleNamespace

from kbo_collector.sources import base as source_base
from kbo_collector.sources.kbo_records import PAGES, parse_tables
from kbo_collector import keys

FIXTURE = (Path(__file__).parent / "fixtures" / "kbo_team_rank_daily.html").read_text(encoding="utf-8")


def test_parse_tables_extracts_headers_and_rows():
    tables = parse_tables(FIXTURE)
    assert tables, "픽스처에서 테이블을 하나도 못 뽑음"
    t = tables[0]
    assert t["headers"] and t["rows"]
    assert all(isinstance(r, list) for r in t["rows"])


def test_kbo_records_key():
    assert keys.kbo_records_key("team-rank-daily", "2026-07-30") == \
        "kbo-records/team-rank-daily/2026-07-30.json"


class FakeClient:
    def get(self, url, headers=None):
        return SimpleNamespace(text=FIXTURE)


class FakeSink:
    def __init__(self):
        self.puts = []

    def put_json(self, key, obj, metadata=None):
        self.puts.append((key, obj))


def test_collect_snapshots_every_page():
    src = source_base.get_source("kbo_records")
    assert getattr(src, "needs_db", True) is False
    sink = FakeSink()
    ctx = source_base.CollectContext(
        settings=SimpleNamespace(kbo_base_url="https://www.koreabaseball.com"),
        client=FakeClient(), sink=sink, date="2026-07-30")
    res = src.collect(ctx)
    assert res.loaded == len(PAGES) and not res.failed
    keys_written = {k for k, _ in sink.puts}
    assert "kbo-records/team-rank-daily/2026-07-30.json" in keys_written
    _, obj = sink.puts[0]
    assert set(obj) == {"page", "url", "date", "fetchedAt", "tables"}


def test_collect_page_failure_is_isolated():
    class FailingClient:
        def __init__(self):
            self.n = 0

        def get(self, url, headers=None):
            self.n += 1
            if self.n == 1:
                raise RuntimeError("boom")
            return SimpleNamespace(text=FIXTURE)

    src = source_base.get_source("kbo_records")
    sink = FakeSink()
    ctx = source_base.CollectContext(
        settings=SimpleNamespace(kbo_base_url="https://x"),
        client=FailingClient(), sink=sink, date="2026-07-30")
    res = src.collect(ctx)
    assert res.loaded == len(PAGES) - 1 and len(res.failed) == 1
```

- [ ] **Step 3: 실행해 실패 확인**

`python -m pytest tests/test_kbo_records_source.py -v` — 기대: `ModuleNotFoundError: kbo_collector.sources.kbo_records`

- [ ] **Step 4: 구현**

`keys.py` 끝에:

```python
def kbo_records_key(page: str, date: str) -> str:
    return f"kbo-records/{page}/{date}.json"
```

`sources/kbo_records.py` (신규):

```python
"""KBO 공식 기록실 페이지 스냅샷 → S3 kbo-records/{page}/{date}.json (브론즈).

envelope 재포장 없음 — 소비자는 통계 집계·시드 생성 스크립트뿐(스펙 4.4).
파싱은 '페이지의 모든 <table>을 헤더+행 그대로' 뜨는 제네릭 방식이라 페이지별
스키마 해석은 소비자 몫이고, 사이트 개편 시엔 여기가 아니라 소비자가 덜 다친다.
페이지 하나 실패는 그 페이지만 건너뛴다(이전 날짜 스냅샷이 남아 있으므로, 스펙 §5).
"""
from datetime import datetime, timezone

from bs4 import BeautifulSoup

from .. import keys
from .base import CollectResult, register

_UA = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
       "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")

# 스펙 4.4의 9개 대상 페이지 (+ history-top의 hitter 페이지). 슬러그가 S3 prefix.
PAGES = {
    "team-rank-daily":       "/Record/TeamRank/TeamRankDaily.aspx",
    "hitter-basic":          "/Record/Player/HitterBasic/Basic1.aspx",
    "pitcher-basic":         "/Record/Player/PitcherBasic/Basic1.aspx",
    "top5":                  "/Record/Ranking/Top5.aspx",
    "history-top-hitter":    "/Record/History/Top/Hitter.aspx",
    "history-player-hitter": "/Record/History/Player/Hitter.aspx",
    "history-player-pitcher": "/Record/History/Player/Pitcher.aspx",
    "history-team":          "/Record/History/Team/Record.aspx",
    "expectation-week":      "/Record/Expectation/WeekList.aspx",
    "record-correct":        "/Record/RecordCorrect/RecordCorrect.aspx",
}


def parse_tables(html: str) -> list:
    """모든 <table> → [{"headers": [...], "rows": [[...]]}]. 행 없는 표는 제외."""
    soup = BeautifulSoup(html, "lxml")
    out = []
    for table in soup.select("table"):
        headers = [th.get_text(" ", strip=True) for th in table.select("tr th")]
        rows = [[td.get_text(" ", strip=True) for td in tr.find_all("td")]
                for tr in table.select("tr") if tr.find_all("td")]
        if rows:
            out.append({"headers": headers, "rows": rows})
    return out


@register
class KboRecords:
    source_id = "kbo_records"
    doc_types = ("kbo_records",)
    needs_db = False    # run.py collect 잡이 DbSink 생성을 건너뛴다

    def collect(self, ctx) -> CollectResult:
        date = ctx.date or datetime.now(timezone.utc).strftime("%Y-%m-%d")
        fetched_at = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
        loaded, failed = 0, []
        for page, path in PAGES.items():
            url = f"{ctx.settings.kbo_base_url}{path}"
            try:
                html = ctx.client.get(url, headers={"User-Agent": _UA}).text
                tables = parse_tables(html)
                if not tables:
                    raise ValueError("no tables parsed")
                ctx.sink.put_json(keys.kbo_records_key(page, date), {
                    "page": page, "url": url, "date": date,
                    "fetchedAt": fetched_at, "tables": tables,
                })
                loaded += 1
            except Exception as exc:   # 페이지 단위 격리
                failed.append(f"{page}: {exc}")
        return CollectResult(loaded=loaded, failed=failed)
```

`sources/__init__.py`:

```python
from . import community_posts, kbo_records, kbo_roster, meme_dict, naver_games  # noqa: F401
```

`run.py`의 collect 분기에서 DbSink를 소스가 필요할 때만 만든다 (현재 무조건 `db = DbSink(settings)`):

```python
    if args.job == "collect":
        from .sources import base as source_base
        src = source_base.get_source(args.target or "")
        db = None
        if getattr(src, "needs_db", True):
            from .db import DbSink
            db = DbSink(settings)
        try:
            with fetch.build_client(settings) as client:
                ctx = source_base.CollectContext(
                    settings=settings, client=client, db=db,
                    sink=S3RawSink(settings), date=args.date)
                result = src.collect(ctx)
                logging.getLogger("collect").info(
                    "%s: loaded=%d failed=%d", src.source_id,
                    result.loaded, len(result.failed))
        finally:
            if db is not None:
                db.close()
        return 0
```

- [ ] **Step 5: 테스트 통과 + 전체 회귀 확인**

`python -m pytest tests/ -v` — 기대: 전체 PASS (`test_registry_contract.py`·`test_run_jobs.py` 회귀 포함)

- [ ] **Step 6: 라이브 스모크 (실 S3 적재 1회)**

```bash
python -m kbo_collector.run collect --target kbo_records
aws s3 ls "s3://$(grep COLLECTOR_S3_BUCKET .env | cut -d= -f2)/kbo-records/" --recursive | head
```

기대: 10개 페이지 중 상당수 적재(`loaded=10 failed=0`이 이상적). 실패 페이지가 있으면 해당 URL을 브라우저로 열어 실제 경로 확인 후 `PAGES` 수정(사이트 리다이렉트 가능성). **이 스냅샷은 Task 9의 입력이 된다.**

- [ ] **Step 7: 커밋**

```bash
git add kbo_collector/sources/kbo_records.py kbo_collector/sources/__init__.py \
        kbo_collector/keys.py kbo_collector/run.py \
        tests/test_kbo_records_source.py tests/fixtures/kbo_team_rank_daily.html
git commit -m "feat(py-collector): kbo_records 기록실 스냅샷 소스 추가"
```

---

### Task 3: py-collector — 잡 wiring (Lambda 핸들러 + Terraform 스케줄)

**Files:**
- Modify: `VictoryFairy_AI/py-collector/deploy/lambda/handler.py`
- Modify: `VictoryFairy_AI/py-collector/kbo_collector/exports/exporter.py` (`DB_FREE` 셋 추가)
- Modify: `VictoryFairy_AI/py-collector/kbo_collector/run.py` (CLI export의 DB-free 처리)
- Modify: `VictoryFairy_AI/py-collector/deploy/lambda/terraform/schedules.tf`, `lambda_db.tf`, `variables.tf`
- Modify: `VictoryFairy_AI/py-collector/tests/test_lambda_handler.py`

**Interfaces:**
- Consumes: Task 1 `read_game_schedules`, Task 2 `kbo_records` 소스, 기존 `run.land_schedule`/`exporter.export`
- Produces: Lambda 이벤트 계약 — `{"job":"kbo_records"}`, `{"job":"game_schedule"}`(내부에서 KST 오늘로 land_schedule 후 export), `{"job":"export","target":"game_result"}`(-db 함수). `exporter.DB_FREE = {"game_schedule", "community_post"}`

> ⚠️ **인프라 전제 확인**: 시작 전에 `git log origin/dev_ai --oneline -5`로 dev_ai 최신을 확인하고 이 브랜치에 머지한다. 최근 다른 세션에서 DB 잡을 EC2 크론으로 옮기는 작업(feat-prod-db-sink 계열)이 있었다 — 머지 후 `deploy/ec2/` 크론 구성이 존재하면 **game_result export는 Terraform이 아니라 그 크론 파일에 한 줄 추가**(records 잡 뒤 `python -m kbo_collector.run export --target game_result`)로 대체하고, 아래 Step 4의 lambda_db.tf 변경은 생략한다. S3 전용 잡 2개(kbo_records·game_schedule)는 어느 쪽이든 Lambda EventBridge로 간다.

- [ ] **Step 1: 실패하는 핸들러 테스트 작성** — `tests/test_lambda_handler.py`에 추가 (기존 테스트의 monkeypatch 스타일 준수 — 파일을 먼저 읽고 기존 픽스처/페이크를 재사용할 것)

```python
def test_handler_kbo_records_job(monkeypatch, fake_settings_env):
    called = {}

    class FakeSrc:
        needs_db = False

        def collect(self, ctx):
            called["date"] = ctx.date
            return SimpleNamespace(loaded=10, failed=[])

    monkeypatch.setattr("kbo_collector.sources.base.get_source", lambda sid: FakeSrc())
    out = handler.handler({"job": "kbo_records"}, None)
    assert out["kboRecords"] == {"loaded": 10, "failed": []}


def test_handler_game_schedule_job(monkeypatch, fake_settings_env):
    seq = []
    monkeypatch.setattr(handler.run, "land_schedule",
                        lambda date, **kw: seq.append(("land", date)) or [])
    monkeypatch.setattr("kbo_collector.exports.exporter.export",
                        lambda t, **kw: seq.append(("export", t, kw["date"])) or 3)
    out = handler.handler({"job": "game_schedule", "date": "2026-07-31"}, None)
    assert seq == [("land", "2026-07-31"), ("export", "game_schedule", "2026-07-31")]
    assert out["gameSchedule"] == 3
```

- [ ] **Step 2: 실행해 실패 확인** — `python -m pytest tests/test_lambda_handler.py -v`

- [ ] **Step 3: 구현**

`exporter.py` 상단(READERS 아래)에:

```python
# DB 없이 export 가능한 docType (reader가 db 인자를 무시)
DB_FREE = {"game_schedule", "community_post"}
```

`handler.py` — DB 잡 분기(`if job in ("records", "registrations")`)를 `("records", "registrations", "export")`로 확장:

```python
    if job in ("records", "registrations", "export"):
        db = DbSink(settings)
        try:
            with fetch.build_client(settings) as client:
                if job == "registrations":
                    summary["registrations"] = run.land_registrations(
                        event.get("date"), settings=settings, db=db, client=client)
                elif job == "export":
                    from kbo_collector.exports import exporter
                    summary["exported"] = exporter.export(
                        event["target"], settings=settings, db=db,
                        sink=S3RawSink(settings), date=event.get("date"))
                else:
                    ...  # 기존 records 분기 그대로
        finally:
            db.close()
        return summary
```

`handler.py` — S3 잡 블록(`with fetch.build_client(...)`) 안에 추가:

```python
        if job == "kbo_records":
            import kbo_collector.sources  # noqa: F401 — REGISTRY 등록
            from kbo_collector.sources import base as source_base
            src = source_base.get_source("kbo_records")
            res = src.collect(source_base.CollectContext(
                settings=settings, client=client, sink=sink, date=event.get("date")))
            summary["kboRecords"] = {"loaded": res.loaded, "failed": res.failed}
        if job == "game_schedule":
            kst = event.get("date") or _kst_today()
            run.land_schedule(kst, settings=settings, sink=sink, client=client,
                              journal=Journal("schedule", kst, run_id, settings.journal_dir))
            from kbo_collector.exports import exporter
            summary["gameSchedule"] = exporter.export(
                "game_schedule", settings=settings, db=None, sink=sink, date=kst)
```

(주의: `game_schedule`은 KST 오늘 기준 — 기존 `_today()`(UTC)가 아니라 `_kst_today()`. 03:00 KST의 game 잡은 UTC 오늘=KST 어제 완료 경기용이라 서로 날짜 기준이 다른 게 맞다.)

`run.py` CLI export 분기 — DB-free docType이면 DbSink 생략:

```python
    if args.job == "export":
        from .exports import exporter
        db = None
        if (args.target or "") not in exporter.DB_FREE:
            from .db import DbSink
            db = DbSink(settings)
        try:
            n = exporter.export(args.target or "", settings=settings, db=db,
                                sink=S3RawSink(settings), date=args.date)
            logging.getLogger("export").info("%s: exported=%d", args.target, n)
        finally:
            if db is not None:
                db.close()
        return 0
```

Terraform — `variables.tf`에 변수 3개 추가(기존 `community_schedule` 선언과 같은 모양):

```hcl
variable "kbo_records_schedule" {
  description = "KBO 기록실 스냅샷 (07:00 KST)"
  type        = string
  default     = "cron(0 22 * * ? *)"
}

variable "game_schedule_schedule" {
  description = "당일 예정경기 export (08:30 KST)"
  type        = string
  default     = "cron(30 23 * * ? *)"
}

variable "export_game_result_schedule" {
  description = "game_result envelope export (04:00 KST, records 03:30 이후)"
  type        = string
  default     = "cron(0 19 * * ? *)"
}
```

`schedules.tf`에 규칙 2개(기존 community 블록 복사 패턴): rule/target/permission 3종 세트로 `kbo_records`(`input = jsonencode({ job = "kbo_records" })`)와 `game_schedule`(`input = jsonencode({ job = "game_schedule" })`) — 둘 다 S3 함수(`aws_lambda_function.this`) 대상.

`lambda_db.tf`(EC2 크론 부재 시에만): `export-game-result` rule/target/permission 세트 — `-db` 함수 대상, `input = jsonencode({ job = "export", target = "game_result" })`. **추가로 -db 함수에 S3 접근이 필요**: 환경변수 `COLLECTOR_S3_BUCKET`/`COLLECTOR_S3_REGION`을 -db 함수 env에 추가하고, -db 함수 role에 `question-source/*` prefix `s3:PutObject`/`s3:GetObject`/`s3:ListBucket` 정책을 붙인다 (iam.tf의 기존 S3 정책 문서를 참조해 같은 스타일로).

- [ ] **Step 4: 테스트 + terraform 검증**

```bash
python -m pytest tests/ -v                     # 전체 PASS
terraform -chdir=deploy/lambda/terraform fmt   # 포맷만 (apply는 배포 소유자가 수행)
```

- [ ] **Step 5: 커밋**

```bash
git add deploy/lambda kbo_collector/run.py kbo_collector/exports/exporter.py tests/test_lambda_handler.py
git commit -m "feat(py-collector): kbo_records·game_schedule·export 잡 스케줄 wiring"
```

---

### Task 4: 통계 집계 스크립트 — game_result 순수 집계 함수

**Files:**
- Create: `VictoryFairy_AI/question-gen/scripts/aggregate_stats.py`
- Create: `VictoryFairy_AI/tests/conftest.py`
- Create: `VictoryFairy_AI/tests/test_aggregate_stats.py`

**Interfaces:**
- Consumes: game_result envelope dict (계약: `payload={"gameId","awayScore","homeScore","winner",...}`, `entities.teamCodes=[away,home]`, gameId 앞 8자리 = YYYYMMDD)
- Produces (Task 5·10이 사용):
  - `@dataclass(frozen=True) Game`: `game_id, date, away, home, away_score, home_score, winner`
  - `parse_game(env: dict) -> Game | None` (필드 결손 시 None)
  - `head_to_head(games) -> dict` — 키 `"A|B"`(코드 사전순), 값 `{"wins": {A: n, B: m}, "draws": d, "last": {"date","gameId","score","winner"}}`
  - `standings(games) -> list[dict]` — `[{"team","wins","losses","draws","winPct","rank"}]` 승률 내림차순, winPct는 (승+패) 기준 소수 3자리
  - `streaks(games) -> dict` — `{team: {"kind": "W"|"L", "length": n}}` (무승부는 연속 끊김)
  - `home_away(games) -> dict` — `{team: {"home": {"wins","losses","draws"}, "away": {...}}}`
  - `monthly(games) -> dict` — `{team: {"YYYY-MM": {"wins","losses","draws","winPct"}}}`
  - `standings_trend(games) -> dict` — 개막일+27일까지의 순위 vs 전체 순위: `{"earlyAsOf", "early": {team: rank}, "now": {team: rank}, "delta": {team: early-now}}`
  - `recent_scoring(games, end_date, days=7) -> dict` — `{team: {"games","runsFor","runsAgainst"}}`
  - `yoy(cur_games, prev_games, as_of) -> dict | None` — 전년 동일 월-일 컷오프 승률 비교 `{team: {"prev","cur","delta"}}`, prev_games 비면 None
  - `season_games(games, year: int) -> list`

- [ ] **Step 1: conftest 작성** — `VictoryFairy_AI/tests/conftest.py` (신규 — 기존에 없음을 확인했음. 있으면 append)

```python
import sys
from pathlib import Path

_ROOT = Path(__file__).resolve().parents[1]
# routine 스크립트(question-gen/wiki-builder — 하이픈 디렉토리라 패키지 불가)를
# 모듈로 import 가능하게 한다.
for _p in ("question-gen/scripts", "wiki-builder/scripts"):
    sys.path.insert(0, str(_ROOT / _p))
```

- [ ] **Step 2: 실패하는 테스트 작성** — `tests/test_aggregate_stats.py`

```python
import aggregate_stats as agg


def env(gid, away, home, a, h, winner):
    return {"docType": "game_result", "docId": f"game_result:{gid}",
            "entities": {"teamCodes": [away, home], "gameId": gid},
            "payload": {"gameId": gid, "awayScore": a, "homeScore": h, "winner": winner}}


# LG-OB 3연전 + LG-HT 1경기 (gameId 앞 8자리 = 날짜)
ENVS = [
    env("20260501LGOB02026", "LG", "OB", 3, 5, "home"),
    env("20260502LGOB02026", "LG", "OB", 7, 2, "away"),
    env("20260503LGOB02026", "LG", "OB", 4, 4, "draw"),
    env("20260601HTLG02026", "HT", "LG", 1, 2, "home"),
]
GAMES = [agg.parse_game(e) for e in ENVS]


def test_parse_game():
    g = GAMES[0]
    assert (g.date, g.away, g.home, g.winner) == ("2026-05-01", "LG", "OB", "home")
    assert agg.parse_game({"payload": {}}) is None


def test_head_to_head():
    h2h = agg.head_to_head(GAMES)
    lg_ob = h2h["LG|OB"]
    assert lg_ob["wins"] == {"LG": 1, "OB": 1} and lg_ob["draws"] == 1
    assert lg_ob["last"]["date"] == "2026-05-03"


def test_standings_rank_and_pct():
    rows = {r["team"]: r for r in agg.standings(GAMES)}
    assert rows["LG"]["wins"] == 2 and rows["LG"]["losses"] == 1 and rows["LG"]["draws"] == 1
    assert rows["LG"]["winPct"] == 0.667
    assert rows["HT"]["losses"] == 1 and rows["HT"]["rank"] >= rows["LG"]["rank"]


def test_streaks():
    s = agg.streaks(GAMES)
    assert s["LG"] == {"kind": "W", "length": 1}   # 무승부(5/3)로 끊긴 뒤 6/1 승


def test_home_away_and_monthly():
    ha = agg.home_away(GAMES)
    assert ha["OB"]["home"]["wins"] == 1
    m = agg.monthly(GAMES)
    assert m["LG"]["2026-05"]["wins"] == 1 and m["LG"]["2026-06"]["wins"] == 1


def test_recent_scoring_window():
    rs = agg.recent_scoring(GAMES, end_date="2026-05-03", days=7)
    assert rs["LG"]["games"] == 3 and rs["LG"]["runsFor"] == 14


def test_yoy_none_without_prev():
    assert agg.yoy(GAMES, [], as_of="2026-06-02") is None
```

- [ ] **Step 3: 실행해 실패 확인**

`cd VictoryFairy_AI && python -m pytest tests/test_aggregate_stats.py -v` — 기대: `ModuleNotFoundError: aggregate_stats`

- [ ] **Step 4: 구현** — `question-gen/scripts/aggregate_stats.py`

파일 헤더 독스트링에 명시: *"⓪ 전처리 — 결정적 통계 재집계. LLM 미사용(스펙 Global 제약). 입력은 로컬 디렉토리(routine이 aws s3 sync로 준비), 출력도 로컬 → routine이 업로드."* 구현 요지:

```python
from dataclasses import dataclass
from collections import defaultdict
from datetime import date as date_cls, timedelta


@dataclass(frozen=True)
class Game:
    game_id: str
    date: str
    away: str
    home: str
    away_score: int
    home_score: int
    winner: str   # away|home|draw


def parse_game(env):
    p = (env or {}).get("payload") or {}
    codes = ((env or {}).get("entities") or {}).get("teamCodes") or []
    gid = p.get("gameId") or ""
    if len(gid) < 8 or len(codes) != 2 or p.get("winner") not in ("away", "home", "draw"):
        return None
    if p.get("awayScore") is None or p.get("homeScore") is None:
        return None
    d = f"{gid[0:4]}-{gid[4:6]}-{gid[6:8]}"
    return Game(gid, d, codes[0], codes[1], p["awayScore"], p["homeScore"], p["winner"])
```

이하 각 함수는 Interfaces 계약대로 dict를 만든다. 공통 헬퍼 `_wld(team, game)`(승/패/무 판정), `_win_pct(w, l)` = `round(w / (w + l), 3)` (w+l==0이면 0.0). `standings`는 winPct 내림차순 정렬 후 `rank` 1부터 부여(동률은 같은 순위 부여 없이 순서대로 — v1 단순화 주석). `streaks`는 팀별 날짜순 마지막 연속 W/L. `standings_trend`는 `early_end = min(g.date) + 27일` 시점까지의 games로 standings를 한 번 더 돌려 rank 비교. `yoy`는 `as_of`의 `MM-DD`를 컷오프로 두 시즌 각각 standings를 내 승률 비교.

- [ ] **Step 5: 테스트 통과 확인**

`python -m pytest tests/test_aggregate_stats.py -v` — 기대: 전체 PASS

- [ ] **Step 6: 커밋**

```bash
git add question-gen/scripts/aggregate_stats.py tests/conftest.py tests/test_aggregate_stats.py
git commit -m "feat(question-gen): 시즌 통계 결정적 집계 함수 (stats ⓪ 전처리 1/2)"
```

---

### Task 5: 통계 집계 스크립트 — 스냅샷 추출·렌더·CLI

**Files:**
- Modify: `VictoryFairy_AI/question-gen/scripts/aggregate_stats.py`
- Modify: `VictoryFairy_AI/tests/test_aggregate_stats.py`

**Interfaces:**
- Consumes: Task 4 함수들, Task 2 스냅샷 계약(`{"page","tables":[{"headers","rows"}]}`)
- Produces:
  - `extract_kbo_official(snapshots: dict) -> dict` — 입력 `{page_slug: snapshot_dict}`, 출력 `{"teamRankDaily","seasonLeaders","milestoneWatch","teamHistory","recordCorrect"}` (각각 해당 페이지 `tables` 그대로 + `asOf`=snapshot date; 없는 페이지 키는 None)
  - `build_season_stats(games, today: str) -> dict` — Task 4 함수 전부 호출해 `{"generatedAt","asOf","headToHead","standings","streaks","homeAway","monthly","standingsTrend","recentScoring","yoy"}` 조립
  - `render_season_md(stats: dict) -> str`, `render_kbo_md(kbo: dict) -> str` — 사람/LLM용 마크다운. 모든 섹션 제목에 `(기준일 {asOf})` 포함
  - CLI: `python question-gen/scripts/aggregate_stats.py --envelopes-dir D --kbo-dir K --out-dir O --date YYYY-MM-DD` → O에 `season.json`, `season.md`, `kbo-official.json`, `kbo-official.md` 생성
- 산출 파일 4개의 S3 목적지는 `wiki/stats/` (업로드는 routine의 aws CLI 몫 — Task 8·10의 ROUTINE.md에 명시)

- [ ] **Step 1: 실패하는 테스트 추가**

```python
def test_extract_kbo_official():
    snap = {"page": "team-rank-daily", "date": "2026-07-30",
            "tables": [{"headers": ["순위", "팀", "승"], "rows": [["1", "LG", "60"]]}]}
    out = agg.extract_kbo_official({"team-rank-daily": snap})
    assert out["teamRankDaily"]["asOf"] == "2026-07-30"
    assert out["teamRankDaily"]["tables"][0]["rows"][0][1] == "LG"
    assert out["seasonLeaders"] is None   # hitter/pitcher/top5 스냅샷 없음


def test_build_season_stats_shape():
    stats = agg.build_season_stats(GAMES, today="2026-06-02")
    assert set(stats) == {"generatedAt", "asOf", "headToHead", "standings", "streaks",
                          "homeAway", "monthly", "standingsTrend", "recentScoring", "yoy"}
    assert stats["asOf"] == "2026-06-02"


def test_render_season_md_mentions_asof():
    md = agg.render_season_md(agg.build_season_stats(GAMES, today="2026-06-02"))
    assert "기준일 2026-06-02" in md and "상대전적" in md and "순위" in md


def test_cli_writes_four_files(tmp_path):
    import json
    envd = tmp_path / "env"; envd.mkdir()
    for i, e in enumerate(ENVS):
        (envd / f"{i}.json").write_text(json.dumps(e), encoding="utf-8")
    outd = tmp_path / "out"
    agg.main(["--envelopes-dir", str(envd), "--kbo-dir", str(tmp_path / "none"),
              "--out-dir", str(outd), "--date", "2026-06-02"])
    assert {p.name for p in outd.iterdir()} == \
        {"season.json", "season.md", "kbo-official.json", "kbo-official.md"}
```

- [ ] **Step 2: 실행해 실패 확인** — `python -m pytest tests/test_aggregate_stats.py -v`

- [ ] **Step 3: 구현**

- `extract_kbo_official`: `seasonLeaders`는 `hitter-basic`/`pitcher-basic`/`top5` 세 스냅샷을 `{"hitterBasic":…, "pitcherBasic":…, "top5":…}`로 묶고 셋 다 없으면 None. `teamHistory`←`history-team`, `milestoneWatch`←`expectation-week`, `recordCorrect`←`record-correct`.
- `load_envelopes_dir(path) -> list[dict]`: 디렉토리 재귀로 `*.json` 로드, `docType=="game_result"`만.
- `load_snapshots_dir(path) -> dict`: `{page}/{date}.json` 구조에서 페이지별 **최신 날짜** 파일만.
- `render_season_md`: 결정적 f-string 렌더 — 섹션: `## 팀 순위 (기준일 …)` 표, `## 상대전적`, `## 연승·연패`, `## 홈/원정`, `## 월별 성적`, `## 시즌 초 대비 순위 변동`, `## 최근 7일 득실`, `## 전년 대비`(yoy None이면 "2025 데이터 없음 — 비활성" 한 줄).
- `main(argv=None)`: argparse → 로드 → `build_season_stats` + `extract_kbo_official` → 4개 파일 기록. `--kbo-dir` 부재 시 kbo-official엔 전부 None(파일은 그래도 생성).

- [ ] **Step 4: 테스트 통과 확인** — `python -m pytest tests/test_aggregate_stats.py -v`

- [ ] **Step 5: 커밋**

```bash
git add question-gen/scripts/aggregate_stats.py tests/test_aggregate_stats.py
git commit -m "feat(question-gen): 통계 렌더·스냅샷 추출·CLI (stats ⓪ 전처리 2/2)"
```

---

### Task 6: quiz-candidates 결정적 검증 스크립트

**Files:**
- Create: `VictoryFairy_AI/question-gen/scripts/validate_candidates.py`
- Create: `VictoryFairy_AI/question-gen/config/banned-topics.txt`
- Create: `VictoryFairy_AI/tests/test_validate_candidates.py`

**Interfaces:**
- Consumes: quiz-candidates JSON 계약(스펙 4.3), `question-gen/config/question-templates.yaml`(PyYAML 로드; 항목 필드 `id/kind/format/enabled`)
- Produces:
  - `POINTS = {"EASY": 30, "MEDIUM": 50, "HARD": 80, "EXPERT": 120}`, `METRICS = {"WIN_TEAM","TOTAL_RUNS","SCORE_GAP","PITCHER_DECISION"}`
  - `load_catalog(path) -> dict[str, dict]` (enabled 기본 True)
  - `load_banned(path) -> list[str]`
  - `validate_candidate(c: dict, catalog: dict, banned: list) -> list[str]` — 위반 메시지 리스트(빈 리스트 = 통과)
  - CLI: `python question-gen/scripts/validate_candidates.py --dir quiz-candidates/2026-07-30/` → 위반 리포트 출력, 위반 있으면 exit 1
- routine(Task 10)이 업로드 직전 마지막 게이트로 호출

- [ ] **Step 1: 실패하는 테스트 작성** — `tests/test_validate_candidates.py`

```python
import validate_candidates as vc

CATALOG = {"H2H_SEASON_RECORD": {"id": "H2H_SEASON_RECORD", "kind": "KNOWLEDGE",
                                 "format": "BINARY", "enabled": True},
           "YOY_TEAM": {"id": "YOY_TEAM", "kind": "KNOWLEDGE",
                        "format": "MULTI4", "enabled": False},
           "PRED_WIN_LOSE": {"id": "PRED_WIN_LOSE", "kind": "PREDICTION",
                             "format": "BINARY", "enabled": True}}
BANNED = ["음주", "폭행"]


def ok_knowledge():
    return {"quizId": "QZ-20260730-001", "gameId": None, "kind": "KNOWLEDGE",
            "type": "HISTORY", "templateId": "H2H_SEASON_RECORD", "format": "BINARY",
            "question": "올 시즌 잠실 라이벌전 우위 팀은?",
            "options": [{"id": "A", "text": "LG"}, {"id": "B", "text": "두산"}],
            "answer": "A",
            "evidence": {"source": "wiki/stats/season.md#상대전적", "quote": "LG 7-4 두산"},
            "settlement": None, "difficulty": "MEDIUM", "pointReward": 50,
            "status": "PENDING", "createdAt": "2026-07-30T00:00:00Z",
            "deadlineAt": "2026-07-30T16:00:00Z", "createdBy": "AI_ENGINE"}


def test_valid_knowledge_passes():
    assert vc.validate_candidate(ok_knowledge(), CATALOG, BANNED) == []


def test_option_count_must_match_format():
    c = ok_knowledge()
    c["options"].append({"id": "C", "text": "무승부"})
    assert any("options" in v for v in vc.validate_candidate(c, CATALOG, BANNED))


def test_knowledge_requires_evidence_and_answer():
    c = ok_knowledge(); c["evidence"] = None
    assert vc.validate_candidate(c, CATALOG, BANNED)
    c = ok_knowledge(); c["answer"] = "Z"
    assert vc.validate_candidate(c, CATALOG, BANNED)


def test_prediction_requires_settlement_metric():
    c = ok_knowledge()
    c.update(kind="PREDICTION", templateId="PRED_WIN_LOSE", answer=None, evidence=None,
             settlement={"gameId": "20260730LGOB02026", "metric": "WIN_TEAM"},
             gameId="20260730LGOB02026")
    assert vc.validate_candidate(c, CATALOG, BANNED) == []
    c["settlement"]["metric"] = "INNINGS_PITCHED"   # 정산 불가 지표
    assert vc.validate_candidate(c, CATALOG, BANNED)


def test_disabled_or_unknown_template_rejected():
    c = ok_knowledge(); c["templateId"] = "YOY_TEAM"; c["format"] = "MULTI4"
    assert vc.validate_candidate(c, CATALOG, BANNED)
    c = ok_knowledge(); c["templateId"] = "NOPE"
    assert vc.validate_candidate(c, CATALOG, BANNED)


def test_point_must_match_difficulty():
    c = ok_knowledge(); c["pointReward"] = 999
    assert vc.validate_candidate(c, CATALOG, BANNED)


def test_banned_topic_rejected():
    c = ok_knowledge(); c["question"] = "음주운전 사건의 주인공은?"
    assert vc.validate_candidate(c, CATALOG, BANNED)


def test_real_catalog_loads():
    from pathlib import Path
    path = Path(__file__).resolve().parents[1] / "question-gen/config/question-templates.yaml"
    cat = vc.load_catalog(str(path))
    assert "H2H_SEASON_RECORD" in cat and cat["YOY_TEAM"]["enabled"] is False
```

- [ ] **Step 2: 실행해 실패 확인** — `python -m pytest tests/test_validate_candidates.py -v`

- [ ] **Step 3: 구현**

`banned-topics.txt` (줄당 1키워드, `#` 주석 허용 — 안전 규칙 2차 방어선의 결정적 부분):

```
# 사건사고·법적·사생활·건강 — 스펙 4.2 출제 금지 소재
음주
음주운전
폭행
폭력
마약
도박
승부조작
성폭
성추행
불법
재판
기소
입건
구속
사생활
열애
이혼
불륜
병역
학폭
학교폭력
암 투병
수술
```

`validate_candidates.py` 검사 항목(각각 위반 메시지 1개):
1. 필수 필드 존재: `quizId, kind, type, templateId, format, question, options, difficulty, pointReward, status, createdAt, deadlineAt, createdBy`
2. `format ∈ {OX, BINARY, MULTI4}`; `len(options)`가 OX/BINARY=2, MULTI4=4; option `id`가 A부터 순서대로 유니크; 각 `text` 비어있지 않음
3. `kind=="KNOWLEDGE"` → `answer`가 option id 중 하나 + `evidence.source` 비어있지 않음 + `settlement is None`
4. `kind=="PREDICTION"` → `settlement.gameId` 존재 + `settlement.metric ∈ METRICS` + `answer is None and evidence is None`
5. `templateId`가 카탈로그에 존재 + `enabled` + 카탈로그의 `kind`/`format`과 일치
6. `POINTS[difficulty] == pointReward`
7. `question` + 모든 option `text`에 banned 키워드 미포함
`main(argv)`: `--dir`의 `*.json` 전부 검사, 파일별 위반 출력, 총계 요약, 위반 있으면 `sys.exit(1)`. quizId 중복(같은 디렉토리 내)도 검사.

- [ ] **Step 4: 테스트 통과 확인** — `python -m pytest tests/test_validate_candidates.py -v`

- [ ] **Step 5: 커밋**

```bash
git add question-gen/scripts/validate_candidates.py question-gen/config/banned-topics.txt tests/test_validate_candidates.py
git commit -m "feat(question-gen): quiz-candidates 결정적 검증 스크립트"
```

---

### Task 7: 위키 그래프 컴파일 스크립트

**Files:**
- Create: `VictoryFairy_AI/wiki-builder/scripts/compile_graph.py`
- Create: `VictoryFairy_AI/tests/test_compile_graph.py`

**Interfaces:**
- Consumes: 위키 선수 문서 front-matter 계약(스펙 4.1): `name, team, playerUid, kboPlayerId, updatedAt, relations: [{type, target, ref}]` — target은 상대 선수의 kboPlayerId 문자열
- Produces:
  - `parse_front_matter(md_text: str) -> dict | None` (`---` 블록 YAML; 없으면 None)
  - `build_graph(docs: list[dict]) -> dict` — `{"compiledAt", "nodes": [{"id","type","name","team"}], "edges": [{"source","target","type","ref"}]}`. 노드: 선수(`player:{kboPlayerId}`)·팀(`team:{code}`). 엣지: 소속(`player→team, type="소속"`) + relations 그대로(`type` 보존 — `사건연루` 포함해 **컴파일은 하되** 소비 금지는 생성기 규칙)
  - CLI: `python wiki-builder/scripts/compile_graph.py --players-dir W --out graph.json`

- [ ] **Step 1: 실패하는 테스트 작성** — `tests/test_compile_graph.py`

```python
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
```

- [ ] **Step 2: 실행해 실패 확인** — `python -m pytest tests/test_compile_graph.py -v`

- [ ] **Step 3: 구현**

`parse_front_matter`: 텍스트가 `---\n`으로 시작하고 두 번째 `\n---`가 있으면 그 사이를 `yaml.safe_load`; 아니면 None. `kboPlayerId` 없으면 None(스킵 대상). `build_graph`: 문서마다 player 노드 + team 노드(중복 dedup) + 소속 엣지 + relations 엣지(`target`은 `player:{target}`으로 정규화; 상대 문서가 없어도 노드 자동 생성 — `name` 미상은 None). 문서가 진실의 원천, graph는 재컴파일 파생물이라는 스펙 원칙을 독스트링에 명시. `main`: 디렉토리 `*.md` 순회 → front-matter 없는 파일은 경고 출력 후 스킵 → json 기록(`ensure_ascii=False, indent=2`).

- [ ] **Step 4: 테스트 통과 확인** — `python -m pytest tests/test_compile_graph.py -v`

- [ ] **Step 5: 커밋**

```bash
git add wiki-builder/scripts/compile_graph.py tests/test_compile_graph.py
git commit -m "feat(wiki-builder): front-matter → graph.json 컴파일 스크립트"
```

---

### Task 8: 위키 빌더 routine 자산 (문서 3종) + 드라이런

**Files:**
- Create: `VictoryFairy_AI/wiki-builder/templates/player-doc.md`
- Create: `VictoryFairy_AI/wiki-builder/prompts/merge-rules.md`
- Create: `VictoryFairy_AI/wiki-builder/ROUTINE.md`

**Interfaces:**
- Consumes: `validation/bedrock/success/{source}/{date}/*.json`(정제 게시글 — body/topComments), `question-source/player_meme/`(시드), `question-source/player_profile/`(선수 매칭용 명단), Task 7 `compile_graph.py`, Task 9 `all-time-records.yaml`
- Produces: S3 `wiki/players/{kboPlayerId}.md`(front-matter 계약은 Task 7과 동일), `wiki/graph.json`, `wiki/stats/trending.md`, `wiki/stats/all-time-records.md`, 실행 로그 `wiki/_meta/builder-runs/{ISO시각}.json`

- [ ] **Step 1: `templates/player-doc.md` 작성** — 스펙 4.1 문서 구조 그대로:

````markdown
---
name: <선수명>
team: <팀코드>
playerUid: <운영DB players.id — player_profile envelope의 entities.playerUids[0]>
kboPlayerId: "<KBO playerId — player_profile envelope의 payload.playerId>"
updatedAt: <YYYY-MM-DD>
relations: []          # [{ type: 밈공유|커리어교차|라이벌|사건연루, target: "<상대 kboPlayerId>", ref: "<sourceRef>" }]
---
## 프로필 요약
<!-- 팀·포지션 등 확정 사실. 1~3문장 -->

## 별명·밈
<!-- 항목마다: - **별명**: 유래 설명 [^ref1] -->

## 사건사고
<!-- 기록만. 퀴즈 출제 금지 섹션(스펙 4.2). 확정 사실만, 선정적 서술 금지 -->

## 커리어 이력
<!-- 이적·수상 등. 시간순 -->

## 최근 여론
<!-- (커뮤니티 전언) 등급 표기 필수. 최신 실행분으로 교체(누적 아님) -->

[^ref1]: community_post:DCINSIDE:2026-07-12:12345
````

- [ ] **Step 2: `prompts/merge-rules.md` 작성** — 병합 LLM에게 그대로 투입되는 규칙 문서. 반드시 포함할 규칙(스펙 4.1을 절차문으로):

1. **출처 없는 문장 병합 금지** — 모든 신규 항목은 `[^refN]` 각주로 `postExternalId` 기반 sourceRef를 단다. 출처를 못 대면 그 문장은 버린다
2. 커뮤니티발 사실은 항목 끝에 `(커뮤니티 전언)` 표기. 공식 기록(envelope 유래)과 구분
3. 기존 문서의 항목은 삭제하지 않는다 — 모순되는 새 정보는 기존 항목에 "이후 정정: …" 형태로 덧붙인다 (`최근 여론` 섹션만 예외: 매 실행 교체)
4. 비속어·비하 표현은 병합 시 중립 표현으로 정제 (validation을 통과했어도 우회 표기가 샜을 수 있음 — 2차 정제)
5. 사건사고 섹션: 확정된 사실(보도·징계 결과)만, 추측·조롱 금지. 이 섹션은 퀴즈 소스로 쓰이지 않음을 문서 주석에 유지
6. 선수 매칭: 게시글의 이름·별명을 `player_profile` 명단과 대조. 확신 없으면(동명이인 등) 병합하지 않고 스킵 목록에 기록
7. relations 추가 기준: 두 선수가 한 밈/사건/커리어 사실로 명시적으로 함께 언급될 때만. type은 `밈공유|커리어교차|라이벌|사건연루` 중 하나
8. 나무위키 내용을 기억으로 재현하는 것 금지 — 입력으로 준 자료 밖 지식으로 새 "사실"을 만들지 않는다

- [ ] **Step 3: `ROUTINE.md` 작성** — 클라우드 routine의 실행 지침서. 필수 목차와 내용:

- **개요**: 주 1~2회(화·금 06:00 KST 권장), 모델 Sonnet 5, 소요 상한·중단 시 재실행 안전(문서 단위 독립 커밋 — 스펙 §5)
- **사전 조건**: env `S3_BUCKET`, AWS 자격증명(routine 전용 IAM — Task 11), aws CLI, `pip install -r question-gen/requirements.txt`
- **절차** (번호 붙은 실행 단계 — 각 단계에 실제 명령 포함):
  1. 증분 파악: `wiki/_meta/builder-runs/`의 마지막 성공 시각 이후 날짜의 `validation/bedrock/success/{dcinside,fmkorea}/{date}/`를 `aws s3 sync`로 `.work/posts/`에 내려받기 (첫 실행이면 최근 14일)
  2. 참조 데이터 동기화: `question-source/player_profile/` 최신 파티션, `question-source/player_meme/`, 기존 `wiki/players/` → `.work/`
  3. **LLM 병합**: 게시글을 선수별로 그룹핑(merge-rules §6) → 선수마다 기존 문서 + 신규 게시글 + player-doc.md 템플릿을 주고 merge-rules.md 전문을 시스템 규칙으로 병합 → `.work/wiki/players/{kboPlayerId}.md`
  4. **trending.md**: 이번 실행에서 읽은 정제 게시글만으로 급증 키워드·화제 선수 top 10 요약. banned-topics.txt에 걸리는 토픽은 제외(안전 규칙)
  5. **all-time-records.md 렌더**: `question-gen/config/all-time-records.yaml`을 표 형태 md로 결정적 변환(간단해서 LLM 없이 인라인 파이썬)
  6. **그래프 컴파일**: `python wiki-builder/scripts/compile_graph.py --players-dir .work/wiki/players --out .work/wiki/graph.json`
  7. 업로드: `.work/wiki/` → `aws s3 sync .work/wiki/ s3://$S3_BUCKET/wiki/` (문서별 멱등 덮어쓰기), 실행 로그(처리 게시글 수·갱신 문서 수·스킵 목록) 업로드
- **실패 처리**: 부분 실패 시 성공한 문서만 업로드하고 로그에 남긴다(다음 실행이 이어서). graph 컴파일 실패 시 graph.json 업로드 생략(이전 버전 유지 — 스펙 §5)

- [ ] **Step 4: 드라이런 (로컬 `claude -p`)**

```bash
mkdir -p /tmp/wiki-dryrun/posts
aws s3 ls s3://$BUCKET/validation/bedrock/success/dcinside/ | tail -3   # 최근 날짜 확인
aws s3 sync s3://$BUCKET/validation/bedrock/success/dcinside/<최근날짜>/ /tmp/wiki-dryrun/posts/ --exclude "*" --include "*.json"
# 게시글 5개 + player_profile 몇 개를 골라 merge-rules.md·player-doc.md와 함께 투입
claude -p "$(cat wiki-builder/prompts/merge-rules.md) ... (선수 1명 병합 지시)" > /tmp/wiki-dryrun/out.md
```

**육안 검증 체크리스트** (하나라도 실패하면 merge-rules.md 규칙 보강 후 재시도):
- [ ] 산출 md의 모든 별명·밈 항목에 `[^refN]` 각주가 있는가
- [ ] 각주의 sourceRef가 실제 입력 게시글 id와 일치하는가 (창작 각주 없는가)
- [ ] 커뮤니티발 서술에 `(커뮤니티 전언)` 표기가 있는가
- [ ] front-matter가 Task 7 `parse_front_matter`로 파싱되는가 (`python -c` 원라이너로 확인)

- [ ] **Step 5: 커밋**

```bash
git add wiki-builder/
git commit -m "feat(wiki-builder): routine 지침·병합 규칙·문서 템플릿 + 드라이런 결과 반영"
```

---

### Task 9: 역대 기록 시드 — 생성 스크립트 + YAML 초안

**Files:**
- Create: `VictoryFairy_AI/wiki-builder/scripts/gen_all_time_seed.py`
- Create: `VictoryFairy_AI/tests/test_gen_all_time_seed.py`
- Create: `VictoryFairy_AI/question-gen/config/all-time-records.yaml`

**Interfaces:**
- Consumes: Task 2 스냅샷(`history-top-hitter`, `history-player-hitter`, `history-player-pitcher`, `history-team`) — Task 2 Step 6에서 실 S3에 적재됨
- Produces:
  - `seed_from_snapshots(snapshots: dict, top_n=10) -> dict` — `{"asOf", "source": "KBO 공식 기록실", "categories": [{"id","title","sourcePage","entries":[{"rank","name","value"}]}]}`
  - `question-gen/config/all-time-records.yaml` — 위 구조의 **사람 검수 완료본**. 퀴즈 생성기(Task 10)가 `stats.all_time_records` needs의 원천으로 읽음. **수치(value)는 참고용 — 문제 정답으로 사용 금지**(순위·최초달성형만, 스펙 4.1) 주석 포함

- [ ] **Step 1: 실패하는 테스트 작성** — `tests/test_gen_all_time_seed.py`

```python
import gen_all_time_seed as gas

SNAP = {"page": "history-player-hitter", "date": "2026-07-30",
        "tables": [{"headers": ["순위", "선수명", "홈런"],
                    "rows": [["1", "최정", "500"], ["2", "이승엽", "467"]]}]}


def test_seed_from_snapshots():
    seed = gas.seed_from_snapshots({"history-player-hitter": SNAP}, top_n=1)
    assert seed["asOf"] == "2026-07-30"
    cat = seed["categories"][0]
    assert cat["sourcePage"] == "history-player-hitter"
    assert cat["entries"] == [{"rank": 1, "name": "최정", "value": "500"}]


def test_empty_snapshot_yields_no_category():
    assert gas.seed_from_snapshots({})["categories"] == []
```

- [ ] **Step 2: 실행해 실패 확인** — `python -m pytest tests/test_gen_all_time_seed.py -v`

- [ ] **Step 3: 구현**

테이블 → 카테고리 변환: 스냅샷의 각 table에 대해 헤더에서 순위·이름 열 인덱스를 찾고(`순위`/`선수명`/`팀명` 포함 헤더), 나머지 첫 수치성 열을 `value`로. 카테고리 `title`은 `{페이지 한글명} — {수치 열 헤더}` 조합(페이지 한글명 dict: history-player-hitter=통산 타자, history-player-pitcher=통산 투수, history-top-hitter=역대 TOP, history-team=역대 팀 기록). `main(argv)`: `--kbo-dir`(Task 5의 `load_snapshots_dir` 재사용 — `from aggregate_stats import load_snapshots_dir`) `--out` `--top-n`. YAML 헤더 주석에 검수 지침을 박는다:

```yaml
# KBO 역대 기록 시드 — gen_all_time_seed.py 초안 + 사람 검수 완료본.
# ⚠️ 규칙(스펙 4.1): 수치(value)는 참고용 — 퀴즈 정답으로 사용 금지(순위·최초달성형만).
#    논란성 항목(약물 등)은 검수 시 삭제. 나무위키 복사 금지 — 원천은 KBO 공식 기록실.
# 갱신: 시즌 종료 후 1회 + 마일스톤 이벤트 시 수동.
```

- [ ] **Step 4: 테스트 통과 확인** — `python -m pytest tests/test_gen_all_time_seed.py -v`

- [ ] **Step 5: 초안 생성 + 검수 요청**

```bash
mkdir -p /tmp/kbo-snap && aws s3 sync s3://$BUCKET/kbo-records/ /tmp/kbo-snap/
python wiki-builder/scripts/gen_all_time_seed.py --kbo-dir /tmp/kbo-snap \
  --out question-gen/config/all-time-records.yaml --top-n 10
```

생성된 YAML을 열어 **사람 검수**: (1) 이름·순위가 KBO 페이지와 일치하는지 표본 확인, (2) 논란성 항목 제거, (3) 의미 없는 카테고리(파싱 잡음) 삭제. **이 검수는 사용자 확인이 필요한 지점 — 초안을 보여주고 승인받은 뒤 커밋한다.**

- [ ] **Step 6: 커밋**

```bash
git add wiki-builder/scripts/gen_all_time_seed.py tests/test_gen_all_time_seed.py question-gen/config/all-time-records.yaml
git commit -m "feat(wiki-builder): 역대 기록 시드 생성 스크립트 + 검수된 시드 v1"
```

---

### Task 10: 퀴즈 생성기 routine 자산 + 카탈로그 정비 + 드라이런

**Files:**
- Create: `VictoryFairy_AI/question-gen/ROUTINE.md`
- Create: `VictoryFairy_AI/question-gen/prompts/generation-rules.md`
- Create: `VictoryFairy_AI/question-gen/prompts/verification-pass.md`
- Create: `VictoryFairy_AI/question-gen/casebook/good.md`, `VictoryFairy_AI/question-gen/casebook/bad.md`
- Create: `VictoryFairy_AI/question-gen/requirements.txt`
- Modify: `VictoryFairy_AI/question-gen/config/question-templates.yaml`

**Interfaces:**
- Consumes: Task 5 산출물(`season.json/md`, `kbo-official.json/md`), Task 6 검증 CLI, Task 8 위키 산출물, Task 9 시드, `question-source/game_schedule/`(Task 1)
- Produces: S3 `quiz-candidates/{date}/{quizId}.json` (스펙 4.3 계약), 갱신되는 casebook

- [ ] **Step 1: 카탈로그 정비** — `question-templates.yaml` 수정

1. 파일 머리 주석에 **needs 어휘 사전** 추가 — 각 needs 값이 어느 산출물에서 오는지 1:1 매핑:
   ```
   # needs 어휘 → 데이터 위치
   #   envelope.game_result.*  → question-source/game_result/ (최근 7일 파티션)
   #   envelope.player_profile → question-source/player_profile/ (최신 파티션)
   #   schedule.today          → question-source/game_schedule/{오늘}/
   #   schedule.starters       → 위 envelope payload.awayStarter/homeStarter
   #   schedule.lineup         → (미지원 — 경기 전 타자 라인업 소스 없음)
   #   stats.head_to_head|standings|streaks|home_away|monthly|standings_trend|recent_scoring|yoy
   #                           → wiki/stats/season.json
   #   stats.season_leaders|milestone_watch|team_history → wiki/stats/kbo-official.json
   #   stats.all_time_records  → question-gen/config/all-time-records.yaml
   #   stats.trending          → wiki/stats/trending.md
   #   wiki.*                  → wiki/players/{kboPlayerId}.md 해당 섹션
   #   graph                   → wiki/graph.json
   ```
2. `TODAY_CLEANUP`·`POSITION_WHO`: `enabled: false  # 경기 전 타자 라인업 소스 없음 — game_schedule은 선발투수까지만`
3. `PRED_SP_WIN`: `needs: [schedule.starters]`로 교체. **Task 1에서 선발투수 필드가 확인 안 됐으면 이것도 `enabled: false`** (사유 주석)
4. `BACK_NUMBER`·`THROW_BAT`·`BIRTHDAY_TODAY`: 운영 `players` 테이블에 등번호·투타·생년월일이 없음(player_profile envelope은 이름·팀뿐 — exporter.py 주석 참조) → `enabled: false  # players 테이블에 상세정보 없음 — 스키마 확장(dev_be 협의) 후 활성화`
5. `YOY_TEAM`: `enabled: false` 유지 (Task 11 백필 후 활성화)
6. `STANDINGS_CLIMB` needs를 `[stats.standings_trend]`로, `RECENT_VS_EARLY` needs를 `[stats.monthly]`로 확인(이미 그렇게 되어 있으면 무변경), `LAST_MATCHUP`는 season.json `headToHead.last`를 쓰므로 유지

- [ ] **Step 2: `requirements.txt` 작성** (routine은 AI 리포 무거운 requirements.txt를 쓰지 않는다 — torch 금지)

```
PyYAML>=6.0
```

(스크립트는 stdlib+PyYAML만. S3는 aws CLI. boto3 불필요.)

- [ ] **Step 3: `prompts/generation-rules.md` 작성** — 문구 생성 LLM 규칙. 필수 내용:

- 형식: OX/BINARY 보기 2개(O·X는 `[{"id":"A","text":"O"},{"id":"B","text":"X"}]`), MULTI4 보기 4개. 질문 1문장(40자 내 권장), 보기는 즉시 판단 가능하게
- 지식 퀴즈: `evidence.quote`는 투입된 자료의 **원문 그대로**(창작 금지), `evidence.source`는 자료 경로+섹션
- 예측 퀴즈: 위키의 밈·여론은 문구 양념으로만(정답 근거 아님). settlement.metric은 템플릿의 settlement 값 그대로
- 안전: banned-topics.txt 소재 언급 금지, 위키 `사건사고` 섹션·graph `사건연루` 엣지는 입력에 있어도 사용 금지, 비하·편향 없는 중립 문구
- 난이도: 기능명세서 기준(EASY 단순 승패~EXPERT 마니아급). pointReward는 30/50/80/120 고정 매핑
- quizId: `QZ-{YYYYMMDD}-{NNN}` — 같은 날 재실행 시 (templateId, 대상 엔티티) 사전순 정렬로 번호를 결정적으로 부여(멱등 덮어쓰기)
- casebook/good.md·bad.md의 사례를 few-shot으로 참조

- [ ] **Step 4: `prompts/verification-pass.md` 작성** — 같은 잡 안 2차 패스(스펙 4.2 검증 5항목의 LLM 담당분):

1. evidence의 quote가 실제 투입 자료에 존재하는지 대조 — 없으면 폐기
2. 최근 7일 `quiz-candidates/` 목록과 의미 중복(같은 사실을 묻는 문제) 폐기, 같은 템플릿 3연속 이상이면 편중으로 일부 폐기
3. 비하·편향·금지 소재 재검(결정적 키워드 필터가 못 잡는 표현 변형 담당)
4. 재미·자연스러움 채점(1~5) → 4 이상만 통과, 채점 결과를 casebook 갱신에 사용
5. 난이도 적정성 검토(예상 정답률 대비) 후 일일 비율 30/40/20/10에 맞춰 최종 선별

- [ ] **Step 5: casebook 시드 작성** — `good.md`: H2H·MEME_ORIGIN·PRED_WIN_LOSE 예시 각 1문항(완성 JSON + 좋은 이유 1줄), `bad.md`: 주관식 형태·evidence 없는 단정·사건 언급 예시 3건(나쁜 이유 1줄). 헤더에 "routine이 매 실행 자기 채점으로 갱신, 사람은 거부권만(스펙 4.2)" 명시.

- [ ] **Step 6: `ROUTINE.md` 작성** — 매일 08:50 KST(game_schedule export 08:30 이후) 실행. 절차:

1. 동기화: `question-source/{game_result,game_schedule,player_profile}/` 최근 7일 + `wiki/` 전체 + `quiz-candidates/` 최근 7일 → `.work/`
2. ⓪ `python question-gen/scripts/aggregate_stats.py --envelopes-dir .work/game_result --kbo-dir .work/kbo-records --out-dir .work/stats --date {오늘}` → `aws s3 sync .work/stats/ s3://$S3_BUCKET/wiki/stats/` (season·kbo-official 4개 파일 — trending.md·all-time-records.md는 위키 빌더 소관이라 건드리지 않음)
3. ① 템플릿 선택: 오늘 `game_schedule` 매치업 + trending.md + 최근 7일 출제 이력으로 오늘 쓸 템플릿·대상 결정 (enabled: false 제외, 같은 템플릿 연속 방지)
4. ② 데이터 바인딩: 각 템플릿 needs에 해당하는 파일만 로드
5. ③ 문구 생성: generation-rules.md 규칙으로 일일 목표 10문항(넉넉히 15개 생성 후 검증에서 추림)
6. 검증: verification-pass.md → 통과분을 `.work/candidates/{date}/`에 기록 → **`python question-gen/scripts/validate_candidates.py --dir .work/candidates/{date}` (exit 0이어야 업로드)**
7. 업로드: `aws s3 cp --recursive .work/candidates/{date}/ s3://$S3_BUCKET/quiz-candidates/{date}/`
8. casebook 갱신 후 리포 커밋은 하지 않음(클라우드 세션) — 갱신본을 `wiki/_meta/casebook/`에 업로드하고, 주기적으로 사람이 리포에 반영
- 실패 처리: 어느 단계든 실패 시 그날 업로드 생략 + 실패 노티(폴백 퀴즈는 BE/어드민 소관 — 스펙 §5)
- 신규 템플릿 제안: 실행 말미에 오늘 데이터에서 가능한 새 템플릿 1~2개를 `wiki/_meta/template-proposals/{date}.md`로 남긴다 — **카탈로그 반영은 사람 승인 후 수동**(무검수 자동 추가 금지)

- [ ] **Step 7: 드라이런 (어제 날짜 재현 실행)**

로컬에서 ROUTINE.md 절차 1~6을 어제 날짜로 수동 실행(`claude -p`로 3·5·6단계 LLM 부분 수행). 확인:
- [ ] `aggregate_stats.py`가 실 데이터로 4개 파일을 만드는가
- [ ] 생성 문항 중 evidence 없는 문제가 검증 패스에서 실제 폐기되는가 (일부러 근거 없는 문항 1개를 섞어 확인)
- [ ] `validate_candidates.py`가 최종 산출물에 exit 0인가
- [ ] 산출 JSON이 스펙 4.3 계약(필드·options 수·settlement)과 일치하는가

- [ ] **Step 8: 커밋**

```bash
git add question-gen/
git commit -m "feat(question-gen): 퀴즈 생성기 routine 지침·프롬프트·사례집 + 카탈로그 정비"
```

---

### Task 11: 운영 준비 — routine IAM·등록 가이드·2025 백필

**Files:**
- Create: `VictoryFairy_AI/deploy/routines/iam-policy-routines.json`
- Create: `VictoryFairy_AI/deploy/routines/README.md`

**Interfaces:**
- Consumes: Task 1~10 전체 산출물, 운영 DB(SSH 터널 127.0.0.1:3306 — **사용자가 터널을 열어야 함**)
- Produces: routine 전용 IAM 정책, 등록 절차 문서, 2025 game_result envelope 백필(성공 시 `YOY_TEAM` enabled: true)

- [ ] **Step 1: IAM 정책 JSON 작성** — `iam-policy-routines.json` (버킷명은 placeholder 변수 `${BUCKET}`로 두고 README에서 치환 지시):

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Sid": "ReadInputs",
      "Effect": "Allow",
      "Action": ["s3:GetObject"],
      "Resource": [
        "arn:aws:s3:::${BUCKET}/question-source/*",
        "arn:aws:s3:::${BUCKET}/kbo-records/*",
        "arn:aws:s3:::${BUCKET}/validation/bedrock/success/*"
      ]
    },
    {
      "Sid": "ReadWriteOutputs",
      "Effect": "Allow",
      "Action": ["s3:GetObject", "s3:PutObject"],
      "Resource": [
        "arn:aws:s3:::${BUCKET}/wiki/*",
        "arn:aws:s3:::${BUCKET}/quiz-candidates/*"
      ]
    },
    {
      "Sid": "ListPrefixes",
      "Effect": "Allow",
      "Action": ["s3:ListBucket"],
      "Resource": "arn:aws:s3:::${BUCKET}",
      "Condition": {
        "StringLike": {
          "s3:prefix": [
            "question-source/*", "kbo-records/*",
            "validation/bedrock/success/*", "wiki/*", "quiz-candidates/*"
          ]
        }
      }
    }
  ]
}
```

- [ ] **Step 2: `deploy/routines/README.md` 작성** — 내용: (1) IAM 사용자 생성 + 위 정책 attach + access key 발급 절차(aws CLI 명령 포함), (2) Claude Code cloud routine 2개 등록 — 위키 빌더(화·금 06:00 KST, 프롬프트 = `wiki-builder/ROUTINE.md` 실행 지시), 퀴즈 생성기(매일 08:50 KST, `question-gen/ROUTINE.md`), 둘 다 모델 Sonnet 5·env `S3_BUCKET`+IAM 키, (3) 모니터링 — routine 실패 노티 확인 위치, `wiki/_meta/builder-runs/` 로그

- [ ] **Step 3: 2025 시즌 백필 확인·실행** (⚠️ **사용자 개입 필요**: SSH 터널)

```bash
# 1) 사용자에게 터널 오픈 요청 후 운영 DB 확인
docker exec vf-local-mysql mysql -h host.docker.internal -P 3306 -uvf -pvfpass \
  -e "SELECT YEAR(game_date) y, COUNT(*) c FROM victoryfairy.games GROUP BY y"
# 2) 2025 행이 없으면 백필 (KBO 2025 정규시즌 개막 2025-03-22 — 종료일은 실행 전 웹 확인)
cd VictoryFairy_AI/py-collector
COLLECTOR_DB_HOST=127.0.0.1 COLLECTOR_DB_PORT=3306 python -m kbo_collector.run records \
  --from 2025-03-22 --to <2025 정규시즌 종료일>
# 3) 전체 game_result envelope export (date 미지정 = games 전체)
python -m kbo_collector.run export --target game_result
aws s3 ls s3://$BUCKET/question-source/game_result/ --recursive | wc -l   # 2025+2026 경기 수 근사 확인
```

- [ ] **Step 4: `YOY_TEAM` 활성화** — 백필 성공 시 `question-templates.yaml`에서 `enabled: false` 줄 제거 + `aggregate_stats.py` 드라이런으로 yoy가 non-null인지 확인. 백필이 막히면(터널 불가 등) 이 단계는 보류하고 enabled: false 유지 — 보류 사실을 커밋 메시지에 기록

- [ ] **Step 5: 커밋**

```bash
git add deploy/routines/ question-gen/config/question-templates.yaml
git commit -m "feat(deploy): routine IAM 정책·등록 가이드 + 2025 백필 처리"
```

---

## 작업 순서·의존성

```
Task 1 (game_schedule) ─┐
Task 2 (kbo_records) ───┼─→ Task 3 (wiring) ─→ (배포는 소유자)
                        │
Task 2 Step 6 스냅샷 ───┼─→ Task 9 (시드)
                        │
Task 4 → Task 5 (stats) ┼─→ Task 10 (생성기 자산·드라이런) → Task 11 (운영)
Task 6 (검증 스크립트) ─┘         ↑
Task 7 (graph) → Task 8 (위키 빌더 자산·드라이런) ┘
```

Task 1·2·4·6·7은 상호 독립 — 순서 바꿔도 됨. Task 10 드라이런은 8·9의 산출물이 S3에 있어야 완전하다(없으면 위키 needs 템플릿은 드라이런에서 제외).

## 계획 밖 (후속·협의 항목)

- **BE 공유**: quiz-candidates 계약(스펙 4.3) + `templateId` 피드백 루프 제안을 dev_be에 전달 — 문서는 이미 스펙에 있으므로 링크 공유만
- 퀴즈 정산·Quiz DB·어드민·푸시(스펙 §8 비범위)
- `players` 스키마 확장(등번호·투타·생년월일 — BACK_NUMBER 등 3개 템플릿 활성화 전제) — dev_be 협의
- 경기 전 타자 라인업 소스 확보(TODAY_CLEANUP·POSITION_WHO 활성화 전제)
- Terraform apply / Lambda 이미지 배포 — 배포 소유자 수행
