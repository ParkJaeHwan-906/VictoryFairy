# LLM 위키 + 퀴즈 생성 파이프라인 설계

> 작성일: 2026-07-28 · 상태: 사용자 승인 대기
> 범위: VictoryFairy_AI 신규 서브시스템(위키 빌더·퀴즈 생성기) + py-collector export 1종 추가

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
| 그래프 | **그래프-라이트** (파생 인덱스 `wiki/graph.json`) | 관계 기반 문제 각도(사건 연루·라이벌·밈 공유)는 확보하되 그래프 DB·동기화·자격증명 부담 제거. 커지면 graph.json이 그대로 Neo4j 임포트 소스 |
| 실행 환경 | Claude Code 클라우드 스케줄 잡(routine) 2개 | 맥북 무관하게 동작. LLM 호출 = Claude Code 자체 |
| 데이터 접근 | **S3 전용** (routine은 DB 미접근) | RDB 데이터는 py-collector export가 envelope로 공급. 클라우드에 DB 자격증명을 열지 않음 |
| 위키 소스 | 커뮤니티 글(LLM 추출) + memes.yaml 시드. 나무위키는 1차 제외 | 나무위키는 CC BY-NC-SA(비영리 한정) + 봇 차단 → 사람이 검수해 시드에 수동 추가하는 보조 경로로만 |
| 퀴즈 인계 | S3 `quiz-candidates/{date}/*.json` 계약 | BE quiz 도메인 엔티티가 아직 없음. 스키마·적재는 dev_be 소관(DDL 사본 금지 규칙) — 우리는 JSON 계약만 정의 |

## 3. 아키텍처

```
[py-collector EC2 크론]                    [Claude Code 클라우드 routine]
 RDB·커뮤니티 → S3 question-source/  ──▶  ① 위키 빌더 (주 1~2회)
   (game_result / game_schedule*        │    S3 wiki/players/{playerId}.md 갱신
    / player_profile / community_post   │    S3 wiki/graph.json 재컴파일
    / player_meme)   *신규              ▼
                                        ② 퀴즈 생성기 (매일 아침, 경기 2h 전 마감)
                                        │    graph.json → 문제 각도 선정
                                        │    위키 문서 + 봉투 → 지식/예측 퀴즈 + 검증
                                        ▼
                                        S3 quiz-candidates/{date}/*.json ──▶ BE 소비 → Quiz DB
```

routine에는 최소 권한 IAM 자격증명만 부여: `question-source/` 읽기, `wiki/`·`quiz-candidates/` 읽기+쓰기.

## 4. 컴포넌트

### 4.1 위키 빌더 (routine ①)

- **입력**: S3 `question-source/community_post/`(무필터 원문), `player_meme/`(시드), 기존 위키 문서
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
  ## 사건사고
  ## 커리어 이력
  ## 최근 여론
  ```
- **환각 방지 규칙**: 모든 항목에 출처(`sourceRef`) 각주 필수. 출처 제시 못 하는 문장은 병합 거부.
  커뮤니티발 사실은 `(커뮤니티 전언)` 등급 표기. 비속어·비하 표현은 병합 시 정제
- **그래프 컴파일**: 갱신 마지막 단계에서 전체 문서 front-matter(`relations` + 팀 소속)를 긁어
  `wiki/graph.json`(nodes: 선수·팀·사건·밈, edges: typed) 하나로 재생성. 문서가 진실의 원천,
  graph.json은 언제든 재컴파일 가능한 파생물

### 4.2 퀴즈 생성기 (routine ②)

- **입력**: 오늘 `game_schedule` 봉투(일정+선발 라인업), 최근 7일 `game_result` 봉투,
  `wiki/graph.json`, 출전 선수 위키 문서, 최근 7일 출제 이력(`quiz-candidates/` 목록)
- **생성**:
  - 예측 퀴즈 (경기당 2~3개): 승패 / 스코어 범위 / 선수 퍼포먼스 — 명세서 QUIZ-001 유형.
    위키의 밈·여론은 문구 양념으로만 사용(정답 근거 아님)
  - 지식 퀴즈 (선수/팀당 1~2개): 위키 + 과거 경기 봉투 기반 정답 확정형.
    graph.json에서 오늘 출전 선수와 2-hop 이내 관계를 순회해 "엮이는 각도"(사건 연루·밈 공유 등)를 우선 채택
- **검증 (같은 잡 내 2차 패스)**:
  1. 지식 퀴즈는 `evidence`에 위키 원문 인용 또는 봉투 필드값 필수 — 근거 없으면 폐기
  2. 최근 7일 출제 이력과 중복 검사 (명세서 비즈니스 규칙)
  3. 비하·편향 표현 필터
  4. 난이도·포인트 산정 (명세서 QUIZ-002 기준표: EASY 30P ~ EXPERT 120P, 일일 비율 30/40/20/10)
- **출력**: `quiz-candidates/{date}/{quizId}.json`

### 4.3 quiz-candidates JSON 계약 (v1)

명세서 3.1.1 퀴즈 데이터 구조 + 3필드 추가. BE는 이 스키마를 보고 엔티티·적재를 구현한다.

```json
{
  "quizId": "QZ-20260728-001",
  "gameId": "20260728LGSS02026",
  "kind": "KNOWLEDGE | PREDICTION",
  "type": "WIN_LOSE | SCORE_RANGE | PLAYER_PERF | MEME | HISTORY | ...",
  "question": "...",
  "options": [{ "id": "A", "text": "..." }],
  "answer": "A",
  "evidence": { "source": "wiki/players/60632.md#별명·밈", "quote": "..." },
  "settlement": { "gameId": "...", "metric": "WIN_TEAM" },
  "difficulty": "MEDIUM",
  "pointReward": 50,
  "status": "PENDING",
  "createdAt": "...", "deadlineAt": "...", "createdBy": "AI_ENGINE"
}
```

- `answer`/`evidence`는 지식 퀴즈 필수, 예측 퀴즈는 `null`
- `settlement`은 예측 퀴즈 필수(경기 종료 후 BE가 RDB로 정답 판정할 키), 지식 퀴즈는 `null`
- 같은 `quizId` 재실행 시 멱등 덮어쓰기 (envelope와 동일 원칙)

### 4.4 py-collector 변경 (유일한 기존 코드 수정)

`game_schedule` docType export 추가 — 오늘·내일 SCHEDULED 경기의 일정+선발 라인업을 결정적
템플릿으로 봉투화. 기존 계약상 소스 추가는 소비자 코드 변경 0. DB 스키마 변경 없음.

## 5. 에러 처리

- routine 실패 시: 폴백 퀴즈 투입은 BE/어드민 영역(명세서 규칙)으로 두고, 생성기는 실패를 알림
  (routine 실패 노티) + 다음 실행에서 재시도. quiz-candidates 적재는 멱등이라 중복 부작용 없음
- 위키 빌더 부분 실패(일부 선수만 갱신): 문서 단위 독립 커밋이라 다음 실행이 이어서 처리
- graph.json 컴파일 실패: 이전 버전 유지(생성기는 stale 그래프로도 동작 가능)

## 6. 리스크

- **나무위키 라이선스/차단**: 1차 제외. 도입 시 사람 검수 + 비영리 조건 재검토 필수
- **커뮤니티 원문 무필터**: 위키 빌더 프롬프트에서 정제를 명시 수행. 필요 시 기존 validation 모듈
  통과 후 투입으로 강화
- **community_post 봉투는 entities가 비어 있음**: 선수 매칭을 위키 빌더의 LLM이 수행(이름·별명 기반).
  오매칭은 출처 각주 덕에 사후 추적 가능
- **routine의 AWS 자격증명 관리**: 최소 권한 IAM 사용자 별도 발급, 시크릿은 routine 환경변수로

## 7. 테스트 전략

- 위키 빌더: 선수 3~5명 샘플로 로컬 `claude -p` 드라이런 → 문서 품질·출처 각주 육안 검증 후 스케줄 등록
- 퀴즈 생성기: 과거 날짜 봉투로 재현 실행 → evidence 없는 문제가 실제로 폐기되는지 확인
- quiz-candidates 스키마 검증 스크립트(stdlib) 1개 — CI 없이 로컬/routine 양쪽에서 실행 가능
- py-collector `game_schedule` export: 기존 exporter 테스트 패턴 준수(pytest)

## 8. 비범위 (Out of Scope)

- 퀴즈 정산(예측 퀴즈 정답 판정)·포인트 지급 — BE 소관 (`settlement` 키만 제공)
- Quiz DB 테이블 설계·적재 — dev_be 소관 (이 리포에 DDL 사본 만들지 않음)
- 어드민 퀴즈 CRUD(QUIZ-003), 푸시 알림(QUIZ-004)
- 벡터 검색/그래프 DB — 문서 수만 개 규모가 되면 graph.json·마크다운을 소스로 재검토
