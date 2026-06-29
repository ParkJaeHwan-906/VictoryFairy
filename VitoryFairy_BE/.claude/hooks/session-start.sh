#!/usr/bin/env bash
# SessionStart hook — 모듈 선택 하니스
# 세션 시작 시 Claude에게 "작업할 모듈"을 먼저 묻도록 컨텍스트를 주입한다.
# additionalContext 본문만 고치면 동작을 바꿀 수 있다.
cat <<'EOF'
{"hookSpecificOutput":{"hookEventName":"SessionStart","additionalContext":"[모듈 선택 하니스]\n이 백엔드 작업은 user · quiz · create · infra 4개 컨텍스트로 나뉜다(앞 3개는 Gradle 모듈, infra는 배포·인프라). 사용자의 첫 요청을 처리하기 전에 다음을 따른다:\n1. 사용자의 메시지에서 작업 대상이 이미 명확하면(예: quiz 기능 요청, 또는 배포/EC2/도커 관련이면 infra) 묻지 말고 그 컨텍스트로 진행한다.\n2. 불명확하면 AskUserQuestion으로 무엇을 작업할지 묻는다 (선택지: user / quiz / create / infra).\n3. 정해지면 Read 로 .claude/modules/<선택>.md 를 읽고 그 안의 지침/맥락을 우선 따른다. 파일이 비어 있으면 사용자에게 알리고 디렉터리 구조를 직접 탐색한다.\n공통 코드는 common·domain 모듈에 있을 수 있으니 필요 시 함께 참고한다."}}
EOF
