---
description: 현재(또는 인자로 지정한) 모듈의 최근 변경을 module-verifier 에이전트로 검증
argument-hint: "[user|quiz|create|infra] (생략 시 현재 작업 모듈/diff로 추정)"
---

`module-verifier` 에이전트를 실행해, 방금 작업한 모듈의 변경이 의도대로 동작하는지 검증하라.

- 인자: `$ARGUMENTS` (모듈명; 비어 있으면 현재 세션에서 작업 중이던 모듈, 그래도 불명확하면 `git diff --name-only HEAD~1`로 추정)
- 검증 대상이 무엇을 바꿨는지(파일/엔드포인트/기대값)도 함께 에이전트에 전달하라.
- 에이전트가 돌려준 PASS/FAIL 보고서를 그대로 사용자에게 요약 보고하고, FAIL이면 원인과 후속 조치를 제시하라.
