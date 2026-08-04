# 문구 생성 규칙 (퀴즈 생성기 routine ③ 단계)

퀴즈 생성기 routine의 ③ 문구 생성 단계(스펙 4.2)에서 LLM이 지켜야 하는 규칙이다.
템플릿 카탈로그(`question-gen/config/question-templates.yaml`)가 문제의 "틀"(id·kind·
format·needs·intent·distractor·settlement·difficulty)을 정하고, 이 문서는 그 틀 안에서
**문구를 실제로 어떻게 쓸지**를 정한다. 문구 자체는 자유 작성이되 아래 규칙을 벗어나면
검증 패스(`verification-pass.md`)에서 폐기된다.

## 1. 출제 형식

- 모든 문제는 **OX / BINARY(2지선다) / MULTI4(4지선다) 중 하나** — 주관식 없음.
- **OX**: 보기는 항상 `[{"id":"A","text":"O"},{"id":"B","text":"X"}]` — 다른 문구로
  바꾸지 않는다(예: "그렇다/아니다" 금지).
- **BINARY**: 보기 2개, 즉시 판단 가능한 단어/짧은 구(예: 팀명 2개, "이긴다/진다").
- **MULTI4**: 보기 4개, `id`는 A/B/C/D 순서. 오답(`distractor`)은 카탈로그의 전략을
  따르되 정답과 형식·길이가 비슷해야 한다(정답만 유난히 길거나 구체적이면 답이 티남).
- 질문은 1문장, **40자 이내 권장**(쇼츠형 UX — 탭 한 번으로 즉답). 보기 텍스트도
  길게 늘어놓지 않는다.

## 2. 지식 퀴즈(KNOWLEDGE) — evidence 규칙

- `evidence.quote`는 **투입된 자료의 원문 그대로**여야 한다. LLM이 요약·의역·창작한
  문장을 quote에 넣는 것을 금지한다 — 검증 패스 1단계가 이 quote를 원문에서
  문자열로 찾아 대조하므로, 원문과 한 글자라도 다르면 대조에 실패해 폐기된다.
- `evidence.source`는 자료의 경로 + 섹션(가능하면)까지 명시한다.
  - 위키 문서 인용: `wiki/players/{kboPlayerId}.md#{섹션명}`
  - stats 봉투 인용: `wiki/stats/season.json#{키경로}` 또는
    `wiki/stats/kbo-official.json#{키경로}`
  - envelope 인용: `question-source/{docType}/{date}/{doc_id}.json`
  - all-time-records 인용: `question-gen/config/all-time-records.yaml#{category id}`
- **`*.json`(season.json/kbo-official.json)은 값 dict일 뿐 자연어 문장이 아니다** —
  이 경우 `evidence.quote`는 짝을 이루는 렌더 결과물(`season.md`/`kbo-official.md`)의
  **해당 줄을 그대로** 인용한다(예: `"- **HH vs WO**: HH 1승 WO 0승 · 0무 (최근
  2026-03-28 10:9, 홈팀 승)"`) — `source`도 이때는 `.md` 파일 경로로 적는다
  (`wiki/stats/season.md#상대전적`). `all-time-records.yaml`처럼 렌더 짝이 없는
  자료는 YAML 항목을 원문 그대로(`rank`/`name`/`value` 키 그대로) 인용한다.
- 근거를 못 찾으면(즉 투입 자료에 실제로 없는 사실이면) 그 문제는 만들지 않는다 —
  "그럴듯해 보이지만 근거 없는" 문제를 만들고 검증 단계가 걸러주길 기대하지 않는다
  (검증은 2차 방어선이다).

## 3. 예측 퀴즈(PREDICTION) 규칙

- 위키의 밈·여론(최근 여론 섹션 등)은 **문구를 재미있게 만드는 양념으로만** 쓴다 —
  "정답 근거"로 인용하지 않는다(예측은 애초에 정답이 없으므로 evidence 필드 자체가
  `null`이다).
- `settlement.metric`은 해당 템플릿의 `settlement` 값을 **그대로** 쓴다
  (`WIN_TEAM` / `TOTAL_RUNS` / `SCORE_GAP` / `PITCHER_DECISION`) — LLM이 새로운
  지표명을 만들어내지 않는다. `settlement.gameId`는 데이터 바인딩 단계에서 확정된
  오늘 경기의 `gameId`를 그대로 넣는다.
- `answer`/`evidence`는 예측 퀴즈에서 항상 `null`.

## 4. 안전 규칙 (스펙 4.2 — 생성 단계 1차 방어선)

- `question-gen/config/banned-topics.txt`에 있는 어떤 소재도 질문·보기 문구에
  등장시키지 않는다(음주·폭행·마약·도박·승부조작·성범죄·불법·구속영장/구속기소·
  사생활·열애·이혼·불륜·병역·학폭·건강 문제 등). 이 목록은 결정적 키워드
  검사(`validate_candidates.py`)의 입력이기도 하므로, 여기서 걸리면 업로드 직전
  게이트에서도 다시 걸린다.
- 위키 문서의 **`사건사고` 섹션**과 `wiki/graph.json`의 **`사건연루` 엣지**는 입력
  자료에 포함돼 있어도 **퀴즈 소재로 사용하지 않는다** — 다른 섹션(별명·밈, 커리어
  이력 등)만 문제화한다.
- 비하·편향 표현, 특정 팀/선수를 깎아내리는 뉘앙스 없이 중립적으로 쓴다.

### 4-1. 밈·별명 고유성 (2026-08-04 추가 — 사람 검수 피드백)

- 밈·별명을 정답으로 삼는 문제는 그 표현이 **해당 선수에게 고유**할 때만 만든다.
  "대◯◯"(잘하면 이름 앞에 大), "갓◯◯", "◯느님", "◯황" 같은 **범용 호칭 패턴에
  이름만 대입한 표현은 밈이 아니다** — 리그 전체에서 아무에게나 통용되므로 오답
  보기들도 성립해 버려 **정답의 유일성이 깨진다** (실사례: casebook/bad.md
  '대자욱' 항목).
- 판별 질문: *"이 별명을 다른 잘하는 선수에게 그대로 옮겨 붙여도 자연스러운가?"*
  — 그렇다면 범용 패턴이므로 출제하지 않는다.
- 고유성이 확실한 소재: 본인 어록, 특정 사건·일화에서 유래한 표현, 사람 승인 밈
  시드(memes.yaml)에 등재된 항목. 위키 `별명·밈` 섹션에 있다는 사실만으로는
  고유성이 보장되지 않는다(위키는 커뮤니티 전언을 수집한 것일 뿐이다).

## 5. 난이도·포인트 매핑

기능명세서 기준 난이도 정의(대략): EASY = 단순 승패·기본 사실 확인, MEDIUM = 최근
경기·시즌 통계 비교, HARD = 추세·순위 변동 등 계산이 필요한 사실, EXPERT = 역대 기록·
마니아급 지식. 카탈로그의 `difficulty` 값을 그대로 쓰고, `pointReward`는 아래 표로
고정 매핑한다(임의 값 부여 금지):

| difficulty | pointReward |
|---|---|
| EASY | 30 |
| MEDIUM | 50 |
| HARD | 80 |
| EXPERT | 120 |

일일 출제 비율은 30/40/20/10(EASY/MEDIUM/HARD/EXPERT) — 검증 패스 5단계에서
최종 선별 시 이 비율에 맞춘다(생성 단계는 넉넉히 만들어 두면 된다).

## 6. all-time-records.yaml 소비 규칙 — rankBasis (혼용 금지)

`stats.all_time_records`(`question-gen/config/all-time-records.yaml`)를 쓰는 템플릿은
`ALL_TIME_LEADER`·`MILESTONE_FIRST`·`RECORD_OX` 세 개뿐이다. 이 파일의 카테고리마다
있는 **`rankBasis` 필드를 반드시 확인**하고 아래 규칙을 지킨다 — 혼용하면 오답 문제가
생성된다:

- `rankBasis: "chronological"` — `rank`는 KBO가 매긴 순위가 아니라 **표의 행 순서
  (연도 오름차순, 1=최초 달성)**다. **`MILESTONE_FIRST`(최초 달성자 맞히기) 전용.**
  `ALL_TIME_LEADER`("역대 1위") 문제에 이 카테고리의 `rank==1`을 "역대 통산 1위"로
  쓰면 안 된다 — 실제로는 "그 부문 최초 달성자"일 뿐이다(예: 1982년 첫 타율왕을
  "역대 타율 1위"로 오인하는 문제가 생김).
- `rankBasis: "true-rank"` — `rank`가 실제 KBO 통산 순위다. **`ALL_TIME_LEADER`
  전용.**
- `RECORD_OX`(사실 확인형, "오승환은 세이브 1위다 — O/X")는 두 `rankBasis` 모두 쓸 수
  있지만, 문구에서 "1위"라고 단언하려면 그 카테고리가 `true-rank`인지 반드시 먼저
  확인한다. `chronological` 카테고리로 "1위" 사실 확인을 만들 때는 "최초 달성"
  의미로만 서술한다(예: "○○는 KBO 최초 타율왕이다 — O/X").
- 이 YAML은 **아직 v0 초안(사람 검수 전)**이다(`YAML_HEADER` 경고 참조). 이 파일을
  실제로 소비해 문제를 만들면, 생성 산출물(candidate JSON 또는 실행 로그)에 "출처가
  검수 전 v0 초안임"을 주석/메모로 남긴다 — 사람이 나중에 검수 완료 표시를 지우면
  이 주석도 생략 가능해진다.
- `value`(수치) 열은 참고용일 뿐 정답으로 쓰지 않는다(스펙 4.1) — 순위·최초달성
  여부만 묻는다.

## 7. quizId 결정적 부여

- 형식: `QZ-{YYYYMMDD}-{NNN}` (`YYYYMMDD`는 출제 대상 날짜, `NNN`은 001부터 3자리).
- **부여 시점은 검증 통과 후다.** ③ 문구 생성 단계(넉넉히 15개 내외)에서는 아직
  quizId를 확정하지 않는다 — 검증 패스(다음 문서)가 evidence 대조·중복·안전·재미·
  난이도비율 검사를 마쳐 **그날 실제로 채택되는 최종 목록**이 정해진 뒤에야
  번호를 매긴다(폐기될 후보에 먼저 번호를 박아두면 재실행 시 폐기 결과가 살짝만
  달라져도 채택분의 번호가 흔들릴 수 있다 — `ROUTINE.md` 6단계와 동일한 시점).
- 같은 날 재실행(routine 재시도, 드라이런 재현 등)에서도 **같은 문제에는 같은
  quizId**가 나와야 한다(멱등 덮어쓰기 — envelope과 동일 원칙). 번호는 검증 통과 후
  확정된 그날의 최종 채택 목록을 **`(templateId, 대상 엔티티 식별자)` 튜플의 사전순으로
  정렬**한 뒤, 그 순서대로 001, 002, ...를 매겨 결정한다.
  - "대상 엔티티 식별자"는 템플릿마다 다르다: 팀 조합이면 `"HT|LG"`처럼 팀코드를
    `|`로 이어 사전순으로 만들고(season.json의 `headToHead` 키 규칙과 동일), 선수면
    `kboPlayerId`, 경기면 `gameId`를 쓴다.
  - 정렬 키가 완전히 같은 두 문제(같은 템플릿·같은 엔티티)가 남아 있으면 애초에
    의미 중복이므로 검증 패스 2단계에서 하나는 폐기됐어야 한다 — 이 단계에 도달할
    때는 정렬 키가 유니크해야 한다.

## 8. casebook 참조 (few-shot)

문구를 쓰기 전에 `question-gen/casebook/good.md`(좋은 예)·`bad.md`(나쁜 예, 왜
나쁜지 포함)를 few-shot으로 참조한다. 매 실행 검증 패스가 이 사례집을 자기 채점
결과로 갱신하므로, 최신본을 그대로 신뢰한다(사람은 사례를 직접 쓰지 않고 거부권만
행사 — 스펙 4.2).

## 9. 실데이터 주의사항 (실측 반영)

- `schedule.today` envelope의 `payload.stadium`은 **실측상 항상 빈 문자열("")**이다
  (네이버 스케줄 API 응답에 구장 필드가 없음). 문구에 구장명을 넣지 않는다.
- `schedule.starters`(`payload.awayStarter`/`homeStarter`)는 **실측상 항상 None**이다
  — 이 필드가 필요한 `PRED_SP_WIN`은 카탈로그에서 `enabled: false`로 비활성화돼
  있으므로 애초에 선택되지 않는다.
- `wiki.*`·`graph`·`stats.trending` needs는 위키 빌더가 아직 운영 S3에 산출물을
  올리지 않은 상태(`wiki/players/`·`wiki/graph.json`·`wiki/stats/trending.md` 부재)라면
  ① 템플릿 선택 단계에서 해당 needs를 쓰는 템플릿을 오늘 후보에서 제외한다(데이터
  없이 문구를 지어내지 않는다).

## 10. deadlineAt 산정

`deadlineAt`은 항상 UTC ISO 8601(`...Z`, `createdAt`과 같은 포맷)로 기록한다. kind별
계산 방법이 다르다:

- **PREDICTION**: `question-source/game_schedule/{오늘}/` envelope의
  `payload.startTime`(KST, `"HH:MM"`)을 기준으로 **경기 시작 2시간 전**을 마감으로
  잡는다 — `deadlineAt` = (오늘 날짜 + `startTime`, KST) − 2시간을 UTC로 변환한 값.
  예: `startTime` "18:30"(KST, 오늘 2026-07-30) → 마감 16:30 KST = `2026-07-30T07:30:00Z`.
  `settlement.gameId`가 가리키는 경기와 `startTime`을 읽은 경기가 반드시 같은 경기여야
  한다(데이터 바인딩 단계에서 매치업별로 묶어 쓸 것).
- **KNOWLEDGE**: 정답이 이미 확정된 사실 퀴즈라 마감을 넉넉히 둔다 — `deadlineAt` =
  출제일 23:59 KST를 UTC로 변환한 값. 예: 출제일 2026-07-30 → `2026-07-30T14:59:00Z`.
- `validate_candidates.py`가 PREDICTION에 한해 `deadlineAt`이 `settlement.gameId`
  날짜(KST)의 유효 범위 안에 있는지 보수적 sanity 검사를 한다(정확한 "시작 2시간 전"
  대조는 이 결정적 스크립트가 아니라 `verification-pass.md`의 LLM 검증 패스 몫) —
  위 계산을 벗어나면 그 게이트에서 걸린다.
