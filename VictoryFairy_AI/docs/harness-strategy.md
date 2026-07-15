# 하네스 전략 (컨텍스트 격리)

## 목적

Claude를 이 프로젝트에서 호출할 때 **작업 모듈 단위로 컨텍스트를 격리**한다.
전체 레포를 한꺼번에 로드하지 않고, **선택된 모듈의 문서·코드만** 컨텍스트에 올려
① 다른 모듈 세부사항의 간섭을 줄이고 ② 토큰을 절약하며 ③ 변경 범위를 모듈 경계 안에 유지한다.

## 메커니즘

```
세션 시작
  └─ [SessionStart Hook] 모듈 선택 규칙을 컨텍스트로 주입
       └─ Claude 가 AskUserQuestion 으로 "어느 모듈?" 질문 (validation/analysis/pipeline)
            └─ 선택된 모듈의 docs/modules/<module>.md 하나만 로드
                 └─ 그 모듈 범위 안에서 작업
```

- **Hook은 대화형 메뉴를 직접 띄우지 못한다.** 그래서 Hook은 "모듈을 물어보라"는 **지시(context)를 주입**하고, 실제 질문은 Claude가 `AskUserQuestion`으로 수행한다.
- 사용자가 이미 특정 파일/모듈을 지목했거나 단순 질문이면 이 절차를 **생략**한다(불필요한 마찰 방지).

## 구성 파일

| 파일 | 역할 |
|---|---|
| `.claude/settings.json` | `SessionStart` Hook 정의(팀 공유용, 커밋 대상) |
| `.claude/harness/module-select.md` | Hook이 주입하는 모듈 선택 규칙 원문 |
| `docs/modules/<module>.md` | 모듈별 컨텍스트(선택 후 로드) |
| `.claude/settings.local.json` | 개인 권한(allow) — 하네스와 분리 |

## 컨텍스트 격리 원칙 (기능 단위)

1. **모듈 경계 우선** — 작업은 선택한 모듈(`validation`/`analysis`/`pipeline`) 안에서 완결한다.
2. **기능 단위 분리** — 각 모듈 문서는 기능 단위(예: analysis의 형태소 / NER / 집계)로 명확히 나뉜다. 한 기능을 고칠 때 다른 기능 문서를 끌어오지 않는다.
3. **교차 참조는 명시적으로만** — 모듈·기능 경계를 넘어야 할 때(예: analysis가 validation의 통과 문장을 입력받음)는 그 의존을 문서에 명시하고 최소로 참조한다.
4. **공유 규약은 architecture.md** — 앱 공통 레이어 구조·데이터 흐름 같은 전역 규약만 상위 문서에 둔다.

## Hook 관리

- 검토·수정·비활성화: 세션에서 `/hooks` 메뉴로 확인.
- Hook이 반영되지 않으면 `/hooks`를 한 번 열거나 세션을 재시작하면 설정이 리로드된다.
