# 하네스 전략 (컨텍스트 격리 + 역할 분할)

## 목적

축이 두 개다.

1. **컨텍스트 격리** — 전체 레포를 한꺼번에 로드하지 않고 **선택된 모듈의 문서·코드만** 올려 ① 다른 모듈 간섭을 줄이고 ② 토큰을 절약하며 ③ 변경 범위를 모듈 경계 안에 유지한다.
2. **역할 분할** — 메인 에이전트는 오케스트레이터만 하고, 실제 작업은 **역할별 서브에이전트**에 위임한다. 각 에이전트가 자기 영역 지침만 들고 도니 메인 컨텍스트가 깨끗하게 유지된다.

## 메커니즘

```
세션 시작
  └─ [SessionStart Hook] 모듈 선택 + 에이전트 분배 규칙을 컨텍스트로 주입
       └─ Claude 가 AskUserQuestion 으로 "어느 모듈?" 질문 (validation/analysis/pipeline)
            └─ 선택된 모듈의 docs/modules/<module>.md 하나만 로드
                 └─ 메인이 작업 유형별로 서브에이전트에 위임
                      코드   fastapi-dev / pipeline-dev → test-writer → module-verifier
                                                        → api-documenter → context-keeper
                      정확도 accuracy-tuner → dict-curator → test-writer → context-keeper
                      인프라 dockerfile-manager / compose-manager → docker-runner
```

- **Hook은 대화형 메뉴를 직접 띄우지 못한다.** 그래서 Hook은 "모듈을 물어보라"는 **지시(context)를 주입**하고, 실제 질문은 Claude가 `AskUserQuestion`으로 수행한다.
- 사용자가 이미 특정 파일/모듈을 지목했거나 단순 질문이면 이 절차를 **생략**한다(불필요한 마찰 방지).

## 에이전트 구성 (14개)

메인 에이전트는 **직접 작업하지 않고 위임**한다(단순 질문·읽기·한 줄 수정은 예외).

### 코드

| 에이전트 | 역할 | 수정 범위 |
|---|---|:---:|
| `fastapi-dev` | validation·analysis 의 FastAPI 기능 구현 | 코드 |
| `pipeline-dev` | 배치 러너 — 파일 입출력 배선 | 코드 |
| `dict-curator` | 사전 8개 JSON — 값 추가·제거·형식 | 사전만 |
| `accuracy-tuner` | 오탐/미탐 측정·분석, 뷰 전략, gazetteer 후처리 | 코드 |
| `perf-optimizer` | 모델 로딩·async 블로킹·torch 메모리·배치 | 코드 |
| `test-writer` | 테스트 코드 (pytest 없이 stdlib) | 코드 |
| `test-data` | 테스트 문장 세트 | 데이터 |
| `module-verifier` | uvicorn 기동→호출 / 러너 실행→산출물 | ❌ 읽기전용 |
| `api-documenter` | `docs/api/<module>.md` 명세 | 문서만 |
| `code-commenter` | 로직 의도('왜') 주석·docstring | 주석만 |

### 인프라

| 에이전트 | 역할 | 수정 범위 |
|---|---|:---:|
| `dockerfile-manager` | 3개 Dockerfile | 코드 |
| `compose-manager` | docker-compose.yml | 코드 |
| `docker-runner` | 빌드·기동·검증 후 정리 | ❌ 읽기전용 |

### 공통

| 에이전트 | 역할 | 수정 범위 |
|---|---|:---:|
| `context-keeper` | `docs/modules/*.md` 를 코드와 일치하게 유지 | 문서만 |

**검증자 둘(`module-verifier`·`docker-runner`)은 `Write`/`Edit` 도구가 없어 구조적으로 코드를 못 고친다** — 검증자가 자기가 검증할 대상을 고치는 이해충돌을 도구 수준에서 막았다.

## 경계 설계 (중복 방지)

에이전트를 나눌 때 가장 위험한 건 **역할이 겹쳐 서로의 작업을 덮어쓰는 것**이다. 다음 경계를 각 정의에 명시했다.

- **`accuracy-tuner` ↔ `dict-curator`** — 무엇을 넣을지 **판단**하는 건 전자, 사전에 정확히 **넣는 실행**은 후자. 이 프로젝트는 "사전만 고치면 코드 변경 없이 반영"되는 구조라 사전 작업이 잦고 단순해서, 판단과 실행을 나누는 게 값어치가 있다.
- **`accuracy-tuner` ↔ `perf-optimizer`** — **판단 기준은 "출력이 같은가?"** 같으면 성능(후자), 한 글자라도 달라지면 정확도(전자). 이 프로젝트의 최적화는 대부분 정확도 트레이드오프라 이 선이 중요하다.
- **`fastapi-dev` ↔ `pipeline-dev`** — pipeline은 FastAPI가 아니라 파일 I/O 배치다. 그리고 **pipeline은 로직을 갖지 않는다** — 서비스를 import해 쓸 뿐이라 로직 문제는 각 모듈로 넘긴다.
- **Docker 3분할** — "무엇을 빌드하나(`dockerfile-manager`) / 어떻게 함께 뜨나(`compose-manager`) / 실제로 되나(`docker-runner`)". `analysis`의 torch 설치가 매우 느려 "쓰는 일"과 "돌리는 일"의 성격이 크게 다르다.
- **동시 실행 주의** — 같은 파일을 고치는 에이전트를 병렬로 띄우면 충돌한다. 파일이 겹치면 순차로.

## 진실의 출처 (에이전트 ↔ 모듈 문서)

**서브에이전트는 메인 에이전트의 컨텍스트를 물려받지 않는다.** 각자 자기 정의 + 메인이 써준 프롬프트만 들고 새로 시작한다.

이걸 "에이전트 정의에 프로젝트 사실을 복사해 넣기"로 때우면 **진실의 출처가 둘로 갈리고**, `context-keeper`가 모듈 문서를 갱신해도 사본은 낡아간다 — 하네스가 막으려던 문제가 재발한다. 그래서:

| 파일 | 담는 것 | 유지 주체 |
|---|---|---|
| `docs/modules/<module>.md` | **모듈 사실** — 진입점·라우트·기능 단위·한계 | `context-keeper` (자동) |
| `.claude/agents/<agent>.md` | **역할 지침** — 어떻게 일하는가 | 사람 (드물게) |

에이전트 전부 "작업 전 `docs/modules/<module>.md`를 먼저 Read하라"는 지시를 갖는다(`context-keeper`만 예외 — 모듈 문서가 작업 *대상*이라 절차 안에서 읽는다). 메인은 프롬프트에 **"어느 모듈 + 무엇을/왜"만** 준다.

역할에 따라 문서를 쓰는 방식이 다르다:
- `code-commenter` — **"한계" 섹션이 곧 주석 소재**
- `test-writer` — 같은 섹션이 곧 **테스트 케이스 목록**
- `accuracy-tuner` — **이미 기각된 시도**를 알아야 되돌아가지 않는다
- **검증자 2개는 반대다** — "문서를 정답으로 삼지 마라". 문서와 코드가 어긋나면 그게 **발견 사항**이다.

## 최적화 주석 규칙

정확도·성능 조정은 **눈에 안 보이는 변경**이다(패턴 한 줄, `async` 하나). 리포트만으론 나중에 확인이 안 되고, 다음 사람이 이유를 모른 채 되돌린다. 그래서 `accuracy-tuner`·`perf-optimizer`는 **고친 자리마다 문제·개선·결과를 주석으로** 남긴다.

단 **"옛날엔 이랬다"는 이력이 아니라 "이 코드가 왜 이렇게 생겼는가"로 재진술**한다 — 이력은 git이 하고, 주석은 코드가 살아있는 한 유효해야 한다. 측정 수치는 근거로 붙이되 **측정 안 했으면 "(미측정)"을 명시**한다. `code-commenter`의 "이력 주석 금지" 규칙과 충돌하므로, **최적화 주석은 이력이 아니라 "제거하면 안 되는 이유"이므로 예외**임을 명시해 조율했다.

## 구성 파일

| 파일 | 역할 |
|---|---|
| `.claude/settings.json` | `SessionStart` Hook 정의(팀 공유용, 커밋 대상) |
| `.claude/harness/module-select.md` | Hook이 주입하는 모듈 선택 + 에이전트 분배 규칙 원문 |
| `.claude/agents/*.md` | 역할별 서브에이전트 14개 — 코드 10 · 인프라 3 · 공통 1 |
| `.claude/commands/verify.md` | 검증을 수동 호출하는 `/verify` 슬래시 커맨드 |
| `docs/modules/<module>.md` | 모듈별 컨텍스트(선택 후 로드) — **모듈 사실의 유일한 출처** |
| `.claude/settings.local.json` | 개인 권한(allow) — 하네스와 분리 |

## 컨텍스트 격리 원칙 (기능 단위)

1. **모듈 경계 우선** — 작업은 선택한 모듈(`validation`/`analysis`/`pipeline`) 안에서 완결한다.
2. **기능 단위 분리** — 각 모듈 문서는 기능 단위(예: analysis의 형태소 / NER / 집계)로 명확히 나뉜다. 한 기능을 고칠 때 다른 기능 문서를 끌어오지 않는다.
3. **교차 참조는 명시적으로만** — 모듈·기능 경계를 넘어야 할 때(예: analysis가 validation의 통과 문장을 입력받음)는 그 의존을 문서에 명시하고 최소로 참조한다.
4. **공유 규약은 architecture.md** — 앱 공통 레이어 구조·데이터 흐름 같은 전역 규약만 상위 문서에 둔다.

## Hook 관리

- 검토·수정·비활성화: 세션에서 `/hooks` 메뉴로 확인.
- Hook이 반영되지 않으면 `/hooks`를 한 번 열거나 세션을 재시작하면 설정이 리로드된다.
- 주입 내용을 바꾸려면 `.claude/harness/module-select.md` 만 고치면 된다(Hook이 그 파일을 통째로 읽어 넣는다).
- **Hook이 `jq` 에 의존한다.** (2026-07-15 실측: jq-1.7.1 설치됨, 3,542자 정상 주입 확인.)

### ⚠️ 고쳐진 함정: 훅이 "조용히 성공한 척"하던 문제

원래 명령은 `jq -Rs '...' .claude/harness/module-select.md 2>/dev/null || true` 였다. 여기엔 **디버깅이 거의 불가능한 실패 모드**가 있었다:

- `jq` 는 파일을 못 찾으면 stderr 로 에러를 내고 **exit 2** 하면서도, **stdout 에는 유효한 JSON 을 그대로 뱉는다** — `{"hookSpecificOutput":{...,"additionalContext":""}}`.
- 여기에 `2>/dev/null` 이 에러를 지우고 `|| true` 가 종료코드를 0으로 세탁한다.
- → Claude Code 는 **exit 0 + 파싱 가능한 JSON + 빈 컨텍스트**를 받는다. **훅은 성공으로 기록되고, 아무것도 주입되지 않으며, 어디에도 에러가 뜨지 않는다.**
- 상대경로였으므로 **프로젝트 루트가 아닌 곳에서 `claude` 를 띄우면 이 일이 그대로 일어났다.** "아무 일도 안 일어남"과 "성공했다고 보고함"은 디버깅 난이도가 다르다.

**수정**: 경로를 `"$CLAUDE_PROJECT_DIR/.claude/harness/module-select.md"` 로 절대화하고 `2>/dev/null || true` 를 **제거**했다. 이제 실패하면 exit 2 + stderr 로 드러난다(실측 확인).

## 확장 방법

- **새 모듈**: `docs/modules/<name>.md` 추가 → `.claude/harness/module-select.md` 의 선택지·분배 목록에 추가 → `CLAUDE.md` 모듈 목록 → 위 구성 파일 표. (`context-keeper` 가 대신 할 수 있다.)
- **새 에이전트**: `.claude/agents/<name>.md` 추가 → `module-select.md` 의 분배 목록에 한 줄 → 위 에이전트 표.
  - frontmatter: `name`(파일명과 일치), `description`(**메인이 이걸 보고 위임을 결정하므로 "언제 쓰는지"를 명확히**), `tools`, `model`.
  - 본문에 **기존 에이전트와의 경계**를 반드시 명시할 것. 안 그러면 역할이 겹쳐 서로 덮어쓴다.
  - 읽기 전용이어야 하면 `tools` 에서 `Write`/`Edit` 를 빼서 강제한다.

## 이력 / 결정

- **2026-07-15 — 에이전트 0개에서 역할별 14개 체제로 전환** (`hwannee/ai/create-agent`). BE(`VitoryFairy_BE`)에 먼저 적용한 전략을 이 프로젝트에 맞춰 옮겼다.
  - **그대로 옮기지 않은 것들** (BE와 스택·도메인이 달라서):
    - BE의 `jpa-query-tuner` 는 **버렸다** — AI엔 DB·SQL·JPA가 없다. 대신 이 프로젝트의 진짜 관심사인 **오탐/재현율**을 맡는 `accuracy-tuner` 와, 동작을 좌우하는 데이터인 사전을 맡는 `dict-curator` 를 만들었다.
    - BE의 `nginx-proxy`·`github-actions` 는 **만들지 않았다** — AI엔 nginx가 없고 `deploy.yml` 은 BE만 다룬다. **없는 일을 위한 에이전트는 하네스가 막으려는 비대함 자체**다.
    - `spring-dev` 는 `fastapi-dev` + `pipeline-dev` 로 **쪼갰다** — pipeline은 FastAPI가 아니라 파일 I/O 배치라 한 에이전트로 묶으면 이름과 지침이 안 맞는다.
  - **구조는 기존 것을 유지했다** — 모듈 문서는 `docs/modules/`, Hook은 jq 방식 그대로. "같은 전략"은 에이전트 분할이지 파일 배치가 아니다.
  - 미해결: **AI를 배포하는 CI가 없다**(BE만 있음). 도입하면 `github-actions` 에이전트를 그때 만든다.
