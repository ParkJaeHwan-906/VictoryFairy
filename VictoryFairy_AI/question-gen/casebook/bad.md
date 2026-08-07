# Casebook — 나쁜 예 (Bad)

> routine이 매 실행 자기 채점으로 이 파일을 갱신한다(검증 패스에서 폐기된 사례,
> 재미 점수 2점 이하 등). **사람은 사례를 직접 작성하지 않고 거부권만 행사한다**
> (스펙 4.2). 아래 3건은 최초 시드 — 검증 패스가 반드시 걸러내야 하는 유형의 예시다.

## 1. 주관식 형태 (형식 위반)

```json
{
  "quizId": "QZ-BAD-001",
  "templateId": "YESTERDAY_WINNER",
  "format": "BINARY",
  "question": "어제 경기에서 가장 인상적인 장면은 무엇이었나요?",
  "options": [{ "id": "A", "text": "자유롭게 서술" }],
  "answer": null
}
```

**나쁜 이유**: `format: BINARY`라 선언했는데 실제 문제는 주관식(자유 서술)이고
`options`도 1개뿐이다 — 출제 형식 규칙(OX/BINARY 2개·MULTI4 4개, 즉시 판단 가능한
보기)을 완전히 벗어나 검증 패스 형식 검사(구 스펙 4.2 검증 4단계)에서 폐기된다.

## 2. evidence 없는 단정 (근거 없는 지식 퀴즈)

```json
{
  "quizId": "QZ-BAD-002",
  "templateId": "MEME_ORIGIN",
  "format": "MULTI4",
  "kind": "KNOWLEDGE",
  "question": "박병호의 별명 '홈런왕'의 유래는?",
  "options": [
    { "id": "A", "text": "2015년 시즌 53홈런으로 개인 최다 기록을 세워서" },
    { "id": "B", "text": "고교 시절부터 붙은 별명" },
    { "id": "C", "text": "은퇴 후 코치가 되면서 붙은 별명" },
    { "id": "D", "text": "팬들이 만든 응원가 제목" }
  ],
  "answer": "A",
  "evidence": {
    "source": "wiki/players/xxxxx.md#별명·밈",
    "quote": "박병호는 2015년 53홈런을 치며 리그 최고의 홈런타자로 자리매김했다."
  }
}
```

**나쁜 이유**: `evidence.quote`가 위키 원문을 그대로 인용한 게 아니라 LLM이
사실을 종합해 **창작**한 문장이다(생성 규칙 2번 위반) — 검증 패스 1단계가
`wiki/players/xxxxx.md`를 실제로 열어 이 문자열을 찾지 못하면 즉시 폐기된다.
근거를 찾지 못했다고 해서 그럴듯한 문장을 지어 채우면 안 된다.

## 3. 사건 언급 (안전 규칙 위반)

```json
{
  "quizId": "QZ-BAD-003",
  "templateId": "RELATION_LINK",
  "format": "MULTI4",
  "kind": "KNOWLEDGE",
  "question": "2023년 음주운전 사건으로 논란이 된 선수는?",
  "options": [
    { "id": "A", "text": "선수 A" },
    { "id": "B", "text": "선수 B" },
    { "id": "C", "text": "선수 C" },
    { "id": "D", "text": "선수 D" }
  ],
  "answer": "A",
  "evidence": { "source": "wiki/players/xxxxx.md#사건사고", "quote": "..." }
}
```

**나쁜 이유**: 위키의 `사건사고` 섹션을 정답 근거로 직접 인용했고, 질문 문구에
"음주운전"(banned-topics.txt 소재)까지 노출됐다 — 안전 규칙(사건사고·법적 논란
소재 출제 금지, 사건사고 섹션은 기록용일 뿐 퀴즈 소스 사용 금지)을 이중으로
위반한다. 결정적 키워드 필터(`validate_candidates.py`)와 검증 패스 3단계
(안전 재검) 양쪽에서 걸려야 정상이다.

## 사례: 범용 호칭 패턴을 고유 밈으로 착각 (2026-08-04, 사람 검수로 적발)

```json
{
  "templateId": "MEME_ORIGIN",
  "format": "MULTI4",
  "kind": "KNOWLEDGE",
  "question": "삼성 구자욱이 결정적 한 방을 쳤을 때 팬들이 부르는 존칭형 별명은?",
  "options": [
    { "id": "A", "text": "대자욱" },
    { "id": "B", "text": "구느님" },
    { "id": "C", "text": "왕자욱" },
    { "id": "D", "text": "갓자욱" }
  ],
  "answer": "A",
  "evidence": { "source": "wiki/players/62404.md#별명·밈", "quote": "대자욱: 결정적 한 방을 쳤을 때 팬들이 부르는 존칭형 별명" }
}
```

**나쁜 이유**: "대◯◯"는 잘한 선수 아무에게나 이름 앞에 "대"를 붙이는 **커뮤니티
공통 관용 패턴**이지 구자욱 고유의 밈이 아니다(사람 검수자 피드백 원문: "그냥
사람들이 보통 홈런 치거나 대단한 일 하면 이름 앞에 '대'를 붙여서 부르곤 하는데?
정답이 있는 밈이 아냐"). 같은 논리로 "갓◯◯"·"◯느님" 같은 오답 보기들도 현실에서
통용될 수 있어 **정답의 유일성이 성립하지 않는다**. evidence가 위키 원문과
일치하고 안전·중복 검사를 통과하더라도, **고유성 검사**(generation-rules §4-1,
verification-pass §3-1)에서 폐기해야 한다. 위키 `별명·밈` 섹션 수록은 커뮤니티
전언의 기록일 뿐 출제 적격의 보증이 아니다 — 출제 적격은 사람 승인 밈
시드(memes.yaml)가 보증한다.
