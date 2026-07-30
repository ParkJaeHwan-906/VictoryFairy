# 위키 빌더 routine 실행 지침

Claude Code 클라우드 스케줄 잡(routine)이 실행마다 그대로 따르는 절차다. 이
routine 자신이 "LLM 병합 엔진"이므로, 3단계(LLM 병합)·4단계(trending 요약)는
별도 프로세스를 호출하지 않고 이 세션이 직접 `prompts/merge-rules.md`를 규칙으로
적용해 Read/Write 도구로 수행한다. 그 외 단계는 실제 셸 명령으로 명시한다.

## 개요

- **주기**: 주 1~2회(화·금 06:00 KST 권장)
- **모델**: Sonnet 5
- **소요 상한**: 1회 실행 60분(그 시점까지 처리 못 한 선수·게시글은 스킵 목록에
  남기고 다음 실행이 이어서 처리 — 아래 "실패 처리" 참고)
- **재실행 안전성**: 문서 단위로 독립적으로 S3에 덮어쓴다(멱등). 실행이 중단돼도
  이미 업로드된 선수 문서는 그대로 유효하고, 다음 실행이 `wiki/_meta/builder-runs/`의
  마지막 성공 시각부터 이어서 처리한다 — 처음부터 다시 돌 필요 없음(스펙 §5)

## 사전 조건

- 환경변수 `S3_BUCKET` (예: `victoryfairy-crawl-dev`)
- routine 전용 최소 권한 IAM 자격증명(`question-source/`·`kbo-records/` 읽기,
  `wiki/` 읽기+쓰기) — 발급·배포는 Task 11 소관, 이 문서는 자격증명이 이미 환경에
  주입돼 있다고 가정한다
- `aws` CLI (자격증명 확인: `aws sts get-caller-identity`)
- Python 실행기: `py-collector/.venv/bin/python` (PyYAML·boto3 기설치 — 이
  venv를 그대로 쓴다. **주의**: 초안 브리프는 `pip install -r
  question-gen/requirements.txt`를 전제했으나 그 파일은 리포에 존재하지 않는다.
  `wiki-builder/scripts/compile_graph.py`가 요구하는 의존성은 PyYAML뿐이고
  `py-collector/.venv`에 이미 설치돼 있으므로 별도 설치 단계 없이 이 venv를
  재사용한다)
- 작업 디렉토리: `VictoryFairy_AI/`. 아래 모든 명령은 이 디렉토리를 cwd로 실행한다.
  임시 작업물은 `.work/`에 모으고(로컬 클론된 routine 워크스페이스, git 추적 대상
  아님), 실행 끝에 `.work/wiki/`만 S3로 올린다.

## 절차

### 1. 증분 파악

`wiki/_meta/builder-runs/`에서 마지막 성공 실행 시각을 찾고, 그 날짜 이후
파티션만 `.work/posts/`로 내려받는다. 첫 실행(마커 없음)이면 최근 14일.

```bash
: "${S3_BUCKET:?S3_BUCKET 환경변수를 설정하라}"
mkdir -p .work/posts

LAST_RUN_KEY=$(aws s3 ls "s3://$S3_BUCKET/wiki/_meta/builder-runs/" 2>/dev/null \
  | awk '{print $4}' | sort | tail -1)

if [ -n "$LAST_RUN_KEY" ]; then
  LAST_RUN_ISO="${LAST_RUN_KEY%.json}"
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

**입력은 `validation/bedrock/success/` 뿐이다.** `community/`(원문) 경로는 위키
빌더가 직접 읽지 않는다 — 검열·주제 필터를 통과한 정제 게시글만 소비한다.

### 2. 참조 데이터 동기화

`player_profile`(선수 명단, 최신 파티션만), `player_meme`(밈 시드, 전체),
기존 `wiki/players/`(병합 대상 기존 문서)를 `.work/`로 내려받는다.

```bash
mkdir -p .work/player_profile .work/player_meme .work/wiki/players

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
aws s3 sync "s3://$S3_BUCKET/wiki/players/" .work/wiki/players/ \
  --exclude "*" --include "*.md"
```

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

1. `.work/wiki/players/{kboPlayerId}.md`를 Read(없으면
   `templates/player-doc.md`를 시작점으로 Read)
2. `.work/groups/{kboPlayerId}.txt`에 나열된 게시글 파일을 모두 Read
3. `prompts/merge-rules.md` 전문을 규칙으로 적용해 병합 — 매칭 확신 없는
   게시글은 그 선수의 스킵 목록에 사유와 함께 남긴다(규칙 6)
4. 결과를 `.work/wiki/players/{kboPlayerId}.md`에 Write(덮어쓰기)

이 서브루틴을 로컬에서 오프라인으로 재현/검증할 때는(운영 실행에서는 쓰지 않음,
드라이런 전용) 이 세션 대신 하위 프로세스로 `claude -p`를 호출해 같은 입력을
준다:

```bash
claude -p "$(cat prompts/merge-rules.md)

[기존 문서]
$(cat ".work/wiki/players/$KBO_ID.md" 2>/dev/null || cat templates/player-doc.md)

[신규 게시글 (JSON, validation/bedrock/success 파티션)]
$(for f in $(cat ".work/groups/$KBO_ID.txt"); do cat "$f"; echo; done)

[선수 프로필]
$(cat ".work/player_profile/player_profile:$KBO_ID.json" 2>/dev/null)

위 규칙을 지켜 이 선수의 위키 문서 전문(front-matter 포함)을 갱신해 출력하라." \
  --model sonnet > ".work/wiki/players/$KBO_ID.md"
```

### 4. trending.md

이번 실행에서 읽은(`.work/posts/`) 정제 게시글만으로 급증 키워드·화제 선수
top 10을 뽑아 `.work/wiki/stats/trending.md`로 요약한다. 이전 실행 결과를
누적하지 않는다(이번 실행분 전용 — `최근 여론` 섹션과 같은 원칙). 이 세션이
직접 `.work/posts/`를 훑고 `question-gen/config/banned-topics.txt`에 걸리는
토픽(음주·폭행·마약·도박·승부조작·사생활·병역·학폭·건강 문제 등)은 후보에서
제외한 뒤 작성한다.

```bash
mkdir -p .work/wiki/stats
cat question-gen/config/banned-topics.txt   # 제외 목록 확인(이 세션이 직접 대조)
# -> .work/wiki/stats/trending.md 작성(Write 도구)
```

### 5. all-time-records.md 렌더

`question-gen/config/all-time-records.yaml`(Task 9 산출물)을 표 형태 md로
결정적 변환한다. LLM을 쓰지 않는 인라인 파이썬이며, **파일이 아직 없으면
(Task 9 완료 전) 이 단계 전체를 스킵하고 다음 단계로 넘어간다** — 실패로
취급하지 않는다.

```bash
py-collector/.venv/bin/python - <<'PY'
import sys
from pathlib import Path
import yaml

src = Path("question-gen/config/all-time-records.yaml")
if not src.exists():
    print("all-time-records.yaml 없음 — Task 9 완료 전까지 이 단계 스킵", file=sys.stderr)
    sys.exit(0)

records = yaml.safe_load(src.read_text(encoding="utf-8")) or []
# 아래 컬럼(rank/player/value/asOf/source)은 Task 9가 확정할 실제 YAML 스키마에
# 맞춰 조정한다 — 지금은 스펙 4.1의 "항목마다 기준일·출처 명시"를 반영한 잠정안이다.
lines = ["# 역대 기록 (All-Time Records)", ""]
for category in records:
    lines.append(f"## {category.get('category', '')}")
    lines.append("")
    lines.append("| 순위 | 선수 | 값 | 기준일 | 출처 |")
    lines.append("|---|---|---|---|---|")
    for row in category.get("rows", []):
        lines.append(
            f"| {row.get('rank', '')} | {row.get('player', '')} | "
            f"{row.get('value', '')} | {row.get('asOf', '')} | {row.get('source', '')} |"
        )
    lines.append("")

out = Path(".work/wiki/stats/all-time-records.md")
out.parent.mkdir(parents=True, exist_ok=True)
out.write_text("\n".join(lines), encoding="utf-8")
print(f"렌더 완료: {out}")
PY
```

### 6. 그래프 컴파일

전체 `.work/wiki/players/*.md`의 front-matter(`relations` + 팀 소속)를 훑어
`graph.json`을 재컴파일한다(Task 7 산출물, 결정적).

```bash
py-collector/.venv/bin/python wiki-builder/scripts/compile_graph.py \
  --players-dir .work/wiki/players --out .work/wiki/graph.json
```

컴파일이 실패(예외로 종료)하면 `.work/wiki/graph.json`을 만들지 말고(또는
만들어졌더라도 업로드 대상에서 제외하고) 7단계에서 이전 버전을 유지한다.

### 7. 업로드

`.work/wiki/`를 그대로 `s3://$S3_BUCKET/wiki/`에 동기화한다(문서별 멱등
덮어쓰기). 이번 실행의 처리 게시글 수·갱신 문서 수·스킵 목록을 실행 로그로
남긴다.

```bash
# graph.json이 6단계에서 만들어지지 않았으면 업로드 대상에서 제외해 이전 버전을 보존한다
if [ ! -f .work/wiki/graph.json ]; then
  echo "graph.json 컴파일 실패/스킵 — 이전 버전 유지, 이번 업로드에서 제외" >&2
  EXCLUDE_GRAPH=(--exclude "graph.json")
else
  EXCLUDE_GRAPH=()
fi

aws s3 sync .work/wiki/ "s3://$S3_BUCKET/wiki/" "${EXCLUDE_GRAPH[@]}"

RUN_ISO=$(date -u +%Y-%m-%dT%H:%M:%SZ)
cat > ".work/run-log.json" <<JSON
{
  "runAt": "$RUN_ISO",
  "postsProcessed": <이번 실행에서 읽은 게시글 수>,
  "playersUpdated": <갱신된 선수 문서 수>,
  "skipped": [<선수 매칭 보류/거부 목록 — 사유 포함>]
}
JSON
aws s3 cp ".work/run-log.json" "s3://$S3_BUCKET/wiki/_meta/builder-runs/$RUN_ISO.json"
```

마커(`builder-runs/{ISO}.json`)는 **문서·그래프 업로드가 끝난 뒤 마지막에** 쓴다
— 먼저 쓰면 산출물 없이 완결로 보여 다음 실행이 그 구간을 재처리하지 않게 된다.

## 실패 처리

- **부분 실패(일부 선수만 갱신 성공)**: 성공한 문서만 업로드하고, 실패/스킵한
  선수는 실행 로그의 `skipped`에 사유와 함께 남긴다. 다음 실행이 증분 기준
  (1단계)에 따라 같은 날짜 구간을 다시 훑으므로 자연히 이어서 처리된다.
- **그래프 컴파일 실패**: `graph.json` 업로드를 생략하고 S3의 이전 버전을
  그대로 유지한다(퀴즈 생성기는 stale 그래프로도 동작 가능 — 스펙 §5).
- **`player_profile` 파티션 부재**: 2단계에서 감지되면 3~4단계를 건너뛰고
  빈 실행 로그만 남긴 채 종료한다(운영 갭 — Task 11에서 다룰 스케줄링 이슈,
  이 routine의 책임이 아니다).
- **60분 소요 상한 초과**: 그 시점까지 처리한 선수까지만 업로드하고 남은
  후보는 스킵 목록에 남긴다 — 강제 종료해도 이미 업로드된 문서는 안전하다
  (문서 단위 독립 커밋).
