# 질문 생성 소스 아키텍처 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** py-collector를 소스 플러그인 구조(`sources/`)로 개편하고, 통일 envelope(v1) export 계층(`exports/`)과 신규 소스 1호(선수-밈 사전)를 추가한다.

**Architecture:** 수집(collect)과 인계(export)를 분리. 각 소스는 `Source` 클래스 1개 = 파일 1개로 REGISTRY에 등록되며, exporter는 docType별 reader가 MySQL/S3/파일에서 읽어 통일 envelope JSON을 S3 `question-source/`에 적재한다. envelope의 `title`/`content`/`tags`는 결정적 f-string 템플릿으로 렌더링(LLM 금지)한다.

**Tech Stack:** Python 3.12, PyYAML, PyMySQL, boto3, pytest. 스펙: `docs/superpowers/specs/2026-07-15-question-source-architecture-design.md`

## Global Constraints

- 기존 CLI job 이름(`schedule result relay game community all teams registrations records`) 하위호환 유지 — 기존 테스트 70개 전부 그린 유지.
- envelope 필수 필드: `envelopeVersion=1, docId, docType, source, sourceRef, collectedAt, title, content, tags, entities, payload, pii`. `content`가 비면 스키마 위반.
- 렌더링은 결정적 템플릿(f-string)만. LLM/외부 API 호출 금지.
- 엔티티 해소 실패는 버리지 않고 `entities.unresolved`에 `{"kind","name","reason"}` 기록.
- S3 경로: `question-source/{docType}/{yyyy-MM-dd}/{safe(docId)}.json`. 날짜는 export 실행일.
- 새 소스 추가 = 모듈 파일 1개 + `@register` — run.py 수정 없어야 함(성공 기준 1).
- 모든 커밋 메시지 끝: `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`
- 작업 디렉토리: `VitoryFairy_BE/py-collector` (모든 경로는 여기 기준). 테스트 실행: `python3 -m pytest`.

## 파일 구조 (전체 조감)

```
kbo_collector/
  exports/
    __init__.py            # (Task 1) 빈 패키지
    envelope.py            # (Task 1) Envelope dataclass + validate + s3_key
    exporter.py            # (Task 5,6) READERS 레지스트리 + export()
  sources/
    __init__.py            # (Task 3) 소스 모듈 임포트 = 등록 트리거
    base.py                # (Task 2) CollectContext/CollectResult/REGISTRY/@register
    naver_games.py         # (Task 3) 기존 land_game_records 위임
    kbo_roster.py          # (Task 3) 기존 land_registrations 위임
    community_posts.py     # (Task 3) 기존 land_community 위임
    meme_dict.py           # (Task 4) 신규: YAML → envelope
  db.py                    # (Task 4) fetch_all 읽기 헬퍼 추가
  sink.py                  # (Task 6) iter_keys/get_json 추가
  config.py                # (Task 4) memes_file 설정 추가
  run.py                   # (Task 3,5) collect/export job 추가
config/memes.yaml          # (Task 4) 밈 시드
tests/
  test_envelope.py         # (Task 1)
  test_sources_base.py     # (Task 2)
  test_sources_wrappers.py # (Task 3)
  test_meme_dict.py        # (Task 4)
  test_exporter.py         # (Task 5,6)
  test_registry_contract.py# (Task 7) 프로토콜 전수검사
  fixtures/memes/memes.yaml# (Task 4)
```

---

### Task 1: Envelope 스키마 모듈

**Files:**
- Create: `kbo_collector/exports/__init__.py` (빈 파일)
- Create: `kbo_collector/exports/envelope.py`
- Test: `tests/test_envelope.py`

**Interfaces:**
- Consumes: 없음 (순수 모듈)
- Produces: `Envelope` dataclass(`doc_id, doc_type, source, source_ref, collected_at, title, content, tags, entities, payload, pii` 필드, `validate()`, `to_dict()`), `EnvelopeError(Exception)`, `safe_id(doc_id: str) -> str`, `s3_key(doc_type: str, date: str, doc_id: str) -> str`, `empty_entities() -> dict`

- [ ] **Step 1: 실패하는 테스트 작성**

```python
# tests/test_envelope.py
import json

import pytest

from kbo_collector.exports.envelope import (
    Envelope, EnvelopeError, empty_entities, s3_key, safe_id,
)


def _env(**over):
    base = dict(
        doc_id="player_meme:HT:김도영:월관보음",
        doc_type="player_meme",
        source="seed_file",
        source_ref="config/memes.yaml",
        collected_at="2026-07-15T09:00:00Z",
        title="김도영 밈: 월관보음",
        content="KIA 김도영의 팬 별명 '월관보음'.",
        tags=["밈", "별명"],
        entities={"playerUids": [123], "teamCodes": ["HT"], "gameId": None, "unresolved": []},
        payload={"text": "월관보음"},
        pii={"masked": True},
    )
    base.update(over)
    return Envelope(**base)


def test_to_dict_has_version_and_camel_keys():
    d = _env().to_dict()
    assert d["envelopeVersion"] == 1
    assert d["docId"] == "player_meme:HT:김도영:월관보음"
    assert d["docType"] == "player_meme"
    assert d["sourceRef"] == "config/memes.yaml"
    assert d["collectedAt"] == "2026-07-15T09:00:00Z"
    assert d["entities"]["playerUids"] == [123]


def test_to_dict_json_roundtrip_keeps_korean():
    s = json.dumps(_env().to_dict(), ensure_ascii=False)
    assert "월관보음" in s and json.loads(s)["title"] == "김도영 밈: 월관보음"


def test_validate_rejects_empty_content():
    with pytest.raises(EnvelopeError):
        _env(content="").validate()
    with pytest.raises(EnvelopeError):
        _env(content="   ").validate()


def test_validate_rejects_blank_required_strings():
    for field in ("doc_id", "doc_type", "source", "title"):
        with pytest.raises(EnvelopeError):
            _env(**{field: ""}).validate()


def test_validate_ok_passes():
    _env().validate()  # no raise


def test_safe_id_replaces_unsafe_chars_keeps_korean():
    assert safe_id("player_meme:HT:김도영:월관보음") == "player_meme_HT_김도영_월관보음"
    assert safe_id("a/b c?d") == "a_b_c_d"


def test_s3_key_layout():
    key = s3_key("player_meme", "2026-07-15", "player_meme:HT:김도영:월관보음")
    assert key == "question-source/player_meme/2026-07-15/player_meme_HT_김도영_월관보음.json"


def test_empty_entities_shape():
    assert empty_entities() == {"playerUids": [], "teamCodes": [], "gameId": None, "unresolved": []}
```

- [ ] **Step 2: 실패 확인**

Run: `python3 -m pytest tests/test_envelope.py -q`
Expected: FAIL — `ModuleNotFoundError: No module named 'kbo_collector.exports'`

- [ ] **Step 3: 구현**

`kbo_collector/exports/__init__.py` — 빈 파일 생성.

```python
# kbo_collector/exports/envelope.py
"""질문 생성 인계용 통일 envelope(v1).

분석자·질문 생성기는 envelope의 공통 필드(title/content/tags/entities)만
소비한다. payload는 docType별 구조 데이터(선택). 스펙:
docs/superpowers/specs/2026-07-15-question-source-architecture-design.md
"""
import re
from dataclasses import dataclass, field

ENVELOPE_VERSION = 1

_UNSAFE = re.compile(r"[^0-9A-Za-z가-힣._-]+")


class EnvelopeError(ValueError):
    pass


def empty_entities() -> dict:
    return {"playerUids": [], "teamCodes": [], "gameId": None, "unresolved": []}


def safe_id(doc_id: str) -> str:
    """docId를 S3 키 조각으로 안전화(한글 유지, 특수문자 → '_')."""
    return _UNSAFE.sub("_", doc_id).strip("_")


def s3_key(doc_type: str, date: str, doc_id: str) -> str:
    return f"question-source/{doc_type}/{date}/{safe_id(doc_id)}.json"


@dataclass
class Envelope:
    doc_id: str
    doc_type: str
    source: str
    source_ref: str
    collected_at: str
    title: str
    content: str
    tags: list = field(default_factory=list)
    entities: dict = field(default_factory=empty_entities)
    payload: dict = field(default_factory=dict)
    pii: dict = field(default_factory=lambda: {"masked": True})

    def validate(self) -> None:
        for name in ("doc_id", "doc_type", "source", "title", "content"):
            if not (getattr(self, name) or "").strip():
                raise EnvelopeError(f"envelope field '{name}' must be non-empty")

    def to_dict(self) -> dict:
        return {
            "envelopeVersion": ENVELOPE_VERSION,
            "docId": self.doc_id,
            "docType": self.doc_type,
            "source": self.source,
            "sourceRef": self.source_ref,
            "collectedAt": self.collected_at,
            "title": self.title,
            "content": self.content,
            "tags": self.tags,
            "entities": self.entities,
            "payload": self.payload,
            "pii": self.pii,
        }
```

- [ ] **Step 4: 통과 확인**

Run: `python3 -m pytest tests/test_envelope.py -q`
Expected: 8 passed

- [ ] **Step 5: 커밋**

```bash
git add kbo_collector/exports/ tests/test_envelope.py
git commit -m "feat(py-collector): envelope v1 schema for question-source handoff

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: Source 레지스트리 (sources/base.py)

**Files:**
- Create: `kbo_collector/sources/__init__.py` (이 태스크에선 빈 파일 — Task 3에서 임포트 추가)
- Create: `kbo_collector/sources/base.py`
- Test: `tests/test_sources_base.py`

**Interfaces:**
- Consumes: 없음
- Produces: `CollectContext(settings, client=None, db=None, sink=None, date=None)`, `CollectResult(loaded: int = 0, failed: list = [])`, `REGISTRY: dict[str, object]`, `@register` (클래스 데코레이터, 인스턴스 등록, source_id 중복 시 ValueError), `get_source(source_id: str)` (미등록이면 등록 목록 포함 KeyError), `available() -> list[str]`

- [ ] **Step 1: 실패하는 테스트 작성**

```python
# tests/test_sources_base.py
import pytest

from kbo_collector.sources import base


@pytest.fixture(autouse=True)
def clean_registry():
    saved = dict(base.REGISTRY)
    base.REGISTRY.clear()
    yield
    base.REGISTRY.clear()
    base.REGISTRY.update(saved)


def test_register_and_get_source():
    @base.register
    class S:
        source_id = "s1"
        doc_types = ("d1",)

        def collect(self, ctx):
            return base.CollectResult()
    assert base.available() == ["s1"]
    src = base.get_source("s1")
    assert src.source_id == "s1"
    assert src.collect(base.CollectContext(settings=None)).loaded == 0


def test_register_rejects_duplicate_id():
    @base.register
    class A:
        source_id = "dup"
        doc_types = ("d",)

        def collect(self, ctx):
            return base.CollectResult()
    with pytest.raises(ValueError, match="dup"):
        @base.register
        class B:
            source_id = "dup"
            doc_types = ("d",)

            def collect(self, ctx):
                return base.CollectResult()


def test_register_rejects_missing_attrs():
    with pytest.raises(ValueError, match="source_id"):
        @base.register
        class Bad:
            def collect(self, ctx):
                return base.CollectResult()


def test_get_source_unknown_lists_available():
    @base.register
    class S:
        source_id = "known"
        doc_types = ("d",)

        def collect(self, ctx):
            return base.CollectResult()
    with pytest.raises(KeyError, match="known"):
        base.get_source("nope")


def test_sources_for_doc_type():
    @base.register
    class S:
        source_id = "s1"
        doc_types = ("player_meme",)

        def collect(self, ctx):
            return base.CollectResult()
    assert base.sources_for("player_meme")[0].source_id == "s1"
    assert base.sources_for("unknown") == []
```

- [ ] **Step 2: 실패 확인**

Run: `python3 -m pytest tests/test_sources_base.py -q`
Expected: FAIL — `ModuleNotFoundError: No module named 'kbo_collector.sources'`

- [ ] **Step 3: 구현**

`kbo_collector/sources/__init__.py` — 빈 파일 생성.

```python
# kbo_collector/sources/base.py
"""소스 플러그인 레지스트리.

새 소스 추가 = 이 패키지에 모듈 1개 + @register 1줄.
run.py는 REGISTRY 조회만 하므로 소스가 늘어도 변경 없음(스펙 성공 기준 1).
"""
from dataclasses import dataclass, field


@dataclass
class CollectContext:
    """소스 실행에 필요할 수 있는 것들. 소스는 필요한 것만 꺼내 쓴다."""
    settings: object
    client: object = None   # httpx.Client (크롤 소스용)
    db: object = None       # DbSink (MySQL 적재/조회용)
    sink: object = None     # S3RawSink (S3 적재용)
    date: str = None        # 'YYYY-MM-DD' (날짜 파라미터를 받는 소스용)


@dataclass
class CollectResult:
    loaded: int = 0
    failed: list = field(default_factory=list)


REGISTRY: dict = {}


def register(cls):
    """Source 클래스를 인스턴스화해 REGISTRY에 등록하는 데코레이터."""
    for attr in ("source_id", "doc_types", "collect"):
        if not hasattr(cls, attr):
            raise ValueError(f"source class {cls.__name__} missing '{attr}'"
                             " (need source_id, doc_types, collect)")
    instance = cls()
    if instance.source_id in REGISTRY:
        raise ValueError(f"duplicate source_id '{instance.source_id}'")
    REGISTRY[instance.source_id] = instance
    return cls


def available() -> list:
    return sorted(REGISTRY)


def get_source(source_id: str):
    if source_id not in REGISTRY:
        raise KeyError(f"unknown source '{source_id}' (available: {', '.join(available())})")
    return REGISTRY[source_id]


def sources_for(doc_type: str) -> list:
    """해당 docType을 방출하는 소스들 (export 위임용)."""
    return [s for s in REGISTRY.values() if doc_type in s.doc_types]
```

- [ ] **Step 4: 통과 확인**

Run: `python3 -m pytest tests/test_sources_base.py -q`
Expected: 5 passed

- [ ] **Step 5: 커밋**

```bash
git add kbo_collector/sources/ tests/test_sources_base.py
git commit -m "feat(py-collector): source plugin registry (CollectContext/register/get_source)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: 기존 3개 잡을 소스로 등록 + `collect` CLI

기존 `land_game_records_range`/`land_registrations`/`land_community`를 얇은 wrapper 소스로 감싼다. 함수 본체는 run.py에 그대로 두고(기존 테스트 보호) wrapper가 **함수 내부에서 지연 임포트**한다(순환 임포트 방지).

**Files:**
- Create: `kbo_collector/sources/naver_games.py`
- Create: `kbo_collector/sources/kbo_roster.py`
- Create: `kbo_collector/sources/community_posts.py`
- Modify: `kbo_collector/sources/__init__.py` (등록 트리거 임포트)
- Modify: `kbo_collector/run.py` (`collect` job + `--target` 인자)
- Test: `tests/test_sources_wrappers.py`

**Interfaces:**
- Consumes: Task 2의 `base.register/CollectContext/CollectResult`, run.py의 `land_game_records_range(start, end, *, settings, db, client, sleep)` → `{"loaded": [...], "failed": [...]}`, `land_registrations(date=None, *, settings, db, client, ...)` → `list[str]`, `land_community(date, *, settings, sink, client, journal, ...)` → `int`
- Produces: REGISTRY에 `naver_games`(doc_types=`("game_result",)`), `kbo_roster`(`("player_profile",)`), `community_posts`(`("community_post",)`) 등록. CLI `python -m kbo_collector.run collect --target <source_id> [--date D]`

- [ ] **Step 1: 실패하는 테스트 작성**

```python
# tests/test_sources_wrappers.py
from unittest.mock import patch

import kbo_collector.sources  # noqa: F401  (등록 트리거)
from kbo_collector.sources import base


def test_builtin_sources_registered():
    assert {"naver_games", "kbo_roster", "community_posts"} <= set(base.REGISTRY)


def test_doc_types_mapping():
    assert base.REGISTRY["naver_games"].doc_types == ("game_result",)
    assert base.REGISTRY["kbo_roster"].doc_types == ("player_profile",)
    assert base.REGISTRY["community_posts"].doc_types == ("community_post",)


def test_naver_games_delegates_to_land_range():
    ctx = base.CollectContext(settings="S", client="C", db="D", date="2026-07-01")
    with patch("kbo_collector.run.land_game_records_range",
               return_value={"loaded": ["g1", "g2"], "failed": ["g3"]}) as m:
        res = base.get_source("naver_games").collect(ctx)
    m.assert_called_once_with("2026-07-01", "2026-07-01",
                              settings="S", db="D", client="C")
    assert res.loaded == 2 and res.failed == ["g3"]


def test_kbo_roster_delegates():
    ctx = base.CollectContext(settings="S", client="C", db="D", date=None)
    with patch("kbo_collector.run.land_registrations",
               return_value=["OB", "LG"]) as m:
        res = base.get_source("kbo_roster").collect(ctx)
    m.assert_called_once_with(None, settings="S", db="D", client="C")
    assert res.loaded == 2 and res.failed == []
```

- [ ] **Step 2: 실패 확인**

Run: `python3 -m pytest tests/test_sources_wrappers.py -q`
Expected: FAIL — `naver_games` not in REGISTRY (모듈 없음)

- [ ] **Step 3: 소스 wrapper 3개 구현**

```python
# kbo_collector/sources/naver_games.py
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
```

```python
# kbo_collector/sources/kbo_roster.py
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
```

```python
# kbo_collector/sources/community_posts.py
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
        date = ctx.date or run._today()
        journal = Journal("community", date, uuid.uuid4().hex[:8],
                          ctx.settings.journal_dir)
        landed = run.land_community(date, settings=ctx.settings, sink=ctx.sink,
                                    client=ctx.client, journal=journal)
        return CollectResult(loaded=landed)
```

`kbo_collector/sources/__init__.py`를 다음으로 교체:

```python
# 소스 모듈 임포트 = @register 실행 = REGISTRY 등록.
from . import community_posts, kbo_roster, naver_games  # noqa: F401
```

- [ ] **Step 4: 통과 확인**

Run: `python3 -m pytest tests/test_sources_wrappers.py -q`
Expected: 4 passed

- [ ] **Step 5: run.py에 `collect` job 연결**

`run.py`의 `main()`에서 ① choices에 `"collect"` 추가, ② `--target` 인자 추가, ③ 디스패치 분기 추가.

`parser.add_argument("job", choices=[...])` 줄을 다음으로 수정:

```python
    parser.add_argument("job", choices=["schedule", "result", "relay", "game",
                                        "community", "all", "teams", "registrations",
                                        "records", "collect", "export"])
    parser.add_argument("--target", default=None,
                        help="collect: source_id / export: docType")
```

(`export` choice는 Task 5에서 구현하지만 인자는 여기서 함께 추가한다.)

`if args.job in ("teams", "registrations", "records"):` 분기 **앞에** 다음 분기 삽입:

```python
    if args.job == "collect":
        from .db import DbSink
        from .sources import base as source_base
        src = source_base.get_source(args.target or "")
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
            db.close()
        return 0
```

- [ ] **Step 6: 전체 테스트로 하위호환 확인**

Run: `python3 -m pytest -q`
Expected: 기존 70개 + 신규 전부 passed, 0 failed

- [ ] **Step 7: 커밋**

```bash
git add kbo_collector/sources/ kbo_collector/run.py tests/test_sources_wrappers.py
git commit -m "feat(py-collector): wrap existing jobs as plugin sources + collect CLI

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: 신규 소스 1호 — meme_dict (선수-밈 사전)

**Files:**
- Create: `config/memes.yaml` (실사용 시드 — 실제 선수 2명 이상)
- Create: `kbo_collector/sources/meme_dict.py`
- Modify: `kbo_collector/sources/__init__.py` (임포트 1줄 추가)
- Modify: `kbo_collector/config.py` (`memes_file` 설정)
- Modify: `kbo_collector/db.py` (`fetch_all` 읽기 헬퍼)
- Test: `tests/test_meme_dict.py`, `tests/fixtures/memes/memes.yaml`

**Interfaces:**
- Consumes: Task 1 `Envelope/s3_key/empty_entities`, Task 2 `base.register/CollectContext/CollectResult`, `DbSink`, `S3RawSink.put_json(key, obj, metadata=None)`
- Produces: REGISTRY에 `meme_dict`(doc_types=`("player_meme",)`), `DbSink.fetch_all(sql, params=()) -> list[tuple]`, `Settings.memes_file`(기본 `"config/memes.yaml"`), `resolve_player_uid(db, name, team_code) -> tuple[int|None, str|None]` (uid 또는 실패 사유 `"not-found"|"duplicate-name"`)

- [ ] **Step 1: 픽스처 YAML 작성**

```yaml
# tests/fixtures/memes/memes.yaml
- player: { name: 김도영, team: HT }
  memes:
    - text: "월관보음"
      origin: "월요일 관중석에서 보기 아까운 선수라는 팬 별명"
      tags: [별명]
- player: { name: 없는선수, team: LG }
  memes:
    - text: "유령밈"
      origin: "매칭 실패 케이스"
      tags: []
```

- [ ] **Step 2: 실패하는 테스트 작성**

```python
# tests/test_meme_dict.py
from pathlib import Path
from types import SimpleNamespace

import kbo_collector.sources  # noqa: F401
from kbo_collector.sources import base
from kbo_collector.sources.meme_dict import resolve_player_uid

FIXTURE = Path(__file__).parent / "fixtures" / "memes" / "memes.yaml"


class FakeDb:
    """name+team → uid 매핑을 흉내내는 fetch_all 스텁."""
    def __init__(self, rows_by_name):
        self.rows_by_name = rows_by_name

    def fetch_all(self, sql, params=()):
        return self.rows_by_name.get(params[0], [])


class FakeSink:
    def __init__(self):
        self.puts = []

    def put_json(self, key, obj, metadata=None):
        self.puts.append((key, obj))
        return 1


def test_resolve_uid_unique_found():
    db = FakeDb({"김도영": [(123,)]})
    assert resolve_player_uid(db, "김도영", "HT") == (123, None)


def test_resolve_uid_not_found_and_duplicate():
    db = FakeDb({"김도영": [], "이철수": [(1,), (2,)]})
    assert resolve_player_uid(db, "김도영", "HT") == (None, "not-found")
    assert resolve_player_uid(db, "이철수", "LG") == (None, "duplicate-name")


def test_collect_emits_envelopes_with_resolution():
    db = FakeDb({"김도영": [(123,)], "없는선수": []})
    sink = FakeSink()
    settings = SimpleNamespace(memes_file=str(FIXTURE))
    ctx = base.CollectContext(settings=settings, db=db, sink=sink)
    res = base.get_source("meme_dict").collect(ctx)

    assert res.loaded == 2 and res.failed == []
    by_id = {obj["docId"]: obj for _, obj in sink.puts}

    ok = by_id["player_meme:HT:김도영:월관보음"]
    assert ok["docType"] == "player_meme"
    assert ok["entities"]["playerUids"] == [123]
    assert ok["entities"]["teamCodes"] == ["HT"]
    assert "월관보음" in ok["content"] and "김도영" in ok["content"]
    assert "밈" in ok["tags"] and "별명" in ok["tags"]

    ghost = by_id["player_meme:LG:없는선수:유령밈"]
    assert ghost["entities"]["playerUids"] == []
    assert ghost["entities"]["unresolved"] == [
        {"kind": "player", "name": "없는선수", "reason": "not-found"}]


def test_s3_keys_under_question_source():
    db = FakeDb({"김도영": [(123,)], "없는선수": []})
    sink = FakeSink()
    settings = SimpleNamespace(memes_file=str(FIXTURE))
    base.get_source("meme_dict").collect(
        base.CollectContext(settings=settings, db=db, sink=sink))
    assert all(k.startswith("question-source/player_meme/") for k, _ in sink.puts)
```

- [ ] **Step 3: 실패 확인**

Run: `python3 -m pytest tests/test_meme_dict.py -q`
Expected: FAIL — `No module named 'kbo_collector.sources.meme_dict'`

- [ ] **Step 4: DbSink.fetch_all + Settings.memes_file 추가**

`kbo_collector/db.py`의 `DbSink`에 메서드 추가 (`close` 위):

```python
    def fetch_all(self, sql, params=()) -> list:
        """읽기 헬퍼 (exporter·엔티티 해소용)."""
        with self._conn.cursor() as cur:
            cur.execute(sql, params)
            return list(cur.fetchall())
```

`kbo_collector/config.py`의 `# --- KBO source (선수 로스터) ---` 블록 아래에 추가:

```python
    # --- question-source (질문 생성 인계) ---
    memes_file: str = Field(default="config/memes.yaml", validation_alias="COLLECTOR_MEMES_FILE")
```

- [ ] **Step 5: meme_dict 소스 구현**

```python
# kbo_collector/sources/meme_dict.py
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
        for entry in entries:
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
        return CollectResult(loaded=loaded, failed=failed)
```

`kbo_collector/sources/__init__.py`를 다음으로 교체:

```python
# 소스 모듈 임포트 = @register 실행 = REGISTRY 등록.
from . import community_posts, kbo_roster, meme_dict, naver_games  # noqa: F401
```

- [ ] **Step 6: 통과 확인**

Run: `python3 -m pytest tests/test_meme_dict.py -q`
Expected: 4 passed

- [ ] **Step 7: 실사용 시드 파일 작성**

```yaml
# config/memes.yaml
# 선수-밈 사전 (사람이 직접 관리). 형식:
#   - player: { name: <등록명>, team: <팀코드 OB LG SS KT WO HT HH NC LT SK> }
#     memes:
#       - text: "<밈/별명>"
#         origin: "<유래 한 줄>"
#         tags: [별명]        # 선택
- player: { name: 김도영, team: HT }
  memes:
    - text: "KIA의 젊은 핵심"
      origin: "2024년 이후 KIA 타선의 중심으로 자리잡은 내야수라는 평가"
      tags: [별명]
- player: { name: 오스틴, team: LG }
  memes:
    - text: "오카도"
      origin: "오스틴+아보카도 합성 팬 별명"
      tags: [별명]
```

- [ ] **Step 8: 전체 테스트 + 커밋**

Run: `python3 -m pytest -q` → 전부 passed

```bash
git add kbo_collector/sources/ kbo_collector/db.py kbo_collector/config.py \
        config/memes.yaml tests/test_meme_dict.py tests/fixtures/memes/
git commit -m "feat(py-collector): meme_dict source — player-meme seed YAML to envelopes

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: Exporter 골격 + game_result export + `export` CLI

**Files:**
- Create: `kbo_collector/exports/exporter.py`
- Modify: `kbo_collector/run.py` (`export` job 분기 — choices/`--target`은 Task 3에서 추가됨)
- Test: `tests/test_exporter.py`

**Interfaces:**
- Consumes: Task 1 `Envelope/s3_key`, Task 2 `base.sources_for`, Task 4 `DbSink.fetch_all`
- Produces: `READERS: dict[str, callable]`, `@reader(doc_type)` 데코레이터, `export(doc_type, *, settings, db, sink, date=None) -> int` (reader 없고 소스가 있으면 collect 위임), `read_game_results(db, date=None) -> Iterator[Envelope]`

- [ ] **Step 1: 실패하는 테스트 작성**

```python
# tests/test_exporter.py
from types import SimpleNamespace

import pytest

import kbo_collector.sources  # noqa: F401
from kbo_collector.exports import exporter


class FakeDb:
    def __init__(self, results_by_sql_frag):
        self.frags = results_by_sql_frag

    def fetch_all(self, sql, params=()):
        for frag, rows in self.frags.items():
            if frag in sql:
                return rows
        return []


class FakeSink:
    def __init__(self):
        self.puts = []

    def put_json(self, key, obj, metadata=None):
        self.puts.append((key, obj))
        return 1


GAME_ROW = ("20260328HTSK02026", "2026-03-28", "regular", "문학", "14:00",
            "HT", "KIA", "SK", "SSG", 6, 7, "home")
DECISIONS = [("W", "김민"), ("L", "조상우"), ("S", None)]


def _db():
    return FakeDb({"FROM games": [GAME_ROW],
                   "FROM game_pitching": [(d, n) for d, n in DECISIONS if n]})


def test_read_game_results_renders_content():
    envs = list(exporter.read_game_results(_db(), date="2026-03-28"))
    assert len(envs) == 1
    e = envs[0]
    assert e.doc_type == "game_result"
    assert e.doc_id == "game_result:20260328HTSK02026"
    assert e.entities["gameId"] == "20260328HTSK02026"
    assert e.entities["teamCodes"] == ["HT", "SK"]
    for frag in ("2026-03-28", "KIA", "SSG", "6", "7", "김민", "조상우"):
        assert frag in e.content
    assert "박스스코어" in e.tags


def test_export_writes_envelopes_to_s3():
    sink = FakeSink()
    n = exporter.export("game_result", settings=SimpleNamespace(), db=_db(),
                        sink=sink, date="2026-03-28")
    assert n == 1
    key, obj = sink.puts[0]
    assert key.startswith("question-source/game_result/")
    assert obj["envelopeVersion"] == 1 and obj["content"]


def test_export_unknown_doc_type_raises():
    with pytest.raises(KeyError, match="game_result"):
        exporter.export("no_such_doc", settings=None, db=None, sink=None)


def test_export_delegates_to_source_when_no_reader():
    # player_meme은 reader가 없고 meme_dict 소스가 doc_type을 소유 → collect 위임
    called = {}

    class StubSource:
        source_id = "meme_dict"
        doc_types = ("player_meme",)

        def collect(self, ctx):
            called["ctx"] = ctx
            return SimpleNamespace(loaded=3, failed=[])

    from kbo_collector.sources import base
    saved = dict(base.REGISTRY)
    base.REGISTRY.clear()
    base.REGISTRY["meme_dict"] = StubSource()
    try:
        n = exporter.export("player_meme", settings="S", db="D", sink="K")
        assert n == 3 and called["ctx"].db == "D"
    finally:
        base.REGISTRY.clear()
        base.REGISTRY.update(saved)
```

- [ ] **Step 2: 실패 확인**

Run: `python3 -m pytest tests/test_exporter.py -q`
Expected: FAIL — `No module named 'kbo_collector.exports.exporter'`

- [ ] **Step 3: exporter 구현**

```python
# kbo_collector/exports/exporter.py
"""docType별 reader → envelope → S3 question-source/ 적재.

reader 추가 = 함수 1개 + @reader 1줄. reader가 없는 docType은
그 docType을 방출하는 소스의 collect로 위임한다(예: player_meme → meme_dict).
"""
from datetime import datetime, timezone

from ..sources import base as source_base
from .envelope import Envelope, empty_entities, s3_key

READERS: dict = {}


def reader(doc_type: str):
    def deco(fn):
        READERS[doc_type] = fn
        return fn
    return deco


def _now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def export(doc_type: str, *, settings, db, sink, date=None) -> int:
    """docType의 envelope들을 S3에 적재하고 건수 반환."""
    if doc_type in READERS:
        today = _now()[:10]
        count = 0
        for env in READERS[doc_type](db, date=date):
            env.validate()
            sink.put_json(s3_key(env.doc_type, today, env.doc_id), env.to_dict())
            count += 1
        return count
    owners = source_base.sources_for(doc_type)
    if owners:  # collect가 곧 export인 소스 (스펙 3-6)
        ctx = source_base.CollectContext(settings=settings, db=db, sink=sink, date=date)
        return owners[0].collect(ctx).loaded
    known = sorted(set(READERS) | {d for s in source_base.REGISTRY.values()
                                   for d in s.doc_types})
    raise KeyError(f"unknown docType '{doc_type}' (known: {', '.join(known)})")


_GAMES_SQL = (
    "SELECT g.game_id, g.game_date, g.game_type, g.stadium, g.start_time, "
    " g.away_team_code, ta.name, g.home_team_code, th.name, "
    " g.away_score, g.home_score, g.winner "
    "FROM games g JOIN teams ta ON ta.team_code=g.away_team_code "
    " JOIN teams th ON th.team_code=g.home_team_code "
    "WHERE (%s IS NULL OR g.game_date=%s)"
)
_DECISION_SQL = (
    "SELECT gp.decision, p.name FROM game_pitching gp "
    "JOIN game_players p ON p.player_uid=gp.player_uid "
    "WHERE gp.game_id=%s AND gp.decision IS NOT NULL"
)


@reader("game_result")
def read_game_results(db, date=None):
    now = _now()
    for (gid, gdate, gtype, stadium, gtime, a_code, a_name, h_code, h_name,
         a_score, h_score, winner) in db.fetch_all(_GAMES_SQL, (date, date)):
        decisions = dict(db.fetch_all(_DECISION_SQL, (gid,)))
        win_name = decisions.get("W")
        lose_name = decisions.get("L")
        if winner == "draw":
            outcome = "무승부로 끝났다"
        else:
            winner_name = h_name if winner == "home" else a_name
            outcome = f"{winner_name}의 승리로 끝났다"
        parts = [f"{gdate} {stadium}에서 열린 {a_name} 대 {h_name} 경기는 "
                 f"{a_score}:{h_score}, {outcome}."]
        if win_name:
            parts.append(f"승리투수 {win_name}.")
        if lose_name:
            parts.append(f"패전투수 {lose_name}.")
        if decisions.get("S"):
            parts.append(f"세이브 {decisions['S']}.")
        entities = empty_entities()
        entities["gameId"] = gid
        entities["teamCodes"] = [a_code, h_code]
        tags = ["박스스코어", "경기결과"]
        if gtype == "preseason":
            tags.append("시범경기")
        if winner == "draw":
            tags.append("무승부")
        yield Envelope(
            doc_id=f"game_result:{gid}",
            doc_type="game_result",
            source="naver",
            source_ref=f"mysql://games/{gid}",
            collected_at=now,
            title=f"{gdate} {a_name} {a_score}:{h_score} {h_name}",
            content=" ".join(parts),
            tags=tags,
            entities=entities,
            payload={"gameId": gid, "awayScore": a_score, "homeScore": h_score,
                     "winner": winner, "stadium": stadium, "startTime": gtime},
        )
```

- [ ] **Step 4: 통과 확인**

Run: `python3 -m pytest tests/test_exporter.py -q`
Expected: 4 passed

- [ ] **Step 5: run.py에 `export` 분기 추가**

Task 3에서 넣은 `if args.job == "collect":` 분기 **바로 아래에** 삽입:

```python
    if args.job == "export":
        from .db import DbSink
        from .exports import exporter
        db = DbSink(settings)
        try:
            n = exporter.export(args.target or "", settings=settings, db=db,
                                sink=S3RawSink(settings), date=args.date)
            logging.getLogger("export").info("%s: exported=%d", args.target, n)
        finally:
            db.close()
        return 0
```

- [ ] **Step 6: 전체 테스트 + 커밋**

Run: `python3 -m pytest -q` → 전부 passed

```bash
git add kbo_collector/exports/exporter.py kbo_collector/run.py tests/test_exporter.py
git commit -m "feat(py-collector): exporter with game_result reader + export CLI

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 6: player_profile + community_post export

**Files:**
- Modify: `kbo_collector/exports/exporter.py` (reader 2개 추가)
- Modify: `kbo_collector/sink.py` (`iter_keys`, `get_json` 추가)
- Test: `tests/test_exporter.py` (테스트 추가)

**Interfaces:**
- Consumes: Task 5의 `@reader`/`Envelope`, `S3RawSink`
- Produces: `read_player_profiles(db, date=None)`, `read_community_posts(db, date=None, sink=None)` — community reader는 sink 필요(아래 시그니처 변경 참고), `S3RawSink.iter_keys(prefix) -> Iterator[str]`, `S3RawSink.get_json(key) -> dict`

**시그니처 변경**: community_post reader는 S3에서 읽으므로 reader 호출부를 `fn(db, date=date, sink=sink)`가 되도록 exporter.export를 수정한다 — 기존 reader들이 깨지지 않게 `**kwargs`가 아니라 **모든 reader가 `(db, date=None, sink=None)` 시그니처를 갖도록 통일**한다(Task 5의 `read_game_results`도 인자 추가).

- [ ] **Step 1: 실패하는 테스트 추가** (tests/test_exporter.py에 append)

```python
PLAYER_ROW = ("53554", "김민석", "LT", "롯데", "10", "외야수", "우투좌타",
              "2004-02-01", 1, 123)


def test_read_player_profiles_renders_content():
    db = FakeDb({"FROM players": [PLAYER_ROW]})
    envs = list(exporter.read_player_profiles(db))
    e = envs[0]
    assert e.doc_id == "player_profile:53554"
    assert e.entities["playerUids"] == [123]
    assert e.entities["teamCodes"] == ["LT"]
    for frag in ("김민석", "롯데", "외야수", "10", "우투좌타"):
        assert frag in e.content
    assert "프로필" in e.tags


def test_read_player_profiles_without_uid():
    row = PLAYER_ROW[:-1] + (None,)
    db = FakeDb({"FROM players": [row]})
    e = list(exporter.read_player_profiles(db))[0]
    assert e.entities["playerUids"] == []
    assert e.entities["unresolved"] == [
        {"kind": "player", "name": "김민석", "reason": "no-game-uid"}]


class FakeS3Sink(FakeSink):
    def __init__(self, docs):
        super().__init__()
        self.docs = docs  # key -> RawPost dict

    def iter_keys(self, prefix):
        return iter([k for k in self.docs if k.startswith(prefix)])

    def get_json(self, key):
        return self.docs[key]


RAWPOST = {"schemaVersion": 2, "source": "DCINSIDE", "postExternalId": "111",
           "sourceUrl": "https://gall.dcinside.com/...&no=111",
           "title": "오늘 경기 미쳤다", "body": "9회말 역전 실화냐",
           "engagement": {"viewCount": 10, "likeCount": 2,
                          "dislikeCount": 0, "commentCount": 1},
           "topComments": [], "team": "DOOSAN",
           "crawledAt": "2026-07-11T10:00:00+00:00", "crawlerVersion": "community-v3"}


def test_read_community_posts_repackages():
    sink = FakeS3Sink({"community/dcinside/2026-07-11/111.json": RAWPOST})
    envs = list(exporter.read_community_posts(None, date="2026-07-11", sink=sink))
    e = envs[0]
    assert e.doc_id == "community_post:DCINSIDE:111"
    assert e.title == "오늘 경기 미쳤다"
    assert e.content.startswith("오늘 경기 미쳤다")   # 제목+본문 통과
    assert "9회말 역전 실화냐" in e.content
    assert {"커뮤니티", "여론"} <= set(e.tags) and "DOOSAN" in e.tags
    assert e.pii == {"masked": True}


def test_read_community_posts_requires_date():
    with pytest.raises(ValueError, match="date"):
        list(exporter.read_community_posts(None, sink=FakeS3Sink({})))
```

- [ ] **Step 2: 실패 확인**

Run: `python3 -m pytest tests/test_exporter.py -q`
Expected: 신규 4개 FAIL (`read_player_profiles` 없음)

- [ ] **Step 3: sink에 읽기 메서드 추가**

`kbo_collector/sink.py`의 `S3RawSink`에 추가 (`dead_letter` 위):

```python
    def iter_keys(self, prefix: str):
        """prefix 아래 모든 오브젝트 키 (pagination 처리)."""
        paginator = self.client.get_paginator("list_objects_v2")
        for page in paginator.paginate(Bucket=self.bucket, Prefix=prefix):
            for item in page.get("Contents", []):
                yield item["Key"]

    def get_json(self, key: str) -> dict:
        body = self.client.get_object(Bucket=self.bucket, Key=key)["Body"].read()
        return json.loads(body)
```

- [ ] **Step 4: reader 2개 구현 + 시그니처 통일**

`exporter.py` 수정. ① `export()`의 reader 호출을 `READERS[doc_type](db, date=date, sink=sink)`로 변경, ② `read_game_results(db, date=None, sink=None)`로 시그니처 통일, ③ 아래 두 reader 추가:

```python
_PLAYERS_SQL = (
    "SELECT p.player_id, p.name, p.team_code, t.name, p.back_number, "
    " p.position, p.throw_bat, p.birth_date, p.is_first_team, gp.player_uid "
    "FROM players p JOIN teams t ON t.team_code=p.team_code "
    "LEFT JOIN game_players gp ON gp.kbo_player_id=p.player_id"
)


@reader("player_profile")
def read_player_profiles(db, date=None, sink=None):
    now = _now()
    for (pid, name, team_code, team_name, back_no, position, throw_bat,
         birth, is_first, uid) in db.fetch_all(_PLAYERS_SQL):
        entities = empty_entities()
        entities["teamCodes"] = [team_code]
        if uid is not None:
            entities["playerUids"] = [uid]
        else:
            entities["unresolved"] = [
                {"kind": "player", "name": name, "reason": "no-game-uid"}]
        first = "1군 등록" if is_first else "1군 미등록"
        content = (f"{team_name} {name}은(는) {position}로, 등번호 {back_no}번, "
                   f"{throw_bat}이다. 생년월일 {birth}. 현재 {first} 상태다.")
        yield Envelope(
            doc_id=f"player_profile:{pid}",
            doc_type="player_profile",
            source="kbo_official",
            source_ref=f"mysql://players/{pid}",
            collected_at=now,
            title=f"{team_name} {name} 프로필",
            content=content,
            tags=["프로필", "선수"],
            entities=entities,
            payload={"playerId": pid, "backNumber": back_no,
                     "position": position, "throwBat": throw_bat,
                     "isFirstTeam": bool(is_first)},
        )


@reader("community_post")
def read_community_posts(db, date=None, sink=None):
    """S3 RawPost 재포장(본문 재크롤 없음). date 필수."""
    if not date:
        raise ValueError("community_post export requires --date")
    now = _now()
    for source_dir in ("dcinside", "fmkorea"):
        for key in sink.iter_keys(f"community/{source_dir}/{date}/"):
            post = sink.get_json(key)
            entities = empty_entities()
            team = post.get("team")
            tags = ["커뮤니티", "여론"]
            if team:
                tags.append(team)
            title = post.get("title") or "(제목 없음)"
            body = post.get("body") or ""
            yield Envelope(
                doc_id=f"community_post:{post['source']}:{post['postExternalId']}",
                doc_type="community_post",
                source=post["source"].lower(),
                source_ref=post.get("sourceUrl") or key,
                collected_at=now,
                title=title,
                content=f"{title}\n{body}".strip(),   # 원문 통과, 요약 없음
                tags=tags,
                entities=entities,
                payload={"engagement": post.get("engagement"),
                         "crawledAt": post.get("crawledAt")},
                pii={"masked": True},
            )
```

- [ ] **Step 5: 통과 확인**

Run: `python3 -m pytest tests/test_exporter.py -q`
Expected: 8 passed (기존 4 + 신규 4)

- [ ] **Step 6: 전체 테스트 + 커밋**

Run: `python3 -m pytest -q` → 전부 passed

```bash
git add kbo_collector/exports/exporter.py kbo_collector/sink.py tests/test_exporter.py
git commit -m "feat(py-collector): player_profile + community_post envelope readers

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 7: 레지스트리 전수검사 + 실데이터 검증 + 문서

**Files:**
- Test: `tests/test_registry_contract.py`
- Modify: `docs/current-crawl-overview.md` (§6에 question-source 계층 요약 추가)

**Interfaces:**
- Consumes: 전체 REGISTRY·READERS

- [ ] **Step 1: 전수검사 테스트 작성**

```python
# tests/test_registry_contract.py
"""REGISTRY의 모든 소스가 프로토콜을 준수하는지 전수 검사 (스펙 §5-1)."""
import kbo_collector.sources  # noqa: F401
from kbo_collector.exports import exporter
from kbo_collector.sources import base


def test_every_source_satisfies_protocol():
    assert base.REGISTRY, "no sources registered"
    for sid, src in base.REGISTRY.items():
        assert isinstance(src.source_id, str) and src.source_id == sid
        assert isinstance(src.doc_types, tuple) and src.doc_types
        assert all(isinstance(d, str) for d in src.doc_types)
        assert callable(src.collect)


def test_every_doc_type_is_exportable():
    """모든 docType이 reader 또는 소유 소스(collect=export)를 가진다."""
    for src in base.REGISTRY.values():
        for doc_type in src.doc_types:
            has_reader = doc_type in exporter.READERS
            has_owner = bool(base.sources_for(doc_type))
            assert has_reader or has_owner, f"{doc_type} not exportable"


def test_expected_initial_sources():
    assert set(base.REGISTRY) == {
        "naver_games", "kbo_roster", "community_posts", "meme_dict"}
```

- [ ] **Step 2: 통과 확인**

Run: `python3 -m pytest tests/test_registry_contract.py -q`
Expected: 3 passed

- [ ] **Step 3: 실데이터 스모크 (터널 DB + 실 S3)**

주의: `127.0.0.1:3306`은 SSH 터널 너머 원격 MySQL이다(로컬 도커 아님).

```bash
python3 -m kbo_collector.run collect --target meme_dict
python3 -m kbo_collector.run export --target game_result --date 2026-03-28
aws s3 ls s3://victoryfairy-crawl-local/question-source/ --recursive | head
```

Expected: `question-source/player_meme/…` 2키 이상, `question-source/game_result/…` 5키(3/28 경기 수), 각 JSON에 `envelopeVersion:1`과 비어있지 않은 `content`.

- [ ] **Step 4: 문서 갱신**

`docs/current-crawl-overview.md` §6(데이터 처리 파이프라인) 끝에 추가:

```markdown
## 질문 생성 인계 계층 (question-source)

소스 플러그인(`kbo_collector/sources/`)이 수집한 데이터를 exporter(`kbo_collector/exports/`)가
**통일 envelope(v1)** JSON으로 S3 `question-source/{docType}/{date}/`에 적재한다.
분석자·질문 생성기는 envelope 공통 필드(`entities`·`title`·`content`·`tags`)만 소비하므로
**소스가 늘어도 소비자 코드는 불변**이다. content는 결정적 템플릿 렌더링(LLM 미사용).
초기 docType: `game_result` `player_profile` `community_post` `player_meme`.
소스 추가 = `sources/` 모듈 1개 + `@register` (스펙: `docs/superpowers/specs/2026-07-15-…-design.md`)
```

- [ ] **Step 5: 전체 테스트 + 최종 커밋**

Run: `python3 -m pytest -q` → 전부 passed

```bash
git add tests/test_registry_contract.py docs/current-crawl-overview.md
git commit -m "test(py-collector): registry protocol contract tests + docs for question-source layer

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Self-Review 결과

- **스펙 커버리지**: §3-2 구조(T1-6), §3-3 프로토콜(T2), §3-4 envelope+소비자계약(T1,5,6), §3-5 exporter(T5,6), §3-6 meme_dict+collect=export(T4, T5의 위임 테스트), §4 에러처리(T4 항목격리·T5 KeyError·unresolved), §5 테스트 5종(T1,2,4,5,7) — 전부 매핑됨.
- **하위호환(성공기준 5)**: T3/T5에서 기존 job 분기 유지 + 전체 스위트 확인 스텝 포함.
- **타입 일관성**: reader 시그니처 `(db, date=None, sink=None)`은 T6에서 통일하며 T5의 `read_game_results` 수정을 명시. `CollectResult.loaded`는 int로 전 태스크 일관.
