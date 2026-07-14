#!/usr/bin/env bash
# SessionStart hook — FE 하네스 (뼈대)
# 세션 시작 시 Claude에 주입할 컨텍스트를 여기서 정의한다.
# additionalContext 본문만 채우면 동작을 바꿀 수 있다. (현재는 자리표시자)
cat <<'EOF'
{"hookSpecificOutput":{"hookEventName":"SessionStart","additionalContext":"[FE 하네스 — TODO] 세션 시작 컨텍스트를 여기에 작성한다."}}
EOF
