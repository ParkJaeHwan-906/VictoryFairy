# KBO 데이터 수집 파이프라인 — 요구사항 & 아키텍처 검토

> 작성: 2026-07-18. 대상: `py-collector`(수집) + 백엔드 `domain`(소비) 경계.
> 목적: 질문생성 AI에 공급할 데이터 수집 파이프라인이 (R1) 소스와 무관한 일관 스키마를 제공하는지,
> (R2) 소스 확장(나무위키·선수 별명 사전 등)에 열려 있는지를 요구사항으로 명문화하고, 현행 구현이
> 이를 어떻게 충족/미충족하는지 코드 근거로 검토한다.

---

## 1. 핵심 요구사항 (EARS)

- **R1 (일관 스키마).** 소스가 무엇이든(경기 데이터/커뮤니티 글/정적 사전), 질문생성 소비자는
  **단일 스키마(Envelope v1)** 로 데이터를 제공받아야 한다. 새 소스/docType이 추가돼도 **소비자 코드 변경은 0**이어야 한다.
- **R2 (소스 확장성).** 새 데이터 소스를 추가할 때, 오케스트레이터(`run.py`)와 소비자(질문생성기)를 수정하지 않고
  **소스 파일 추가만으로** 파이프라인에 편입될 수 있어야 한다.
- **R3 (수집 토폴로지의 IP 제약 대응).** AWS IP가 차단되는 소스(FMKorea)는 주거 IP 경로로,
  차단되지 않는 소스는 서버리스(Lambda) 경로로 수집하되, 두 경로의 산출물은 동일한 스키마로 합류해야 한다.

---

## 2. 현행 아키텍처

### 2.1 수집 토폴로지 — 실행 경로 3개

| 경로 | 실행 환경 | 수집 대상 | 싱크 | 주기 |
|---|---|---|---|---|
| **Lambda** | AWS IP (컨테이너 이미지) | 경기 원본 JSON(`game`), DCInside(`community`) | **S3** | 경기 `cron(0 18 UTC)`=03:00 KST / 커뮤니티 `rate(10 min)` |
| **맥북 크론** | 주거 IP | FMKorea 인기 KBO글 | **S3** | 하루 1회 (`crawl_fmkorea.sh`) |
| **DB 호스트 CLI** | SSH 터널(DB 접근) | `teams`·`registrations`·`records`(박스스코어), `export` | **MySQL** (+ export는 S3) | **스케줄 미커밋(수동/외부 cron)** |

- **FMKorea가 왜 맥북인가**: FMKorea는 AWS 데이터센터 IP에 HTTP 430(rate-limit)을 반환한다.
  주거 IP에서만 수집 가능(`config/targets.yaml`, `deploy/local/README.md`). `fetch.py`가 429/430 쿨다운을 처리한다.
- **중요**: Lambda는 MySQL에 쓰지 않는다(`handler.py`에 `DbSink` import 없음). MySQL 적재는 전적으로 CLI 경로 소관.

### 2.2 통합 지점 — exporter

수집 원천이 갈라져도 **`exports/exporter.py`가 단일 합류점**이다. reader가 저장소별로 다르다:

| docType | reader | 읽는 저장소 |
|---|---|---|
| `game_result` | `read_game_results` | **MySQL** (`games`·`teams`·`game_pitching`·`game_players`) |
| `player_profile` | `read_player_profiles` | **MySQL** (`players`·`teams`) |
| `community_post` | `read_community_posts` | **S3** (`community/dcinside/…`, `community/fmkorea/…`) |
| `player_meme` | (소스 `collect`가 곧 export) | 파일 `config/memes.yaml` + MySQL(uid 해소) |

- exporter는 각 reader가 낸 Envelope를 검증 후 **S3 `question-source/{docType}/{date}/{safeId}.json`** 에 적재한다.
- 따라서 exporter는 **MySQL과 S3 둘 다 접근 가능한 DB 호스트에서만** 돈다(CLI `export`가 두 싱크를 동시 생성).
- **소비자(질문생성기)는 S3 `question-source/` 한 곳만 읽는다** → 소스 이질성이 여기서 감춰진다.

### 2.3 Envelope v1 — 소비자 계약 (R1의 실체)

`exports/envelope.py`. 직렬화 시 **JSON 키 12개 고정**, 소스와 무관하게 동일:

```
envelopeVersion(=1) · docId · docType · source · sourceRef · collectedAt
title · content · tags · entities · payload · pii
```

- **소비자가 쓰는 공통 필드**: `title`·`content`·`tags`·`entities` (모듈 docstring 명시).
  `entities` = `{playerUids, teamCodes, gameId, unresolved}`로 대상 필터링, `tags`로 질문 유형 선택,
  `title`+`content`를 프롬프트 컨텍스트로 투입.
- **`payload`는 docType별 구조(선택)** — 소비자는 파싱하지 않아도 됨.
- **`content` 자연어화 책임은 소스에 있다**(결정적 템플릿, LLM 사용 금지 — 사실 데이터에 환각 방지). 커뮤니티 글만 원문 통과.
- 검증(`validate`): `docId/docType/source/title/content` 5개 공백 불가. 나머지는 빈 값 허용하되 키는 항상 존재.

### 2.4 소스 플러그인 레지스트리 (R2의 실체)

`sources/base.py`. 전역 dict + `@register` 데코레이터.

- 소스 계약(런타임 강제): 클래스 속성 `source_id`, `doc_types`, 메서드 `collect(ctx) -> CollectResult`.
- `CollectContext` = `{settings, client(httpx), db(DbSink), sink(S3RawSink), date}` — 소스는 필요한 것만 꺼내 쓴다.
- `run.py`는 `get_source(target)` 한 줄만 호출 → **소스가 늘어도 run.py 불변**.
- 현존 소스 4개: `naver_games`(game_result) · `kbo_roster`(player_profile) · `community_posts`(community_post) · `meme_dict`(player_meme).

---

## 3. 요구사항 충족 여부

### R1 (일관 스키마) — **충족 (형태), 부분 충족 (의미)**

- ✅ 모든 산출물이 단일 `Envelope` dataclass + 단일 `to_dict()`를 거친다 → **12키 형태는 소스 무관 항상 동일**.
- ✅ spec 성공 기준에 "질문생성 소비자도 소스 추가 시 코드 변경 0" 명시. 소비자는 공통 필드만 읽으면 됨.
- ⚠️ **의미적 비대칭**: `community_post`는 `entities`가 비어 방출된다(팀은 `tags`에만). 소비자가 스펙대로
  `entities`로 필터링하면 **커뮤니티 글은 선수/팀으로 필터링되지 않는다**. 봉투는 같으나 라우팅 가능성이 소스별로 다름.
- ⚠️ **content 성격 이질**: 커뮤니티는 무필터 원문, 나머지는 자기완결 문장(의도된 예외이나 소비자가 인지해야 함).

### R2 (확장성) — **정적 소스 충족, 크롤 소스 부분 충족**

- ✅ **정적 데이터 소스(예: 선수-별명 사전)**: `meme_dict.py`가 살아있는 템플릿. YAML 로드→엔티티 해소→Envelope→S3.
  **새 파일 복제 + `sources/__init__.py` import 1줄 + `config.py` 설정 1줄**이면 끝. 이 구조의 스위트스팟.
- ⚠️ **크롤 소스(예: 나무위키)**: 인터페이스는 열려 있다(`ctx.client` 주입, `fetch.fetch`가 재시도·rate-limit·UA 처리).
  그러나 **파싱 로직은 100% 자체 구현**, **fetch→journal→dead-letter→페이징 오케스트레이션 배선을 매번 재작성**해야 한다.
  기존 크롤 소스의 로직은 `run.py`의 `land_*`에 집중돼 있고 재사용 가능한 "크롤 소스 베이스"로 추출돼 있지 않다.
- 공통 계층 정리: **envelope·sink·fetch(유틸)는 공유**, **parse·오케스트레이션(페이징/journal)은 소스별 자체 구현**.

---

## 4. 확인된 갭 / 리스크

| # | 갭 | 근거 | 영향 |
|---|---|---|---|
| **G1** | **Lambda는 MySQL에 직접 적재하지 않는다** — S3에만 쓴다. "Lambda→MySQL 직접"은 의도라면 미구현 | `handler.py`(DbSink 없음), `run.py` land_* 분기 | 토폴로지 인식과 실제 구현 불일치 |
| **G2** | **MySQL 적재(records/registrations)와 export 잡에 커밋된 스케줄러가 없다** | `deploy/lambda/README.md` "DB 잡은 별도 cron" | 자동화된 건 Lambda+FMKorea 크론뿐. 합류 신뢰성이 미커밋 수동 cron에 의존 |
| **G3** | **미소비 브론즈**: Lambda가 S3에 쌓는 경기 원본(schedule/result/relay)은 exporter가 안 읽는다. exporter `game_result`는 **MySQL** `games`를 읽고, 그건 별도 `records` CLI가 채운다 | `exporter.py` reader, `current-crawl-overview.md` | 같은 "경기"가 두 파이프라인(S3 원본 / MySQL 박스스코어)으로 갈림 |
| **G4** | **스키마 소유권 충돌**: `schema.sql`은 `teams/players/games`를 "collector 소유"(문자열 PK)로 정의. 백엔드 `domain` JPA는 같은 이름 테이블을 BIGINT PK+FK 정규화로 재정의(PR #14) | `deploy/sql/schema.sql:1`, domain 엔티티 | 같은 MySQL 공유 시 스키마 불일치. **별도 정리 필요** |
| **G5** | **크롤 소스 공통 스캐폴드 부재** | `run.py` land_* 집중, `sources/*`는 얇은 위임 래퍼 | 나무위키 추가 시 fetch/journal/페이징 배선 재작성 |
| **G6** | **entities 비대칭** | `exporter.py` community reader가 `empty_entities()` | 커뮤니티 글은 선수/팀 링크 없어 대상 필터 불가 |

---

## 5. 개선 권고 (우선순위 순)

1. **collector ↔ domain 스키마 소유권 합의** (G4). 같은 MySQL을 공유할지(collector=writer, domain=읽기전용) /
   테이블을 분리할지(수집 raw ↔ 서비스 도메인 + ETL) 결정. 이게 정해져야 마이그레이션(Flyway 등) 도입 가능.
2. **MySQL 적재·export 스케줄러를 코드로 커밋** (G2). 합류의 신뢰성을 미커밋 cron에서 회수.
3. **크롤 소스 베이스 추출** (G5). 나무위키 대비 — `run.py`의 fetch+journal+페이징을 재사용 가능한 `CrawlSource`로.
   정적 소스(별명 사전)는 지금도 저비용이라 후순위.
4. **엔티티 해소 헬퍼 공통화**. `resolve_player_uid`(현재 `meme_dict` 로컬)를 공통 모듈로 승격 — 별명 사전·나무위키 등
   선수 기반 소스가 재사용.
5. **community_post의 entities 채우기** (G6). 본문에서 선수/팀을 링크해 소비자 필터링 대상에 포함.
6. **소스 자동 발견**. `sources/__init__.py` 수동 import를 `pkgutil.walk_packages`로 대체 → "파일 1개"가 진짜 1곳.

---

## 6. 예정 소스 편입 체크리스트

**선수-별명 사전 (정적)** — 저비용:
- [ ] `sources/player_aliases.py` 생성(`meme_dict.py` 복제, YAML 스키마·docType만 교체)
- [ ] `sources/__init__.py`에 import 1줄
- [ ] `config.py`에 파일 경로 `Field` 1줄
- [ ] docType을 `player_meme`에 합류시킬지 신규로 둘지 결정(합류 시 소비자 변경 0)

**나무위키 (크롤)** — 중비용:
- [ ] `sources/namuwiki.py` 생성(`community_posts.py` 패턴: fetch→parse→sink)
- [ ] HTML 파서 자체 구현(문서 단위, community의 목록-페이징과 다름)
- [ ] journal·dead-letter 배선(현재 공통 스캐폴드 없어 수동)
- [ ] `export` reader 필요 여부 판단(collect가 곧 export면 불필요)
- [ ] 원문 통과 vs 렌더링 정책 결정(저작권/PII는 하류 소관이나 경계 명시)
