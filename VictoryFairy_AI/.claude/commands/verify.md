---
description: 현재(또는 인자로 지정한) 모듈의 최근 변경을 검증 (코드=module-verifier / 컨테이너=docker-runner)
argument-hint: "[validation|analysis|pipeline|docker] (생략 시 현재 작업 모듈/diff로 추정)"
---

방금 작업한 것이 의도대로 동작하는지 검증 에이전트를 실행하라. 검증자는 둘로 나뉘어 있으니 **대상에 맞는 쪽을 고를 것.**

## 대상 판정
인자: `$ARGUMENTS` (비어 있으면 현재 세션에서 작업 중이던 모듈, 그래도 불명확하면 `git diff --name-only HEAD~1`로 추정)

| 대상 | 에이전트 |
|---|---|
| `validation` · `analysis` | **module-verifier** — import → 테스트 → 라우트 대조 → uvicorn 기동 후 curl |
| `pipeline` | **module-verifier** — import → 러너 실행 → 산출물 확인 |
| `docker` (Dockerfile·compose) | **docker-runner** — config → 빌드 → 컨테이너 기동 → 호출 → 정리 |

- 변경이 **양쪽에 걸쳐 있으면**(예: 라우트 추가 + Dockerfile 수정) **둘 다** 실행한다. 서로 독립이므로 한 메시지에서 병렬로 띄울 것.
- 불명확하면 `git diff --name-only`로 판단: `validation/**`·`analysis/**`·`pipeline/**` → 코드, `*/Dockerfile`·`docker-compose.yml` → docker.

## 전달할 것
- **무엇을 바꿨는지**(파일/엔드포인트/기대값). 이게 없으면 검증자가 무엇을 확인해야 할지 모른다.
- 코드면 `module=<validation|analysis|pipeline>`.

## 보고
- 에이전트가 돌려준 PASS/FAIL/SKIP 보고서를 그대로 요약 보고하고, FAIL이면 원인과 후속 조치를 제시한다.
- **SKIP을 PASS로 뭉뚱그리지 말 것.** 이 프로젝트는 SKIP이 흔하다 — docker 심링크·데몬·샌드박스, analysis 모델 다운로드(네트워크), 파이프라인 입력 파일 부재. 그 경우 원인과 해결 방법을 그대로 전달한다.
- 검증자는 코드를 고치지 않는다(Write/Edit 도구가 없다). 문제가 나오면 담당 에이전트에 넘길지 사용자에게 물어라.
