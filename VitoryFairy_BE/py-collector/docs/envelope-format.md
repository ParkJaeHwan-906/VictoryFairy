# question-source envelope 포맷 (v1) — 질문생성 인계 산출물

> 수집 파이프라인의 **최종 산출물** 스키마입니다. 소스가 무엇이든(경기/선수/커뮤니티/사전) 질문생성기는
> **단일 통일 봉투(Envelope v1)** 하나만 소비합니다. 근거 코드: `kbo_collector/exports/envelope.py`,
> `kbo_collector/exports/exporter.py`, `kbo_collector/sources/meme_dict.py`.

`data-formats.md`가 **원본(RawPost·네이버 원본)** 까지를 다룬다면, 이 문서는 그 뒤 단계 —
소스별 이질성을 감추고 `question-source/`에 적재되는 **소비자 계약** 을 다룹니다.

## 파이프라인상의 위치

```
소스 수집(S3 원본 / MySQL 정규화)
        │  export / collect
        ▼
question-source/{docType}/{date}/{safeId}.json   ← 이 문서 (Envelope v1)
        │  소비
        ▼
질문 생성기 (공통 4필드만 읽음)
```

- **game_result / player_profile / community_post**: `exporter.py`의 reader가 저장소(MySQL·S3)를
  읽어 envelope로 재포장 → `python -m kbo_collector.run export --target <docType>`.
- **player_meme**: 중간 저장소가 없어 소스 `collect`가 곧 export → `run collect --target meme_dict`.

---

## 1. 봉투 12필드 (소스 무관 고정)

직렬화(`Envelope.to_dict()`) 시 JSON 키 **12개가 항상 존재**하며, 순서·이름이 소스와 무관하게 동일합니다.

| # | JSON 키 | 타입 | 의미 |
|---|---|---|---|
| 1 | `envelopeVersion` | int | 봉투 스키마 버전. 현재 **`1`** (`ENVELOPE_VERSION`) |
| 2 | `docId` | string | 봉투 고유 ID. docType별 규칙으로 결정론적 생성(아래 표). S3 파일명의 근거 |
| 3 | `docType` | string | `game_result` / `player_profile` / `community_post` / `player_meme` 중 하나 |
| 4 | `source` | string | 원천 식별자(`naver` / `kbo_official` / `dcinside`\|`fmkorea` / `seed_file`) |
| 5 | `sourceRef` | string | 원본 위치 역참조(`mysql://games/{id}`, 커뮤니티 원글 URL, `config/memes.yaml` 등) |
| 6 | `collectedAt` | string | 봉투 생성 시각(UTC ISO-8601, `YYYY-MM-DDThh:mm:ssZ`) |
| 7 | **`title`** | string | 짧은 제목. 프롬프트 컨텍스트 헤더로 투입 |
| 8 | **`content`** | string | 자연어 본문. 프롬프트 컨텍스트 본체 |
| 9 | **`tags`** | string[] | 질문 유형 라우팅용 라벨(예: `박스스코어`, `프로필`, `밈`, `여론`, 팀코드) |
| 10 | **`entities`** | object | 대상 필터링용 링크. `{playerUids[], teamCodes[], gameId, unresolved[]}` |
| 11 | `payload` | object | docType별 구조 데이터(선택). 소비자는 파싱하지 않아도 됨 |
| 12 | `pii` | object | PII 상태. 기본 `{"masked": true}` |

### `entities` 하위 구조 (`empty_entities()`)

```json
{ "playerUids": [], "teamCodes": [], "gameId": null, "unresolved": [] }
```

- `playerUids` — `game_players.player_uid`(자체 surrogate) 배열.
- `teamCodes` — 팀코드(`OB LG SS KT WO HT HH NC LT SK`) 배열.
- `gameId` — 경기 ID(있으면), 없으면 `null`.
- `unresolved` — 링크 실패 대상 `[{kind, name, reason}]`(예: `no-game-uid`, `not-found`, `duplicate-name`).

### 검증 규칙 (`Envelope.validate()`)

- `docId` · `docType` · `source` · `title` · `content` **5개는 공백 불가**(비면 `EnvelopeError` → export 시 그 항목만 skip).
- 나머지 7개는 빈 값 허용하되 **키는 항상 존재**한다(소비자가 `KeyError` 없이 읽도록).

---

## 2. 소비자 계약 — 공통 4필드만 읽는다

> **설계 원칙**: 질문생성기는 `title` · `content` · `tags` · `entities` **4개만** 소비한다.
> `payload`는 docType별 구조라 **읽지 않아도 되고**, `source`/`docType`/`sourceRef`/`pii`는 메타.
> → **소스가 늘어도 소비자 코드 변경은 0.** (모듈 docstring 명시, 요구사항 R1)

| 공통 필드 | 소비자가 쓰는 방식 |
|---|---|
| `entities` | `playerUids`/`teamCodes`/`gameId`로 **질문 대상 필터링**(어느 선수·팀·경기에 대한 질문인가) |
| `tags` | **질문 유형 선택**(박스스코어 문제 / 프로필 문제 / 밈 문제 …) |
| `title` + `content` | **프롬프트 컨텍스트**로 그대로 투입(사실 문장) |

핵심 함의: 새 docType/소스를 추가해도 이 4필드만 채우면 소비자는 **기존 코드 그대로** 처리한다.
새 소스는 `sources/` 모듈 추가로 파이프라인에 편입되고(자세히는 `directory-structure.md` ②),
소비자·오케스트레이터는 건드리지 않는다.

---

## 3. docType 4종별 차이

| docType | 읽는 곳 | content 생성 방식 | entities 채움 | 대표 tags | payload 키 |
|---|---|---|---|---|---|
| **game_result** | MySQL `games`·`teams`·`game_pitching`·`game_players` | **결정적 템플릿**(스코어·승패투수를 문장 조립) | `gameId` + `teamCodes` | `박스스코어`,`경기결과`(+`시범경기`/`무승부`) | `gameId`,`awayScore`,`homeScore`,`winner`,`stadium`,`startTime` |
| **player_profile** | MySQL `players`·`teams`(+`game_players`로 uid) | **결정적 템플릿**(포지션·등번호·투타·생일 조립) | `teamCodes` + (`playerUids` 또는 `unresolved`) | `프로필`,`선수` | `playerId`,`backNumber`,`position`,`throwBat`,`isFirstTeam` |
| **community_post** | S3 `community/{dcinside\|fmkorea}/{date}/` RawPost | **커뮤니티 원문 통과**(`title`+`body`, 요약·필터 없음) | **비어 있음**(`empty_entities()`) | `커뮤니티`,`여론`(+팀코드) | `engagement`,`crawledAt` |
| **player_meme** | `config/memes.yaml`(+MySQL로 uid 해소) | **결정적 템플릿**(별명+유래 조립) | `teamCodes` + (`playerUids` 또는 `unresolved`) | `밈`(+사전 tags) | `text`,`origin` |

> `content`는 **커뮤니티만 원문 통과**, 나머지 3종은 사실 데이터에서 **결정적으로 렌더된 자기완결 문장**입니다.
> content 생성에 **LLM을 쓰지 않습니다**(사실 데이터 환각 방지 — 4절 주의점).

### docId 규칙

| docType | docId 형태 | 예 |
|---|---|---|
| game_result | `game_result:{gameId}` | `game_result:20260708LGSS02026` |
| player_profile | `player_profile:{playerId}` | `player_profile:60632` |
| community_post | `community_post:{SOURCE}:{postExternalId}` | `community_post:DCINSIDE:11158020` |
| player_meme | `player_meme:{team}:{name}:{text}` | `player_meme:LG:오스틴:오카도` |

---

## 4. docType별 예시 JSON

### game_result

```json
{
  "envelopeVersion": 1,
  "docId": "game_result:20260708LGSS02026",
  "docType": "game_result",
  "source": "naver",
  "sourceRef": "mysql://games/20260708LGSS02026",
  "collectedAt": "2026-07-18T05:00:12Z",
  "title": "2026-07-08 LG 8:2 삼성",
  "content": "2026-07-08 대구에서 열린 LG 대 삼성 경기는 8:2, LG의 승리로 끝났다. 승리투수 임찬규. 패전투수 오러클린.",
  "tags": ["박스스코어", "경기결과"],
  "entities": { "playerUids": [], "teamCodes": ["LG", "SS"], "gameId": "20260708LGSS02026", "unresolved": [] },
  "payload": { "gameId": "20260708LGSS02026", "awayScore": 8, "homeScore": 2, "winner": "away", "stadium": "대구", "startTime": "18:30:00" },
  "pii": { "masked": true }
}
```

### player_profile

```json
{
  "envelopeVersion": 1,
  "docId": "player_profile:60632",
  "docType": "player_profile",
  "source": "kbo_official",
  "sourceRef": "mysql://players/60632",
  "collectedAt": "2026-07-18T05:00:12Z",
  "title": "KIA 김도영 프로필",
  "content": "KIA 김도영은(는) 내야수로, 등번호 5번, 우투우타이다. 생년월일 2003-10-02. 현재 1군 등록 상태다.",
  "tags": ["프로필", "선수"],
  "entities": { "playerUids": [412], "teamCodes": ["HT"], "gameId": null, "unresolved": [] },
  "payload": { "playerId": "60632", "backNumber": "5", "position": "내야수", "throwBat": "우투우타", "isFirstTeam": true }
}
```

> uid 해소 실패 시(경기 미출전 등) `playerUids`는 비고 `unresolved: [{"kind":"player","name":"김도영","reason":"no-game-uid"}]`가 채워집니다.

### community_post

```json
{
  "envelopeVersion": 1,
  "docId": "community_post:DCINSIDE:11158020",
  "docType": "community_post",
  "source": "dcinside",
  "sourceRef": "https://gall.dcinside.com/board/view/?id=doosanbears_new1&no=11158020",
  "collectedAt": "2026-07-18T05:00:12Z",
  "title": "구멍게이 병살ㅋㅋㅋㅋ",
  "content": "구멍게이 병살ㅋㅋㅋㅋ\n…(본문 원문, 요약·필터 없음)…",
  "tags": ["커뮤니티", "여론", "DOOSAN"],
  "entities": { "playerUids": [], "teamCodes": [], "gameId": null, "unresolved": [] },
  "payload": { "engagement": { "viewCount": 10, "likeCount": 0, "dislikeCount": 0, "commentCount": 0 }, "crawledAt": "2026-07-10T10:30:34+00:00" },
  "pii": { "masked": true }
}
```

> `entities`가 **비어 있고** 팀은 `tags`에만 표기됨(4절 주의). `content`는 제목+본문 원문 그대로.

### player_meme

```json
{
  "envelopeVersion": 1,
  "docId": "player_meme:LG:오스틴:오카도",
  "docType": "player_meme",
  "source": "seed_file",
  "sourceRef": "config/memes.yaml",
  "collectedAt": "2026-07-18T05:00:12Z",
  "title": "오스틴 밈: 오카도",
  "content": "LG 오스틴의 밈 '오카도': 오스틴+아보카도 합성 팬 별명",
  "tags": ["밈", "별명"],
  "entities": { "playerUids": [305], "teamCodes": ["LG"], "gameId": null, "unresolved": [] },
  "payload": { "text": "오카도", "origin": "오스틴+아보카도 합성 팬 별명" },
  "pii": { "masked": true }
}
```

---

## 5. S3 키

```
question-source/{docType}/{date}/{safeId}.json
```

- `{docType}` — 4종 중 하나. `{safeId}` — `safe_id(docId)`로 특수문자를 `_`로 치환(한글은 유지).
  예: `player_meme:LG:오스틴:오카도` → `player_meme_LG_오스틴_오카도.json`.
- `{date}` — **export/collect 실행일**(UTC, `_now()[:10]`)이지 콘텐츠 자체의 날짜가 아님.
  같은 경기라도 오늘 export하면 오늘 파티션에 놓입니다.
- 같은 `docId`를 다시 export하면 같은 키에 **멱등 덮어쓰기**.

예: `question-source/game_result/2026-07-18/game_result_20260708LGSS02026.json`

---

## 6. 주의점

- **community_post는 `entities`가 비어 방출된다.** 본문에서 선수/팀을 아직 링크하지 않으므로
  소비자가 `entities`로 필터링하면 **커뮤니티 글은 선수/팀 대상 필터에 걸리지 않는다**. 팀 단서는
  `tags`(예: `DOOSAN`)에만 있다 — 봉투 형태는 같아도 **라우팅 가능성은 소스별로 비대칭**(요구사항 검토 G6).
- **content 자연어화 책임은 소스/exporter에 있고, LLM을 쓰지 않는다.** game_result·player_profile·
  player_meme의 `content`는 사실 필드를 **결정적 템플릿**으로 렌더한 문장이다(환각 방지). 커뮤니티만
  원문 통과이며, 이때 본문은 무필터라는 점을 소비자가 인지해야 한다.
- **`content` 성격이 docType마다 다르다.** 3종은 자기완결 사실 문장, community_post는 무필터 원문.
  봉투 스키마는 같지만 프롬프트에 넣을 때 소스 성격 차이를 감안할 것.
- **`payload`는 계약이 아니다.** docType별로 구조가 다르고 소비자 필수도 아니다. 안정적으로 의존하려면
  공통 4필드(`title`/`content`/`tags`/`entities`)만 사용.

## 관련 문서

- 원본(RawPost·네이버 원본) 필드: [`data-formats.md`](./data-formats.md)
- 소스 플러그인·exporter 배치: [`directory-structure.md`](./directory-structure.md) ②③
- 요구사항·아키텍처 검토(R1/R2·갭): [`data-pipeline-requirements.md`](./data-pipeline-requirements.md)
</content>
