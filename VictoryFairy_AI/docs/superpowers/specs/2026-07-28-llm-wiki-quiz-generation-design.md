# LLM 위키 + 퀴즈 생성 파이프라인 설계

> 작성일: 2026-07-28 · 상태: **승인됨 (2026-07-30)** — 다음 단계: 구현 계획(writing-plans)
> 범위: VictoryFairy_AI 신규 서브시스템(위키 빌더·퀴즈 생성기) + py-collector 변경 2건
> (game_schedule export · kbo_records 수집)

## 1. 목표

선수별 밈·사건사고·이력을 모은 **LLM 위키**를 구축하고, 위키와 RDB 경기 데이터(일정·결과·라인업)를
결합해 **지식 퀴즈 + 예측 퀴즈**를 자동 생성한다. 산출물은 BE 팀이 소비할 수 있는 계약(JSON)으로
S3에 적재한다 (기능명세서 QUIZ-001/002 대응).

## 2. 결정 요약

| 결정 | 선택 | 이유 |
|---|---|---|
| 퀴즈 유형 | 지식 + 예측 둘 다 | 위키(밈·이력)는 정답 확정형 지식 퀴즈에, RDB 일정·라인업은 명세서의 예측 퀴즈에 대응 |
| 위키 형태 | 선수별 마크다운 문서 1개 | 전체 위키가 수 MB 규모(선수 600~700명) — 검색 인프라 불필요. 대상 선수가 정해지면 문서를 통째로 프롬프트 투입 |
| 저장소 | S3 (`wiki/players/`) | 운영 잡(EC2 크론·Lambda)과 동일 인프라. Bedrock KB는 고정비·규모 면에서 과함 |
| 그래프 | **그래프-라이트** (파생 인덱스 `wiki/graph.json`) | 관계 기반 문제(사건 연루·라이벌·밈 공유)는 확보하되 그래프 DB·동기화·자격증명 부담 제거. 커지면 graph.json이 그대로 Neo4j 임포트 소스 |
| 질문 다양성 | **질문 템플릿 카탈로그 × 데이터 바인딩** | 흥미로운 문제 수만 개를 직접 기획할 수 없음. 템플릿("올해 {팀A} vs {팀B} 상대전적 우위는?") 수십 개를 엔티티 조합·시점으로 곱해 문제 공간을 만든다. 어떤 템플릿이 잘 먹히는지는 유저 반응으로 선별(피드백 루프 자체는 2차, 계약에 `templateId`만 선반영) |
| 통계 데이터 접근 | 2층 구조: 시의성 층(최근 7일 봉투) + **축적 층(`wiki/stats/` 시즌 요약)** | "작년 대비", "시즌 초 대비" 비교는 시즌 전체 집계가 필요한데, 생성기가 매번 수백 개 봉투를 읽는 건 낭비. 퀴즈 생성기의 전처리 단계(결정적 스크립트, LLM 미사용)가 **매일** 집계해 요약 문서로 유지 — "오늘 기준 순위" 문제의 신선도 보장 |
| 실행 환경 | Claude Code 클라우드 스케줄 잡(routine) 2개 | 맥북 무관하게 동작. LLM 호출 = Claude Code 자체 |
| 데이터 접근 | **S3 전용** (routine은 DB 미접근) | RDB 데이터는 py-collector export가 envelope로 공급. 클라우드에 DB 자격증명을 열지 않음 |
| 위키 소스 | **validation 통과 커뮤니티 글**(LLM 추출) + memes.yaml 시드. 나무위키는 1차 제외 | 위키 빌더는 원문을 직접 소비하지 않는다 — 기존 validation 파이프라인(욕설·비속어 검열)을 통과하고 야구 무관 이슈(정치 등)가 걸러진 정제 데이터만 입력. 나무위키는 CC BY-NC-SA(비영리 한정) + 봇 차단 → 사람이 검수해 시드에 수동 추가하는 보조 경로로만 |
| 퀴즈 인계 | S3 `quiz-candidates/{date}/*.json` 계약 | BE quiz 도메인 엔티티가 아직 없음. 스키마·적재는 dev_be 소관(DDL 사본 금지 규칙) — 우리는 JSON 계약만 정의 |

## 3. 아키텍처

```
[py-collector (Lambda·크론)]               [Claude Code 클라우드 routine]
 RDB → S3 question-source/          ──▶  ① 위키 빌더 (주 1~2회, LLM)
   (game_result / game_schedule*         │    S3 wiki/players/{playerId}.md 병합 갱신
    / player_profile / player_meme)      │    S3 wiki/graph.json 재컴파일
 KBO 기록실 → S3 kbo-records/* 스냅샷    │    S3 wiki/stats/trending.md·all-time-records.md
                                         ▼
[validation 러너 (기존 AI 파이프라인)]    ② 퀴즈 생성기 (매일 아침, 경기 2h 전 마감)
 커뮤니티 원문 → 검열(욕설·비속어)        │    0) wiki/stats/ 시즌 요약 재집계 (결정적 스크립트)
   + 주제 필터(야구 무관 배제)      ──▶  │    1) 템플릿 선택 → 2) 데이터 바인딩
   → S3 정제 게시글  (위키 빌더 입력)    │    3) 문구 생성(LLM) → 검증 패스
   *신규                                 ▼
                                         S3 quiz-candidates/{date}/*.json ──▶ BE 소비 → Quiz DB
```

routine에는 최소 권한 IAM 자격증명만 부여: `question-source/`·`kbo-records/` 읽기,
`wiki/`·`quiz-candidates/` 읽기+쓰기.

## 4. 컴포넌트

### 4.1 위키 빌더 (routine ①)

- **입력**: **validation 러너 산출물**(검열·주제 필터 통과한 정제 커뮤니티 게시글 — 원문 직접
  소비 금지), `question-source/player_meme/`(시드), 기존 위키 문서,
  `question-gen/config/all-time-records.yaml`(역대 기록 시드 — 아래)
  - 정제 기준: 욕설·비속어 검열(기존 validation 모듈) + **야구 무관 이슈 배제**(정치·사회 등).
    주제 필터가 현재 validation에 없으면 validation 파이프라인 확장이 선행 작업(해당 모듈 소관)
  - 산출물 S3 경로는 기존 검열 러너의 out 경로를 따름(구현 시 확정)
- **처리**: 선수별로 관련 글 그룹핑 → 기존 문서 로드 → LLM이 신규 사실만 병합. 갱신 대상은
  마지막 실행 이후 수집분(파티션 날짜 기준 증분)
- **문서 구조** (고정 섹션):
  ```markdown
  ---
  name: 김도영
  team: HT
  playerUid: 412
  kboPlayerId: "60632"
  updatedAt: 2026-07-28
  relations:
    - { type: 밈공유, target: "60633", ref: "community_post:DCINSIDE:111..." }
  ---
  ## 프로필 요약
  ## 별명·밈
  ## 사건사고        ← 기록만, 퀴즈 출제 금지 (4.2 안전 규칙)
  ## 커리어 이력
  ## 최근 여론
  ```
- **환각 방지 규칙**: 모든 항목에 출처(`sourceRef`) 각주 필수. 출처 제시 못 하는 문장은 병합 거부.
  커뮤니티발 사실은 `(커뮤니티 전언)` 등급 표기. 비속어·비하 표현은 병합 시 정제
- **그래프 컴파일**: 갱신 마지막 단계에서 전체 문서 front-matter(`relations` + 팀 소속)를 긁어
  `wiki/graph.json`(nodes: 선수·팀·사건·밈, edges: typed) 하나로 재생성. 문서가 진실의 원천,
  graph.json은 언제든 재컴파일 가능한 파생물
- **화제 토픽 추출**: **정제 게시글(validation 통과분)에서만** 화제성 높은 주제(급증 키워드)를
  뽑아 `wiki/stats/trending.md`로 요약 — 생성기가 시의성 템플릿 우선순위에 사용. 논란·사건 관련
  토픽은 트렌딩에서 제외(안전 규칙)
- **역대 기록 (시드 기반)**: `question-gen/config/all-time-records.yaml` — KBO 기록실 History 페이지
  크롤 스냅샷에서 **반자동 생성 + 사람 검수**(통산 순위 top N·마일스톤·단일 경기 진기록,
  항목마다 기준일·출처 명시).
  위키 빌더가 `wiki/stats/all-time-records.md`로 렌더. **나무위키 원문 복사 금지**(CC BY-NC-SA
  비영리 라이선스 — 사실 수치만 공식 출처로 자체 구성), 논란성 주석(약물 등)은 안전 규칙에 따라 제외.
  현역 선수 통산 기록은 계속 변하므로 문제는 수치 정답형이 아닌 **순위·최초달성형**으로만 출제,
  갱신은 시즌 종료 후 1회 + 마일스톤 이벤트 시 수동 추가

### 4.2 퀴즈 생성기 (routine ②)

- **입력 (2층)**:
  - 시의성 층: 오늘 `game_schedule` 봉투(일정+선발 라인업), 최근 7일 `game_result` 봉투,
    `wiki/stats/trending.md`(화제 토픽)
  - 축적 층: `wiki/stats/` 시즌 요약, 출전 선수 위키 문서, `wiki/graph.json`
  - 그 외: 최근 7일 출제 이력(`quiz-candidates/` 목록), 질문 템플릿 카탈로그
- **질문 템플릿 카탈로그** (`question-gen/config/question-templates.yaml`, repo 관리): 문제
  다양성의 원천. 초기 시드 34종은 v0로 작성 완료. 이후 LLM에게 데이터 스키마·위키 샘플·트렌딩
  토픽을 주고 신규 템플릿을 제안시켜 **사람 검수 후** 카탈로그에 추가(성장 경로 — 카탈로그가
  안전·정산 정책의 통제면이므로 무검수 자동 추가는 하지 않는다). 템플릿마다 정의 — `id`,
  `kind`(지식/예측), `format`(출제 형식), 필요 입력(`needs`), 문제 의도 서술(문구는 LLM이
  자유 작성), 오답 전략(`distractor`), 예상 난이도. 예:
  ```yaml
  - id: H2H_SEASON_RECORD
    kind: KNOWLEDGE
    format: BINARY          # OX | BINARY(2지선다) | MULTI4(4지선다)
    needs: [stats.head_to_head]
    intent: "올해 두 팀의 상대전적 우위를 묻는다. 오늘 매치업 팀 우선"
    difficulty: MEDIUM
  - id: MEME_ORIGIN
    kind: KNOWLEDGE
    format: MULTI4
    needs: [wiki.별명·밈]
    intent: "선수 별명/밈의 유래를 4지선다로 묻는다"
    difficulty: EASY
  ```
- **출제 형식 규칙**: 모든 문제는 **O/X, 2지선다, 4지선다 중 하나** — 주관식 없음. 쇼츠처럼
  탭 한 번으로 즉답하고 넘기는 UX가 전제이므로 질문은 짧게, 보기는 즉시 판단 가능하게.
  사실 확인형은 가능하면 O/X 변형 우선(예: "김도영의 등번호는 5번이다 — O/X")
- **안전 규칙 (출제 금지 주제)**: 위키 `사건사고` 섹션과 graph의 `사건연루` 엣지는 **퀴즈
  소스로 사용 금지** — 기록은 유지하되(선수 맥락 이해용) 문제화하지 않는다. 법적 사건·논란·
  사생활·건강 문제를 소재로 한 문제는 명예훼손·2차 가해 리스크로 생성 단계에서 원천 배제
- **생성 (0+3단계)**: ⓪ 통계 재집계(전처리, 결정적 스크립트·LLM 미사용) — `question-source/
  game_result/` 봉투(상대전적·홈원정·연승연패·월별 추이·전년 대비)와 `kbo-records/` 스냅샷
  (일별 팀 순위·시즌 스탯 순위·마일스톤 임박)을 집계해 `wiki/stats/`를 **매일** 갱신, 기록
  정정(RecordCorrect) 공지 반영 → ① 템플릿 선택 — 오늘 매치업·트렌딩 토픽·graph.json 2-hop
  관계·출제 이력을 보고 오늘 쓸 템플릿과 대상 엔티티 결정(같은 템플릿 연속 출제 방지) →
  ② 데이터 바인딩 — 템플릿의 `needs`에 해당하는 데이터만 로드 → ③ 문구 생성 — LLM이 문제·
  선택지 작성. 예측 퀴즈에서 위키의 밈·여론은 문구 양념으로만 사용(정답 근거 아님)
- **검증 (같은 잡 내 2차 패스)**:
  1. 지식 퀴즈는 `evidence`에 위키 원문 인용 또는 봉투/stats 필드값 필수 — 근거 없으면 폐기
  2. 최근 7일 출제 이력과 중복 검사 (명세서 비즈니스 규칙) + 템플릿 편중 검사
  3. 안전 필터: 비하·편향 표현, 그리고 사건사고·법적 논란·사생활·건강을 언급하는 문제는 폐기
     (안전 규칙의 2차 방어선)
  4. 형식 검사: 보기 2개(O/X 포함) 또는 4개, 주관식 형태면 폐기
  5. 난이도·포인트 산정 (명세서 QUIZ-002 기준표: EASY 30P ~ EXPERT 120P, 일일 비율 30/40/20/10)
- **사례집 자동 갱신**: 검증 후 자기 출제물을 채점(재미·자연스러움·난이도 적합)해
  good/bad 사례집을 갱신 — 다음 실행의 few-shot으로 사용. 사람은 사례를 작성하지 않고
  거부권만 행사(별로인 유형을 한 줄 피드백 → 검증 규칙/템플릿에 반영)
- **출력**: `quiz-candidates/{date}/{quizId}.json`

### 4.3 quiz-candidates JSON 계약 (v2 — v1 하위호환)

명세서 3.1.1 퀴즈 데이터 구조 + 4필드 추가. BE는 이 스키마를 보고 엔티티·적재를 구현한다.
v2는 v1에 `subject`(주제 축, optional)를 더한 것이다 — BE가 optional로 받으므로
`subject` 없는 v1 후보도 그대로 유효하다(하위호환).

```json
{
  "quizId": "QZ-20260728-001",
  "gameId": "20260728LGSS02026",
  "teamCodes": ["LG", "SK"],
  "kind": "KNOWLEDGE | PREDICTION",
  "type": "WIN_LOSE | SCORE_RANGE | PLAYER_PERF | MEME | HISTORY | ...",
  "templateId": "H2H_SEASON_RECORD",
  "format": "OX | BINARY | MULTI4",
  "question": "...",
  "options": [{ "id": "A", "text": "..." }],
  "answer": "A",
  "evidence": { "source": "wiki/players/60632.md#별명·밈", "quote": "..." },
  "settlement": { "gameId": "...", "metric": "WIN_TEAM" },
  "subject": {
    "scope": "PLAYER | TEAM | MATCHUP | LEAGUE | GAME",
    "playerIds": [68050],
    "teamCodes": ["HH", "KT"],
    "gameId": "20260804SKLG02026"
  },
  "difficulty": "MEDIUM",
  "pointReward": 50,
  "status": "PENDING",
  "createdAt": "...", "deadlineAt": "...", "createdBy": "AI_ENGINE"
}
```

- `options`는 항상 2개(O/X·2지선다) 또는 4개(4지선다) — 주관식 없음
- **top-level `teamCodes` (귀속 축)** — 이 문항을 **어느 팀 팬에게 보여줄지**.
  경기 문항은 그 경기 양 팀 코드 2개, 공통 문항은 `[]`(빈 배열)이다
  (`question-gen/ROUTINE.md` 3단계 표가 정본). 실운영 후보에 처음부터 실려
  나가던 필드인데 v1 문서에 빠져 있어 여기서 명문화한다. 코드 축은 BE
  `teams.code`(= py-collector `dimensions.py`의 TEAMS, `infra/sql/teams-init.sql`
  시드)와 같다: 두산=OB, LG=LG, 삼성=SS, KT=KT, 키움=WO, KIA=HT, 한화=HH,
  NC=NC, 롯데=LT, SSG=SK. `validate_candidates.py`가 이 화이트리스트로 검사한다.
- **`subject` (주제 축, v2 optional)** — 이 문항이 **무엇에 관한 문제인지**.
  귀속 축과는 다른 축이다: "강백호가 FA로 새로 합류한 팀은?"이 한화 경기
  문항으로 **귀속**될 수 있어도 **주제**는 선수 강백호다. BE는 이 필드로
  문제를 선수/팀/매치업/리그/경기 단위로 분류·적재한다.
  - `scope`는 문항마다 판단하지 않는다 — **카탈로그 템플릿의 `subjectScope`
    선언을 그대로 따른다**(`question-gen/config/question-templates.yaml`, 결정적).
  - **정답 유출 방지 규칙**: subject에는 문제가 **'전제'하는 엔티티만** 담고,
    정답(또는 정답을 강하게 시사하는) 엔티티는 담지 않는다. 예 — "강백호가
    FA로 새로 합류한 팀은?"은 강백호가 전제(playerIds), 한화는 정답이라
    `teamCodes`를 비운다(scope=PLAYER). "이번 주 커뮤니티 최다 화제 선수는?"은
    전제 엔티티가 없다(선수가 정답 — scope=LEAGUE, 전부 빔). `validate_candidates.py`
    check 9가 팀에 한해 결정적으로 검사한다(subject 팀 이름이 정답 보기 문면에
    등장하면 위반, 오답 보기는 허용).
  - scope별 카디널리티: PLAYER→`playerIds` 1개 이상 / TEAM→`teamCodes` 정확히
    1개·`playerIds` 빔 / MATCHUP→`teamCodes` 정확히 2개 / LEAGUE→전부 빔 /
    GAME→`gameId` 필수. `subject.gameId`는 scope=GAME일 때만 채우고 그 외 null.
  - 값의 축: `playerIds`=KBO playerId 정수(위키 `wiki/players/{id}.md` 파일명과
    같은 축), `teamCodes`=위 구단 코드(= `season.json` `headToHead` 키의 팀코드),
    `gameId`=네이버 게임ID(envelope `entities.gameId`).
  - 작성 규칙 상세는 `question-gen/prompts/generation-rules.md` §11.
- `options`의 `id`는 **A부터 순서대로**(A/B/C/D)이고 배열 순서가 곧 표시 순서다.
  RDB `quiz_options.option`(UI 보기 번호)은 **0부터** 배열 인덱스 그대로 매긴다
  — A=0, B=1, C=2, D=3. OX는 `O`=A→0, `X`=B→1로 BE의 O/X 표기(0:1)와 일치한다.
  후보 JSON에 번호를 별도 필드로 싣지 않는다(`validate_candidates.py` check 2가
  id 순서를 강제하므로 위치만으로 결정된다 — 같은 정보를 두 곳에 두면 어긋난다)
- `answer`/`evidence`는 지식 퀴즈 필수, 예측 퀴즈는 `null`
- `settlement`은 예측 퀴즈 필수(경기 종료 후 BE가 RDB로 정답 판정할 키), 지식 퀴즈는 `null`
- `templateId`: 출제 템플릿 식별자. 지금은 기록만 하고, 추후 BE가 템플릿별 유저 반응(정답률·
  스킵률)을 집계해주면 템플릿 가중치 조정(피드백 루프)에 사용 — 미래 확장을 위한 선반영 필드
- 같은 `quizId` 재실행 시 멱등 덮어쓰기 (envelope와 동일 원칙)

### 4.4 py-collector 변경 (기존 코드 수정 2건)

1. **`game_schedule` docType export 추가** — 오늘·내일 SCHEDULED 경기의 일정+선발 라인업을
   결정적 템플릿으로 봉투화. 기존 계약상 소스 추가는 소비자 코드 변경 0.
2. **`kbo_records` 수집 소스 추가** — KBO 공식 기록실 페이지를 일 1회 파싱해 테이블을 JSON
   스냅샷으로 S3 `kbo-records/{page}/{date}.json`에 적재 (기존 `kbo_register`와 같은
   kbo_official 크롤 패턴). 대상 페이지: TeamRankDaily(일별 팀 순위), Hitter/PitcherBasic·
   Top5(시즌 개인 스탯), History/Top·History/Player(역대 통산), History/Team(역대 팀 기록),
   Expectation/WeekList(기록 달성 예상), RecordCorrect(기록 정정). 공식 소스라 라이선스 안전.
   위키 빌더 축적 층이 유일한 소비자이므로 envelope 재포장 없이 브론즈 스냅샷으로 충분.

DB 스키마 변경 없음. routine IAM 읽기 권한에 `kbo-records/` prefix 추가.

## 5. 에러 처리

- routine 실패 시: 폴백 퀴즈 투입은 BE/어드민 영역(명세서 규칙)으로 두고, 생성기는 실패를 알림
  (routine 실패 노티) + 다음 실행에서 재시도. quiz-candidates 적재는 멱등이라 중복 부작용 없음
- 위키 빌더 부분 실패(일부 선수만 갱신): 문서 단위 독립 커밋이라 다음 실행이 이어서 처리
- graph.json 컴파일 실패: 이전 버전 유지(생성기는 stale 그래프로도 동작 가능)

## 6. 리스크

- **나무위키 라이선스/차단**: 1차 제외. 도입 시 사람 검수 + 비영리 조건 재검토 필수
- **validation 파이프라인 의존**: 위키 빌더는 validation 통과 데이터만 소비하므로, 검열 커버리지
  (우회 표기)와 주제 필터(야구 무관 배제) 품질이 위키 품질의 상한. 우회 표기가 새는 경우를 대비해
  위키 빌더 LLM 병합 시 2차 정제는 유지. 주제 필터 신설 여부는 validation 모듈과 협의 필요
- **정제 게시글의 선수 매칭**: 선수 매칭(이름·별명 기반)은 위키 빌더의 LLM이 수행.
  오매칭은 출처 각주 덕에 사후 추적 가능
- **routine의 AWS 자격증명 관리**: 최소 권한 IAM 사용자 별도 발급, 시크릿은 routine 환경변수로
- **전년 데이터 부재 (확인 필요)**: "작년 대비" 류 템플릿은 2025 시즌 game_result가 S3/DB에
  있어야 성립. 백필 여부를 확인하고, 없으면 py-collector 백필(`records --from ... --to`) 선행.
  백필 전까지 해당 템플릿은 카탈로그에서 비활성 처리
- **선수 퍼포먼스 예측은 정산 불가**: 운영 스키마에 이닝·안타 등 상세 기록이 없음(games 스코어 +
  game_lineups decision뿐). 명세서의 "6이닝 이상 투구할까?" 류는 BE가 정답 판정을 못 하므로
  1차 제외 — 예측 템플릿은 스코어/승패/투수 decision으로 판정 가능한 것만. 마일스톤 달성 예측도
  같은 이유로 지식형(임박자 맞히기)으로만 출제. 상세 스탯 예측을 원하면 수집기·스키마 확장
  (dev_be 협의)이 별도 선행 과제
- **KBO 기록실 페이지 구조 변경 취약성**: ASP.NET 서버렌더 테이블 파싱이라 사이트 개편 시 파서
  깨짐. 일 1회 저빈도 크롤 + 파싱 실패 시 이전 스냅샷 유지·알림으로 대응

## 7. 테스트 전략

- 위키 빌더: 선수 3~5명 샘플로 로컬 `claude -p` 드라이런 → 문서 품질·출처 각주 육안 검증 후 스케줄 등록
- 통계 요약 집계: 결정적 스크립트이므로 고정 입력 봉투 → 기대 출력 단위 테스트(stdlib)
- 퀴즈 생성기: 과거 날짜 봉투로 재현 실행 → evidence 없는 문제가 실제로 폐기되는지 확인
- quiz-candidates 스키마 검증 스크립트(stdlib) 1개 — CI 없이 로컬/routine 양쪽에서 실행 가능
- py-collector `game_schedule` export: 기존 exporter 테스트 패턴 준수(pytest)

## 8. 비범위 (Out of Scope)

- 퀴즈 정산(예측 퀴즈 정답 판정)·포인트 지급 — BE 소관 (`settlement` 키만 제공)
- Quiz DB 테이블 설계·적재 — dev_be 소관 (이 리포에 DDL 사본 만들지 않음)
- 어드민 퀴즈 CRUD(QUIZ-003), 푸시 알림(QUIZ-004)
- 템플릿 피드백 루프(유저 정답률·스킵률 기반 가중치 조정) — `templateId` 선반영으로 자리만 확보,
  집계·조정 로직은 유저 데이터가 쌓인 뒤 2차로
- 벡터 검색/그래프 DB — 문서 수만 개 규모가 되면 graph.json·마크다운을 소스로 재검토
