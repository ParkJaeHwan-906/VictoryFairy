# 질문 생성 소스 아키텍처 설계 (py-collector 개편)

- 날짜: 2026-07-15
- 상태: 승인됨
- 관련: `2026-07-14-test-container-and-roster-db-design.md` (로스터·경기기록 적재)

## 1. 배경과 목표

VictoryFairy의 핵심 기능은 각종 정보(경기 데이터, 선수 데이터, 밈, 커뮤니티 여론 등)를 AI(Amazon Bedrock)에 넘겨 **야구팬용 이지선다/사지선다 문제**를 생성하고, 실제 경기 관람 중 풀게 하는 것이다.

전체 파이프라인과 이번 작업의 위치:

```
[Python py-collector]                    [분석자]               [Spring]
 sources/* ─collect─▶ MySQL·S3 원본      S3 envelope 소비        quiz/create 모듈
 exports/* ─export──▶ S3 envelope ─────▶ 검열·형태소분석 ─▶     정제본 저장 API,
                      (★인계 지점)        정제본 산출             Bedrock 질문생성
```

**이번 작업 범위**: py-collector를 소스 플러그인 구조로 개편 + 통일 envelope export 계층 신설 + 신규 소스 1호(선수-밈 사전) 추가. Spring·Bedrock·분석 단계는 범위 밖 — envelope 스키마만 계약으로 정의한다.

**목표(성공 기준)**:
1. 새 소스 추가 = "소스 모듈 파일 1개 + 레지스트리 등록"으로 끝난다. run.py나 다른 소스 수정 불필요.
2. 분석자는 소스가 몇 개든 **한 가지 스키마(envelope v1)** 만 알면 된다.
3. **질문 생성 소비자도 소스 추가 시 코드 변경 0(또는 최소)** — envelope의 공통 필드(`entities`·`title`·`content`·`tags`)만 소비하면 새 docType이 자동으로 흘러들어온다. (§3-4 소비자 계약)
4. 크롤이 아닌 소스(파일 시드)도 같은 인터페이스로 수용된다 — 밈 사전으로 증명.
5. 기존 CLI job 이름( `game`, `community`, `records`, `registrations` 등)은 하위호환 유지.

## 2. 결정 사항 (질의 결과)

| 결정 | 선택 | 근거 |
|---|---|---|
| 분석자 인계 형태 | **S3 JSON 배치** | 배치 분석(파이썬/스파크)에 적합, 기존 RawPost 방식의 확장이라 구현 빠름 |
| 이번 범위 | **아키텍처 개편 + 신규 소스 1개** | 확장 구조가 실제 작동하는지 신규 소스로 검증 |
| 신규 소스 1호 | **선수-밈 사전 (수작업 YAML 시드)** | 크롤 없는 소스도 소스임을 증명. 나무위키 크롤은 후속(방어 강함) |
| 접근안 | **A: Source 플러그인 + Export 계층 분리** | 수집 실패와 인계 스키마 분리, 소스 증가에도 분석자 인터페이스 불변 |

기각한 대안: (B) 기존 구조 유지+export만 추가 — run.py 분기가 계속 불어나 확장성 미검증. (C) ETL 프레임워크(Dagster 등) — Airflow를 비용으로 제거한 이력, 소스 10개 미만 규모에 과함.

## 3. 아키텍처

### 3-1. 역할 경계 (Python vs Spring)

- **Python (py-collector)**: 수집(collect), 정규화, 엔티티 링크(선수/팀/경기 식별), envelope export. 데이터 무거운 작업 전부.
- **분석자**: S3 `question-source/` envelope 소비 → 검열·형태소 분석 → 정제본 산출(산출 위치는 분석자 소관).
- **Spring (quiz/create)**: 정제본 이후 서비스 로직·API·Bedrock 호출. py-collector와의 접점은 (a) MySQL 읽기전용 엔티티(기존 방식 유지), (b) envelope 스키마 문서.

### 3-2. py-collector 새 구조

```
kbo_collector/
  sources/                 # ★ 확장 포인트: 소스 1개 = 파일 1개
    __init__.py
    base.py                # Source 프로토콜 + REGISTRY + @register 데코레이터
    naver_games.py         # 기존 game_records 수집을 소스로 이식
    kbo_roster.py          # 기존 kbo_register(land_registrations) 이식
    community_posts.py     # 기존 community(land_community) 이식
    meme_dict.py           # 신규: config/memes.yaml 파일 소스
  exports/
    __init__.py
    envelope.py            # Envelope dataclass + 스키마 검증 + docId 규칙
    exporter.py            # doc_type별 reader → envelope → S3 적재
  # fetch / db / sink / keys / masking / journal / dimensions 등 공용 유틸은 그대로
```

`naver.py`(schedule/result/relay 원본 S3 랜딩)는 소스화하지 않고 그대로 둔다 — 원본 랜딩은 질문 소스 계층과 목적이 다르고(분석자는 envelope만 봄), 잘 돌고 있는 걸 옮길 이유가 없다(YAGNI).

### 3-3. Source 프로토콜 (base.py)

```python
class Source(Protocol):
    source_id: str            # "meme_dict", "naver_games", ...
    doc_types: tuple[str, ...] # 이 소스가 방출하는 docType들

    def collect(self, ctx: CollectContext) -> CollectResult: ...
```

- `CollectContext`: settings, client(httpx), db(DbSink|None), date 등 실행 컨텍스트를 담는 경량 객체. 소스가 필요한 것만 꺼내 씀.
- `CollectResult`: 적재 건수·실패 목록(기존 loaded/failed 관례 유지).
- 등록: `@register` 데코레이터가 `REGISTRY: dict[str, Source]`에 추가. `run.py collect <source_id>`는 레지스트리 조회 → 실행이 전부라 소스가 늘어도 불변.
- 기존 land_* 함수는 소스 모듈 안으로 이동하되 함수 시그니처는 유지(기존 테스트 보호). run.py의 기존 job 분기는 소스 호출로 위임.

### 3-4. Envelope 스키마 v1 (분석자 인계 계약)

```json
{
  "envelopeVersion": 1,
  "docId": "player_meme:HT:김도영:월관보음",
  "docType": "player_meme",
  "source": "seed_file",
  "sourceRef": "config/memes.yaml",
  "collectedAt": "2026-07-15T09:00:00Z",
  "entities": {
    "playerUids": [123],
    "teamCodes": ["HT"],
    "gameId": null,
    "unresolved": []
  },
  "title": "김도영 밈: 월관보음",
  "content": "KIA 김도영의 팬 별명 '월관보음'은 …라는 뜻이다.",
  "tags": ["밈", "별명"],
  "payload": { },
  "pii": { "masked": true }
}
```

- `docId`: docType별 결정적 규칙(멱등). 재-export 시 같은 키 덮어씀.
- `entities`: 질문 생성 AI가 조인 없이 "누구/어느 팀/어느 경기 얘기인지" 아는 필드. 선수 공용 ID는 기존 `player_uid` 재사용.
- `entities.unresolved`: 엔티티 해소 실패(동명이인·미등록 선수) 항목을 **버리지 않고** `[{"kind":"player","name":"김철수","reason":"duplicate-name"}]` 형태로 남김 — 분석자가 판단.
- **`title`/`content`/`tags` (소비자 공통 계층)**: 각 소스(exporter)가 자기 payload를 **LLM이 바로 읽을 한국어 자연어로 렌더링**해 담는다. `content`는 그 문서 하나만 읽어도 사실이 성립하는 자기완결 문장(들)이어야 한다(예: "2026-03-28 문학 KIA 6:7 SSG, 승리투수 김민"). `tags`는 질문 유형 라우팅용 자유 문자열(예: `밈`, `박스스코어`, `프로필`, `여론`).
- `payload`: docType별 구조화 본문(아래 표). 구조가 필요한 소비자용 **선택** 필드.
- S3 경로: `question-source/{docType}/{yyyy-MM-dd}/{docId 안전화}.json` (기존 sink 멱등 PUT 재사용, 날짜는 export 실행일)

**소비자 계약 (질문 생성기 코드 변경 0의 원리)**: 질문 생성 파이프라인(분석자→Bedrock 프롬프트 구성)은 envelope의 **공통 필드만** 사용한다 — `entities`로 대상(경기/선수/팀) 필터링, `tags`로 질문 유형 선택, `title`+`content`를 프롬프트 컨텍스트로 투입. docType별 payload 파싱을 소비자에 두지 않는다. 따라서:
- 새 소스/docType 추가 → 소스가 content 렌더링 책임을 짐 → 소비자는 **무변경**으로 새 문서를 자동 수용.
- 렌더링(자연어화) 책임은 **데이터를 가장 잘 아는 쪽 = 소스**에 있다. 소비자가 특정 docType을 특별 취급하고 싶을 때만(예: 박스스코어 표 재구성) payload를 선택적으로 파싱한다 — 이것이 "최소" 변경의 경계.
- **렌더링은 결정적 템플릿, AI 사용 금지**: title/content/tags는 구조화된 사실의 f-string 문장화(+파생 태그는 단순 조건문: 끝내기·역전승 등)로 만든다. 커뮤니티 글은 렌더링이 아닌 원문 통과(제목=title, 본문=content) — 요약·감성·토픽은 하류(분석자/AI) 소관. 소스 계층에 LLM 요약을 넣으면 사실 데이터에 환각이 섞이고 멱등·골든테스트가 깨지므로 금지한다.

**초기 docType 4종과 payload·데이터 출처**:

| docType | 출처(reader) | payload 요지 |
|---|---|---|
| `game_result` | MySQL games+innings+pitching+batting | 스코어·승패세홀·이닝·박스스코어 요약 |
| `player_profile` | MySQL players(+registrations) | 신상·포지션·등번호·1군 여부 |
| `community_post` | S3 community RawPost | 제목·본문·engagement (마스킹 유지) |
| `player_meme` | config/memes.yaml | 밈 텍스트·유래·태그 |

### 3-5. Exporter (exports/exporter.py)

- `run.py export <docType> [--date D]` → docType별 reader가 저장소(MySQL/S3/파일)에서 읽고 envelope로 변환해 S3 적재.
- reader도 소스처럼 docType→함수 매핑 테이블로 등록 — export 추가도 "함수 1개 + 등록 1줄".
- community_post export는 원본 RawPost가 이미 S3에 있으므로 **재포장**(envelope 겉면 씌우기)이며 본문 재크롤 없음.

### 3-6. 신규 소스 1호: 선수-밈 사전 (meme_dict)

`config/memes.yaml` — 사람이 직접 관리:

```yaml
- player: { name: 김도영, team: HT }
  memes:
    - text: "월관보음"
      origin: "월요일 관중석에서 보기 아까운 선수라는 팬 별명"
      tags: [별명]
```

- `meme_dict.collect()`: YAML 로드 → 이름+팀으로 `game_players`/`players`에서 `player_uid` 해소 → `player_meme` envelope 직접 방출(S3). MySQL 테이블 없음(YAGNI — 인계가 S3이므로).
- 해소 실패는 unresolved로 방출. 파일 소스라 fetch/재시도 없음 — 프로토콜이 크롤을 강제하지 않음을 증명.
- **collect와 export의 관계**: meme_dict는 중간 저장소가 없으므로 collect가 곧 export다(파일→envelope→S3 한 번에). `export player_meme`은 `collect meme_dict`의 별칭으로 동작한다. 중간 저장소가 있는 docType(game_result 등)만 collect(수집)와 export(재포장)가 분리된다.

## 4. 에러 처리

| 상황 | 처리 |
|---|---|
| 소스 collect 중 항목 실패 | 기존 관례 유지: 항목 격리(dead-letter 또는 failed 목록), 실행 계속 |
| 엔티티 해소 실패 | envelope `entities.unresolved`에 기록, 문서는 방출 |
| envelope 스키마 위반 | export 시 검증 실패 → 해당 문서 skip + 경고 로그(전체 중단 없음) |
| 미등록 source_id/docType | CLI 즉시 에러 + 등록된 id 목록 출력 |

## 5. 테스트 전략

1. **프로토콜 준수**: REGISTRY의 모든 소스가 source_id/doc_types/collect를 갖는지 전수 검사.
2. **envelope 검증**: 필수 필드(title/content/tags 포함)·docId 결정성·직렬화 왕복 테스트. content 비어있으면 스키마 위반.
3. **meme_dict 골든 테스트**: 픽스처 YAML → 기대 envelope JSON 비교(해소 성공/실패 케이스 포함).
4. **exporter 단위 테스트**: FakeConn/FakeSink로 reader→envelope→PUT 호출 검증(기존 test_db_sink 패턴).
5. **하위호환**: 기존 run job 테스트(69개+) 전부 그린 유지.

## 6. 범위 밖 (명시)

- 나무위키 등 신규 크롤 소스(후속: meme_dict와 같은 docType으로 합류 가능)
- 분석자 파이프라인·정제본 스키마
- Spring quiz/create 구현, Bedrock 연동
- 순위표(standings)·기록이벤트(etcRecords) 소스화 — 별도 스펙으로
