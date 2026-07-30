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
    "source": "wiki/stats/season.json#headToHead.LG|HT",
    "quote": "LG 4승 HT 2승 · 1무"
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

**좋은 이유**: `evidence.quote`가 `season.json`의 `headToHead` 값 형식("A승 B승 ·
무")을 원문 그대로 인용했고, 오늘 매치업 팀(LG vs HT) 조합을 우선한 것이 카탈로그
intent와 정확히 맞는다.

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
  "createdAt": "2026-07-30T23:50:10Z",
  "deadlineAt": "2026-07-30T18:29:59Z",
  "createdBy": "AI_ENGINE"
}
```

**좋은 이유**: 예측 퀴즈답게 `answer`/`evidence`가 `null`이고 `settlement.metric`이
템플릿 정의(`WIN_TEAM`)를 그대로 썼으며, 위키의 여론·밈은 문구에 등장시키지 않고
순수 승부 예측으로만 남겨 정산 가능성을 지켰다.
