#!/usr/bin/env bash
#
# PR 이 자동화를 태워도 되는 상태인지 판정한다.
#
# 이벤트 페이로드가 아니라 API 를 조회한다. 페이로드의 draft·라벨·상태는 이벤트 발생
# 시각에 고정돼, 실행 도중 draft 로 되돌리거나 hold 를 붙여도 반영되지 않는다.
# 그래서 이 스크립트는 진입 시점과 머지 직전에 각각 호출한다.
#
# 사용법: pr-ready.sh <pr_number> <owner/repo>
# 출력  : GITHUB_OUTPUT 에 ready=true|false, reason=<차단 사유>

set -euo pipefail

PR="${1:?pr number}"
REPO="${2:?owner/repo}"

# 붙어 있으면 자동화를 진행하지 않는 라벨
BLOCKING_LABELS="hold wip draft do-not-merge 작업중 보류"

block() {
  {
    echo "ready=false"
    echo "reason=$1"
  } >>"$GITHUB_OUTPUT"
  echo "자동화 진행 안 함 — $1"
  exit 0
}

# gh 에 내장된 jq 를 쓴다. 러너에 별도 jq 가 없어도 동작한다.
data=$(gh pr view "$PR" --repo "$REPO" --json state,isDraft,title,labels \
  --jq '[.state, (.isDraft | tostring), ([.labels[].name] | join(" ")), .title] | @tsv')
IFS=$'\t' read -r state draft labels title <<<"$data"

# MERGED / CLOSED 는 물론이고, 예상 못 한 상태값도 전부 막는다.
[ "$state" = "OPEN" ] || block "PR 상태가 ${state} 입니다"

[ "$draft" = "false" ] || block "초안(Draft) PR 입니다"

# 제목에 작업 중 표시가 있으면 아직 리뷰 대상이 아니다.
# Draft 기능을 쓰지 않고 제목으로 표시하는 습관을 흔히 쓰기 때문에 함께 본다.
# 영문 키워드는 뒤에 알파벳이 오면 매치하지 않는다. 그렇지 않으면 "drafting ..." 같은
# 멀쩡한 제목이 걸린다. 대괄호를 감싼 패턴은 따옴표로 글롭 문자 클래스 해석을 막는다.
lower=$(printf '%s' "$title" | tr '[:upper:]' '[:lower:]')
case "$lower" in
  wip|draft|"do not merge"|"do-not-merge"|작업중|초안|보류) block "제목이 작업 중임을 나타냅니다: ${title}" ;;
  wip[!a-z]*|draft[!a-z]*|"do not merge"[!a-z]*|"do-not-merge"[!a-z]*) block "제목이 작업 중임을 나타냅니다: ${title}" ;;
  "[wip]"*|"(wip)"*|"[draft]"*|"[작업중]"*) block "제목이 작업 중임을 나타냅니다: ${title}" ;;
  작업중*|초안*|보류*) block "제목이 작업 중임을 나타냅니다: ${title}" ;;
esac

for l in $BLOCKING_LABELS; do
  case " ${labels} " in
    *" ${l} "*) block "'${l}' 라벨이 붙어 있습니다" ;;
  esac
done

{
  echo "ready=true"
  echo "reason="
} >>"$GITHUB_OUTPUT"
echo "자동화 진행 가능 (state=${state}, draft=${draft})"
