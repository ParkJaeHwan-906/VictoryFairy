---
description: 현재(또는 인자로 지정한) 모듈의 최근 변경을 검증 (코드=module-verifier / 인프라=docker-runner)
argument-hint: "[user|quiz|create|infra] (생략 시 현재 작업 모듈/diff로 추정)"
---

방금 작업한 것이 의도대로 동작하는지 검증 에이전트를 실행하라. 검증자는 둘로 나뉘어 있으니 **대상에 맞는 쪽을 고를 것.**

## 대상 판정
인자: `$ARGUMENTS` (비어 있으면 현재 세션에서 작업 중이던 모듈, 그래도 불명확하면 `git diff --name-only HEAD~1`로 추정)

| 대상 | 에이전트 |
|---|---|
| `user` · `quiz` · `create` (코드 모듈) | **module-verifier** — gradle 컴파일 → 테스트 → 엔드포인트 대조 → bootRun 후 curl |
| `infra` (Dockerfile · compose · nginx · CI/CD) | **docker-runner** — compose config → 빌드 → 로컬 스택 기동 → health·라우팅 curl → 정리 |

- 변경이 **양쪽에 걸쳐 있으면**(예: 컨트롤러 추가 + nginx location 추가) **둘 다** 실행한다. 서로 독립이므로 한 메시지에서 병렬로 띄울 것.
- 어느 쪽인지 불명확하면 `git diff --name-only`로 판단한다: `*/src/**` → 코드, `Dockerfile`·`docker-compose*.yml`·`nginx.conf`·`.github/workflows/**` → 인프라.

## 전달할 것
- **무엇을 바꿨는지**(파일/엔드포인트/기대값). 이게 없으면 검증자가 무엇을 확인해야 할지 모른다.
- 코드 모듈이면 `module=<user|quiz|create>`.

## 보고
- 에이전트가 돌려준 PASS/FAIL/SKIP 보고서를 그대로 요약 보고하고, FAIL이면 원인과 후속 조치를 제시한다.
- **SKIP을 PASS로 뭉뚱그리지 말 것.** 특히 인프라 검증은 docker 심링크·데몬·샌드박스 때문에 SKIP이 날 수 있다 — 그 경우 원인과 해결 방법을 그대로 전달한다.
- 검증자는 코드를 고치지 않는다(Write/Edit 도구가 없다). 문제가 나오면 담당 에이전트에 넘길지 사용자에게 물어라.
