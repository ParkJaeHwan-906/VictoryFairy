# 퀴즈 생성기 routine 실행 지침

> **실행체 안내 (2026-08-04)**: 실행체는 Claude Code 클라우드 루틴으로 확정됐다
> (스펙: docs/superpowers/specs/2026-08-04-claude-routine-s3-direct-design.md —
> 2026-08-03 Bedrock 러너 스펙을 대체). 루틴 세션이 이 문서를 그대로 따르되,
> 결정적 단계(템플릿 선택·바인딩·evidence 대조·선별·quizId 부여)는 `runner/`
> 패키지의 catalog/binding/finalize 모듈을 우선 사용한다. 구현과 이 문서가
> 어긋나면 이 문서를 먼저 고치고 구현을 맞춘다.

Claude Code 클라우드 스케줄 잡(routine)이 실행마다 그대로 따르는 절차다. 이 routine
자신이 "① 템플릿 선택·③ 문구 생성·검증 엔진"이므로, 그 세 단계는 별도 프로세스를
호출하지 않고 이 세션이 직접 `question-gen/prompts/generation-rules.md`·
`question-gen/prompts/verification-pass.md`를 규칙으로 적용해 Read/Write 도구로
수행한다(위키 빌더 routine과 동일한 원칙 — `wiki-builder/ROUTINE.md` 참고). 그 외
단계(동기화·전처리 스크립트·업로드)는 실제 셸 명령으로 명시한다.

## 개요

- **주기**: 매일 08:50 KST (`game_schedule` export가 08:30에 도는 것을 전제 —
  py-collector 크론 이후 실행)
- **모델**: Sonnet 5
- **소요 상한**: 1회 실행 60분(그 시점까지 만들지 못한 문항은 그날 목표 미달로 두고
  다음 실행에서 별도로 보충하지 않는다 — 일일 신선도가 핵심이라 어제 몫을 오늘
  합쳐 만들지 않는다). **경기 단위 fail-closed**: 시간이 모자라면 완성한 경기의
  문항까지만 올리고 나머지 경기는 통째로 생략한다 — 한 경기 안에서 문항이 반쪽만
  나가는 것보다 그 경기가 없는 편이 낫다. 그래서 **경기 문항을 먼저** 만들고
  공통 문항은 마지막에 만든다(응원팀 경기 문제가 서비스 가치의 핵심).
- **재실행 안전성**: `quizId`가 `(templateId, 대상 엔티티)` 사전순으로 결정적으로
  부여되므로(`generation-rules.md` §7), 같은 날 재실행해도 같은 문제는 같은
  `quizId`로 멱등 덮어쓰기된다 — S3 `quiz-candidates/{date}/`가 유일한 산출 대상이고
  리포 커밋은 하지 않는다(routine은 S3 전용)

## 사전 조건

- 환경변수 `S3_BUCKET` (예: `victoryfairy-crawl-dev`)
- routine 전용 최소 권한 IAM 자격증명(`question-source/`·`kbo-records/`·`wiki/` 읽기,
  `wiki/`·`quiz-candidates/` 쓰기) — 발급·배포는 Task 11 소관, 이 문서는 자격증명이
  이미 환경에 주입돼 있다고 가정한다
- `aws` CLI (자격증명 확인: `aws sts get-caller-identity`). 클라우드 세션 VM에
  없으면 `python3 -m pip install --quiet awscli`로 설치한다(환경 setup script에
  넣어두면 캐시되어 더 빠르다)
- Python 실행기: `py-collector/.venv/bin/python` (PyYAML 기설치). 클라우드 세션처럼
  venv가 없으면 `python3 -m venv py-collector/.venv &&
  py-collector/.venv/bin/pip install --quiet PyYAML`로 생성한다. `question-gen/
  requirements.txt`는 PyYAML만 요구한다(boto3 불필요 — S3는 aws CLI로만 접근)
- 작업 디렉토리: `VictoryFairy_AI/`. 아래 모든 명령은 이 디렉토리를 cwd로 실행하고,
  파일 참조는 항상 `question-gen/` 프리픽스를 붙인다(`question-gen/config/...`,
  `question-gen/scripts/...`, `question-gen/prompts/...`). 임시 작업물은 `.work/`에
  모으고(git 추적 대상 아님), 실행 끝에 `.work/quiz-candidates/{date}/`(업로드 대상)만
  S3로 올린다

## 절차

### 1. 동기화

소스마다 필요한 범위가 다르다 — `question-source/game_result/`는 **가장 최신 파티션
하나만**(리뷰 C1: 이 docType은 date 없이 호출되면 그 실행일 파티션 하나에 시즌
전체 경기를 통째로 재export한다 — `py-collector/kbo_collector/exports/exporter.py`의
`read_game_results`/`export()` 계약 참고. 파티션 1개가 이미 시즌 전체 스냅샷이므로
7일치를 순회 동기화하면 같은 경기 envelope를 최대 7배 중복으로 받는 것과 같다.
`aggregate_stats.py`의 docId dedupe가 방어선으로 남아 있긴 하지만, 애초에 최신
파티션 하나만 받는 편이 더 정확하고 가볍다 — "어제 경기"·"최근 7일" 같은 세분화는
그 안의 개별 게임이 자기 `gameId`에 담긴 날짜로 필터링되므로 파티션을 여러 개
동기화할 필요가 없다), `quiz-candidates/`는 **최근 7일**(이건 매일 독립적으로
새로 쌓이는 파티션이라 중복 검사·통계 재집계에 실제로 날짜 창이 필요하다),
`question-source/game_schedule/`는 **오늘(KST) 파티션 하나만**(예측 퀴즈는 오늘
경기만 대상 — `game_schedule` export는 KST-오늘 날짜로 호출되고 C1 수정 이후 그
날짜가 그대로 파티션 키가 되므로 `$TODAY`를 KST로 잡기만 하면 항상 일치한다),
`question-source/player_profile/`는 **가장 최신 파티션 하나만**(명단은 스냅샷
성격, 날짜 창이 필요 없음), `wiki/`는 **전체**(위키 빌더 산출물 — 문서·그래프·통계
축적 층)를 `.work/`로 내려받는다.

```bash
: "${S3_BUCKET:?S3_BUCKET 환경변수를 설정하라}"
mkdir -p .work/game_result .work/game_schedule .work/player_profile \
  .work/kbo-records .work/wiki .work/quiz-candidates .work/stats

# 이 routine의 "오늘"은 KST다(리뷰 I1) — game_schedule 오늘 파티션, quiz-candidates
# 업로드 경로, casebook/템플릿 제안 파일명 등 아래 모든 $TODAY 파생 경로가 KST
# 기준이어야 정합이 맞는다. UTC로 잡으면 08:50 KST 실행 시각이 UTC로는 전날 23:50
# 이라 game_schedule 오늘(KST) 파티션과 하루 어긋난다.
TODAY=$(TZ=Asia/Seoul date +%Y-%m-%d)

# game_result: 최신 파티션 1개만(위 설명 참고 — 파티션 자체가 이미 시즌 전체 스냅샷).
LATEST_GAME_RESULT_DATE=$(aws s3 ls "s3://$S3_BUCKET/question-source/game_result/" \
  2>/dev/null | awk '{print $2}' | tr -d '/' | sort | tail -1)
if [ -n "$LATEST_GAME_RESULT_DATE" ]; then
  aws s3 sync "s3://$S3_BUCKET/question-source/game_result/$LATEST_GAME_RESULT_DATE/" \
    ".work/game_result/$LATEST_GAME_RESULT_DATE/" --exclude "*" --include "*.json"
fi

# quiz-candidates만 최근 7일 파티션을 순회한다(중복·편중 검사에 실제로 날짜 창이
# 필요). $TODAY(KST)를 기준으로 역산한다 — 시스템 UTC "오늘"에서 역산하면 KST와
# 최대 하루 어긋난다.
for i in 0 1 2 3 4 5 6; do
  D=$(date -d "$TODAY -$i days" +%Y-%m-%d 2>/dev/null \
      || date -j -v-"${i}"d -f "%Y-%m-%d" "$TODAY" +%Y-%m-%d)
  aws s3 sync "s3://$S3_BUCKET/quiz-candidates/$D/" \
    ".work/quiz-candidates/$D/" --exclude "*" --include "*.json" 2>/dev/null
done

aws s3 sync "s3://$S3_BUCKET/question-source/game_schedule/$TODAY/" \
  ".work/game_schedule/$TODAY/" --exclude "*" --include "*.json" 2>/dev/null \
  || echo "경고: 오늘($TODAY) game_schedule 파티션 없음 — schedule.today/starters 계열 템플릿은 오늘 후보에서 제외" >&2

LATEST_PROFILE_DATE=$(aws s3 ls "s3://$S3_BUCKET/question-source/player_profile/" \
  2>/dev/null | awk '{print $2}' | tr -d '/' | sort | tail -1)
if [ -n "$LATEST_PROFILE_DATE" ]; then
  aws s3 sync "s3://$S3_BUCKET/question-source/player_profile/$LATEST_PROFILE_DATE/" \
    .work/player_profile/ --exclude "*" --include "*.json"
fi

aws s3 sync "s3://$S3_BUCKET/wiki/" .work/wiki/
aws s3 sync "s3://$S3_BUCKET/kbo-records/" .work/kbo-records/
```

`wiki/players/`·`wiki/graph.json`·`wiki/stats/trending.md`가 아직 비어 있으면(위키
빌더가 아직 그 산출물을 올리지 않은 상태) 3단계에서 `wiki.*`·`graph`·`stats.trending`
needs를 쓰는 템플릿은 자연히 후보에서 빠진다(데이터 없이 문구를 지어내지 않는다,
`generation-rules.md` §9) — 실패로 취급하지 않는다.

### 2. ⓪ 통계 재집계 (결정적 스크립트, LLM 미사용)

```bash
py-collector/.venv/bin/python question-gen/scripts/aggregate_stats.py \
  --envelopes-dir .work/game_result --kbo-dir .work/kbo-records \
  --out-dir .work/stats --date "$TODAY"

# .md는 charset=utf-8 명시(없으면 S3 콘솔 미리보기에서 한글 mojibake —
# wiki-builder/ROUTINE.md 7단계와 같은 이유), .json은 기본 추론으로 충분해 나눠 올린다.
aws s3 sync .work/stats/ "s3://$S3_BUCKET/wiki/stats/" \
  --exclude "*" --include "season.md" --include "kbo-official.md" \
  --content-type "text/markdown; charset=utf-8"
aws s3 sync .work/stats/ "s3://$S3_BUCKET/wiki/stats/" \
  --exclude "*" --include "season.json" --include "kbo-official.json"
```

`season.json`·`season.md`·`kbo-official.json`·`kbo-official.md` 4개 파일만 갱신한다
— `trending.md`·`all-time-records.md`는 위키 빌더 소관이라 이 단계에서 건드리지
않는다(`--include` 화이트리스트로 실수로 다른 파일을 지우지 않게 방어).

### 3. ① 템플릿 선택 (이 세션이 직접 수행)

`question-gen/config/question-templates.yaml`을 로드해 `enabled: false`인 템플릿은
제외하고, 오늘 `.work/game_schedule/$TODAY/`의 매치업, `.work/wiki/stats/
trending.md`(있으면), 최근 7일 `.work/quiz-candidates/`의 `templateId`·대상 엔티티
분포를 보고 오늘 쓸 템플릿과 대상 엔티티를 정한다. 같은 템플릿이 최근 이력에서
과다하게 반복됐으면 후순위로 미룬다. `needs`가 가리키는 데이터가 이번 동기화에
없는 템플릿(예: `wiki.*`인데 `wiki/players/`가 비어 있음)은 제외한다.

**작업 단위는 경기다.** 오늘 스케줄의 경기마다 한 묶음씩, 마지막에 공통 문항
한 묶음을 만든다.

| 묶음 | 대상 엔티티 범위 | `gameId`·`teamCodes` |
|---|---|---|
| 경기 문항 (경기 수만큼) | **그 경기 양 팀 소속만** — 선수 밈·기록·상대전적·순위·최근 맞대결·예측 | 채운다 |
| 공통 문항 (하루 1묶음) | 특정 팀에 치우치지 않는 것만 — 리그 전체 순위·역대 팀 기록·통산 기록·트렌딩 | `gameId: null`, `teamCodes: []` |

경기 문항에 다른 팀 선수를 섞지 않는다. 삼성 팬이 삼성전을 보는 중에 두산 선수
밈이 뜨는 것이 이 구조가 막으려는 바로 그 상황이다. 오답 보기도 같은 규칙을
따르되, 오답으로 쓰는 다른 팀 선수 이름은 허용한다(정답이 양 팀 소속이면 된다).

물량은 `question-gen/config/scoring.yaml`의 `volume`이 정본이다 —
`perGame`(경기 하나당 최종 채택 수)과 `common`(하루 전체)을 실행 시점에 읽는다.
이 문서에 숫자를 적지 않는다. 후보는 `candidateMultiplier`배로 넉넉히 골라
검증 폐기율을 흡수한다(예: 슬롯 12 → 조합 18개).

중복 회피 창(최근 7일)은 **팀 단위로** 적용한다 — 어제 삼성전에서 쓴 사실을
오늘 삼성전에서 또 쓰지 않는다. 서로 다른 경기 묶음끼리는 대상 엔티티가 겹치지
않으므로 별도 조정이 필요 없다.

### 4. ② 데이터 바인딩 (이 세션이 직접 수행)

3단계에서 고른 각 (템플릿, 대상 엔티티)에 대해 카탈로그 `needs`가 가리키는 파일만
로드한다(카탈로그 머리 주석의 needs 어휘 사전 참고). 필요 이상으로 넓게 읽지 않는다
— 예를 들어 `stats.head_to_head`만 필요하면 `season.json` 전체를 읽더라도 실제로
쓰는 건 `headToHead` 키뿐이다.

### 5. ③ 문구 생성 (이 세션이 직접 수행)

`question-gen/prompts/generation-rules.md` 규칙 + `question-gen/casebook/good.md`·
`bad.md`(few-shot)를 적용해, 4단계에서 바인딩한 데이터로 문구를 작성한다. 물량은
3단계에서 고른 조합 수 그대로 — `scoring.yaml`의 `volume` 슬롯 ×
`candidateMultiplier`만큼 생성한다(검증에서 추릴 폐기율을 흡수). **경기 묶음
순서대로 만들고 공통 묶음을 마지막에** 둔다.

부문 1위를 단정할 때 주의한다 — `kbo-official.md`의 타자 표는 **타율순 30명**,
투수 표는 **평균자책점순 20명**만 담긴다. 그래서 타율 1위·평균자책점 1위는
표만으로 확정되지만, 홈런·타점·탈삼진 등 **다른 부문의 "리그 1위"는 표 밖 선수를
놓칠 수 있어 단정하면 안 된다**. 이런 부문은 "타율 30걸 중" 같은 범위 한정어를
붙이거나, `trending.md`·위키가 별도로 1위라고 적은 경우에만 1위로 묻는다.

각 후보를
`.work/raw-candidates/$TODAY/{NN}.json`(NN=생성 순번, 임시 파일명일 뿐 최종
`quizId`가 아니다)에 스펙 4.3 계약 형태로 기록한다 — `quizId` 필드에도 이 시점엔
가제(예: 순번 그대로)를 채워 둔다. **최종 `quizId`는 검증(6단계)에서 어떤 후보가
채택됐는지 확정된 뒤에** `generation-rules.md` §7 규칙으로 부여한다(폐기될 수도
있는 후보에 번호를 먼저 박아두면, 재실행 시 같은 최종 채택 목록이라도 그 사이에
낀 후보 하나가 다르게 생성/폐기되는 것만으로 번호가 흔들릴 수 있기 때문 — 멱등성은
"이번 실행에서 실제로 살아남은 목록" 기준으로만 보장한다).

### 6. 검증 (이 세션이 직접 수행 + 결정적 게이트)

`question-gen/prompts/verification-pass.md` 규칙을 `.work/raw-candidates/$TODAY/`의
모든 후보에 적용해(evidence 원문 대조 → 중복·편중 검사 → 안전 재검 → 재미 채점
→ 난이도·일일비율 최종 선별), 통과분만 남긴다. 이렇게 **확정된 최종 채택 목록**을
`(templateId, 대상 엔티티 식별자)` 사전순으로 정렬해 `generation-rules.md` §7
규칙대로 `quizId`(`QZ-{date}-001`, `002`, ...)를 이때 처음으로 확정 부여하고,
그 `quizId`로 `.work/candidates/$TODAY/{quizId}.json`에 기록한다(5단계의 임시
파일명·가제 quizId는 버린다).

```bash
VALIDATE_DIR=".work/candidates/$TODAY"
mkdir -p "$VALIDATE_DIR"
# (통과분에 quizId를 확정 부여해 $VALIDATE_DIR/{quizId}.json으로 쓰는 것은
#  이 세션이 검증 결과 + 위 quizId 규칙에 따라 직접 수행)

py-collector/.venv/bin/python question-gen/scripts/validate_candidates.py \
  --dir "$VALIDATE_DIR"
VALIDATE_EXIT=$?
if [ "$VALIDATE_EXIT" -ne 0 ]; then
  echo "validate_candidates.py 실패(exit=$VALIDATE_EXIT) — 오늘 업로드 생략" >&2
  exit 1
fi
```

**exit code를 반드시 확인한다** — 0이 아니면(형식 위반·카탈로그 불일치·banned-topic
잔존 등 결정적으로 잡히는 결함) 그날 업로드를 생략한다(아래 "실패 처리" 참고).
`validate_candidates.py`는 검증 패스(판단)와 독립된 별개 방어선이므로, 검증 패스를
통과했더라도 이 게이트는 항상 돌린다.

### 7. 업로드

```bash
aws s3 cp --recursive "$VALIDATE_DIR/" "s3://$S3_BUCKET/quiz-candidates/$TODAY/"
```

문항별 독립 파일이라 부분 실패 시에도 이미 올라간 파일은 유효하다(멱등 원칙 —
동일 `quizId`는 재실행 시 그대로 덮어쓴다).

### 8. casebook 갱신 + 신규 템플릿 제안

```bash
# good.md/bad.md는 이 세션이 검증 패스 4단계(재미 채점) 결과로 직접 갱신
# (5점 사례 → good.md에 추가, 2점 이하 사례 → 사유와 함께 bad.md에 추가)
aws s3 cp question-gen/casebook/good.md "s3://$S3_BUCKET/wiki/_meta/casebook/good.md" \
  --content-type "text/markdown; charset=utf-8"
aws s3 cp question-gen/casebook/bad.md "s3://$S3_BUCKET/wiki/_meta/casebook/bad.md" \
  --content-type "text/markdown; charset=utf-8"
```

routine은 리포에 커밋하지 않는다(클라우드 세션, S3 전용) — 갱신본은 S3에만
올리고, 주기적으로 사람이 `wiki/_meta/casebook/`의 최신본을 리포의
`question-gen/casebook/`에 반영한다.

실행 말미에 오늘 데이터에서 가능해 보이는 새 템플릿 아이디어 1~2개(예: 이번 실행에서
카탈로그에 없어 못 만든 흥미로운 조합)를 `wiki/_meta/template-proposals/$TODAY.md`로
남긴다.

```bash
# (제안 내용은 이 세션이 오늘 실행 경험을 바탕으로 직접 작성)
aws s3 cp .work/template-proposals.md \
  "s3://$S3_BUCKET/wiki/_meta/template-proposals/$TODAY.md" \
  --content-type "text/markdown; charset=utf-8" 2>/dev/null || true
```

**카탈로그 반영은 사람 승인 후 수동**이다 — 이 routine은 절대 `question-templates.yaml`
을 스스로 수정하지 않는다(무검수 자동 추가 금지, 카탈로그가 안전·정산 정책의
통제면이기 때문).

## 실패 처리

- **어느 단계든 실패 시**: 그날 `quiz-candidates/{date}/` 업로드를 생략하고 실패를
  노티한다(실행 로그에 실패 단계·사유 기록). 폴백 퀴즈 투입은 BE/어드민 소관(스펙
  §5) — 이 routine이 대체 문항을 만들지 않는다.
- **`validate_candidates.py` exit != 0**: 6단계에서 즉시 중단, 업로드 생략(위 스크립트
  블록의 가드). 다음 실행이 재시도한다 — 업로드가 멱등이라 중복 부작용 없음.
- **`aggregate_stats.py` 실패(예: `--kbo-dir` 스냅샷이 깨진 JSON)**: `season.json`·
  `kbo-official.json`을 만들지 못하면 `stats.*` needs를 쓰는 템플릿 전체가 이번
  실행에서 제외된다 — 나머지 needs(예: `envelope.game_result.*`, `schedule.today`)만
  쓰는 템플릿으로 계속 진행하거나, 그마저도 부족하면 이번 실행은 빈 산출(0문항)로
  끝낸다. 이전 `wiki/stats/` 스냅샷은 손대지 않는다(2단계의 `--include` 화이트리스트
  동기화가 실패하면 애초에 덮어쓰기가 일어나지 않는다).
- **오늘 `game_schedule` 파티션 부재**: 1단계에서 감지되면 `schedule.today`·
  `schedule.starters`를 쓰는 예측 템플릿 전부를 제외하고 지식 템플릿만으로 진행한다
  (예측 퀴즈 0개인 날이 있을 수 있다 — 정상 동작).
- **60분 상한 초과**: 그 시점까지 검증 통과한 문항만 업로드하고,
  나머지는 만들지 않은 채로 종료한다(다음 실행에서 보충하지 않음 — 신선도가 핵심
  이므로 어제치를 오늘 몫에 얹지 않는다).

## 신규 템플릿 제안 (참고)

8단계에서 남기는 제안은 **카탈로그에 자동 반영되지 않는다**. 사람이
`wiki/_meta/template-proposals/`를 검토해 괜찮다고 판단하면 직접
`question-gen/config/question-templates.yaml`에 새 항목을 추가하고 PR로 리포에
반영한다 — 이 문서(ROUTINE.md)나 routine 실행 자체는 그 반영 과정에 관여하지 않는다.
