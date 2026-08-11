#!/usr/bin/env bash
#
# main 의 .github/ 를 dev_* 브랜치들에 복제한다.
#
# 왜 필요한가:
# pull_request 이벤트는 대상(base) 브랜치 쪽 워크플로 정의로 실행된다. 그래서 CI 를
# 고쳐 main 에 넣어도, dev_* 로 향하는 PR 은 그 브랜치에 남아 있는 옛 정의로 계속 돈다.
# dev_* 는 서로 독립 트리라 main 을 머지해 맞출 수도 없다(다른 디렉터리가 지워진다).
# 실제 사고: 리뷰 프롬프트 수정이 dev_be·main 에만 들어가, dev_fe 로 가던 PR 이 며칠 동안
# 옛 규칙으로 차단됐다. 증상이 "고쳤는데 그대로" 라 알아채기 어렵다.
#
# 무엇을 하지 않는가 — 브랜치에만 있는 .github 파일은 지우지 않는다.
# dev_* 에 새 워크플로가 머지됐지만 아직 main 에 배포되지 않은 창이 존재한다.
# main 을 통째로 덮어쓰면 그 창에 들어온 작업이 사라진다. 그래서 삭제는 이번 push 가
# main 에서 실제로 지운 경로에 한정한다(수동 실행 때는 아예 지우지 않는다).
#
# 왜 PR 이 아니라 직접 push 하는가:
# 올리는 내용이 main 의 .github 와 바이트 단위로 같다. 즉 이미 리뷰·머지된 것을 옮길
# 뿐이라 새로 판단할 것이 없고, 브랜치 수만큼 리뷰를 돌리면 그 자체가 비용이다.
# 대신 push 직전에 두 가지를 기계적으로 검증한다.
#   1. 스테이징된 경로가 전부 .github/ 아래인가
#   2. 결과 트리의 .github 가 main 과 일치하는가 (브랜치 고유 파일 제외)
# 하나라도 어긋나면 그 브랜치는 건너뛰고 실행을 실패시킨다.
#
# 환경변수:
#   PAT       AUTOMATION_TOKEN. 룰셋("PR 필수")을 우회할 수 있는 관리자 토큰이어야 한다.
#   REPO      owner/name
#   MAIN_SHA  복제할 main 커밋 (push 이벤트의 github.sha)
#   BEFORE    push 이전 main 커밋. 삭제 경로 판정에만 쓴다. 비어 있으면 삭제하지 않는다.
#   TARGETS   대상 브랜치 (공백 구분). 비어 있으면 origin/dev_* 전체
#   DRY_RUN   true 면 커밋까지만 하고 push 하지 않는다

set -euo pipefail

REPO="${REPO:?owner/repo}"
MAIN_SHA="${MAIN_SHA:?main sha}"
BEFORE="${BEFORE:-}"
TARGETS="${TARGETS:-}"
DRY_RUN="${DRY_RUN:-false}"

if [ "$DRY_RUN" != "true" ] && [ -z "${PAT:-}" ]; then
  echo "::error::AUTOMATION_TOKEN 시크릿이 없습니다. dev_* 는 룰셋으로 보호돼 있어 관리자 토큰이 필요합니다."
  exit 1
fi

git config user.name "github-actions[bot]"
git config user.email "41898282+github-actions[bot]@users.noreply.github.com"

git fetch --no-tags --quiet origin "+refs/heads/*:refs/remotes/origin/*"

if [ -n "$TARGETS" ]; then
  branches="$TARGETS"
else
  branches=$(git for-each-ref --format='%(refname:strip=3)' 'refs/remotes/origin/dev_*')
fi

# 이번 push 로 main 에서 사라진 .github 경로. 이것만 dev_* 에서도 지운다.
# 첫 push·강제 push 로 BEFORE 가 존재하지 않는 커밋일 수 있어 실재를 확인하고 쓴다.
deleted=""
if [ -n "$BEFORE" ] && git cat-file -e "${BEFORE}^{commit}" 2>/dev/null; then
  deleted=$(git diff --diff-filter=D --name-only "$BEFORE" "$MAIN_SHA" -- .github)
fi

synced=""
skipped=""
failed=""

for branch in $branches; do
  echo "── ${branch}"

  if ! git rev-parse --verify --quiet "origin/${branch}^{commit}" >/dev/null; then
    echo "::warning::origin/${branch} 이 없습니다 — 건너뜁니다"
    skipped="${skipped} ${branch}(없음)"
    continue
  fi

  git checkout -q --detach "origin/${branch}"

  # main 의 .github 전체를 덮어쓴다. 추가·수정만 일어나고 삭제는 아래에서 따로 다룬다.
  git checkout "$MAIN_SHA" -- .github
  for path in $deleted; do
    git rm -rq --ignore-unmatch -- "$path"
  done

  if git diff --cached --quiet; then
    echo "이미 main 과 동일 — 변경 없음"
    skipped="${skipped} ${branch}(동일)"
    continue
  fi

  # 안전장치 1: .github 밖을 건드리면 그 브랜치는 손대지 않는다.
  # checkout 대상 경로가 .github 하나뿐이라 여기 걸릴 일은 없어야 하지만,
  # 걸린다면 스크립트가 의도와 다르게 도는 것이므로 조용히 넘기지 않는다.
  outside=$(git diff --cached --name-only | grep -v '^\.github/' || true)
  if [ -n "$outside" ]; then
    echo "::error::${branch}: .github 밖 경로가 포함됐습니다 — ${outside}"
    git checkout -q --force "origin/${branch}"
    failed="${failed} ${branch}(범위이탈)"
    continue
  fi

  changed=$(git diff --cached --name-only | tr '\n' ' ')
  git commit -q -m "chore(ci): main 의 .github 를 ${branch} 에 동기화한다 (${MAIN_SHA:0:7})

pull_request 는 대상 브랜치 쪽 워크플로 정의로 실행되므로, main 에만 반영된 CI 변경은
이 브랜치로 오는 PR 에 적용되지 않는다. dev_* 는 독립 트리라 main 을 머지할 수 없어
.github 만 복제한다. 자동 생성 커밋이다."

  # 안전장치 2: 결과가 정말 main 과 같은가.
  # main 에 없는(=이 브랜치 고유의) 파일은 대조 대상이 아니다. 아직 배포되지 않은
  # 브랜치 로컬 워크플로가 여기 해당하며, 그것을 지우지 않는 것이 이 스크립트의 규칙이다.
  mismatch=""
  while read -r path; do
    [ -z "$path" ] && continue
    if git cat-file -e "${MAIN_SHA}:${path}" 2>/dev/null; then
      mismatch="${mismatch} ${path}"
    fi
  done <<EOF
$(git diff --name-only "$MAIN_SHA" HEAD -- .github)
EOF

  if [ -n "$mismatch" ]; then
    echo "::error::${branch}: 동기화 후에도 main 과 다른 파일이 남았습니다 —${mismatch}"
    failed="${failed} ${branch}(불일치)"
    continue
  fi

  if [ "$DRY_RUN" = "true" ]; then
    echo "DRY_RUN — push 하지 않음. 변경: ${changed}"
    synced="${synced} ${branch}(dry-run)"
    continue
  fi

  # 토큰을 원격 URL 에 담아 이 호출에만 쓴다. remote set-url 로 남기면 이후 스텝의
  # 무관한 git 명령에도 자격증명이 붙는다.
  if git push --quiet "https://x-access-token:${PAT}@github.com/${REPO}.git" \
      "HEAD:refs/heads/${branch}"; then
    echo "push 완료 — ${changed}"
    synced="${synced} ${branch}"
  else
    # 실행 중 브랜치가 움직였으면 non-fast-forward 로 거부된다. 강제로 밀지 않는다.
    echo "::error::${branch}: push 실패 (브랜치가 움직였을 수 있습니다). 다음 실행이나 수동 실행으로 재시도하세요."
    failed="${failed} ${branch}(push실패)"
  fi
done

{
  echo "## .github 동기화 (main \`${MAIN_SHA:0:7}\`)"
  echo
  echo "- 동기화:${synced:- 없음}"
  echo "- 변경 없음:${skipped:- 없음}"
  echo "- 실패:${failed:- 없음}"
} >>"${GITHUB_STEP_SUMMARY:-/dev/stdout}"

[ -z "$failed" ] || exit 1
