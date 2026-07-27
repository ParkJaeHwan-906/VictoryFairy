# 하네스(Harness) 전략

> 목적: **CLAUDE.md 비대화로 인한 중간 context 유지 어려움**을 막기 위해, 컨텍스트를 "항상 로드"에서 "필요할 때만 로드"로 전환한다.
> 최종 업데이트: 2026-07-27
>
> 이 문서는 **현재 유효한 구성과 설계 근거**만 담는다. 날짜별 도입 이력은 `git log`가 갖고 있으므로 여기 남기지 않는다.

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
                           인프라 dockerfile-manager / compose-manager / github-actions
                                            → docker-runner(검증) → context-keeper
```

요구사항 단계만 **⇄(왕복)**인 이유: 나머지는 에이전트가 답을 알지만, "무엇을 만들 것인가"는 **사용자만 안다.** 그래서 여기서만 메인이 중간에 서서 묻는다(서브에이전트는 사용자에게 질문할 수 없다).

## 하네스가 둘로 나뉜다

이 저장소는 **BE 하네스**와 **인프라 하네스**를 따로 갖는다. 트리가 다르므로 헷갈리지 말 것.

| 위치 | 대상 | 에이전트 |
|---|---|---|
| `VictoryFairy_BE/.claude/` | Spring 멀티모듈 BE | 아래 15개 (코드 9 · 인프라 4 · 공통 2) |
| 저장소 루트 `.claude/` | Terraform·EKS 인프라 | `terraform-writer` · `terraform-validator` · `k8s-manifest` · `context-keeper` · `commit-writer` + `terraform-infra` 스킬 |

BE 쪽 `infra` 모듈(`.claude/modules/infra.md`)은 **EC2+compose·배포 파이프라인** 관점의 컨텍스트다. Terraform·k8s 매니페스트 자체를 고치는 작업은 루트 하네스 소관이고, 코드는 `VictoryFairy_Infra/`에 있다(`dev_*` 브랜치는 분리된 트리라 `dev_be`에서는 안 보인다 — `main` 기준으로 볼 것).

## 에이전트 구성 (BE)

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
| `dockerfile-manager` | `Dockerfile` — 멀티스테이지·레이어 캐시·이미지 크기. ⚠ EKS CI 도 이 파일로 빌드한다 | 코드 | sonnet |
| `compose-manager` | `docker-compose.yml` — **로컬 개발용**(mysql·redis) | 코드 | sonnet |
| `github-actions` | `.github/workflows/deploy-eks.yml` — CI/CD 전략 | 코드 | inherit |
| `docker-runner` | 실제 빌드·기동·health 검증 후 정리 | ❌ 읽기전용 | sonnet |

> **EC2+docker-compose 배포 경로는 2026-07-27 폐기됐다.** 대상 인스턴스가 이미 사라져 워크플로가 실패만 하고
> 있었다. `deploy.yml`·`docker-compose.prod.yml`·`nginx.conf`를 삭제하고 `nginx-proxy` 에이전트도 제거했다
> (라우팅 규칙을 담을 파일이 없어졌다). 서빙은 EKS다 — 매니페스트·Terraform은 **저장소 루트 하네스** 소관.

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
- **`dockerfile-manager` ↔ `github-actions`** — 이미지를 **어떻게 만드나**는 전자, CI에서 **언제·무엇을 빌드하나**(트리거·매트릭스·태그)는 후자. 둘은 `ARG MODULE` 계약으로 연결되어 있어, 그걸 깨면 양쪽을 함께 고쳐야 한다.
- **BE 하네스 ↔ 루트(인프라) 하네스** — BE 쪽 인프라 에이전트는 **이미지 빌드와 로컬 개발 스택**까지만 다룬다. EKS 매니페스트·Terraform 은 루트의 `k8s-manifest`·`terraform-writer` 소관이다. 같은 "인프라"라는 말을 쓰지만 대상 파일이 겹치지 않는다.
- **내장 커맨드와의 경계** — 범용 리팩터링은 `/simplify`, 버그 탐지는 `/code-review`가 이미 한다. `spring-optimizer`는 Spring 고유 문제만 다뤄 중복을 피한다. **계획(plan) 모드와 `requirements-writer`도 다르다** — 계획은 *어떻게 만들 것인가*(순서·파일·전략, 사용자가 검수해도 코드를 봐야 안다), 요구사항은 *무엇이 참이어야 하는가*(코드를 몰라도 검수할 수 있는 계약). 요구사항 문서에 구현 순서가 적히고 있으면 선을 넘은 것이다.
- **동시 실행 주의** — 같은 파일을 고치는 에이전트를 병렬로 띄우면 충돌한다. 파일이 겹치면 순차로.

### 진실의 출처 (에이전트 ↔ 모듈 컨텍스트)

**서브에이전트는 메인 에이전트의 컨텍스트를 물려받지 않는다.** 각자 자기 정의 파일 + 메인이 써준 프롬프트만 들고 새로 시작한다. 따라서 메인이 읽은 `modules/user.md`는 `spring-optimizer`에게 도달하지 않는다.

이걸 "에이전트 정의에 프로젝트 사실을 복사해 넣기"로 때우면 **진실의 출처가 둘로 갈리고**, `context-keeper`가 모듈 파일을 갱신해도 사본은 낡아간다 — 하네스가 막으려던 "낡음"이 재발한다. 그래서 역할을 이렇게 갈랐다:

| 파일 | 담는 것 | 유지 주체 |
|---|---|---|
| `modules/<module>.md` | **모듈 사실** — 포트·엔드포인트·정책·엔티티 위치 | `context-keeper` (자동) |
| `agents/<agent>.md` | **역할 지침** — 어떻게 일하는가 | 사람 (드물게) |

13개 에이전트가 "작업 전 `.claude/modules/<module>.md`를 먼저 Read하라"는 지시를 갖는다. 공통 에이전트 2개가 예외다 — `context-keeper`는 모듈 파일이 작업 *대상*이라 절차 안에서 읽고, `commit-writer`는 git 히스토리를 다룰 뿐 모듈 사실이 필요 없다(커밋 컨벤션의 출처는 모듈 파일이 아니라 `git log`다). 메인 에이전트는 프롬프트에 **"어느 모듈 + 무엇을/왜"만** 주면 되고, 모듈 사실을 길게 복사하지 않는다.

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

> 기존 기능(user 인증·비밀번호 정책 등)에는 요구사항 문서가 없다. 소급 작성하지 않는다 — 사후 요구사항은 코드를 그대로 옮겨 적게 되어(=코드가 계약을 정함) 이 단계의 목적이 뒤집힌다. 해당 기능을 **다음에 변경할 때** 그 변경분부터 쓴다.

### 작업 후 검증

작업을 마치면 **증거 기반**으로 확인한다. 검증자는 둘 다 코드를 수정하지 않는다.

- **코드 → `module-verifier`**: 컴파일 → (테스트) → 엔드포인트 정적 확인 → 가능하면 `bootRun` 후 컨트롤러 호출로 상태코드·응답값 검증
- **인프라 → `docker-runner`**: compose 문법(`config`) → 이미지 빌드 → 로컬 스택 기동 → health를 `curl`로 확인 → **정리(down)**

호출 방식: 작업 완료 시 메인 에이전트가 자동 호출(SessionStart 지침), 또는 사용자가 `/verify [모듈]`로 수동 호출.

**로컬 검증은 운영과 다르다.** 로컬은 `docker-compose.yml`(mysql·redis) 기준이고, 운영은 EKS다 — 로컬에서 통과했다고 운영 라우팅까지 검증된 게 아니다. 운영 확인은 `kubectl`·`gh run`으로 직접 한다(둘 다 설치돼 있다).

### 컨텍스트 유지 (context-keeper)

하네스의 자기 유지 장치. 기능이 추가·변경되면 `.claude/modules/<module>.md`에 반영해 **컨텍스트가 코드에 대해 거짓말하지 않게** 한다.

막아야 할 실패 모드가 둘이고, 서로 반대 방향으로 당긴다:
- **낡음** — 코드는 바뀌었는데 컨텍스트가 옛말을 한다. 컨텍스트가 없는 것보다 나쁘다.
- **비대** — 매 작업마다 덧붙여 파일이 불어난다. 애초에 풀려던 문제로 되돌아간다.

→ 갱신하되 불리지 않는다. 한 줄 추가할 때 낡은 한 줄을 지울 수 있는지 확인한다. 사소한 변경(내부 리팩터링, 오타)은 **기록하지 않는 게 옳은 결과**다.

> **"낡음"은 실제로 재발한다.** 2026-07-27 점검에서 이 문서와 `session-start.sh`가 "gh·aws·kubectl 미설치"라고 주장하고 있었다(전부 설치돼 동작 중). 훅은 그 거짓을 근거로 "배포·K8s 확인은 SKIP하고 보고하라"고 지시하고 있었다 — **검증을 지어내지 말라고 쓴 섹션이 스스로 거짓이 되어 검증을 막고 있던 것**이다. 환경 사실을 적을 때는 적은 날짜와 확인 방법을 함께 남기고, 의심되면 문서를 믿지 말고 직접 실행해 확인한다.

## 구성 요소

| 경로 | 역할 |
|------|------|
| `.claude/settings.json` | SessionStart 훅 등록 (커밋되는 프로젝트 설정) |
| `.claude/hooks/session-start.sh` | 모듈 선택 + 에이전트 분배 지침을 `additionalContext`로 주입하는 훅 |
| `.claude/modules/user.md` | user 모듈(JWT 인증·이메일 인증·계정) 슬림 컨텍스트 |
| `.claude/modules/quiz.md` | quiz 모듈(구단별 채팅 REST/SSE) 슬림 컨텍스트 |
| `.claude/modules/domain.md` | domain 모듈(공유 JPA 엔티티/리포지토리) 슬림 컨텍스트 |
| `.claude/modules/web-support.md` | web-support 모듈(user·quiz 공유 JWT 발급/검증·예외 핸들러·401 엔트리포인트 라이브러리) 슬림 컨텍스트 |
| `.claude/modules/infra.md` | 배포·인프라 컨텍스트 (EC2+compose 경로 + EKS 현황) |
| `.claude/agents/*.md` | 역할별 서브에이전트 15개 — 코드 9 · 인프라 4 · 공통 2 (위 표) |
| `.claude/commands/verify.md` | 검증을 수동 호출하는 `/verify` 슬래시 커맨드 |
| `.claude/commands/requirements.md` | 요구사항 단계를 수동 호출하는 `/requirements` 슬래시 커맨드 |
| `docs/requirements/<module>/<feature>.md` | 기능별 EARS 요구사항 (구현 전 계약). `requirements-writer`가 쓰고 **사용자가 승인** |

## 동작 원리 (훅)

`session-start.sh`는 stdout으로 아래 형태 JSON을 출력하고, Claude Code가 `additionalContext`를 세션 컨텍스트에 주입한다.

```json
{ "hookSpecificOutput": {
    "hookEventName": "SessionStart",
    "additionalContext": "[1. 모듈 선택] ... [2. 작업 분배] ... [3. 표준 흐름] ... [4. 이 환경의 제약] ..." } }
```

- 질문 문구·동작을 바꾸려면 `session-start.sh`의 `additionalContext` 텍스트만 수정.
- 훅은 **다음 세션부터** 발동(SessionStart 특성). 적용하려면 `claude` 재실행 또는 `/hooks` 리로드.
- 고친 뒤에는 **JSON이 깨지지 않았는지 반드시 확인**할 것: `bash .claude/hooks/session-start.sh | jq .`

## 모듈 파일 작성 원칙 (컨텍스트 축소)

- **고신호만**: 책임 범위 / 핵심 클래스 / 엔드포인트 / 의존 / 주의점·컨벤션.
- 장황한 코드 인용·자명한 내용 제외.
- 공통 사실(독립 Spring Boot 앱, `:common`·`:domain`·`:web-support` 의존, MySQL+dotenv, `server.servlet.context-path`)은 각 파일 상단 1줄로만 반복.

## 이 환경의 제약 (검증을 지어내지 않기 위한 실측 기록)

> **2026-07-27 실측.** 하네스가 "확인했다"고 거짓 보고하는 걸 막으려면 무엇이 **가능하고 불가능한지**를 알아야 한다.
> 이 표는 낡기 쉬우니 **의심되면 `Get-Command <도구>`로 직접 확인**하고 이 표를 고칠 것.

| 항목 | 상태 |
|---|---|
| OS | Windows 11 (PowerShell + Git Bash 병용, 문법이 서로 다름) |
| `docker` | ✅ 29.6.1 |
| `gh` · `aws` · `kubectl` · `curl` | ✅ 전부 설치·동작 |
| `minikube` | ❌ 미설치 |

**따라서 배포 워크플로 상태(`gh run`)·EKS(`kubectl`)·AWS 리소스(`aws`) 확인은 실제로 가능하다.** 근거 없이 SKIP하지 말 것.

**Gradle 주의 2가지** — 안 맞추면 빌드가 아예 안 돈다.
- `GRADLE_USER_HOME`이 **한글 경로면 워커가 깨져 테스트가 항상 실패**한다(기본값이 한글 사용자명 경로다). ASCII 경로로 지정할 것.
- `JAVA_HOME`이 비어 있을 수 있다. JDK 21 경로를 지정해야 `gradlew`가 돈다.

**앱 경로 규약** — `server.servlet.context-path`를 쓴다(user `/api/member`, quiz `/api/game`).
컨트롤러 `@RequestMapping`과 Security `requestMatchers`는 **둘 다 접두사를 뺀 경로**다(컨테이너가 필터 체인 이전에 접두사를 떼므로). MockMvc도 context-path를 적용하지 않으므로 슬라이스 테스트 경로 역시 접두사가 없다 — **접두사가 실제로 붙는지는 테스트로 증명되지 않으니** 실기동·`curl`로 확인해야 한다.

## 알려진 갭

- **CI에 테스트 단계가 없다** — `deploy-eks.yml`은 빌드만 하고 배포한다. 테스트가 32개 있으므로 넣을 명분이 있다.
- **로컬 compose에 앱 healthcheck가 없다** — actuator readiness 엔드포인트가 생겼으니 붙일 수 있다. 경로가 `context-path` 아래라 `/health`·`/healthz`로 걸면 영구 unhealthy가 되니 주의(`compose-manager` 정의 참고).
- **DB 통합 테스트 전략 미정** — H2·Testcontainers가 없어 DB를 타는 테스트가 없다. `test-writer`는 단위·슬라이스 테스트까지만 커버하도록 지시되어 있다.
- **요구사항과 구현의 어긋남을 잡는 건 규칙뿐** — ID 누락을 도구 수준에서 강제하는 장치는 없고, `test-writer`·`module-verifier`의 "미커버 ID 보고"에 의존한다.

## 확장 방법

- **새 작업 영역**: `.claude/modules/<name>.md` 추가 → `session-start.sh`의 선택지 목록에 `<name>` 추가 → 위 구성 요소 표 갱신. (`context-keeper`가 이 3단계를 대신 할 수 있다.)
- **새 에이전트**: `.claude/agents/<name>.md` 추가 → `session-start.sh`의 분배 목록에 한 줄 추가 → 위 에이전트 표 갱신.
  - frontmatter: `name`(파일명과 일치), `description`(**메인 에이전트가 이걸 보고 위임을 결정하므로 "언제 쓰는지"를 명확히**), `tools`, `model`.
  - 본문에는 **기존 에이전트와의 경계**를 반드시 명시할 것. 안 그러면 역할이 겹쳐 서로 덮어쓴다.
  - 읽기 전용이어야 하는 에이전트는 `tools`에서 `Write`/`Edit`을 빼서 강제한다 (예: `module-verifier`).
- **존재하지 않는 일을 위한 에이전트는 만들지 않는다.** 검증할 수단이 없는 영역에 에이전트를 두면 하네스가 막으려는 그 비대함이 된다. 실제로 도입할 때 만든다.
