# Casebook — 좋은 예 (Good)

> routine이 매 실행 자기 채점으로 이 파일을 갱신한다(검증 패스 4단계 — 재미·자연스러움
> 5점 채점 사례). **사람은 사례를 직접 작성하지 않고 거부권만 행사한다**(별로인 유형을
> 한 줄 피드백으로 남기면 다음 실행의 검증 규칙/템플릿 판단에 반영, 스펙 4.2). 아래
> 3건은 최초 시드(사람이 임시로 작성) — 이후 실행에서 실제 출제 결과로 자연히 대체된다.

## 1. H2H_SEASON_RECORD (지식 · 상대전적)

```json
{
  "quizId": "QZ-20260730-001",
  "gameId": "20260730LGHT02026",
  "kind": "KNOWLEDGE",
  "type": "HISTORY",
  "templateId": "H2H_SEASON_RECORD",
  "format": "BINARY",
  "question": "올해 LG는 KIA와의 상대전적에서 우위다?",
  "options": [{ "id": "A", "text": "LG 우위" }, { "id": "B", "text": "KIA 우위" }],
  "answer": "A",
  "evidence": {
    "source": "wiki/stats/season.md#상대전적",
    "quote": "- **HT vs LG**: HT 2승 LG 4승 · 1무 (최근 2026-07-15 3:5, 홈팀 승)"
  },
  "settlement": null,
  "difficulty": "MEDIUM",
  "pointReward": 50,
  "status": "PENDING",
  "createdAt": "2026-07-30T23:50:00Z",
  "deadlineAt": "2026-07-30T23:59:59Z",
  "createdBy": "AI_ENGINE"
}
```

**좋은 이유**: `season.json`은 dict일 뿐 자연어 문장이 아니므로(`generation-rules.md`
§2) `evidence.source`가 렌더 짝인 `.md` 경로(`wiki/stats/season.md#상대전적`)를
가리키고, `evidence.quote`도 `aggregate_stats.py`의 `_render_head_to_head_section`이
실제로 만드는 렌더 줄 형식(`- **A vs B**: ...`, 키는 팀코드 사전순 — `HT` < `LG`)을
글자 그대로 인용했다. 오늘 매치업 팀(LG vs HT) 조합을 우선한 것도 카탈로그 intent와
정확히 맞는다.

## 2. MEME_ORIGIN (지식 · 밈 유래)

```json
{
  "quizId": "QZ-20260730-002",
  "gameId": null,
  "kind": "KNOWLEDGE",
  "type": "MEME",
  "templateId": "MEME_ORIGIN",
  "format": "MULTI4",
  "question": "김도영의 별명 '5도영'의 유래는?",
  "options": [
    { "id": "A", "text": "등번호 5번과 배터리 소모 밈이 결합" },
    { "id": "B", "text": "5월에 데뷔했기 때문" },
    { "id": "C", "text": "통산 5번째 신인왕이라서" },
    { "id": "D", "text": "5경기 연속 홈런을 쳐서" }
  ],
  "answer": "A",
  "evidence": {
    "source": "wiki/players/60632.md#별명·밈",
    "quote": "등번호 5번에 '한 게임 배터리 5%씩 깎아먹는다'는 커뮤니티 드립이 붙어 '5도영'으로 불린다."
  },
  "settlement": null,
  "difficulty": "EASY",
  "pointReward": 30,
  "status": "PENDING",
  "createdAt": "2026-07-30T23:50:05Z",
  "deadlineAt": "2026-07-30T23:59:59Z",
  "createdBy": "AI_ENGINE"
}
```

**좋은 이유**: 오답 3개가 전부 "그럴듯한 가짜 유래"(distractor 전략 그대로) 형태고,
정답만 위키 원문을 그대로 인용해 evidence 대조에 그대로 통과한다.

## 3. PRED_WIN_LOSE (예측 · 오늘 경기 승자)

```json
{
  "quizId": "QZ-20260730-003",
  "gameId": "20260730LGHT02026",
  "kind": "PREDICTION",
  "type": "WIN_LOSE",
  "templateId": "PRED_WIN_LOSE",
  "format": "BINARY",
  "question": "오늘 LG vs KIA, 승자는?",
  "options": [{ "id": "A", "text": "LG 승" }, { "id": "B", "text": "KIA 승" }],
  "answer": null,
  "evidence": null,
  "settlement": { "gameId": "20260730LGHT02026", "metric": "WIN_TEAM" },
  "difficulty": "MEDIUM",
  "pointReward": 50,
  "status": "PENDING",
  "createdAt": "2026-07-29T23:50:10Z",
  "deadlineAt": "2026-07-30T07:30:00Z",
  "createdBy": "AI_ENGINE"
}
```

**좋은 이유**: 예측 퀴즈답게 `answer`/`evidence`가 `null`이고 `settlement.metric`이
템플릿 정의(`WIN_TEAM`)를 그대로 썼으며, 위키의 여론·밈은 문구에 등장시키지 않고
순수 승부 예측으로만 남겨 정산 가능성을 지켰다. 날짜도 서로 앞뒤가 맞는다 —
routine은 매일 08:50 KST에 도는데 그 시각은 UTC로 전날 23:50이라
`createdAt`(`2026-07-29T23:50:10Z`)이 `quizId`/`gameId`의 날짜(`20260730`)보다
하루 이른 UTC 타임스탬프인 게 맞고, `deadlineAt`은 `generation-rules.md`의
"PREDICTION = 경기 시작(KST) 2시간 전" 규칙대로 이 경기의 18:30 KST 시작 기준
16:30 KST(= `07:30:00Z`)로 계산됐다 — `createdAt`(생성 시점)보다 미래이면서
경기 당일(KST) 안에 들어온다.

## 4. CAREER_PATH (지식 · 위키 커리어 이력) — 2026-08-01 실행 5점 사례

```json
{
  "quizId": "QZ-20260801-002",
  "gameId": null,
  "kind": "KNOWLEDGE",
  "type": "CAREER",
  "templateId": "CAREER_PATH",
  "format": "MULTI4",
  "question": "하주석이 올해 7월 트레이드로 합류한 팀은?",
  "options": [
    { "id": "A", "text": "KIA" }, { "id": "B", "text": "삼성" },
    { "id": "C", "text": "롯데" }, { "id": "D", "text": "NC" }
  ],
  "answer": "A",
  "evidence": {
    "source": "wiki/players/62700.md#커리어 이력",
    "quote": "2026년 7월 말 한화 이글스에서 KIA 타이거즈로 트레이드 이적(상대는 투수 이형범)"
  },
  "settlement": null,
  "difficulty": "HARD",
  "pointReward": 80,
  "status": "PENDING",
  "createdAt": "2026-08-01T12:05:00Z",
  "deadlineAt": "2026-08-01T14:59:00Z",
  "createdBy": "AI_ENGINE"
}
```

**좋은 이유**: 위키 빌더가 그 주의 커뮤니티 화제(트레이드 직후)를 커리어 이력으로
병합해 두면, 퀴즈 생성기가 그 문서를 근거로 시의성 있는 문제를 만들 수 있음을 보여주는
파이프라인 관통 사례다. evidence가 위키 문서의 해당 섹션 원문을 글자 그대로 인용했고,
오답 보기(같은 리그 타 구단)도 형식·길이가 정답과 균일하다.

## 5. RELATION_LINK (지식 · 그래프 관계) — 2026-08-01 실행 5점 사례

```json
{
  "quizId": "QZ-20260801-007",
  "gameId": null,
  "kind": "KNOWLEDGE",
  "type": "MEME",
  "templateId": "RELATION_LINK",
  "format": "MULTI4",
  "question": "두산 곽빈이 삼성 페덱에게 받은 도움은?",
  "options": [
    { "id": "A", "text": "커브 구종 조언" }, { "id": "B", "text": "웨이트 프로그램" },
    { "id": "C", "text": "영어 회화 과외" }, { "id": "D", "text": "배트 선물" }
  ],
  "answer": "A",
  "evidence": {
    "source": "wiki/players/68220.md#커리어 이력",
    "quote": "NC 라일리와 삼성 페덱에게 커브 구종에 대한 도움을 받았다고 본인이 밝혔다"
  },
  "settlement": null,
  "difficulty": "EXPERT",
  "pointReward": 120,
  "status": "PENDING",
  "createdAt": "2026-08-01T12:05:00Z",
  "deadlineAt": "2026-08-01T14:59:00Z",
  "createdBy": "AI_ENGINE"
}
```

**좋은 이유**: graph.json의 `커리어교차` 엣지(곽빈↔페덱)를 실제로 소비한 첫 사례.
관계의 근거 문장이 위키 문서에 각주와 함께 존재해 evidence 대조가 한 번에 통과했고,
`사건연루` 엣지가 아닌 안전한 엣지만 썼다(생성 규칙 §4). 팀을 넘나드는 의외의
연결고리라 EXPERT 난이도에 걸맞은 "아는 사람만 아는" 재미가 있다.
