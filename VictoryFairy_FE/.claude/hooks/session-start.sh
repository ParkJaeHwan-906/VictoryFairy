#!/usr/bin/env bash
# SessionStart hook — FE 하네스 (에이전트 라우팅)
# 세션 시작 시 Claude에게 FE 작업을 어느 전문 에이전트로 넘길지 컨텍스트를 주입한다.
# additionalContext 본문만 고치면 동작을 바꿀 수 있다.
cat <<'EOF'
{"hookSpecificOutput":{"hookEventName":"SessionStart","additionalContext":"[FE 하네스 — 에이전트 라우팅]\nVictoryFairy_FE는 React + Vite + TypeScript 웹 앱이다. FE 작업은 계층(폴더 소유권)별 전문 에이전트로 나뉜다. 사용자의 요청을 처리할 때, 해당 계층의 에이전트(Agent 도구, agentType=<이름>)로 위임하는 것을 우선 고려한다:\n- react-agent: src/components·src/pages — 컴포넌트/페이지 구조·로직, 라우팅, 커스텀 훅, 접근성.\n- css-agent: src/styles — 레이아웃/스타일링(웹·모바일). 시각적 속성 전담.\n- api-agent: src/api·src/types — Axios 클라이언트/엔드포인트 함수/응답 타입/에러 핸들링, 백엔드 계약 정합성.\n- store-agent: src/stores — Zustand 전역 상태 설계·selector 최적화·persist.\n한 요청이 여러 계층에 걸치면 각 에이전트에 나눠 맡기고 hand-off 지점(어떤 함수/스토어/클래스명을 주고받는지)을 명확히 한다. 계층 경계가 모호하면 추측하지 말고 확인한다.\n주의: react-native-agent(모바일)는 추후 구조 변경 예정이므로 지시가 없으면 수정·생성하지 않는다.\n\n[작업 후 검증]\n컴포넌트/상태/API/스타일 변경을 완료하면, 변경 규모에 맞게 fe-verifier 에이전트(agentType=fe-verifier)를 호출해 검증한다. '무엇을 바꿨는지'를 전달하면 타입체크→린트→정적 대조→빌드/dev 스모크로 확인한다. 사용자가 /verify 로 직접 호출할 수도 있다. 단순 질문·읽기·사소한 변경에는 생략 가능."}}
EOF
