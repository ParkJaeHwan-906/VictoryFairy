#!/usr/bin/env bash
#
# 열린 PR 을 훑어 충돌 여부를 확인하고 이슈를 열거나 닫는다.
#
# pr-guard.yml 이 못 잡는 사각지대를 메운다. PR 에 충돌이 있으면 GitHub 은 merge ref 를
# 만들지 못하고 pull_request 워크플로를 아예 시작하지 않는다. 그래서 충돌 PR 은
# pr-guard 의 충돌 검사에 도달조차 못 하고, 자동화가 이슈도 남기지 못한 채 조용히 멈춘다.
# 이 스크립트는 PR 이벤트와 무관하게 주기적으로 돌아 그 상태를 드러낸다.
#
# 환경변수: GH_TOKEN, REPO(owner/name), RUN_URL(선택)

set -euo pipefail

REPO="${REPO:?owner/repo}"
RUN_URL="${RUN_URL:-}"
SCRIPTS=$(cd "$(dirname "$0")" && pwd)

# mergeable 은 GitHub 이 비동기로 계산한다. 첫 조회에서 UNKNOWN 이면 잠시 뒤 다시 묻는다.
mergeable_of() {
  local n="$1" m
  m=$(gh pr view "$n" --repo "$REPO" --json mergeable --jq .mergeable)
  if [ "$m" = "UNKNOWN" ]; then
    sleep 8
    m=$(gh pr view "$n" --repo "$REPO" --json mergeable --jq .mergeable)
  fi
  printf '%s' "$m"
}

numbers=$(gh pr list --repo "$REPO" --state open --json number --jq '.[].number')
if [ -z "$numbers" ]; then
  echo "열린 PR 없음"
  exit 0
fi

for n in $numbers; do
  data=$(gh pr view "$n" --repo "$REPO" --json isDraft,headRefName,baseRefName,headRefOid,author \
    --jq '[(.isDraft|tostring), .headRefName, .baseRefName, .headRefOid, .author.login] | @tsv')
  IFS=$'\t' read -r draft head_ref base_ref head_sha author <<<"$data"

  # 자동화 대상이 아닌 PR 은 건너뛴다. 판정 기준은 pr-ready.sh 와 같아야 한다.
  if [ "$draft" != "false" ]; then
    echo "#${n} 초안 — 건너뜀"
    continue
  fi
  case "$base_ref" in
    main | dev_*) ;;
    *) echo "#${n} base=${base_ref} — 대상 아님"; continue ;;
  esac

  m=$(mergeable_of "$n")
  echo "#${n} ${head_ref} → ${base_ref} mergeable=${m}"

  if [ "$m" = "CONFLICTING" ]; then
    if bash "${SCRIPTS}/conflict-report.sh" "$base_ref" "$head_sha" "watch-report-${n}.md"; then
      REPO="$REPO" bash "${SCRIPTS}/conflict-issue.sh" open "$n" "$head_ref" "$base_ref" \
        "$head_sha" "$author" "watch-report-${n}.md" "$RUN_URL" counterparts.txt
    else
      echo "::warning::#${n} 충돌 리포트 생성 실패"
    fi
  elif [ "$m" = "MERGEABLE" ]; then
    REPO="$REPO" bash "${SCRIPTS}/conflict-issue.sh" close "$n" "$head_ref" "$base_ref"
  fi
done
