#!/usr/bin/env bash
#
# base 에 head 를 실제로 머지해 보고 충돌 내역을 마크다운 리포트로 뽑는다.
#
# GitHub 의 pull_request.mergeable 은 비동기로 계산돼 이벤트 시점엔 null 인 경우가 잦다.
# 그 값에 의존하면 충돌 PR 을 그냥 통과시키거나, 멀쩡한 PR 을 충돌로 오판한다.
# 그래서 러너에서 직접 머지를 시도한다.
#
# 사용법: conflict-report.sh <base_ref> <head_sha> [out.md]
# 출력  : GITHUB_OUTPUT 에 conflicted=true|false, files=<개수>

set -uo pipefail

BASE_REF="${1:?base ref}"
HEAD_SHA="${2:?head sha}"
REPORT="${3:-conflict-report.md}"

MAX_FILES=10        # 리포트에 자세히 실을 최대 파일 수
MAX_HUNK_LINES=40   # 파일당 충돌 구간 발췌 줄 수

out() { echo "$1" >>"$GITHUB_OUTPUT"; }

git config user.email "actions@github.com"
git config user.name "github-actions"

git fetch --no-tags --quiet origin "+refs/heads/${BASE_REF}:refs/remotes/origin/${BASE_REF}"
git checkout --quiet --detach "origin/${BASE_REF}"

if git merge --no-commit --no-ff "$HEAD_SHA" >/dev/null 2>&1; then
  git merge --abort 2>/dev/null || git reset --hard --quiet
  out "conflicted=false"
  out "files=0"
  echo "충돌 없음: ${HEAD_SHA} → ${BASE_REF}"
  exit 0
fi

mapfile -t CONFLICTS < <(git diff --name-only --diff-filter=U)

# 충돌이 아닌 사유(예: 커밋되지 않은 변경)로 머지가 실패한 경우
if [ "${#CONFLICTS[@]}" -eq 0 ]; then
  git merge --abort 2>/dev/null || git reset --hard --quiet
  out "conflicted=false"
  out "files=0"
  echo "머지는 실패했으나 충돌 파일이 없음 — 통과 처리"
  exit 0
fi

MERGE_BASE=$(git merge-base "origin/${BASE_REF}" "$HEAD_SHA")

# 커밋 SHA → GitHub 로그인. 멘션에 쓰려면 %an(표시 이름)이 아니라 로그인이 필요하다.
# gh 는 실패 시 에러 JSON 을 stdout 으로 뱉으므로, 종료 코드를 보고 버려야 한다.
login_of() {
  local out
  out=$(gh api "repos/${GITHUB_REPOSITORY}/commits/$1" --jq '.author.login // empty' 2>/dev/null) || return 0
  case "$out" in *'{'*) return 0 ;; esac
  printf '%s' "$out"
}

# <side_range> <path> → "abc1234 (@login) 커밋제목" 한 줄
side_summary() {
  local range="$1" path="$2" sha
  sha=$(git log -1 --format='%H' "$range" -- "$path")
  [ -z "$sha" ] && { echo "변경 없음"; return; }
  local login subject
  login=$(login_of "$sha")
  subject=$(git log -1 --format='%s' "$sha")
  echo "\`${sha:0:7}\` ${login:+(@${login}) }${subject}"
}

{
  echo "## 충돌 요약"
  echo
  echo "| 항목 | 값 |"
  echo "| --- | --- |"
  echo "| 머지 방향 | \`${HEAD_SHA:0:7}\` → \`${BASE_REF}\` |"
  echo "| 공통 조상 | \`${MERGE_BASE:0:7}\` |"
  echo "| 충돌 파일 | ${#CONFLICTS[@]}개 |"
  echo
  echo "충돌 파일 목록:"
  echo
  for f in "${CONFLICTS[@]}"; do echo "- \`${f}\`"; done
  echo
  echo "---"
  echo
} >"$REPORT"

idx=0
for f in "${CONFLICTS[@]}"; do
  idx=$((idx + 1))
  if [ "$idx" -gt "$MAX_FILES" ]; then
    echo "> 나머지 $((${#CONFLICTS[@]} - MAX_FILES))개 파일은 생략했습니다. 로컬에서 확인해 주세요." >>"$REPORT"
    break
  fi

  stages=$(git ls-files -u -- "$f" | awk '{print $3}' | sort -u | tr -d '\n')
  case "$stages" in
    123) kind="양쪽 수정 (both modified)" ;;
    12)  kind="head 쪽에서 삭제 (deleted by them)" ;;
    13)  kind="base 쪽에서 삭제 (deleted by us)" ;;
    23)  kind="양쪽에서 신규 추가 (both added)" ;;
    *)   kind="기타 (stage: ${stages:-unknown})" ;;
  esac

  hunks=$(grep -c '^<<<<<<<' -- "$f" 2>/dev/null || echo 0)

  {
    echo "### ${idx}. \`${f}\`"
    echo
    echo "- 유형: ${kind}"
    echo "- 충돌 구간: ${hunks}곳"
    echo "- \`${BASE_REF}\` 쪽 최근 변경: $(side_summary "${MERGE_BASE}..origin/${BASE_REF}" "$f")"
    echo "- PR 쪽 최근 변경: $(side_summary "${MERGE_BASE}..${HEAD_SHA}" "$f")"
    echo
  } >>"$REPORT"

  if [ "$hunks" -gt 0 ] && [ -f "$f" ]; then
    {
      echo "<details><summary>충돌 구간 발췌</summary>"
      echo
      echo '```'
      awk '/^<<<<<<</{p=1} p{print} /^>>>>>>>/{p=0}' "$f" | head -n "$MAX_HUNK_LINES"
      echo '```'
      echo
      echo "</details>"
      echo
    } >>"$REPORT"
  fi
done

# 충돌 상대편을 만든 사람들도 멘션 대상에 넣는다. 해결에 합의가 필요한 경우가 많다.
{
  for f in "${CONFLICTS[@]}"; do
    git log --format='%H' "${MERGE_BASE}..origin/${BASE_REF}" -- "$f"
  done
} | sort -u | head -n 8 | while read -r sha; do login_of "$sha"; done | sort -u | tr '\n' ' ' >counterparts.txt

git merge --abort 2>/dev/null || git reset --hard --quiet

out "conflicted=true"
out "files=${#CONFLICTS[@]}"
echo "충돌 ${#CONFLICTS[@]}개 파일 — ${REPORT} 생성"
