#!/usr/bin/env bash
#
# 열린 PR 을 훑어 충돌 여부를 확인하고 이슈를 열거나 닫는다.
#
# pr-guard.yml 이 못 잡는 사각지대를 메운다. PR 에 충돌이 있으면 GitHub 은 merge ref 를
# 만들지 못하고 pull_request 워크플로를 아예 시작하지 않는다. 그래서 충돌 PR 은
# pr-guard 의 충돌 검사에 도달조차 못 하고, 자동화가 이슈도 남기지 못한 채 조용히 멈춘다.
# 이 스크립트는 PR 이벤트와 무관하게 주기적으로 돌아 그 상태를 드러낸다.
#
# set -e 를 쓰지 않는다. PR 하나에서 API 가 실패하면 나머지 PR 스캔이 통째로
# 중단되기 때문이다(실제로 GraphQL 일시 오류로 그런 적이 있다). 대신 PR 마다
# 실패를 삼키고 계속 진행한 뒤, 마지막에 실패가 있었으면 실행 자체를 실패로 끝낸다.
#
# 환경변수: GH_TOKEN, REPO(owner/name), RUN_URL(선택)

set -uo pipefail

REPO="${REPO:?owner/repo}"
RUN_URL="${RUN_URL:-}"
SCRIPTS=$(cd "$(dirname "$0")" && pwd)

FAILED=0

warn() {
  echo "::warning::$1"
  FAILED=1
}

# mergeable 은 GitHub 이 비동기로 계산한다. 첫 조회에서 UNKNOWN 이면 잠시 뒤 다시 묻는다.
mergeable_of() {
  local n="$1" m
  m=$(gh pr view "$n" --repo "$REPO" --json mergeable --jq .mergeable 2>/dev/null) || return 1
  if [ "$m" = "UNKNOWN" ]; then
    sleep 8
    m=$(gh pr view "$n" --repo "$REPO" --json mergeable --jq .mergeable 2>/dev/null) || return 1
  fi
  printf '%s' "$m"
}

# 충돌이 풀려도 base 갱신만으로는 pull_request 이벤트가 발생하지 않는다. head 가 바뀔 때만
# synchronize 가 뜬다. 그래서 충돌이 해소된 PR 은 리뷰도 머지도 되지 않고 멈춰 있게 된다.
# 닫았다 다시 열어 reopened 이벤트를 만들어 준다.
#
# close 는 성공했는데 reopen 이 실패하면 PR 이 닫힌 채로 남는다. 그 경우는 반드시 드러내야 한다.
nudge() {
  local n="$1"
  gh pr close "$n" --repo "$REPO" >/dev/null 2>&1 || { warn "#${n} 재검사용 close 실패"; return 1; }
  for _ in 1 2 3; do
    if gh pr reopen "$n" --repo "$REPO" >/dev/null 2>&1; then
      echo "#${n} 재검사 트리거 (close/reopen)"
      return 0
    fi
    sleep 5
  done
  echo "::error::#${n} 을 다시 열지 못했습니다. PR 이 닫힌 상태입니다 — 수동으로 열어 주세요."
  FAILED=1
  return 1
}

scan_pr() {
  local n="$1"
  local data draft head_ref base_ref head_sha author m out

  data=$(gh pr view "$n" --repo "$REPO" --json isDraft,headRefName,baseRefName,headRefOid,author \
    --jq '[(.isDraft|tostring), .headRefName, .baseRefName, .headRefOid, .author.login] | @tsv' 2>/dev/null) \
    || { warn "#${n} 정보 조회 실패"; return 0; }
  IFS=$'\t' read -r draft head_ref base_ref head_sha author <<<"$data"

  # 자동화 대상이 아닌 PR 은 건너뛴다. 판정 기준은 pr-ready.sh 와 같아야 한다.
  if [ "$draft" != "false" ]; then
    echo "#${n} 초안 — 건너뜀"
    return 0
  fi
  case "$base_ref" in
    main | dev_*) ;;
    *) echo "#${n} base=${base_ref} — 대상 아님"; return 0 ;;
  esac

  m=$(mergeable_of "$n") || { warn "#${n} mergeable 조회 실패"; return 0; }
  echo "#${n} ${head_ref} → ${base_ref} mergeable=${m}"

  if [ "$m" = "CONFLICTING" ]; then
    if ! bash "${SCRIPTS}/conflict-report.sh" "$base_ref" "$head_sha" "watch-report-${n}.md"; then
      warn "#${n} 충돌 리포트 생성 실패"
      return 0
    fi
    REPO="$REPO" bash "${SCRIPTS}/conflict-issue.sh" open "$n" "$head_ref" "$base_ref" \
      "$head_sha" "$author" "watch-report-${n}.md" "$RUN_URL" counterparts.txt \
      || warn "#${n} 충돌 이슈 생성/갱신 실패"
    return 0
  fi

  if [ "$m" = "MERGEABLE" ]; then
    out=$(REPO="$REPO" bash "${SCRIPTS}/conflict-issue.sh" close "$n" "$head_ref" "$base_ref" 2>&1) \
      || { warn "#${n} 충돌 이슈 정리 실패"; echo "$out"; return 0; }
    echo "$out"
    # 방금 충돌이 해소된 경우에만 재검사를 건다. 매 스캔마다 걸면 PR 이 계속 재실행된다.
    case "$out" in
      *CLOSED_ISSUE=*) nudge "$n" ;;
    esac
  fi
  return 0
}

numbers=$(gh pr list --repo "$REPO" --state open --json number --jq '.[].number' 2>/dev/null) || {
  echo "::error::열린 PR 목록을 가져오지 못했습니다."
  exit 1
}
if [ -z "$numbers" ]; then
  echo "열린 PR 없음"
  exit 0
fi

for n in $numbers; do
  scan_pr "$n"
done

exit "$FAILED"
