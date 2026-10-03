# 퀴즈 생성기 routine 실행 지침

> **실행체 안내 (2026-08-04)**: 실행체는 Claude Code 클라우드 루틴으로 확정됐다
> (검토했던 Bedrock 러너 전환안은 철회). 루틴 세션이 이 문서를 그대로 따르되,
> 결정적 단계(템플릿 선택·바인딩·evidence 대조·선별·quizId 부여)는 `runner/`
> 패키지의 catalog/binding/finalize 모듈을 우선 사용한다. 구현과 이 문서가
> 어긋나면 이 문서를 먼저 고치고 구현을 맞춘다.

Claude Code 클라우드 스케줄 잡(routine)이 실행마다 그대로 따르는 절차다. 이 routine
자신이 "① 템플릿 선택·③ 문구 생성·검증 엔진"이므로, 그 세 단계는 별도 프로세스를
호출하지 않고 `question-gen/prompts/generation-rules.md`·`question-gen/prompts/
verification-pass.md`를 규칙으로 적용해 Read/Write 도구로 수행한다(위키 빌더
routine과 동일한 원칙 — `wiki-builder/ROUTINE.md` 참고). **단, ③ 문구 생성은
2026-10-03부터 이 세션이 혼자 하지 않는다** — 유닛(경기·팀·공통)마다 서브에이전트로
병렬 위임하는 게 필수 절차다(§4). 메인 세션은 ①(템플릿 선택)·검증(⑥)과 위임·수거
오케스트레이션을 직접 하고, ③의 실제 문구 작성은 위임받은 서브에이전트들이 한다 —
"엔진이 이 세션"이라는 성격은 그대로지만 더는 단일 스레드가 아니다. 그 외 단계
(동기화·전처리 스크립트·업로드)는 실제 셸 명령으로 명시한다.

## 개요

- **주기**: 매일 08:50 KST (`game_schedule` export가 08:30에 도는 것을 전제 —
  py-collector 크론 이후 실행)
- **모델**: Sonnet 5
- **소요 상한**: 고정된 분 단위 상한을 두지 않는다(2026-09-29 변경). 예전에는
  "1회 실행 60분"으로 못박아 뒀지만, 실제 등록된 routine 프롬프트(claude.ai
  routine `vf-quiz-daily`)에는 애초에 그 숫자가 없었고 — "시간 초과는 경기
  단위로 끊는다"만 있다 — 최근 실행 10회를 확인한 결과도 전부 20~40분에
  끝나 60분 근처에도 간 적이 없다. 그 물량(perGame+common, 팀당 최대 42)
  기준으로는 60분이 애초에 병목이 아니었다는 뜻이다. `perTeam` 신설로 물량이
  크게 늘었으니 실행이 그만큼 오래 걸리는 건 당연하고 정상이다 — 임의로
  시간을 잘라 물량을 못 채우게 만들지 말고, **경기 단위 fail-closed** 원칙
  (아래)만으로 스스로 멈추게 둔다. 다만 이 routine이 무한정 도는 클라우드
  세션이라는 보장은 없으므로(플랫폼 자체의 세션 한도가 있는지는 확인된 바
  없다), 다음 며칠 실행 결과(소요 시간·팀별 완주 여부)를 관찰해 실제로 잘리는
  지점이 보이면 그때 구체적인 숫자를 다시 문서화한다. **경기 단위 fail-closed**:
  시간이 모자라면(또는 다른 이유로 세션이 끊기면) 완성한 경기의 문항까지만
  올리고 나머지 경기는 통째로 생략한다 — 한 경기 안에서 문항이 반쪽만 나가는
  것보다 그 경기가 없는 편이 낫다. 같은 원칙을 팀 특화 묶음에도 적용한다(§3).
  그래서 **경기 문항을 먼저**, 그다음 **팀 특화 묶음**, **공통 문항은 마지막**에
  만든다(응원팀 경기 문제가 서비스 가치의 핵심이라 먼저 지킨다). 그날 만들지
  못한 문항은 목표 미달로 두고 다음 실행에서 별도로 보충하지 않는다(일일
  신선도가 핵심이라 어제 몫을 오늘에 합쳐 만들지 않는다).
- **재실행 안전성**: `quizId`가 `(templateId, 대상 엔티티)` 사전순으로 결정적으로
  부여되므로(`generation-rules.md` §7), 같은 날 재실행해도 같은 문제는 같은
  `quizId`로 멱등 덮어쓰기된다. 산출 대상은 둘이다 — 문항은 S3
  `quiz-candidates/{date}/`, 통계·casebook·템플릿 제안은 `VictoryFairy_WIKI` 리포의
  `dev` 브랜치(8단계에서 한 번에 커밋). **이 리포(VictoryFairy)에는 커밋하지 않는다**

## 사전 조건

- 환경변수 `S3_BUCKET` (**필수** — 실버킷명은 문서에 적지 않는다. 값 확인 절차는
  `deploy/routines/README.md` 1번 섹션)
- routine 전용 최소 권한 IAM 자격증명(`question-source/`·`kbo-records/`·
  `quiz-candidates/` 읽기, `quiz-candidates/` 쓰기) — 이 문서는 자격증명이
  이미 환경에 주입돼 있다고 가정한다. **위키는 S3가 아니라 git에서 읽는다**
- GitHub 쓰기 자격증명 — claude.ai 계정에 연결된 것을 세션이 자동으로 쓴다
  (통계·casebook을 위키 리포에 커밋). 프롬프트나 이 문서에 토큰을 적지 않는다
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
  모으고(이 리포의 git 추적 대상 아님), 실행 끝에
  `.work/quiz-candidates/{date}/`(업로드 대상)만 S3로 올린다. 위키 리포는
  `.work/wiki-repo/`에 클론하고 그 안의 `wiki/`를 읽는다(통계·casebook은 그리로 커밋)

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
성격, 날짜 창이 필요 없음)을 `.work/`로 내려받는다. 위키(문서·그래프·통계 축적
층)는 S3가 아니라 **`VictoryFairy_WIKI` 리포 `dev` 브랜치를 클론**해서 읽는다.

```bash
: "${S3_BUCKET:?S3_BUCKET 환경변수를 설정하라}"
mkdir -p .work/game_result .work/game_schedule .work/player_profile \
  .work/kbo-records .work/quiz-candidates .work/stats

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

aws s3 sync "s3://$S3_BUCKET/kbo-records/" .work/kbo-records/

# 위키는 S3가 아니라 git이 원본이다. public 리포라 자격증명 없이 클론된다.
rm -rf .work/wiki-repo
git clone --depth 1 -b dev \
  https://github.com/ParkJaeHwan-906/VictoryFairy_WIKI.git .work/wiki-repo \
  || echo "경고: 위키 클론 실패 — wiki.*·graph·stats.trending needs 템플릿은 오늘 제외" >&2
```

위키 클론이 실패해도 이 routine은 계속 진행한다(그 needs를 쓰는 템플릿만
빠진다) — 빌더와 달리 퀴즈는 위키 없이도 만들 수 있는 템플릿이 많다.

`wiki/players/`·`wiki/graph.json`·`wiki/stats/trending.md`가 아직 비어 있으면(위키
빌더가 아직 그 산출물을 올리지 않은 상태) 3단계에서 `wiki.*`·`graph`·`stats.trending`
needs를 쓰는 템플릿은 자연히 후보에서 빠진다(데이터 없이 문구를 지어내지 않는다,
`generation-rules.md` §9) — 실패로 취급하지 않는다.

### 2. ⓪ 통계 재집계 (결정적 스크립트, LLM 미사용)

```bash
py-collector/.venv/bin/python question-gen/scripts/aggregate_stats.py \
  --envelopes-dir .work/game_result --kbo-dir .work/kbo-records \
  --out-dir .work/stats --date "$TODAY"

# 위키 클론이 있으면 그 안의 stats/ 에 반영한다(커밋·푸시는 8단계에서 한 번에).
# 4개 파일만 이름으로 지정해 복사한다 — trending.md·all-time-records.md 는 위키
# 빌더 소관이라 절대 건드리지 않는다.
if [ -d .work/wiki-repo/wiki ]; then
  mkdir -p .work/wiki-repo/wiki/stats
  for f in season.json season.md kbo-official.json kbo-official.md; do
    [ -f ".work/stats/$f" ] && cp ".work/stats/$f" ".work/wiki-repo/wiki/stats/$f"
  done
fi
```

`season.json`·`season.md`·`kbo-official.json`·`kbo-official.md` 4개 파일만 갱신한다.
파일명을 하나씩 지정해 복사하므로 `stats/`의 다른 파일은 손대지 않는다.

이 단계에서 만든 통계는 **이번 실행이 곧바로 소비**한다(4단계 바인딩). 커밋은
8단계로 미루므로, 도중에 실패하면 위키에는 아무것도 반영되지 않는다.

### 3. ① 템플릿 선택 (이 세션이 직접 수행)

`question-gen/config/question-templates.yaml`을 로드해 `enabled: false`인 템플릿은
제외하고, 오늘 `.work/game_schedule/$TODAY/`의 매치업, `.work/wiki-repo/wiki/stats/
trending.md`(있으면), 최근 7일 `.work/quiz-candidates/`의 `templateId`·대상 엔티티
분포를 보고 오늘 쓸 템플릿과 대상 엔티티를 정한다. 같은 템플릿이 최근 이력에서
과다하게 반복됐으면 후순위로 미룬다. `needs`가 가리키는 데이터가 이번 동기화에
없는 템플릿(예: `wiki.*`인데 `wiki/players/`가 비어 있음)은 제외한다.

**작업 단위는 경기 + 팀이다.** 오늘 스케줄의 경기마다 한 묶음씩, 그 다음
**10개 구단 각각**에 팀 특화 묶음 하나씩(2026-09-29 신설 — 아래 `perTeam` 행),
마지막에 공통 문항 한 묶음을 만든다.

| 묶음 | 대상 엔티티 범위 | `gameId`·`teamCodes` |
|---|---|---|
| 경기 문항 (경기 수만큼) | **그 경기 자체·오늘 매치업에 관한 것만** — 어제 승자·스코어·승리투수·시즌 상대전적·맞대결·예측(`YESTERDAY_WINNER`·`YESTERDAY_SCORE`·`WINNING_PITCHER`·`LAST_MATCHUP`·`H2H_SEASON_RECORD`·`PRED_*`). **`CAREER_PATH`·`MEME_ORIGIN`·`RELATION_LINK`는 절대 쓰지 않는다**(2026-10-03 — 아래 참고) | `gameId` 채움, `teamCodes`에 양 팀 |
| 팀 특화 문항 (**구단마다 매일 1묶음**, 경기 유무 무관) | **그 팀 로스터만** — `CAREER_PATH`·`MEME_ORIGIN`·`RELATION_LINK`·`RECORD_OX`(PLAYER scope), `STREAK_CURRENT`·`HOME_AWAY_SPLIT`·`RECENT_VS_EARLY`(TEAM scope) 중심. 위키에 팀당 63~89명이 등재돼 있어 재료는 경기 일정과 무관하게 항상 있다 | `gameId: null`, `teamCodes: [그 팀 하나]` |
| 공통 문항 (하루 1묶음) | 특정 팀에 치우치지 않는 것만 — 리그 전체 순위·역대 팀 기록·통산 기록·트렌딩 | `gameId: null`, `teamCodes: []` |

⚠️ **경기 문항에 `CAREER_PATH`·`MEME_ORIGIN`·`RELATION_LINK`를 쓰지 않는다
(2026-10-03 변경)** — 이 셋은 로스터 기반이라 경기 당일 여부와 무관하고,
`perTeam` 신설 이후 팀 특화 유닛이 이미 매일 전담한다. perTeam 이전에는
게임 묶음이 이 소재의 유일한 통로였어서 허용했었지만, 지금은 경기 유닛과
팀 특화 유닛이 서로의 산출물을 모른 채 독립적으로 같은 선수 소재를 뽑는
**교차 중복**을 낳는다 — 2026-10-03 첫 전면 실행에서 폐기 39건 중 28건이
이 원인이었다(`wiki/_meta/casebook/bad.md` #102). `question-gen/scripts/
validate_candidates.py` check 10이 이걸 결정적으로 막는다(`gameId`가 있는데
이 세 템플릿이면 하드 실패) — 프롬프트 설명만으로는 다시 샐 수 있다고
판단해 게이트로 못박았다.

### 3-1. PRED_BATTER_HIT_INNING 전용 절차 (2026-10-04 신설 — 실험적/제한적)

이 템플릿은 "오늘 경기 특정 이닝에 특정 타자가 안타를 칠지" 예측하고, 다른
`PRED_*`와 달리 **이닝 트리거 기반 실시간 정산**이다 — py-collector가 그 이닝이
끝나는 시점에 S3 `inning-events/{date}/{gameId}/{inning}-{half}.json`에 적재하는
사실로 BE(`QuizSettlementListener`)가 나중에 정산한다(생성 시점엔 다른 `PRED_*`
처럼 `answer`를 모른다). **경기 문항(perGame) 묶음에서만 쓴다** — 특정 경기·
이닝이 전제이므로 팀 특화(perTeam)·공통 묶음에는 쓰지 않는다.

- **조사 결과(2026-10-04 실측) — 확정 선발 라인업 소스 없음**: 경기 전 확정
  타순은 `question-source/`에 없다. py-collector의 `games_sync`가 네이버
  preview API로 MySQL `game_lineups`에는 적재하지만(`kbo_collector/run.py`의
  `_land_preview_lineups`), `kbo_collector/exports/exporter.py`의 `READERS`에
  그 docType용 reader가 없어 S3로 export되지 않는다(이 카탈로그 needs 어휘
  사전의 `schedule.lineup` 항목도 이미 "미지원"으로 명시돼 있다 — 같은 이유로
  `TODAY_CLEANUP`·`POSITION_WHO`도 비활성). **py-collector 코드를 고쳐 새 export
  reader를 추가하는 것은 이번 변경 범위 밖이다**(별도 PR 대상 — 억지로 이 routine
  단계에서 우회하지 않는다).
- **그래서 택한 대안**: 위키 선수 문서(`wiki/players/*.md`)에도 "주전/타순"
  구조화 필드가 없다(`wiki-builder/templates/player-doc.md` 확인 — 프로필 섹션은
  자유 서술이라 타순 추출 근거로 못 쓴다). 대신 `stats.season_leaders`
  (`wiki/stats/kbo-official.json#seasonLeaders.hitterBasic`, 타율 상위 30명 —
  `SEASON_STAT_LEADER` 템플릿이 이미 쓰고 있는 실재 데이터)에서 **오늘 경기 양
  팀 소속 선수**를 추려 "사실상 매일 출전하는 주전"으로 추정해 쓴다. 그 선수명을
  `envelope.player_profile`(최신 파티션)의 `title`/`content`와 문자열 대조해
  `kboPlayerId`(`payload.playerId`)로 매핑한다(동명이인이 있으면 `entities.
  teamCodes`로 추가 대조할 것).
- **한계(투명하게 남긴다)**: 이 추정은 확정 라인업이 아니다 — 그 선수가 실제로
  결장·교체되면 해당 이닝에 전혀 타석에 서지 않을 수 있다. BE
  `InningEventFact`/`QuizSettlementService` 설계상 "관측 없음"은 "미적중"과
  동일하게 처리되므로(스펙 범위, 결함 아님) 퀴즈 자체는 항상 정산되지만, 체감상
  "무조건 미적중" 쪽으로 편향될 수 있다는 점을 안다. 확정 라인업 export가
  생기면(py-collector 쪽 작업) 이 절을 고쳐 `needs`를 `schedule.lineup`로
  바꿀 것.
- **이닝 선택**: 1~5회 중에서 고른다(`settlement.inning`은 계약상 1~11까지
  허용되지만 — `validate_candidates.py`의 `INNING_MAX`, py-collector
  `game_records.py`와 동일 — 후반 이닝일수록 그 선수가 교체·결장으로 안 뛸
  가능성이 커지므로 낮은 이닝을 우선한다. 품질 지침일 뿐 게이트 상한은 아니다).
- **초/말 결정**: `gameId`(`YYYYMMDD{awayCode}{homeCode}0{season}`)에서 고른
  선수의 소속이 away팀이면 `half: "TOP"`, home팀이면 `half: "BOTTOM"`(BE
  `InningHalf` — TOP=초, BOTTOM=말). **문자열이다, `0`/`1` 정수가 아니다** —
  `validate_candidates.py` check 11이 이를 하드 검사한다.
- **settlement 채우기**: `settlement.metric: "BATTER_HIT_IN_INNING"`,
  `settlement.gameId`는 top-level `gameId`와 반드시 같은 값(기존 `PRED_*` 규칙과
  동일, check 8(a)), `settlement.inning`·`settlement.half`는 위에서 고른 값.
- **subject 채우기**: `subjectScope: PLAYER`(카탈로그 선언) — `subject.playerIds`
  에 고른 선수의 `kboPlayerId` 정수 하나(BE `QuizIngestService.resolvePlayer()`
  가 이 필드로 `players.kbo_player_id`를 찾아 player FK를 정한다 — **subject를
  빠뜨리면 BE가 player를 못 찾아 영영 정산되지 않는다.** 다른 PREDICTION
  템플릿과 달리 이 템플릿은 subject가 사실상 필수다). `teamCodes`·`gameId`는
  빈다(PLAYER scope 카디널리티 규칙, `validate_candidates.py` check 9).
- **deadlineAt**: 다른 `PRED_*` 템플릿과 **같은 규칙**을 그대로 쓴다
  (`generation-rules.md` §10 — 경기 시작 2시간 전). 특정 이닝의 시작 시각은
  알 수 없지만, 이 규칙은 경기 전체 시작을 막으므로 **어떤 이닝을 고르더라도
  그 이닝 시작보다 항상 먼저다** — 이닝별 별도 deadline 계산이 필요 없는 이유다.
- **보기(options) 순서 고정 계약**: BE 고정 계약
  (`QuizIngestService.ingestPrediction` javadoc) — `options[0]`=적중(예: "안타를
  친다"), `options[1]`=미적중(예: "안타를 치지 못한다"). 텍스트가 아니라 이
  인덱스 관례로 정산하므로 순서를 바꾸면 정산이 거꾸로 채점된다.

경기 문항·팀 특화 문항 모두 다른 팀 선수를 섞지 않는다. 삼성 팬이 삼성 묶음을
보는 중에 두산 선수 밈이 뜨는 것이 이 구조가 막으려는 바로 그 상황이다. 오답
보기도 같은 규칙을 따르되, 오답으로 쓰는 다른 팀 선수 이름은 허용한다(정답이
그 묶음 소속이면 된다).

물량은 `question-gen/config/scoring.yaml`의 `volume`이 정본이다 —
`perGame`(경기 하나당)·`perTeam`(구단 하나당, 매일)·`common`(하루 전체) 세
축을 실행 시점에 읽는다. 이 문서에 숫자를 적지 않는다. 후보는
`candidateMultiplier`배로 넉넉히 골라 검증 폐기율을 흡수한다.
`runner/runner/finalize.py`의 `select_final`이 후보를 `gameId`·`teamCodes`로
자동으로 세 그룹(경기/팀 특화/공통)으로 나눠 각자의 슬롯을 적용하므로, 이
단계에서 할 일은 대상 엔티티(경기·팀·리그 전체)를 정확히 고르고 그에 맞는
`gameId`/`teamCodes`를 후보 JSON에 채우는 것뿐이다.

**`perGame`은 곧 구단별 경기 문항 수, `perTeam`은 곧 구단별 팀 특화 문항
수다** — 귀속 축(top-level `teamCodes`)이 그 묶음의 대상 팀을 그대로 담으므로
나눠 갖는 게 아니라 그 팀에 통째로 귀속된다. 각 구단이 매일 1경기씩 뛰므로
`perGame` 합계가 경기 문항 수, `perTeam` 합계가 팀 특화 문항 수이고 **경기
유무와 무관하게 매일 발생한다**(로스터 기반이라 상대·일정에 의존하지 않음).

**성공 기준은 사용자 1인이 받는 문항 수**이지 그날 발행 총량이 아니다 — 한
팬이 받는 건 자기 팀 경기 묶음(있는 날만) + 자기 팀 특화 묶음(매일) + 공통
묶음이므로 1인 체감은 `perGame 합계(경기 있는 날만) + perTeam 합계 + common
합계`다. 목표치와 근거는 `scoring.yaml`의 "perTeam 신설" 주석에 적혀 있다
(목표 100~150/일).

**경기가 없는 날도 `perTeam`·`common`은 그대로 돈다.** 월요일과 전 경기
취소일에는 `perGame`이 0이 될 뿐, 팀 특화 묶음은 로스터 기반이라 경기 여부와
무관하게 10개 구단 전부에 대해 평소와 똑같이 만든다 — 예전에는 이 경우를
대비해 공통 묶음의 범위를 임시로 넓히는 절차가 있었으나, `perTeam` 신설로
그 필요가 사라져 이 절차는 폐기한다.

중복 회피 창(최근 7일)은 **팀 단위로** 적용한다 — 어제 롯데 경기 묶음이나
어제 롯데 팀 특화 묶음에서 쓴 사실을 오늘 롯데 관련 어느 묶음에서도 또 쓰지
않는다(둘 다 top-level `teamCodes`가 같은 축이므로 하나의 창으로 본다). 서로
다른 팀의 묶음끼리는 대상 엔티티가 겹치지 않으므로 별도 조정이 필요 없다.
공통 묶음(`teamCodes: []`)은 `subject.teamCodes`·`subject.playerIds`(주제
축)를 키로 최근 7일과 대조한다.

⚠️ **하루 총 필요 물량이 크게 늘었다는 점에 유의한다**(구단 10개 × perTeam
슬롯 × `candidateMultiplier`) — 실행이 예전보다 훨씬 오래 걸리는 게 정상이다
(위 "개요"의 소요 상한 변경 참고). 그래도 세션이 어떤 이유로든(플랫폼 한도 등)
끊기는 경우를 대비해 "실패 처리" 절의 **경기 단위 fail-closed** 원칙을 팀에도
그대로 적용한다 — 시간이 모자라면 완성한 팀의 팀 특화 묶음까지만 올리고
나머지 구단은 그날 `perTeam` 없이 넘어간다(다음 실행이 보충하지 않음). 경기
묶음 → 팀 특화 묶음(구단 코드 사전순) → 공통 묶음 순서로 만들어, 끊기면
뒤로 갈수록 잘리게 한다.

### 4. ② 데이터 바인딩 + ③ 문구 생성 (유닛별 서브에이전트 병렬 위임 — **필수**, 혼자 하지 않는다)

**2026-10-03 변경 근거**: `perTeam` 신설(2026-09-29) 이후, 이 세션이 모든 유닛을
혼자 손으로 만든 날(2026-10-01·10-02)은 각각 100개·56개에 그쳤다. 반대로 같은
주간에 유닛마다 서브에이전트를 병렬로 띄워 위임한 날(2026-09-30)은 345개가
나왔다 — 거의 7배 차이다. 손으로 검증하며 채우는 방식은 perTeam 이후 물량
(경기당 20 + 팀마다 90 × 10개 팀 + 공통 22 — 하루 1,000개 안팎)을 구조적으로
감당 못 한다(2026-10-02 실행 자기보고에 그대로 적혀 있다). 그래서 이 단계부터는
**유닛 병렬 위임이 선택이 아니라 필수 절차**다.

**절차**:

1. 3단계에서 정한 유닛(경기 N개 + 구단 10개 + 공통 1개, 최대 N+11개) 하나당
   서브에이전트 하나를 **병렬로** 띄운다(이 클라우드 세션이 쓸 수 있는 하위
   에이전트 위임 수단 — Task 도구 또는 동등한 메커니즘. 2026-09-30 실행이 이미
   이 방식으로 성공했으니 그 세션이 쓴 방법을 그대로 따르면 된다).
2. 각 서브에이전트에게 반드시 줄 것:
   - 유닛 식별자(`gameId` 하나, 또는 팀코드 하나, 또는 "공통")와 그 유닛의
     `teamCodes`(경기 유닛=양 팀, 팀 유닛=그 팀 하나, 공통 유닛=`[]`)
   - 그 유닛의 목표 물량 = `scoring.yaml`의 해당 슬롯(`perGame`/`perTeam`/
     `common` 중 하나) × `candidateMultiplier`
   - 1~2단계에서 이미 동기화·재집계된 데이터 파일 경로(`.work/stats/`,
     `.work/wiki-repo/wiki/`, `.work/game_result/` 등 — 다시 받아오지 않고
     그대로 읽게 한다)
   - `question-gen/prompts/generation-rules.md`(작성 규칙, §7 quizId 가제
     원칙·§11 subject 규칙 포함)와 casebook 경로(위키 클론이 있으면
     `.work/wiki-repo/wiki/_meta/casebook/{good,bad}.md`, 없으면 리포 시드
     `question-gen/casebook/{good,bad}.md`). 경기 유닛에 `PRED_BATTER_HIT_INNING`
     이 포함되면 위 §3-1(선수·이닝 선정, settlement/subject 채우기, 보기 순서
     고정 계약)도 함께 전달한다 — 일반 PRED_* 절차와 다른 부분이 있다
   - 최근 7일 출제 이력 중 **그 유닛의 teamCodes(또는 공통 유닛은
     subject.teamCodes/playerIds)와 겹치는 것만** 추려서 건넨다(중복 회피창,
     §3 — 서브에이전트가 전체 이력을 다시 긁지 않아도 되게)
   - 안전 규칙(banned-topics.txt, `사건사고` 섹션·`사건연루` 엣지 금지)과
     경기 문항·팀 특화 문항에 다른 팀 선수를 섞지 않는다는 원칙(§3)
3. 각 서브에이전트는 자기 몫을
   `.work/raw-candidates/$TODAY/{유닛}-{NNN}.json`(예: `team-HH-001.json`,
   `game-NCOB-001.json`, `common-001.json` — 유닛을 구분할 수 있는 임시
   파일명이면 충분하고 최종 `quizId`가 아니다. `quizId` 필드 자체도 이
   시점엔 그 임시값을 그대로 채워 둔다)에 스펙 4.3 계약 형태로 쓰고, **반드시**
   `python3 question-gen/scripts/self_check.py .work/raw-candidates/$TODAY --work .work --repo-root .`
   를 돌려 **자기 몫이 0 FAIL일 때까지** 고친 뒤에만 핸드백한다(`self_check.py`는
   `validate_candidates.py`의 결정적 검사 전부 + evidence 원문 대조를 겸한다 —
   업로드 전 최종 게이트(6단계)와 같은 기준을 미리 통과시켜 그 단계에서의
   탈락을 줄인다). 핸드백 메시지에는 생성/통과 개수, 난이도별 결과(쿼터 대비),
   재료 부족으로 스킵한 템플릿과 사유를 요약해 담는다.
4. 부문 1위를 단정할 때 주의하라고 서브에이전트에게 전달한다 —
   `kbo-official.md`의 타자 표는 **타율순 30명**, 투수 표는 **평균자책점순
   20명**만 담긴다. 타율 1위·평균자책점 1위는 표만으로 확정되지만, 홈런·타점·
   탈삼진 등 **다른 부문의 "리그 1위"는 표 밖 선수를 놓칠 수 있어 단정하면
   안 된다**("타율 30걸 중" 같은 범위 한정어를 붙이거나, `trending.md`·위키가
   별도로 1위라고 적은 경우에만 1위로 묻는다).
5. 메인 세션은 **경기 유닛 → 팀 유닛(구단 코드 사전순) → 공통 유닛** 순서로
   핸드백을 기다린다(순서 자체가 fail-closed 우선순위다 — 세션이 중간에
   끊기면 뒤쪽 유닛이 통째로 빠진다. §3 참고). 모든 핸드백이 끝나면(또는
   끊겨서 일부만 돌아왔으면 그 상태로) 6단계로 넘어간다 — `.work/raw-candidates/
   $TODAY/`에는 모든 유닛의 파일이 섞여 쌓여 있다.

최종 `quizId`는 **아직 부여하지 않는다** — 검증(6단계)에서 어떤 후보가 최종
채택됐는지 확정된 뒤에 `generation-rules.md` §7 규칙으로 그때 처음 부여한다
(폐기될 수도 있는 후보에 번호를 먼저 박아두면, 재실행 시 같은 최종 채택
목록이라도 그 사이에 낀 후보 하나가 다르게 생성/폐기되는 것만으로 번호가
흔들릴 수 있기 때문 — 멱등성은 "이번 실행에서 실제로 살아남은 목록" 기준으로만
보장한다).

### 6. 검증 (이 세션이 직접 수행 + 결정적 게이트)

`question-gen/prompts/verification-pass.md` 규칙을 `.work/raw-candidates/$TODAY/`의
모든 후보에 적용해(evidence 원문 대조 → 중복·편중 검사 → 안전 재검 → 재미 채점
→ 난이도·일일비율 최종 선별), 통과분만 남긴다. 이렇게 **확정된 최종 채택 목록**을
`(templateId, 대상 엔티티 식별자)` 사전순으로 정렬해 `generation-rules.md` §7
규칙대로 `quizId`(`QZ-{date}-001`, `002`, ...)를 이때 처음으로 확정 부여하고,
그 `quizId`로 `.work/candidates/$TODAY/{quizId}.json`에 기록한다(4단계의 유닛별
임시 파일명·가제 quizId는 버린다).

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
게이트는 `subject`(주제 축, 스펙 4.3 v2)도 검사한다 — scope·카디널리티·팀코드
화이트리스트·정답 유출(subject 팀명이 정답 보기에 등장)은 하드 실패, `subject`
부재는 경고만 출력한다(구계약 공존 — exit code 미반영).
`validate_candidates.py`는 검증 패스(판단)와 독립된 별개 방어선이므로, 검증 패스를
통과했더라도 이 게이트는 항상 돌린다.

### 7. 업로드

```bash
aws s3 cp --recursive "$VALIDATE_DIR/" "s3://$S3_BUCKET/quiz-candidates/$TODAY/"
```

문항별 독립 파일이라 부분 실패 시에도 이미 올라간 파일은 유효하다(멱등 원칙 —
동일 `quizId`는 재실행 시 그대로 덮어쓴다).

### 8. 위키 리포 커밋 (통계 + casebook + 템플릿 제안)

2단계에서 복사한 통계 4개 파일, 이번 실행에서 갱신한 casebook, 오늘의 템플릿 제안을
**한 커밋으로** `VictoryFairy_WIKI`의 `dev`에 올린다.

- casebook `good.md`/`bad.md`는 이 세션이 검증 패스 4단계(재미 채점) 결과로 직접
  갱신한다 — 5점 사례는 `good.md`에, 2점 이하 사례는 사유와 함께 `bad.md`에 추가.
  **갱신 대상은 위키 클론 쪽 파일(`.work/wiki-repo/wiki/_meta/casebook/`)이고,
  리포의 `question-gen/casebook/`을 그 위에 복사하지 않는다.** 리포 사본은 시드일
  뿐이라 덮어쓰면 이전 실행 사례가 전부 사라진다 — 2026-08-06에 실제로 그렇게
  되어 있었다(리포·`dev`·S3에 서로 다른 사례가 흩어져 어느 쪽도 상위집합이
  아니었고, 합본 커밋으로 복구했다). 절 번호는 이어서 붙인다
- 템플릿 제안은 오늘 데이터에서 가능해 보이는 새 아이디어 1~2개(카탈로그에 없어
  못 만든 흥미로운 조합 등)를 이 세션이 직접 작성한다

```bash
if [ -d .work/wiki-repo/wiki ]; then
  mkdir -p .work/wiki-repo/wiki/_meta/casebook \
           .work/wiki-repo/wiki/_meta/template-proposals
  # casebook 은 위 설명대로 이 세션이 위키 클론 쪽 파일을 직접 Read → 사례 추가 →
  # Write 로 갱신한다. 리포 사본을 여기로 복사하지 않는다(누적분이 날아간다).
  # 템플릿 제안은 없을 수도 있다(제안할 게 없으면 생략).
  [ -f .work/template-proposals.md ] && cp .work/template-proposals.md \
    ".work/wiki-repo/wiki/_meta/template-proposals/$TODAY.md"

  cd .work/wiki-repo
  git add -A wiki/
  if git diff --cached --quiet; then
    echo "위키에 반영할 변경 없음 — 커밋 생략"
  else
    git commit -m "wiki: quiz routine $TODAY (stats/casebook/proposals)"
    # 위키 빌더(화·금 06:00)도 같은 브랜치에 쓴다 — 그 사이 커밋이 있으면 rebase
    # 후 재시도. 서로 건드리는 파일이 달라 충돌은 사실상 없다.
    git push origin dev || { git pull --rebase origin dev && git push origin dev; }
  fi
  cd -
fi
```

casebook의 **누적본은 위키(`dev`)에 있고, 리포의 `question-gen/casebook/`은 시드**다.
주기적으로 사람이 위키 최신본을 리포에 반영한다(이 routine은 VictoryFairy 리포에
커밋하지 않는다). 4단계에서 유닛 서브에이전트에게 건네는 few-shot도 위키 클론이
있으면 그쪽 누적본을 쓴다 — 사례가 많을수록 문구 품질이 올라간다.

푸시가 실패해도 **문항 업로드(7단계)는 이미 끝났으므로 그날 퀴즈는 유효하다** —
실패 지점을 보고하고 종료한다. 통계·casebook은 다음 실행이 다시 만들어 올린다.

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
- **세션이 도중에 끊김(고정 시간 상한은 없음 — 위 "개요" 참고)**: 그 시점까지
  검증 통과한 문항만 업로드하고, 나머지는 만들지 않은 채로 종료한다(다음
  실행에서 보충하지 않음 — 신선도가 핵심이므로 어제치를 오늘 몫에 얹지 않는다).
- **유닛 서브에이전트 핸드백 누락/타임아웃(4단계)**: 그 유닛만 빈 산출로 두고
  나머지 유닛의 핸드백은 그대로 쓴다 — 한 유닛이 막혀도 전체를 막지 않는다
  (경기 단위 fail-closed와 같은 원칙, §4 순서 참고). 핸드백이 돌아왔는데
  내용이 비정상(JSON 파싱 실패 등)이어도 같다 — 그 유닛만 버리고 진행.
- **작업 디렉토리를 별도 저장소로 착각하지 않는다**: `VictoryFairy_AI`는
  `VictoryFairy` 리포 **안의 서브디렉토리**이고 별도 GitHub 저장소가 아니다
  (`VictoryFairy_WIKI`만 별도 저장소). 1단계에서 이미 `VictoryFairy` 소스로
  작업 디렉토리가 마운트돼 있으므로, 그 안의 `VictoryFairy_AI/`를 그냥 `cd`해서
  쓰면 된다 — `add_repo`류 도구로 "VictoryFairy_AI"라는 이름의 저장소를 따로
  찾거나 요청하지 않는다(2026-10-03에 실제로 이 착오로 0단계에서 전체 실행이
  실패했다 — 이미 있는 작업 디렉토리를 먼저 확인했으면 피할 수 있었다).

## 신규 템플릿 제안 (참고)

8단계에서 남기는 제안은 **카탈로그에 자동 반영되지 않는다**. 사람이
`wiki/_meta/template-proposals/`를 검토해 괜찮다고 판단하면 직접
`question-gen/config/question-templates.yaml`에 새 항목을 추가하고 PR로 리포에
반영한다 — 이 문서(ROUTINE.md)나 routine 실행 자체는 그 반영 과정에 관여하지 않는다.
