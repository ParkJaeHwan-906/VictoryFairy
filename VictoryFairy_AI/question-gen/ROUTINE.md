# 퀴즈 생성기 routine 실행 지침

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
- **소요 상한**: 1회 실행 30분(그 시점까지 만들지 못한 문항은 그날 목표 미달로 두고
  다음 실행에서 별도로 보충하지 않는다 — 일일 신선도가 핵심이라 어제 몫을 오늘
  합쳐 만들지 않는다)
- **재실행 안전성**: `quizId`가 `(templateId, 대상 엔티티)` 사전순으로 결정적으로
  부여되므로(`generation-rules.md` §7), 같은 날 재실행해도 같은 문제는 같은
  `quizId`로 멱등 덮어쓰기된다 — S3 `quiz-candidates/{date}/`가 유일한 산출 대상이고
  리포 커밋은 하지 않는다(routine은 S3 전용)

## 사전 조건

- 환경변수 `S3_BUCKET` (예: `victoryfairy-crawl-dev`)
- routine 전용 최소 권한 IAM 자격증명(`question-source/`·`kbo-records/`·`wiki/` 읽기,
  `wiki/`·`quiz-candidates/` 쓰기) — 발급·배포는 Task 11 소관, 이 문서는 자격증명이
  이미 환경에 주입돼 있다고 가정한다
- `aws` CLI (자격증명 확인: `aws sts get-caller-identity`)
- Python 실행기: `py-collector/.venv/bin/python` (PyYAML 기설치). `question-gen/
  requirements.txt`는 PyYAML만 요구하므로 이 venv를 그대로 재사용한다(boto3 불필요 —
  S3는 aws CLI로만 접근)
- 작업 디렉토리: `VictoryFairy_AI/`. 아래 모든 명령은 이 디렉토리를 cwd로 실행하고,
  파일 참조는 항상 `question-gen/` 프리픽스를 붙인다(`question-gen/config/...`,
  `question-gen/scripts/...`, `question-gen/prompts/...`). 임시 작업물은 `.work/`에
  모으고(git 추적 대상 아님), 실행 끝에 `.work/quiz-candidates/{date}/`(업로드 대상)만
  S3로 올린다

## 절차

### 1. 동기화

`question-source/{game_result,game_schedule,player_profile}/` 최근 7일 + `wiki/`
전체(위키 빌더 산출물 — 문서·그래프·통계 축적 층) + `quiz-candidates/` 최근 7일을
`.work/`로 내려받는다.

```bash
: "${S3_BUCKET:?S3_BUCKET 환경변수를 설정하라}"
mkdir -p .work/game_result .work/game_schedule .work/player_profile \
  .work/kbo-records .work/wiki .work/quiz-candidates .work/stats

TODAY=$(date -u +%Y-%m-%d)

for i in 0 1 2 3 4 5 6; do
  D=$(date -u -d "-$i days" +%Y-%m-%d 2>/dev/null || date -u -v-"${i}"d +%Y-%m-%d)
  aws s3 sync "s3://$S3_BUCKET/question-source/game_result/$D/" \
    ".work/game_result/$D/" --exclude "*" --include "*.json" 2>/dev/null
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

aws s3 sync .work/stats/ "s3://$S3_BUCKET/wiki/stats/" \
  --exclude "*" --include "season.json" --include "season.md" \
  --include "kbo-official.json" --include "kbo-official.md"
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
없는 템플릿(예: `wiki.*`인데 `wiki/players/`가 비어 있음)은 제외한다. 목표는 최종
10문항이므로 이후 폐기율을 감안해 넉넉히(템플릿·대상 엔티티 조합 15개 내외) 고른다.

### 4. ② 데이터 바인딩 (이 세션이 직접 수행)

3단계에서 고른 각 (템플릿, 대상 엔티티)에 대해 카탈로그 `needs`가 가리키는 파일만
로드한다(카탈로그 머리 주석의 needs 어휘 사전 참고). 필요 이상으로 넓게 읽지 않는다
— 예를 들어 `stats.head_to_head`만 필요하면 `season.json` 전체를 읽더라도 실제로
쓰는 건 `headToHead` 키뿐이다.

### 5. ③ 문구 생성 (이 세션이 직접 수행)

`question-gen/prompts/generation-rules.md` 규칙 + `question-gen/casebook/good.md`·
`bad.md`(few-shot)를 적용해, 4단계에서 바인딩한 데이터로 문구를 작성한다. 일일
목표 10문항, **넉넉히 15개 생성**(검증에서 추릴 폐기율을 흡수). 각 후보를
`.work/raw-candidates/$TODAY/{quizId}.json`에 스펙 4.3 계약 형태로 임시 기록한다
(quizId는 `generation-rules.md` §7의 결정적 규칙으로 이 시점에 이미 확정).

### 6. 검증 (이 세션이 직접 수행 + 결정적 게이트)

`question-gen/prompts/verification-pass.md` 규칙을 `.work/raw-candidates/$TODAY/`의
모든 후보에 적용해(evidence 원문 대조 → 중복·편중 검사 → 안전 재검 → 재미 채점
→ 난이도·일일비율 최종 선별), 통과분만 `.work/candidates/$TODAY/`에 복사한다.

```bash
VALIDATE_DIR=".work/candidates/$TODAY"
mkdir -p "$VALIDATE_DIR"
# (통과분을 $VALIDATE_DIR로 복사하는 것은 이 세션이 검증 결과에 따라 직접 수행)

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
aws s3 cp question-gen/casebook/good.md "s3://$S3_BUCKET/wiki/_meta/casebook/good.md"
aws s3 cp question-gen/casebook/bad.md "s3://$S3_BUCKET/wiki/_meta/casebook/bad.md"
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
  "s3://$S3_BUCKET/wiki/_meta/template-proposals/$TODAY.md" 2>/dev/null || true
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
- **60분이 아니라 30분 상한 초과**: 그 시점까지 검증 통과한 문항만 업로드하고,
  나머지는 만들지 않은 채로 종료한다(다음 실행에서 보충하지 않음 — 신선도가 핵심
  이므로 어제치를 오늘 몫에 얹지 않는다).

## 신규 템플릿 제안 (참고)

8단계에서 남기는 제안은 **카탈로그에 자동 반영되지 않는다**. 사람이
`wiki/_meta/template-proposals/`를 검토해 괜찮다고 판단하면 직접
`question-gen/config/question-templates.yaml`에 새 항목을 추가하고 PR로 리포에
반영한다 — 이 문서(ROUTINE.md)나 routine 실행 자체는 그 반영 과정에 관여하지 않는다.
