# 하네스(Harness) 전략

> 목적: **CLAUDE.md 비대화로 인한 중간 context 유지 어려움**을 막기 위해, 컨텍스트를 "항상 로드"에서 "필요할 때만 로드"로 전환한다.
> 최종 업데이트: 2026-07-16

## 핵심 아이디어

축이 세 개다.

1. **컨텍스트 분할** — 큰 단일 CLAUDE.md를 들고 다니는 대신, **작업 단위(모듈)별로 컨텍스트를 쪼개고** 세션 시작 시 작업할 모듈을 선택해 **그 파일 하나만 로드**한다.
2. **역할 분할** — 메인 에이전트는 오케스트레이터 역할만 하고, 실제 작업은 **역할별 서브에이전트**에 위임한다. 각 에이전트가 자기 영역의 지침만 들고 도니 메인 컨텍스트가 깨끗하게 유지된다.
3. **시점 분할** — 기능은 **요구사항(무엇이 참이어야 하나) → 구현(어떻게) → 검증(정말 그런가)** 순으로 간다. 앞의 둘을 섞으면 "무엇을 만들 것인가"가 코드로 굳은 뒤에야 드러난다.

```
세션 시작
  └─ SessionStart 훅 실행 → 모듈 선택 + 에이전트 분배 지침 주입
       └─ 사용자 첫 요청
            ├─ 모듈이 명확 → 바로 진행
            └─ 불명확 → AskUserQuestion(user/quiz/domain/web-support/infra)
                 └─ 선택된 .claude/modules/<선택>.md 만 Read → 슬림 컨텍스트 확보
                      └─ 메인 에이전트가 작업 유형별로 서브에이전트에 위임
                           코드   (새 기능이면) requirements-writer ⇄ 사용자 협의 → 승인
                                            → spring-dev → test-writer → module-verifier
                                            → (API면) api-documenter → context-keeper
                           인프라 dockerfile-manager / compose-manager
                                  / nginx-proxy / github-actions
                                            → docker-runner(검증) → context-keeper
```

요구사항 단계만 **⇄(왕복)**인 이유: 나머지는 에이전트가 답을 알지만, "무엇을 만들 것인가"는 **사용자만 안다.** 그래서 여기서만 메인이 중간에 서서 묻는다(서브에이전트는 사용자에게 질문할 수 없다).

## 에이전트 구성

메인 에이전트는 **직접 작업하지 않고 위임**한다(단순 질문·읽기·한 줄 수정은 예외).

### 코드 (user · quiz · domain · web-support)

| 에이전트 | 역할 | 수정 범위 | model |
|---|---|:---:|---|
| `requirements-writer` | 구현 **전** EARS 요구사항 정의 (`docs/requirements/<module>/<feature>.md`) | 문서만 | inherit |
| `spring-dev` | Java Spring 기능 구현 (컨트롤러·서비스·DTO·설정). domain이면 엔티티·리포지토리 | 코드 | inherit |
| `test-writer` | JUnit/MockMvc 테스트 코드 작성 | 코드 | sonnet |
| `test-data` | 목업·시드·픽스처 데이터 | 코드 | sonnet |
| `module-verifier` | gradle 컴파일→bootRun→엔드포인트 호출→응답 검증 (domain은 포트가 없어 컴파일·테스트까지만) | ❌ 읽기전용 | sonnet |
| `api-documenter` | `docs/api/<module>.md` 명세 생성·갱신 (domain은 엔드포인트가 없어 대상 아님) | 문서만 | sonnet |
| `spring-optimizer` | 트랜잭션 경계·open-in-view·커넥션풀·설정 | 코드 | inherit |
| `jpa-query-tuner` | SQL/JPA 쿼리·N+1·fetch join·인덱스·페이징 | 코드 | inherit |
| `code-commenter` | 로직 의도('왜') 주석·Javadoc | 주석만 | sonnet |

### 인프라 (infra)

| 에이전트 | 역할 | 수정 범위 | model |
|---|---|:---:|---|
| `dockerfile-manager` | `Dockerfile` — 멀티스테이지·레이어 캐시·이미지 크기 | 코드 | sonnet |
| `compose-manager` | `docker-compose.yml` / `.prod.yml` — 서비스·볼륨·`mem_limit` | 코드 | sonnet |
| `nginx-proxy` | `nginx.conf` — 경로 라우팅·프록시 헤더 | 코드 | sonnet |
| `github-actions` | `.github/workflows/deploy.yml` — CI/CD 전략 | 코드 | inherit |
| `docker-runner` | 실제 빌드·기동·health·라우팅 검증 후 정리 | ❌ 읽기전용 | sonnet |

### 공통

| 에이전트 | 역할 | 수정 범위 | model |
|---|---|:---:|---|
| `context-keeper` | 모듈 컨텍스트를 코드와 일치하게 유지 | `.claude/`만 | sonnet |
| `commit-writer` | 워킹 트리 변경을 의도 단위로 쪼개 커밋 (push 안 함) | ❌ git만 | inherit |

`commit-writer`는 **사용자가 커밋을 요청했을 때만** 호출한다. 작업이 끝났다고 자동으로 부르지 않는다 — 언제 무엇을 커밋할지는 사용자의 결정이다. `Write`/`Edit` 도구가 없어 커밋할 코드를 고칠 수 없고, push는 규칙으로 금지해 **되돌릴 수 없는 조작을 하지 않는다**.

**검증 담당이 둘로 나뉜다**: 코드는 `module-verifier`(gradle), 인프라는 `docker-runner`(컨테이너). 둘 다 `Write`/`Edit` 도구가 없어 **구조적으로 코드를 못 고친다** — 검증자가 자기가 검증할 대상을 고치는 이해충돌을 도구 수준에서 막았다.

### 경계 설계 (중복 방지)

에이전트를 나눌 때 가장 위험한 건 **역할이 겹쳐 서로의 작업을 덮어쓰는 것**이다. 다음 경계를 각 에이전트 정의에 명시해 두었다.

- **`requirements-writer` ↔ `api-documenter` ↔ `context-keeper`** — 셋 다 마크다운만 쓰는데, 갈라놓은 축은 **시점**이다. 요구사항은 구현 **전**의 *의도*(사용자가 승인한 계약), `docs/api/*.md`는 구현 **후**의 *사실*(실제 엔드포인트), `modules/*.md`는 *지금 코드*의 사실. **셋이 어긋나는 건 버그가 아니라 신호다** — 요구사항과 코드가 다르면 미구현이거나 계약 위반이고, 각 문서는 자기 시점의 진실을 그대로 쓰고 어긋남을 **보고**한다. 서로에 맞춰 고쳐 쓰면 세 문서가 동시에 거짓말을 하게 된다.
- **`requirements-writer` ↔ `spring-dev`** — "무엇이 참이어야 하는가"(요구사항) ↔ "어떻게 만드는가"(구현). 요구사항이 클래스·라이브러리를 못 박으면 계약이 아니라 설계 지시가 되어 구현자가 더 나은 길을 못 고른다. 반대로 구현자가 요구사항을 늘리거나 줄이면 사용자 승인이 무의미해진다.
- **`spring-optimizer` ↔ `jpa-query-tuner`** — 최적화를 둘로 나눈 기준은 "쿼리인가 아닌가"다. N+1·fetch join·인덱스·페이징은 전부 `jpa-query-tuner`, 트랜잭션 경계·open-in-view·풀·설정은 `spring-optimizer`. 서로의 영역을 발견하면 고치지 말고 **위임을 권고**한다.
- **`test-writer` ↔ `test-data` ↔ `module-verifier`** — 각각 테스트 *로직* / 테스트 *데이터* / *런타임* 검증.
- **Docker 3분할** — **"무엇을 빌드하나(`dockerfile-manager`) / 어떻게 함께 뜨나(`compose-manager`) / 실제로 되나(`docker-runner`)"**로 나눴다. 앞의 둘은 *쓰고*, 마지막은 *돌린다*. 빌드가 느려 검증이 오래 걸리므로, 작성자가 직접 풀 빌드를 돌리지 않고 `docker-runner`에 넘기는 구조다.
- **`compose-manager` ↔ `nginx-proxy`** — `nginx.conf`의 **내용**(라우팅 규칙)은 `nginx-proxy`, compose에서 그걸 **마운트하는 방식**은 `compose-manager`.
- **`dockerfile-manager` ↔ `github-actions`** — 이미지를 **어떻게 만드나**는 전자, CI에서 **언제·무엇을 빌드하나**(트리거·매트릭스·캐시 scope·태그)는 후자. 둘은 `ARG MODULE` 계약으로 연결되어 있어, 그걸 깨면 양쪽을 함께 고쳐야 한다.
- **내장 커맨드와의 경계** — 범용 리팩터링은 `/simplify`, 버그 탐지는 `/code-review`가 이미 한다. `spring-optimizer`는 Spring 고유 문제만 다뤄 중복을 피한다. **계획(plan) 모드와 `requirements-writer`도 다르다** — 계획은 *어떻게 만들 것인가*(순서·파일·전략, 사용자가 검수해도 코드를 봐야 안다), 요구사항은 *무엇이 참이어야 하는가*(코드를 몰라도 검수할 수 있는 계약). 요구사항 문서에 구현 순서가 적히고 있으면 선을 넘은 것이다.
- **동시 실행 주의** — 같은 파일을 고치는 에이전트를 병렬로 띄우면 충돌한다. 파일이 겹치면 순차로.

### 진실의 출처 (에이전트 ↔ 모듈 컨텍스트)

**서브에이전트는 메인 에이전트의 컨텍스트를 물려받지 않는다.** 각자 자기 정의 파일 + 메인이 써준 프롬프트만 들고 새로 시작한다. 따라서 메인이 읽은 `modules/user.md`는 `spring-optimizer`에게 도달하지 않는다.

이걸 "에이전트 정의에 프로젝트 사실을 복사해 넣기"로 때우면 **진실의 출처가 둘로 갈리고**, `context-keeper`가 모듈 파일을 갱신해도 사본은 낡아간다 — 하네스가 막으려던 "낡음"이 재발한다. 그래서 역할을 이렇게 갈랐다:

| 파일 | 담는 것 | 유지 주체 |
|---|---|---|
| `modules/<module>.md` | **모듈 사실** — 포트·엔드포인트·정책·엔티티 위치 | `context-keeper` (자동) |
| `agents/<agent>.md` | **역할 지침** — 어떻게 일하는가 | 사람 (드물게) |

14개 에이전트가 "작업 전 `.claude/modules/<module>.md`를 먼저 Read하라"는 지시를 갖는다. 공통 에이전트 2개가 예외다 — `context-keeper`는 모듈 파일이 작업 *대상*이라 절차 안에서 읽고, `commit-writer`는 git 히스토리를 다룰 뿐 모듈 사실이 필요 없다(커밋 컨벤션의 출처는 모듈 파일이 아니라 `git log`다). 메인 에이전트는 프롬프트에 **"어느 모듈 + 무엇을/왜"만** 주면 되고, 모듈 사실을 길게 복사하지 않는다.

역할에 따라 컨텍스트를 쓰는 방식이 다르다:
- `code-commenter` — 모듈 컨텍스트의 "주의/컨벤션"이 곧 **주석 소재**
- `test-writer` — 같은 섹션이 곧 **테스트 케이스 목록**
- `requirements-writer` — 같은 섹션이 곧 **제약 목록**. 이걸 안 읽으면 *이미 있는 정책과 충돌하는 계약*을 쓰게 된다 — 이 역할의 가장 흔한 실패다
- **검증자 2개는 반대다** — "컨텍스트를 정답으로 삼지 마라". 이들의 일은 *컨텍스트가 말하는 대로 코드가 실제로 동작하는가*를 보는 것이라, 둘이 어긋나면 그게 **발견 사항**이지 따를 기준이 아니다.

### 최적화 주석 규칙

최적화는 **눈에 안 보이는 변경**이다(`@EntityGraph` 한 줄, `fetch = LAZY` 한 글자). 리포트만으로는 나중에 확인이 안 되고, 다음 사람이 이유를 모른 채 되돌린다. 그래서 `spring-optimizer`·`jpa-query-tuner`는 **고친 자리마다 문제·개선·결과를 주석으로** 남긴다.

단 **"옛날엔 이랬다"는 이력이 아니라 "이 코드가 왜 이렇게 생겼는가"로 재진술**한다 — 이력은 git이 하고, 주석은 코드가 살아있는 한 유효해야 한다. 측정 수치는 근거로 붙이되 **측정 안 했으면 "(미측정)"을 명시**한다.

```java
/**
 * accounts 를 @EntityGraph 로 함께 조회한다.
 * 지연로딩이면 호출부 루프에서 계정마다 추가 쿼리가 나가 N+1이 된다.
 * (사용자 20명 기준 21회 → 1회, show-sql 측정 — 제거하지 말 것)
 */
```

`code-commenter`에는 "이력 주석 금지" 규칙이 있어 충돌했다 → **최적화 주석은 이력이 아니라 "제거하면 안 되는 이유"이므로 예외**임을 명시해 조율했다. 이 주석의 존재 이유는 **다음 사람이 모르고 되돌리는 걸 막는 것**이다.

### 구현 전 요구사항 (requirements-writer)

새 기능은 코드보다 **계약**이 먼저다. `requirements-writer`가 `docs/requirements/<module>/<feature>.md`에 **EARS 표기법**으로 "무엇이 참이어야 하는가"를 쓰고, 사용자가 승인해야 구현이 시작된다.

**EARS를 고른 이유**는 표기법이 예뻐서가 아니라 **패턴 6개 중 하나가 예외 경로 전용**이기 때문이다.

| 유형 | 형태 |
|---|---|
| 유비쿼터스 | THE 시스템 SHALL \<동작\> |
| 이벤트 | WHEN \<트리거\>, THE 시스템 SHALL \<동작\> |
| 상태 | WHILE \<상태\>, THE 시스템 SHALL \<동작\> |
| 선택 | WHERE \<기능이 포함된 경우\>, THE 시스템 SHALL \<동작\> |
| **예외** | **IF \<트리거\>, THEN THE 시스템 SHALL \<동작\>** — 원치 않는 동작(EARS *unwanted behaviour*) |
| 복합 | 위 조합 |

이 프로젝트에서 반복적으로 새는 건 정상 경로가 아니라 **예외 경로**다(정책 위반 시 상태코드·메시지, 중복, 만료). `IF...THEN` 칸이 비어 있으면 그게 눈에 보인다 — **정상 3줄·예외 0줄인 요구사항은 실패한 요구사항이다.** 서술은 한국어로 쓰되 **키워드는 영어 대문자 그대로** 둔다. 튀어야 누락이 보인다.

**협의 루프가 메인 에이전트에 있는 이유**: 서브에이전트는 사용자에게 질문할 수 없다(`AskUserQuestion`이 없고 대화가 단절된다). 그래서 역할을 이렇게 갈랐다 — 에이전트는 코드·모듈 컨텍스트를 읽어 **초안 + 미해결 질문**을 만들고, **묻는 건 메인**이 하고, 답을 들고 `SendMessage`로 같은 에이전트를 다시 부른다(새 `Agent` 호출은 문맥을 잃는다). 이러면 탐색 비용은 서브에이전트가 치르고 메인 컨텍스트는 깨끗하게 남는다.

**추측을 빈칸이 아니라 `(가정)`으로 두게 한 것**이 이 설계의 핵심이다. 빈칸은 답을 못 받는다 — 사람은 **고칠 대상이 있어야** 고친다. 대신 메인이 `(가정)` 항목을 반드시 사용자에게 보여줘야 한다. 안 그러면 가정이 조용히 계약이 된다.

**요구사항 ID(`USER-PW-3`)가 하류로 흐른다** — 이게 문서를 장식이 아니게 만드는 유일한 장치다.
- `test-writer` — 인수 기준이 곧 테스트 케이스. ID를 `@DisplayName`에 접두로 달고 **커버 못 한 ID를 보고**한다.
- `module-verifier` — 인수 기준이 곧 검증 시나리오. **여기서는 문서가 기준이고 코드가 검증 대상**이다(모듈 컨텍스트와 정반대 — 그건 코드가 진실이었다). 요구사항을 코드가 못 만족하면 FAIL.
- 번호는 **재사용 금지**. 요구사항을 지워도 당기지 않는다 — 테스트·커밋이 그 ID를 참조하고 있어서, 당기는 순간 남의 문서가 다른 요구사항을 가리킨다.

**모든 작업에 태우지 않는다.** 버그 수정·오타·한 줄 수정·리팩터링은 그냥 간다. 자명한 일에 계약을 쓰는 건 하네스가 막으려는 그 비대함이다. 애매하면 메인이 사용자에게 묻는다. 수동 호출은 `/requirements`.

### 작업 후 검증

작업을 마치면 **증거 기반**으로 확인한다. 검증자는 둘 다 코드를 수정하지 않는다.

- **코드 → `module-verifier`**: 컴파일 → (테스트) → 엔드포인트 정적 확인 → 가능하면 `bootRun` 후 컨트롤러 호출로 상태코드·응답값 검증
- **인프라 → `docker-runner`**: compose 문법(`config`) → 이미지 빌드 → 로컬 스택 기동 → health·라우팅을 `curl`로 확인 → **정리(down)**

호출 방식: 작업 완료 시 메인 에이전트가 자동 호출(SessionStart 지침), 또는 사용자가 `/verify [모듈]`로 수동 호출.

**인프라 검증이 로컬 Docker 기반인 이유**: 원래 `module-verifier`의 infra 분기는 `gh run`으로 배포 상태를 보고 EC2 health URL을 찔렀는데, **`gh`도 `aws`도 이 환경에 설치되어 있지 않아** 사실상 항상 SKIP이었다. 로컬 compose 기동은 설치된 도구만으로 되고, 재현 가능하고, 운영을 건드리지 않는다.

**단, 운영 스택은 로컬에서 그대로 뜨지 않는다.** `docker-compose.prod.yml`은 GHCR 이미지를 pull하고 DB로 AWS RDS를 본다 → `config` 문법 검증까지만 하고, 실제 기동은 `docker-compose.yml`(로컬 mysql 포함)로 한다.

### 컨텍스트 유지 (context-keeper)

하네스의 자기 유지 장치. 기능이 추가·변경되면 `.claude/modules/<module>.md`에 반영해 **컨텍스트가 코드에 대해 거짓말하지 않게** 한다.

막아야 할 실패 모드가 둘이고, 서로 반대 방향으로 당긴다:
- **낡음** — 코드는 바뀌었는데 컨텍스트가 옛말을 한다. 컨텍스트가 없는 것보다 나쁘다.
- **비대** — 매 작업마다 덧붙여 파일이 불어난다. 애초에 풀려던 문제로 되돌아간다.

→ 갱신하되 불리지 않는다. 한 줄 추가할 때 낡은 한 줄을 지울 수 있는지 확인한다. 사소한 변경(내부 리팩터링, 오타)은 **기록하지 않는 게 옳은 결과**다.

## 구성 요소

| 경로 | 역할 |
|------|------|
| `.claude/settings.json` | SessionStart 훅 등록 (커밋되는 프로젝트 설정) |
| `.claude/hooks/session-start.sh` | 모듈 선택 + 에이전트 분배 지침을 `additionalContext`로 주입하는 훅 |
| `.claude/modules/user.md` | user 모듈(JWT 인증) 슬림 컨텍스트 |
| `.claude/modules/quiz.md` | quiz 모듈(스켈레톤) 슬림 컨텍스트 |
| `.claude/modules/domain.md` | domain 모듈(공유 JPA 엔티티/리포지토리) 슬림 컨텍스트 |
| `.claude/modules/web-support.md` | web-support 모듈(user·quiz 공유 JWT 발급/검증·예외 핸들러·401 엔트리포인트 라이브러리) 슬림 컨텍스트 |
| `.claude/modules/infra.md` | 배포·인프라(EC2→Docker→K8s) 학습/운영 컨텍스트 |
| `.claude/agents/*.md` | 역할별 서브에이전트 16개 — 코드 9 · 인프라 5 · 공통 2 (위 "에이전트 구성" 표) |
| `.claude/commands/verify.md` | 검증을 수동 호출하는 `/verify` 슬래시 커맨드 |
| `.claude/commands/requirements.md` | 요구사항 단계를 수동 호출하는 `/requirements` 슬래시 커맨드 |
| `docs/requirements/<module>/<feature>.md` | 기능별 EARS 요구사항 (구현 전 계약). `requirements-writer`가 쓰고 **사용자가 승인** |
| `docs/하네스/flow-prompt.md` | 세션 플로우를 claude.ai(웹)에서 다이어그램으로 그리게 하는 자기완결 프롬프트. **하네스를 바꾸면 함께 갱신** (웹은 저장소를 못 본다) |

## 동작 원리 (훅)

`session-start.sh`는 stdout으로 아래 형태 JSON을 출력하고, Claude Code가 `additionalContext`를 세션 컨텍스트에 주입한다.

```json
{ "hookSpecificOutput": {
    "hookEventName": "SessionStart",
    "additionalContext": "[1. 모듈 선택] ... [2. 작업 분배] ... [3. 표준 흐름] ... [4. 이 환경의 제약] ..." } }
```

- 질문 문구·동작을 바꾸려면 `session-start.sh`의 `additionalContext` 텍스트만 수정.
- 훅은 **다음 세션부터** 발동(SessionStart 특성). 적용하려면 `claude` 재실행 또는 `/hooks` 리로드.

## 모듈 파일 작성 원칙 (컨텍스트 축소)

- **고신호만**: 책임 범위 / 핵심 클래스 / 엔드포인트 / 의존 / 주의점·컨벤션.
- 장황한 코드 인용·자명한 내용 제외.
- 공통 사실(독립 Spring Boot 앱, `:common`·`:domain` 의존, MySQL+dotenv, prod `ddl-auto=none`)은 각 파일 상단 1줄로만 반복.

## 이력 / 결정

- 기존 전역 `~/CLAUDE.md`(EC2→K8s 인프라 학습 일지, 127줄)는 상위 디렉터리 자동로드로 **모든 작업에 항상 주입**되어 비효율 → 내용을 `.claude/modules/infra.md`로 이전하고 전역 파일은 스텁으로 축소. (당시 원본 백업 `C:\Users\doorm\CLAUDE.md.bak` 은 **다른 머신(Windows)의 경로**다. 현재 개발 환경은 macOS이고 `~/CLAUDE.md`·`~/.claude/CLAUDE.md` 모두 존재하지 않는다 — 이 백업은 이 환경에서 확인 불가.)
- **2026-07-15 — 단일 `module-verifier`에서 역할별 14개 에이전트 체제로 전환** (`hwannee/be/create-agent`).
  - 배경: 검증만 분리되어 있고 구현·테스트·문서·최적화·인프라를 메인 에이전트가 전부 떠안아 컨텍스트가 다시 비대해짐.
  - 결정한 것들:
    - **API 문서화는 마크다운만** (`docs/api/*.md`). springdoc/swagger 의존성을 추가하지 않고 코드에 애너테이션도 넣지 않는다 — 대신 `api-documenter`가 매번 코드를 다시 읽어 갱신한다.
    - **최적화를 쿼리 기준으로 2분할** (`spring-optimizer` / `jpa-query-tuner`). 하나로 두면 범위가 너무 넓어 판단이 흐려진다.
    - **`module-verifier`는 신규 작성하지 않고 재사용.** "실제 앱 동작 확인"과 역할이 동일했다. 다만 infra 분기는 `docker-runner`로 넘기고 코드 모듈 전담으로 좁혔다.
    - `context-keeper` 신설 — 하네스가 스스로를 최신으로 유지하게 한다.
    - **인프라를 5분할** — 처음엔 `docker-engineer` 하나로 뭉쳤다가, Docker를 **작성(Dockerfile/compose) ↔ 실행(runner)** 축으로 갈라 3개로 쪼개고 `nginx-proxy`·`github-actions`를 분리했다. 빌드가 느려 "쓰는 일"과 "돌리는 일"의 성격이 크게 달랐던 게 분할의 실질적 근거다.
    - **`aws-ec2`·K8s 에이전트는 만들지 않았다.** `aws`/`kubectl`이 미설치라 검증할 수단이 없고, K8s는 `infra.md`의 로드맵(STEP 3~5)일 뿐 매니페스트가 아직 없다. **존재하지 않는 일을 위한 에이전트는 하네스가 막으려는 그 비대함 자체**다. 실제로 도입할 때 만든다.
  - 미해결:
    - H2·Testcontainers가 없어 **DB 통합 테스트 전략이 미정**. `test-writer`는 단위 테스트까지만 커버하고 이 지점에서 멈추도록 지시되어 있다. (당시 테스트는 0개였다. 2026-07-16 기준 `user/src/test`에 3개 — 컨트롤러 슬라이스 2 + 정책 단위 1. 여전히 DB를 타는 테스트는 없다.)
    - **CI에 롤백 전략이 없다** — `:sha` 태그를 푸시하면서 배포는 `:latest`로만 하고 `image prune -f`가 이전 이미지를 지운다. `github-actions` 에이전트 정의에 기록해 두었다.
    - **운영 앱의 생존을 확인할 수단이 없다** — prod compose에 healthcheck가 없고, nginx `/healthz`는 nginx 자신이 200을 반환할 뿐 백엔드를 보지 않는다.
- **2026-07-16 — 구현 전 요구사항 단계 신설** (`requirements-writer` + `/requirements`). 하네스에 **시점 축**이 추가됐다.
  - 배경: 하네스가 "어떻게 만들고 어떻게 검증하나"는 촘촘한데 **"무엇을 만들 것인가"를 확정하는 지점이 없었다.** 그래서 그게 코드로 굳은 뒤에야 드러났고, 테스트는 *만들어진 것*을 그대로 승인했다.
  - 결정한 것들:
    - **표기법은 EARS.** 고른 이유는 패턴 6개 중 하나(`IF...THEN`)가 **예외 경로 전용**이라, 비어 있으면 눈에 보이기 때문이다. 이 프로젝트가 반복적으로 놓치던 게 정확히 거기였다. 서술은 한국어, 키워드는 영어 대문자.
    - **협의는 메인이, 초안은 서브에이전트가.** 서브에이전트는 사용자에게 질문할 수 없다 — 이 제약이 역할 분할을 결정했다. 에이전트는 `(가정)` 표시 + 미해결 질문을 돌려주고, 메인이 `AskUserQuestion`으로 묻고, `SendMessage`로 같은 에이전트를 다시 부른다.
    - **모호한 건 빈칸이 아니라 `(가정)`으로.** 빈칸은 답을 못 받는다 — 사람은 고칠 대상이 있어야 고친다.
    - **요구사항 ID를 하류로 흘린다** (`test-writer` 1:1 대응 + 미커버 보고, `module-verifier` 인수 기준 대조). ID가 안 흐르면 요구사항 문서는 아무도 안 읽는 장식이 된다 — **이게 이 단계의 성패를 가른다.**
    - **모든 작업에 태우지 않는다.** 새 엔드포인트·정책·엔티티가 생기는 기능 구현만. 자명한 일에 계약을 쓰는 건 하네스가 막으려는 그 비대함이다.
    - **`spring-dev`가 아니라 별도 에이전트.** "만들 사람이 스펙도 정한다"는 이해충돌이고, 승인 전에 코드를 쓸 위험이 있다. 검증자를 도구로 못 고치게 막은 것과 같은 이유다 — 다만 요구사항은 **승인 전 구현 금지**를 규칙으로만 막는다(`spring-dev`는 코드 도구가 필요하므로).
  - 미해결:
    - **기존 기능에는 요구사항 문서가 없다.** user 인증·비밀번호 정책은 이미 구현·문서화돼 있고 소급 작성은 하지 않았다 — 사후 요구사항은 코드를 그대로 옮겨 적게 되어(=코드가 계약을 정함) 이 단계의 목적이 뒤집힌다. 해당 기능을 **다음에 변경할 때** 그 변경분부터 쓰는 게 맞다.
    - **요구사항과 구현의 어긋남을 잡는 건 규칙뿐이다.** ID 누락을 도구 수준에서 강제하는 장치는 없고, `test-writer`·`module-verifier`의 "미커버 ID 보고"에 의존한다.

## 이 환경의 제약 (검증을 지어내지 않기 위한 실측 기록)

> 2026-07-15 실측. 하네스가 "확인했다"고 거짓 보고하는 걸 막으려면 무엇이 **불가능한지**를 알아야 한다.

| 도구 | 상태 |
|---|---|
| `docker` / `compose` | ⚠️ **바이너리는 있으나 심링크가 끊어져 있다** (아래) |
| `gh` · `aws` · `kubectl` · `minikube` | ❌ 미설치 |
| `curl` | ✅ |

**Docker 심링크 파손**: `Docker.app`을 `~/Desktop` → `/Applications`로 옮기면서 심링크 3개가 전부 dangling 상태가 되었다(2026-06-14자).
- `/usr/local/bin/docker`, `~/.docker/cli-plugins/docker-compose`, `~/.docker/cli-plugins/docker-buildx` → 전부 `~/Desktop/Docker.app/...`을 가리킴
- 바이너리 자체는 멀쩡하다: docker 29.5.3 / compose v5.1.4 (`/Applications/Docker.app/Contents/Resources/` 아래)
- 복구:
  ```bash
  ln -sf /Applications/Docker.app/Contents/Resources/cli-plugins/docker-compose ~/.docker/cli-plugins/docker-compose
  ln -sf /Applications/Docker.app/Contents/Resources/cli-plugins/docker-buildx  ~/.docker/cli-plugins/docker-buildx
  sudo ln -sf /Applications/Docker.app/Contents/Resources/bin/docker /usr/local/bin/docker   # sudo 필요
  open -a Docker   # 데몬 기동
  ```

**추가 장벽 2개** — `docker-runner`가 실패를 오진하지 않도록 정의에 명시해 두었다:
- **데몬**이 꺼져 있으면 소켓 연결 실패(`~/.docker/run/docker.sock`).
- **Claude Code 샌드박스**가 docker 소켓 접근을 막는다 → `dangerouslyDisableSandbox: true` 필요.

## 확장 방법

- **새 작업 영역**: `.claude/modules/<name>.md` 추가 → `session-start.sh`의 선택지 목록에 `<name>` 추가 → 위 구성 요소 표 갱신. (`context-keeper`가 이 3단계를 대신 할 수 있다.)
- **새 에이전트**: `.claude/agents/<name>.md` 추가 → `session-start.sh`의 분배 목록에 한 줄 추가 → 위 에이전트 표 갱신.
  - frontmatter: `name`(파일명과 일치), `description`(**메인 에이전트가 이걸 보고 위임을 결정하므로 "언제 쓰는지"를 명확히**), `tools`, `model`.
  - 본문에는 **기존 에이전트와의 경계**를 반드시 명시할 것. 안 그러면 역할이 겹쳐 서로 덮어쓴다.
  - 읽기 전용이어야 하는 에이전트는 `tools`에서 `Write`/`Edit`을 빼서 강제한다 (예: `module-verifier`).
