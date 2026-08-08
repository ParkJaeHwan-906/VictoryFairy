# 위키 빌더 routine 실행 지침

Claude Code 클라우드 스케줄 잡(routine)이 실행마다 그대로 따르는 절차다. 이
routine 자신이 "LLM 병합 엔진"이므로, 3단계(LLM 병합)·4단계(trending 요약)는
별도 프로세스를 호출하지 않고 이 세션이 직접 `wiki-builder/prompts/merge-rules.md`를
규칙으로 적용해 Read/Write 도구로 수행한다. 그 외 단계는 실제 셸 명령으로 명시한다.

## 개요

- **주기**: 주 1~2회(화·금 06:00 KST 권장)
- **모델**: Sonnet 5
- **소요 상한**: 1회 실행 60분(그 시점까지 처리 못 한 선수·게시글은 스킵 목록에
  남기고 다음 실행이 이어서 처리 — 아래 "실패 처리" 참고)
- **재실행 안전성**: 문서 단위로 독립적으로 덮어쓴다(멱등). 실행이 중단돼도
  이미 커밋된 선수 문서는 그대로 유효하고, 다음 실행이 `wiki/_meta/builder-runs/`의
  마지막 성공 시각부터 이어서 처리한다 — 처음부터 다시 돌 필요 없음(스펙 §5)

## 위키의 원본은 git이다

**위키의 유일한 원본은 `VictoryFairy_WIKI` 리포의 `dev` 브랜치**이고, 이 routine은
그 리포를 클론해 고친 뒤 직접 커밋·푸시한다. S3에 위키 사본을 두지 않는다.

2026-08-06 이전에는 루틴 세션이 GitHub에 푸시할 수 없어서 S3 `wiki-outbox/`로
갱신분을 반출하고 GitHub Actions(`wiki-sync`)가 대신 커밋하는 구조였다. 원인은
권한이 아니라 **claude.ai 계정에 연결된 GitHub 자격증명에 write가 없었던 것**이고,
`/web-setup`으로 그 자격증명을 교체해 해소됐다. 리포가 public이라 clone은 무자격으로도
되기 때문에 "읽기는 되는데 쓰기만 막힌다"처럼 보였을 뿐이다. outbox·`wiki-sync`·
S3 `wiki/` 읽기 캐시는 그 우회로였으므로 전부 폐기했다.

## 사전 조건

- 입력 버킷 — **입력**(크롤 게시글·선수 프로필·밈 시드)을 읽는 용도로만 쓴다. 위키
  산출물은 S3로 가지 않는다. 버킷은 환경변수 `S3_BUCKET`이 **필수**이고 기본값이 없다
  — 미설정이면 1단계가 즉시 **중단·보고**한다. 실버킷명을 문서에 박아 기본값으로 쓰면
  dev→prod 전환 때 옛 값이 정답처럼 되살아난다(`deploy/routines/README.md`의
  "하드코딩하지 않는다" 원칙 — 값 확인 절차도 그 문서에 있다).
  `victoryfairy-crawl-local`은 테스트·퀴즈용 구식 버킷이라 위키 빌드 입력으로 쓰면
  안 된다 — 두 버킷은 prefix 구조가 같아서 잘못 잡아도 **에러 없이 조용히 옛
  데이터로 완주한다**. 2026-08-06에 실제로 이 사고가 났다: 프롬프트로 dev를 지시했는데도
  환경변수가 local이라 8,265건(local 전량)만 읽고 끝났고, 인용 각주가 8/1에서 끊긴 것을
  보고서야 발견했다. 그래서 1단계는 잘못된 버킷을 조용히 고쳐 주지 않고 **중단**한다
  (강제 전환하면 그날 실행은 살아도 환경설정 오류가 영영 드러나지 않는다) +
  최신 파티션 로그를 남긴다
- routine 전용 최소 권한 IAM 자격증명(`question-source/`·`validation/` 읽기) —
  이 문서는 자격증명이 이미 환경에 주입돼 있다고 가정한다
- GitHub 쓰기 자격증명 — claude.ai 계정에 연결된 것을 세션이 자동으로 쓴다.
  프롬프트나 이 문서에 토큰을 적지 않는다
- `aws` CLI (자격증명 확인: `aws sts get-caller-identity`)
- `git` (커밋 주체는 세션 기본값 `Claude <noreply@anthropic.com>`)
- Python 실행기: `py-collector/.venv/bin/python` (PyYAML·boto3 기설치 — 이
  venv를 그대로 쓴다. `question-gen/requirements.txt`는 PyYAML만 요구하므로
  `pip install -r question-gen/requirements.txt`로 새 venv를 구성해도 되지만,
  `wiki-builder/scripts/compile_graph.py`가 요구하는 의존성도 PyYAML뿐이고
  `py-collector/.venv`에 이미 설치돼 있으므로 이 routine은 별도 설치 단계 없이
  그 venv를 그대로 재사용한다)
- 작업 디렉토리: `VictoryFairy_AI/`. 아래 모든 명령은 이 디렉토리를 cwd로 실행한다.
  임시 작업물은 `.work/`에 모은다(이 리포의 git 추적 대상 아님). 위키는
  `.work/wiki-repo/`에 클론하고, 그 안의 `wiki/`가 모든 편집 대상이다

## 절차

### 0. 위키 클론

```bash
rm -rf .work/wiki-repo
git clone --depth 1 -b dev \
  https://github.com/ParkJaeHwan-906/VictoryFairy_WIKI.git .work/wiki-repo
```

`--depth 1`이면 충분하다 — 이 routine은 이력을 읽지 않고 최신 상태 위에 덮어쓴다.
클론이 실패하면 **여기서 중단한다**: 기존 문서를 못 읽은 채 진행하면 3단계 병합이
빈 문서에서 시작해 누적된 위키를 통째로 날린다.

### 1. 증분 파악

`wiki/_meta/builder-runs/`에서 마지막 성공 실행 시각을 찾고, 그 날짜 이후
파티션만 `.work/posts/`로 내려받는다. 첫 실행(마커 없음)이면 최근 14일.

**단, 직전 마커에 `pendingPlayers`가 남아 있으면 전체 파티션을 받는다.** 증분 창은
*게시글 날짜* 기준이라, "게시글은 예전 것이지만 시간이 없어 아직 못 다룬 선수"를
기억하지 못한다. 2026-08-06에 실제로 이 구멍에 빠졌다 — 후보 557명 중 110명만
처리하고 마커를 찍자 다음 실행의 증분 창이 그 시각 이후로 좁혀져, 남은 447명의
근거 게시글(7/19~8/1 파티션)이 영영 후보에서 빠지게 됐다. 백필 세션으로 메웠다.

```bash
# 입력 버킷 확정 — 기본값 없음, 잘못된 값은 고쳐 주지 않고 중단한다(fail-closed).
# 실버킷명을 기본값으로 박으면 dev→prod 전환 때 옛 값이 되살아나고, 강제 전환은
# 환경설정 오류를 영영 숨긴다(사전 조건·2026-08-06 사고 참고).
if [ -z "${S3_BUCKET:-}" ]; then
  echo "중단: S3_BUCKET 미설정 — routine 환경의 env 값을 확인하라(deploy/routines/README.md 2번)" >&2
  exit 1
fi
if [ "$S3_BUCKET" = "victoryfairy-crawl-local" ]; then
  echo "중단: S3_BUCKET이 구식 테스트 버킷(victoryfairy-crawl-local)이다 — routine 환경의 env 값을 운영 버킷으로 고쳐라" >&2
  exit 1
fi

# 이번 실행이 실제로 무엇을 보고 있는지 남긴다 — 버킷을 잘못 잡으면 여기서 드러난다.
# 최신 파티션이 오늘·어제가 아니면 입력이 멈춘 것이므로 보고에 반드시 적는다.
echo "입력 버킷: s3://$S3_BUCKET"
for SRC in dcinside fmkorea; do
  echo "  $SRC 최신 파티션: $(aws s3 ls "s3://$S3_BUCKET/validation/bedrock/success/$SRC/" \
    2>/dev/null | tail -1 | awk '{print $2}')"
done

mkdir -p .work/posts

# 마커는 위키 리포 안에 있다(0단계에서 클론됨) — S3가 아니다.
LAST_RUN_KEY=$(ls .work/wiki-repo/wiki/_meta/builder-runs/ 2>/dev/null \
  | sort | tail -1)

PENDING_COUNT=0
if [ -n "$LAST_RUN_KEY" ]; then
  LAST_RUN_ISO="${LAST_RUN_KEY%.json}"
  PENDING_COUNT=$(py-collector/.venv/bin/python -c "
import json,sys
d=json.load(open('.work/wiki-repo/wiki/_meta/builder-runs/$LAST_RUN_KEY'))
print(len(d.get('pendingPlayers') or []))" 2>/dev/null || echo 0)
fi

if [ "$PENDING_COUNT" -gt 0 ]; then
  # 미처리 선수가 남아 있다 — 그들의 근거 게시글은 증분 창 밖(과거)이므로 전량 받는다.
  SINCE_DATE="0000-00-00"
  echo "미처리 선수 ${PENDING_COUNT}명 — 전체 파티션 수집"
elif [ -n "$LAST_RUN_KEY" ]; then
  SINCE_DATE=$(date -u -d "$LAST_RUN_ISO" +%Y-%m-%d 2>/dev/null \
    || date -u -jf "%Y-%m-%dT%H:%M:%S" "${LAST_RUN_ISO%%.*}" +%Y-%m-%d 2>/dev/null \
    || date -u -jf "%Y-%m-%dT%H:%M:%SZ" "$LAST_RUN_ISO" +%Y-%m-%d)
  echo "증분 기준: 마지막 성공 실행 $LAST_RUN_ISO 이후 ($SINCE_DATE~)"
else
  SINCE_DATE=$(date -u -d "-14 days" +%Y-%m-%d 2>/dev/null || date -u -v-14d +%Y-%m-%d)
  echo "첫 실행 — 최근 14일($SINCE_DATE~)부터 수집"
fi

for SRC in dcinside fmkorea; do
  DATES=$(aws s3 ls "s3://$S3_BUCKET/validation/bedrock/success/$SRC/" \
    | awk '{print $2}' | tr -d '/')
  for D in $DATES; do
    if [[ "$D" > "$SINCE_DATE" || "$D" == "$SINCE_DATE" ]]; then
      mkdir -p ".work/posts/$SRC/$D"
      aws s3 sync "s3://$S3_BUCKET/validation/bedrock/success/$SRC/$D/" \
        ".work/posts/$SRC/$D/" --exclude "*" --include "*.json"
    fi
  done
done
```

`pendingPlayers`가 있으면 **그 선수들을 3단계에서 먼저 처리**한다(신규 게시글보다
우선). 오래 밀린 선수가 계속 뒤로 밀리는 것을 막기 위한 순서다.

**입력은 `validation/bedrock/success/` 뿐이다.** `community/`(원문) 경로는 위키
빌더가 직접 읽지 않는다 — 검열·주제 필터를 통과한 정제 게시글만 소비한다.

### 2. 참조 데이터 동기화

`player_profile`(선수 명단, 최신 파티션만)과 `player_meme`(밈 시드, 전체)을
`.work/`로 내려받는다.

```bash
mkdir -p .work/player_profile .work/player_meme

LATEST_PROFILE_DATE=$(aws s3 ls "s3://$S3_BUCKET/question-source/player_profile/" \
  2>/dev/null | awk '{print $2}' | tr -d '/' | sort | tail -1)
if [ -n "$LATEST_PROFILE_DATE" ]; then
  aws s3 sync "s3://$S3_BUCKET/question-source/player_profile/$LATEST_PROFILE_DATE/" \
    .work/player_profile/ --exclude "*" --include "*.json"
else
  echo "경고: question-source/player_profile/ 파티션이 없음 — 선수 매칭 명단 없이는" >&2
  echo "게시글을 선수에 매칭할 수 없다. player_profile export 실행 여부를 확인하라" >&2
  echo "(운영 스케줄링은 Task 11 소관 — 알려진 갭, task-8-report.md concerns 참고)" >&2
fi

aws s3 sync "s3://$S3_BUCKET/question-source/player_meme/" .work/player_meme/ \
  --exclude "*" --include "*.json"
```

기존 `wiki/players/`는 따로 내려받지 않는다 — 0단계 클론에 이미 들어 있다.

`player_profile` 파티션이 비어 있으면(2026-07-30 기준 실측: 운영 버킷에 아직
0건) 이번 실행은 선수 매칭이 전혀 안 되므로, 로그에 그 사실을 남기고 3~4단계를
건너뛴 채 종료해도 된다(문서 갱신 없음 자체가 안전한 결과다 — 잘못된 매칭보다
매칭 안 함이 낫다, merge-rules.md 규칙 6).

### 3. LLM 병합

**3-1. 후보 그룹핑(결정적 사전 필터, LLM 아님)** — 각 게시글의 텍스트에 선수명이
문자열로 포함되는지만으로 후보를 넓게 잡는다(재현율 우선, 오탐은 다음 단계에서
LLM이 규칙 6으로 걸러낸다).

```bash
py-collector/.venv/bin/python - <<'PY'
import json
from pathlib import Path

work = Path(".work")
profiles = []
for p in (work / "player_profile").glob("*.json"):
    env = json.loads(p.read_text(encoding="utf-8"))
    # title 형식: "{team_name} {name} 프로필" -> 마지막 공백 이전까지가 팀명,
    # 마지막 토큰이 선수명이 아니라 "{name} 프로필"의 name 부분이므로 " 프로필"을 뗀다.
    # 이 파싱은 팀 단축명이 공백 없는 단일 토큰이라는 전제(현재 KBO 10개 구단
    # 전부 성립 — "LG"/"두산"/"롯데" 등)에 의존한다. 구단이 늘거나 팀명 표기가
    # 다중 토큰으로 바뀌면(예: "SSG 랜더스"처럼 팀명 자체에 공백) split(" ", 1)이
    # 팀명 뒷부분을 선수명에 잘못 붙일 수 있어 이 로직을 다시 봐야 한다.
    title = env.get("title", "")
    name = title.rsplit(" ", 1)[0].split(" ", 1)[-1] if title.endswith("프로필") else None
    kbo_id = (env.get("payload") or {}).get("playerId")
    if name and kbo_id:
        profiles.append({"name": name, "kboPlayerId": str(kbo_id)})

groups = {p["kboPlayerId"]: [] for p in profiles}
for post_path in (work / "posts").glob("*/*/*.json"):
    post = json.loads(post_path.read_text(encoding="utf-8"))
    blob = " ".join([
        post.get("title") or "", post.get("body") or "",
        *[(c.get("body") or "") for c in (post.get("topComments") or [])
          if isinstance(c, dict)],
    ])
    for prof in profiles:
        if prof["name"] in blob:
            groups[prof["kboPlayerId"]].append(str(post_path))

out_dir = work / "groups"
out_dir.mkdir(exist_ok=True)
hit = 0
for kbo_id, paths in groups.items():
    if paths:
        (out_dir / f"{kbo_id}.txt").write_text("\n".join(paths), encoding="utf-8")
        hit += 1
print(f"후보 그룹 {hit}명 (선수 명단 {len(profiles)}명 중)")
PY
```

**3-2. 선수별 병합(LLM, 이 세션이 직접 수행)** — `.work/groups/`에 후보가 생긴
`kboPlayerId`마다 반복한다:

1. `.work/wiki-repo/wiki/players/{kboPlayerId}.md`를 Read(없으면
   `wiki-builder/templates/player-doc.md`를 시작점으로 Read)
2. `.work/groups/{kboPlayerId}.txt`에 나열된 게시글 파일을 모두 Read
3. `.work/player_meme/`의 밈 시드 envelope 중 이 선수(`entities.playerUids[0]`
   또는 이름 매칭)에 해당하는 것을 모두 Read(선택 입력 — 스텝 2에서 이미 동기화됨)
4. `wiki-builder/prompts/merge-rules.md` 전문을 규칙으로 적용해 병합 — 매칭
   확신 없는 게시글은 그 선수의 스킵 목록에 사유와 함께 남긴다(규칙 6)
5. 결과를 `.work/wiki-repo/wiki/players/{kboPlayerId}.md`에 Write(덮어쓰기)

이 서브루틴을 로컬에서 오프라인으로 재현/검증할 때는(운영 실행에서는 쓰지 않음,
드라이런 전용) 이 세션 대신 하위 프로세스로 `claude -p`를 호출해 같은 입력을
준다:

```bash
MERGE_RULES=$(cat wiki-builder/prompts/merge-rules.md) || {
  echo "wiki-builder/prompts/merge-rules.md를 열 수 없음 — 중단" >&2; exit 1; }
EXISTING_DOC=$(cat ".work/wiki-repo/wiki/players/$KBO_ID.md" 2>/dev/null) || true
if [ -z "$EXISTING_DOC" ]; then
  EXISTING_DOC=$(cat wiki-builder/templates/player-doc.md) || {
    echo "wiki-builder/templates/player-doc.md를 열 수 없음 — 중단" >&2; exit 1; }
fi

claude -p "$MERGE_RULES

[기존 문서]
$EXISTING_DOC

[신규 게시글 (JSON, validation/bedrock/success 파티션)]
$(for f in $(cat ".work/groups/$KBO_ID.txt"); do cat "$f"; echo; done)

[선수 프로필]
$(cat ".work/player_profile/player_profile:$KBO_ID.json" 2>/dev/null)

위 규칙을 지켜 이 선수의 위키 문서 전문(front-matter 포함)을 갱신해 출력하라." \
  --model sonnet > ".work/wiki-repo/wiki/players/$KBO_ID.md"
```

`cat`이 조용히 실패해 규칙 없이(또는 빈 템플릿 없이) 프롬프트가 조립되는 것을
막기 위해, `merge-rules.md`·`player-doc.md`는 변수로 먼저 읽어 실패 시 즉시
`exit 1`한다 — 실패한 채로 `claude -p`를 실행해 규칙 누락 상태로 문서를
덮어쓰는 위험한 실패 모드를 차단한다.

### 4. trending.md

이번 실행에서 읽은(`.work/posts/`) 정제 게시글만으로 급증 키워드·화제 선수
top 10을 뽑아 `.work/wiki-repo/wiki/stats/trending.md`로 요약한다. 이전 실행 결과를
누적하지 않는다(이번 실행분 전용 — `최근 여론` 섹션과 같은 원칙). 이 세션이
직접 `.work/posts/`를 훑고 `question-gen/config/banned-topics.txt`에 걸리는
토픽(음주·폭행·마약·도박·승부조작·사생활·병역·학폭·건강 문제 등)은 후보에서
제외한 뒤 작성한다.

화제 선수 top 10 **표에는 `playerId` 컬럼을 반드시 포함**한다(순위·선수명·playerId·
언급 수·주요 토픽 순 권장). `playerId`는 KBO playerId — `.work/player_profile/`
명단(`payload.playerId`)과 이름을 대조해 채우며, 위키 문서 파일명
(`wiki/players/{kboPlayerId}.md`)·퀴즈 후보 `subject.playerIds`(스펙 4.3 v2)와
같은 축이다. **이름 문자열 집계는 동명이인을 구분하지 못한다** — 명단 매칭이
정확히 1명으로 떨어질 때만 id를 적고, 동명이인 등으로 확신이 없으면 그 행의
`playerId`는 비워 둔다(빈 칸인 행은 퀴즈 생성기가 `subject.playerIds`로 쓰지
않는다 — 잘못된 id보다 빈 칸이 낫다, merge-rules.md 규칙 6과 같은 원칙).

```bash
mkdir -p .work/wiki-repo/wiki/stats
cat question-gen/config/banned-topics.txt   # 제외 목록 확인(이 세션이 직접 대조)
# -> .work/wiki-repo/wiki/stats/trending.md 작성(Write 도구)
#    (화제 선수 표: | 순위 | 선수명 | playerId | 언급 수 | 주요 토픽 | — playerId는
#     player_profile 명단 매칭이 유일 확정일 때만, 동명이인이면 빈 칸)
```

### 5. all-time-records.md 렌더

`question-gen/config/all-time-records.yaml`(Task 9 산출물)을 표 형태 md로
결정적 변환한다. LLM을 쓰지 않는 인라인 파이썬이며, **파일이 아직 없으면
(Task 9 완료 전) 이 단계 전체를 스킵하고 다음 단계로 넘어간다** — 실패로
취급하지 않는다.

아래 컬럼(순위/이름/값)과 최상단 asOf·source, 카테고리별 rankBasis 표기는
실제 YAML 스키마(`{asOf, source, categories: [{id, title, sourcePage, rankBasis,
entries: [{rank, name, value}]}]}`)를 그대로 반영한다(리뷰 I2 — 이전 버전은 존재하지
않는 `category`/`rows`/`player` 키를 가정해 실행 즉시 `AttributeError`였다). `value`는
참고용 — 퀴즈 정답으로 쓰지 않는다(`generation-rules.md` §6). `rankBasis`는 그대로
노출해 병합 LLM·사람 모두가 "최초달성"과 "역대 1위"를 혼동하지 않게 한다.

```bash
py-collector/.venv/bin/python - <<'PY'
import sys
from pathlib import Path
import yaml

src = Path("question-gen/config/all-time-records.yaml")
if not src.exists():
    print("all-time-records.yaml 없음 — Task 9 완료 전까지 이 단계 스킵", file=sys.stderr)
    sys.exit(0)

seed = yaml.safe_load(src.read_text(encoding="utf-8")) or {}
as_of = seed.get("asOf", "미상")
source = seed.get("source", "미상")
categories = seed.get("categories") or []

RANK_BASIS_LABEL = {
    "chronological": "최초 달성 순(연대순) — 최초 달성자 맞히기 전용",
    "true-rank": "역대 통산 순위 — 역대 1위 맞히기 전용",
}

lines = ["# 역대 기록 (All-Time Records)", "",
         f"- 기준일(asOf): {as_of}", f"- 출처(source): {source}", ""]
for category in categories:
    title = category.get("title", category.get("id", ""))
    rank_basis = category.get("rankBasis", "")
    basis_label = RANK_BASIS_LABEL.get(rank_basis, rank_basis or "미상")
    lines.append(f"## {title}")
    lines.append("")
    lines.append(f"- sourcePage: {category.get('sourcePage', '')}")
    lines.append(f"- rankBasis: {rank_basis} ({basis_label})")
    lines.append("")
    lines.append("| 순위 | 이름 | 값(참고용) |")
    lines.append("|---|---|---|")
    for entry in category.get("entries", []):
        lines.append(f"| {entry.get('rank', '')} | {entry.get('name', '')} | {entry.get('value', '')} |")
    lines.append("")

out = Path(".work/wiki-repo/wiki/stats/all-time-records.md")
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text("\n".join(lines), encoding="utf-8")
print(f"렌더 완료: {out}")
PY
```

**실행 검증 결과(2026-07-31, 실물 `question-gen/config/all-time-records.yaml` 3개
카테고리로 스니펫 자체 실행)**: exit 0, `all-time-records.md` 정상 생성 — 최상단에
`기준일(asOf): 2026-07-30`·`출처(source): KBO 공식 기록실`, 카테고리 3개
(`통산 타자 — 타수`/`통산 투수 — 승`/`역대 TOP — 기록`) 각각 `sourcePage`·`rankBasis`
표기 + `순위 | 이름 | 값(참고용)` 3열 표(카테고리당 10행)로 렌더됨을 확인했다.

### 6. 그래프 컴파일

전체 `.work/wiki-repo/wiki/players/*.md`의 front-matter(`relations` + 팀 소속)를 훑어
`graph.json`을 재컴파일한다(Task 7 산출물, 결정적).

```bash
if py-collector/.venv/bin/python wiki-builder/scripts/compile_graph.py \
     --players-dir .work/wiki-repo/wiki/players --out .work/wiki-repo/wiki/graph.json.tmp; then
  mv .work/wiki-repo/wiki/graph.json.tmp .work/wiki-repo/wiki/graph.json
  echo "그래프 컴파일 성공 — .work/wiki-repo/wiki/graph.json 갱신"
else
  COMPILE_EXIT=$?
  echo "그래프 컴파일 실패(exit=$COMPILE_EXIT) — .work/wiki-repo/wiki/graph.json.tmp 폐기, 업로드 대상에서 제외" >&2
  rm -f .work/wiki-repo/wiki/graph.json.tmp
fi
```

**exit code를 반드시 확인한다** — `compile_graph.py`가 예외로 죽거나 도중에
강제 종료되면(타임아웃 등) `.work/wiki-repo/wiki/graph.json.tmp`만 불완전한 상태로 남고
`.work/wiki-repo/wiki/graph.json`은 손대지 않는다(동일 파일시스템에서 `mv`는 원자적
rename이라, 컴파일이 성공해 exit code 0을 반환한 뒤에만 최종 경로로
옮겨진다). 이 패턴이 없으면 `--out .work/wiki-repo/wiki/graph.json`에 직접 쓰다가
중간에 끊긴 손상된 JSON이 그대로 커밋돼 `dev`의 정상 이전 버전을 덮어쓸 수 있다 —
임시 파일 경유가 그 위험을 차단하는 핵심 장치다(실패하면 클론된 원본이 그대로 남아
git이 변경 없음으로 본다). `compile_graph.py` 자체는 수정하지 않는다(문서 레벨
가드로 충분).

### 7. 커밋·푸시

편집한 `.work/wiki-repo/wiki/`를 `dev`에 커밋해 푸시한다. 실행 로그(마커)도 같은
커밋에 넣는다 — 산출물과 마커가 한 커밋으로 원자적으로 올라가야 "마커는 올라갔는데
문서는 안 올라간" 상태가 생기지 않는다(예전 S3 경로에서는 업로드 순서로 이 문제를
막았지만, git은 커밋 하나로 끝난다).

```bash
cd .work/wiki-repo

# 6단계가 exit code 0으로 mv까지 끝냈을 때만 wiki/graph.json이 갱신돼 있다(임시
# 파일 경유 원자적 쓰기 — 6단계 참고). 실패했으면 파일이 클론된 이전 버전 그대로라
# git이 변경 없음으로 보고 자연히 커밋에서 빠진다. *.tmp 잔여물은 절대 커밋하지
# 않는다.
rm -f wiki/*.tmp wiki/**/*.tmp

RUN_ISO=$(date -u +%Y-%m-%dT%H:%M:%SZ)
mkdir -p wiki/_meta/builder-runs
cat > "wiki/_meta/builder-runs/$RUN_ISO.json" <<JSON
{
  "runAt": "$RUN_ISO",
  "postsProcessed": <이번 실행에서 읽은 게시글 수>,
  "playersUpdated": <갱신된 선수 문서 수>,
  "skipped": [<선수 매칭 보류/거부 목록 — 사유 포함>],
  "pendingPlayers": [<후보였으나 시간 상한으로 이번에 못 다룬 kboPlayerId 목록>]
}
JSON

git add -A wiki/
if git diff --cached --quiet; then
  echo "갱신된 문서 없음 — 커밋 생략"
else
  git commit -m "wiki: builder run $RUN_ISO"
  # 퀴즈 루틴도 같은 브랜치에 쓴다(stats·casebook) — 그 사이 올라온 커밋이 있으면
  # rebase 후 재시도한다. 서로 건드리는 파일이 달라 충돌은 사실상 없다.
  git push origin dev || {
    git pull --rebase origin dev && git push origin dev
  }
fi
cd -
```

**푸시 실패는 실패로 보고한다.** 예전 outbox 구조에서는 반출만 하면 나중에 Actions가
재시도해줬지만, 이제 푸시가 곧 반영이라 실패하면 그 실행의 산출물은 사라진다. 다음
실행이 같은 증분 구간을 다시 훑으므로 데이터가 영구히 유실되지는 않는다(마커도 같은
커밋에 있어 함께 롤백된다).

## 실패 처리

- **0단계 클론 실패**: 즉시 중단한다. 기존 문서를 못 읽은 채 병합하면 빈 문서에서
  시작해 누적된 위키를 통째로 날린다 — 이 routine에서 유일하게 회복 불가능한
  실패 모드다.
- **부분 실패(일부 선수만 갱신 성공)**: 성공한 문서만 커밋하고, 실패/스킵한
  선수는 실행 로그의 `skipped`에 사유와 함께 남긴다. 다음 실행이 증분 기준
  (1단계)에 따라 같은 날짜 구간을 다시 훑으므로 자연히 이어서 처리된다.
- **그래프 컴파일 실패**: 6단계의 임시 파일(`.tmp`) + exit code 확인 + `mv`
  패턴 덕에 실패 시 `wiki/graph.json`이 클론된 이전 버전 그대로 남는다 — git이
  변경 없음으로 보고 커밋에서 자연히 빠지므로 `dev`의 이전 버전이 유지된다(퀴즈
  생성기는 stale 그래프로도 동작 가능 — 스펙 §5).
- **`player_profile` 파티션 부재**: 2단계에서 감지되면 3~4단계를 건너뛰고
  빈 실행 로그만 남긴 채 종료한다(운영 갭 — Task 11에서 다룰 스케줄링 이슈,
  이 routine의 책임이 아니다).
- **푸시 실패**: 그 실행의 산출물은 반영되지 않는다. 마커가 같은 커밋에 있으므로
  증분 기준도 함께 롤백돼, 다음 실행이 같은 구간을 다시 훑는다 — 데이터 유실은
  없고 한 주기 늦어질 뿐이다. 실패 지점과 에러 전문을 보고한다.
- **60분 소요 상한 초과**: 그 시점까지 처리한 선수까지만 커밋하고, **못 다룬
  후보의 `kboPlayerId`를 마커의 `pendingPlayers`에 반드시 남긴다.** 이걸 빠뜨리면
  다음 실행의 증분 창(게시글 날짜 기준)이 좁혀지면서 그 선수들이 후보에서 영영
  사라진다 — 2026-08-06에 447명이 이 구멍에 빠졌다(1단계 설명 참고). 강제 종료돼
  커밋조차 못 했으면 마커도 안 써지므로 다음 실행이 같은 구간을 다시 처리한다.
