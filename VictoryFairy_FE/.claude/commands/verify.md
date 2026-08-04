---
description: 방금 작업한 프론트엔드 변경을 fe-verifier 에이전트로 검증 (타입체크→린트→빌드→dev 스모크)
argument-hint: "[변경 요약: 파일/컴포넌트/엔드포인트/기대 동작] (생략 시 git diff로 추정)"
---

`fe-verifier` 에이전트(Agent 도구, agentType=fe-verifier)를 실행해, 방금 작업한 프론트엔드 변경이 의도대로 동작하는지 **증거 기반**으로 검증하라.

- 에이전트에 전달할 것: `$ARGUMENTS`(무엇을 바꿨는지 — 파일/컴포넌트/엔드포인트/기대 동작). 비어 있으면 현재 세션에서 작업하던 내용과 `git diff --name-only HEAD` / `git status`로 변경 범위를 추정해 함께 넘겨라.
- 에이전트는 코드를 수정하지 않고 타입체크→린트→정적 대조→빌드/dev 스모크 순으로 확인한다.
- 에이전트가 돌려준 PASS/FAIL/SKIP 보고서를 그대로 사용자에게 요약 보고하고, FAIL이면 원인과 후속 조치를 제시하라.
