#!/usr/bin/env bash
#
# 충돌 이슈를 열거나 닫는다. PR 당 이슈 하나만 유지한다.
#
# pr-guard.yml(PR 이벤트 경로)과 conflict-watch.yml(주기 스캔 경로)이 함께 쓴다.
# 두 곳에서 같은 제목 규칙을 써야 이슈가 중복 생성되지 않으므로 여기 한 곳에 모았다.
#
# 사용법:
#   conflict-issue.sh open  <pr> <head_ref> <base_ref> <head_sha> <author> <report.md> [run_url] [counterparts.txt]
#   conflict-issue.sh close <pr> <head_ref> <base_ref>
#
# 환경변수: GH_TOKEN, REPO(owner/name)

set -euo pipefail

ACTION="${1:?open|close}"
PR="${2:?pr number}"
HEAD_REF="${3:?head ref}"
BASE_REF="${4:?base ref}"
REPO="${REPO:?owner/repo}"

export CONFLICT_ISSUE_TITLE="[충돌] PR #${PR}: ${HEAD_REF} → ${BASE_REF}"
TITLE="$CONFLICT_ISSUE_TITLE"

# 제목 완전 일치로 찾는다. --search 만으로는 부분 일치가 섞여 들어온다.
#
# 제목을 jq 프로그램 문자열에 끼워 넣지 않고 env 로 읽는다. 제목에는 브랜치 이름이
# 들어가는데, git ref 는 큰따옴표를 허용하므로 PR 작성자가 `foo"bar` 같은 이름으로
# jq 표현식을 깨고 임의 필터를 주입할 수 있다. 이 함수의 반환값은 워크플로 로그에
# 그대로 출력되므로, 공개 저장소에서는 환경변수 유출 경로가 된다.
find_issue() {
  gh issue list --repo "$REPO" --state open --label merge-conflict \
    --search "\"PR #${PR}:\" in:title" --json number,title \
    --jq '[.[] | select(.title == env.CONFLICT_ISSUE_TITLE)][0].number // empty'
}

if [ "$ACTION" = "close" ]; then
  gh pr edit "$PR" --repo "$REPO" --remove-label merge-conflict 2>/dev/null || true
  num=$(find_issue)
  if [ -n "$num" ]; then
    gh issue close "$num" --repo "$REPO" \
      --comment "PR #${PR} 의 충돌이 해소되어 자동으로 닫습니다."
    # 호출자가 "방금 해소됐다" 를 구분할 수 있도록 표시한다.
    # 충돌이 풀려도 base 갱신만으로는 pull_request 이벤트가 발생하지 않아
    # 재검사를 따로 걸어야 하는데, 매번 거는 대신 이 전이 시점에만 건다.
    echo "CLOSED_ISSUE=${num}"
    echo "이슈 #${num} 닫음"
  fi
  exit 0
fi

HEAD_SHA="${5:?head sha}"
AUTHOR="${6:?pr author}"
REPORT="${7:?report file}"
RUN_URL="${8:-}"
COUNTERPARTS="${9:-counterparts.txt}"

# 충돌 상대편 커밋 작성자도 부른다. 해결에 합의가 필요한 경우가 많다.
mentions="@${AUTHOR}"
if [ -f "$COUNTERPARTS" ]; then
  for login in $(cat "$COUNTERPARTS"); do
    [ "$login" = "$AUTHOR" ] && continue
    mentions="${mentions} @${login}"
  done
fi

body=$(mktemp)
{
  echo "${mentions} 자동 머지를 진행할 수 없습니다. 검토가 필요합니다."
  echo
  echo "- 대상 PR: #${PR} (\`${HEAD_REF}\` → \`${BASE_REF}\`)"
  echo "- 감지 커밋: \`${HEAD_SHA}\`"
  [ -n "$RUN_URL" ] && echo "- 워크플로 로그: ${RUN_URL}"
  echo
  cat "$REPORT"
  echo
  echo "---"
  echo
  echo "### 해결 방법"
  echo
  echo '```bash'
  echo "git fetch origin"
  echo "git switch ${HEAD_REF}"
  echo "git merge origin/${BASE_REF}   # 충돌 해결 후 커밋"
  echo "git push"
  echo '```'
  echo
  echo "해결 후 다시 푸시하면 자동화가 충돌을 재검사하고, 해소되면 이 이슈를 닫습니다."
} >"$body"

num=$(find_issue)
if [ -n "$num" ]; then
  gh issue comment "$num" --repo "$REPO" --body-file "$body"
  echo "기존 이슈 #${num} 갱신"
else
  num=$(gh issue create --repo "$REPO" --title "$TITLE" --body-file "$body" \
    --label merge-conflict --assignee "$AUTHOR" | grep -oE '[0-9]+$')
  echo "이슈 #${num} 생성"
  gh pr comment "$PR" --repo "$REPO" \
    --body "머지 충돌로 자동화를 중단했습니다. 상세 내역은 #${num} 을 확인해 주세요."
fi

gh pr edit "$PR" --repo "$REPO" --add-label merge-conflict
rm -f "$body"
