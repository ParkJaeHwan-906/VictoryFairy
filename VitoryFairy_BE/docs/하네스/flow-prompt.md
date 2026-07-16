# Claude Web용 플로우 시각화 프롬프트

> 용도: claude.ai(웹)에 아래 블록을 **통째로 붙여넣으면** 현재 하네스의 세션 시작 플로우를 인터랙티브 다이어그램 아티팩트로 그려준다.
> 웹 Claude는 이 저장소를 볼 수 없으므로 **프롬프트가 자기완결적**이어야 한다 — 하네스를 바꾸면 이 파일도 함께 갱신할 것. 기준: 2026-07-16 (에이전트 16개).

---

## 붙여넣을 프롬프트 (여기부터)

Claude Code 하네스(멀티 에이전트 오케스트레이션 설정)를 운영 중이야. 아래는 그 **세션 시작 플로우**의 전체 사실이다. 이걸 **한 장으로 이해되는 인터랙티브 다이어그램**으로 그려줘. show me.

### 이 하네스가 푸는 문제
CLAUDE.md 하나가 비대해져 컨텍스트가 터지는 걸 막는 것. 컨텍스트를 "항상 로드"에서 "필요할 때만 로드"로 바꿨다. 축이 셋이다.
1. **컨텍스트 분할** — 모듈별로 쪼갠 `.claude/modules/<module>.md` 중 **작업할 하나만** 로드
2. **역할 분할** — 메인 에이전트는 오케스트레이터만 하고 실제 작업은 서브에이전트 16개에 위임
3. **시점 분할** — 요구사항(무엇이 참이어야 하나) → 구현(어떻게) → 검증(정말 그런가)

### 1단계: 세션 시작
SessionStart 훅이 실행되어 "모듈 선택 + 에이전트 분배" 지침을 컨텍스트에 주입한다. 그다음 사용자의 첫 요청이 들어오면:
- 대상 모듈이 **명확하면** 묻지 않고 진행
- **불명확하면** AskUserQuestion으로 사용자에게 물음 (선택지: user / quiz / create / domain / infra)
- 정해지면 `.claude/modules/<선택>.md` **하나만** Read → 슬림 컨텍스트 확보

### 2단계: 모듈 5개
| 모듈 | 정체 | 특이점 |
|---|---|---|
| user | JWT 인증 앱 (Spring Boot) | 포트 8080. 유일하게 테스트가 있음 |
| quiz | 앱 (스켈레톤) | |
| create | 앱 (부트스트랩) | |
| domain | **공유 JPA 엔티티/리포지토리** | **실행 앱이 아님 — 포트도 엔드포인트도 없음** |
| infra | 배포·인프라 | Gradle 모듈이 아님 (Dockerfile·compose·nginx·CI/CD) |

### 3단계: 작업 유형별 표준 흐름 (여기가 다이어그램의 중심)

**기능 구현 (user·quiz·create)** — 새 엔드포인트·정책·엔티티가 생기는 경우:
```
requirements-writer ⇄ (사용자 협의) → 사용자 승인
  → spring-dev → test-writer (+필요시 test-data)
  → module-verifier → (API면) api-documenter → context-keeper
```

**domain 작업 (엔티티·리포지토리)**:
```
(새 엔티티·정책이면 requirements-writer 먼저)
  → spring-dev (매핑·쿼리 중심이면 jpa-query-tuner)
  → test-writer(@DataJpaTest) → module-verifier(컴파일·테스트만) → context-keeper
```
domain은 포트가 없어 **bootRun·curl·api-documenter가 대상이 아니다.**

**인프라 작업**:
```
dockerfile-manager / compose-manager / nginx-proxy / github-actions 중 해당하는 것
  → docker-runner (실제 빌드·기동·health·라우팅 검증 후 정리)
  → context-keeper
```

**최적화**: 쿼리/SQL/JPA면 jpa-query-tuner, 트랜잭션/설정이면 spring-optimizer. 둘 다면 병렬.

### 4단계: 요구사항 협의 루프 — **이 플로우에서 유일하게 왕복(⇄)하는 지점**

왜 여기만 왕복인가: 나머지 단계는 에이전트가 답을 알지만, **"무엇을 만들 것인가"는 사용자만 안다.** 그런데 **서브에이전트는 사용자에게 질문할 수 없다**(AskUserQuestion이 없고 대화가 단절됨). 그래서 메인이 중간에 서서 대신 묻는다.

```
메인 → requirements-writer 호출
        (모듈 컨텍스트 + 코드를 읽고 EARS 초안 작성)
     ← 초안 + "(가정)" 표시 항목 + 미해결 질문 목록
메인 → AskUserQuestion 으로 사용자에게 질문
     ← 사용자 답변
메인 → SendMessage 로 같은 에이전트 재호출 (새 Agent 호출은 문맥을 잃음)
     ← 개정본
       ... 미해결이 없어지고 사용자가 승인할 때까지 반복 ...
사용자 승인 → 상태 '초안' → '승인됨' → 비로소 spring-dev 시작
```
- **승인 없이 구현을 시작하지 않는다. 승인은 사용자만 한다.**
- 산출물: `docs/requirements/<module>/<feature>.md`
- 모호한 건 **빈칸이 아니라 `(가정)`으로 채운다** — 빈칸은 답을 못 받는다. 사람은 고칠 대상이 있어야 고친다.

### 5단계: EARS 표기법 (요구사항 문서의 문법)
서술은 한국어, 키워드는 영어 대문자. 패턴 6개:

| 유형 | 형태 |
|---|---|
| 유비쿼터스 | THE 시스템 SHALL \<동작\> |
| 이벤트 | WHEN \<트리거\>, THE 시스템 SHALL \<동작\> |
| 상태 | WHILE \<상태\>, THE 시스템 SHALL \<동작\> |
| 선택 | WHERE \<기능이 포함된 경우\>, THE 시스템 SHALL \<동작\> |
| **예외** | **IF \<트리거\>, THEN THE 시스템 SHALL \<동작\>** ← 원치 않는 동작 |
| 복합 | 위 조합 |

EARS를 고른 이유는 표기법이 예뻐서가 아니라 **패턴 하나가 예외 경로 전용**이라 비어 있으면 눈에 보이기 때문이다. 이 프로젝트가 반복적으로 놓치던 게 정확히 거기(정책 위반 시 상태코드·메시지, 중복, 만료)였다.

**요구사항 ID(예: `USER-PW-3`)가 하류로 흐른다** — 이게 문서를 장식이 아니게 만드는 유일한 장치:
- `test-writer` → 인수 기준이 곧 테스트 케이스. ID를 `@DisplayName`에 접두로 달고 **커버 못 한 ID를 보고**
- `module-verifier` → 인수 기준이 곧 검증 시나리오. **여기서는 문서가 기준이고 코드가 검증 대상**

### 6단계: 에이전트 16개

**코드 (9)**
| 에이전트 | 역할 | 수정 범위 |
|---|---|---|
| requirements-writer | 구현 **전** EARS 요구사항 정의 | 문서만 |
| spring-dev | Spring 기능 구현 (컨트롤러·서비스·DTO) | 코드 |
| test-writer | JUnit/MockMvc 테스트 작성 | 코드 |
| test-data | 목업·시드·픽스처 | 코드 |
| module-verifier | 컴파일→bootRun→엔드포인트 호출→응답 검증 | **읽기전용** |
| api-documenter | `docs/api/<module>.md` 명세 (구현 **후**) | 문서만 |
| spring-optimizer | 트랜잭션·open-in-view·커넥션풀·설정 | 코드 |
| jpa-query-tuner | SQL/JPA·N+1·fetch join·인덱스 | 코드 |
| code-commenter | 로직 의도('왜') 주석 | 주석만 |

**인프라 (5)**: dockerfile-manager / compose-manager / nginx-proxy / github-actions / docker-runner(**읽기전용**)

**공통 (2)**: context-keeper(`.claude/`만) / commit-writer(**git만**, push 안 함, 사용자가 요청할 때만)

### 7단계: 설계 원칙 3개 (다이어그램에 함께 드러나면 좋음)
1. **진실의 출처 분리** — 모듈 사실(포트·엔드포인트·정책)은 `modules/<module>.md`가 **유일한 출처**이고 context-keeper가 유지. 에이전트 정의에는 역할 지침만. 서브에이전트는 메인 컨텍스트를 물려받지 않으므로 **각자 직접 Read**한다(사본을 만들면 낡는다).
2. **검증자는 도구로 막았다** — module-verifier·docker-runner는 Write/Edit 도구가 아예 없어서 **자기가 검증할 대상을 고칠 수 없다**(이해충돌을 구조로 차단).
3. **세 문서의 시점이 다르다** — `requirements/`=구현 전 의도(계약), `docs/api/`=구현 후 사실, `modules/`=지금 코드의 사실. **셋이 어긋나면 버그가 아니라 신호**이고, 각자 자기 시점의 진실을 쓰고 어긋남을 보고한다.

### 만들어줬으면 하는 것
**단일 HTML 인터랙티브 아티팩트**로:
- **세로 흐름**: 세션 시작 → 훅 주입 → 모듈 선택(분기 5개) → 작업 유형 분기 → 표준 흐름 → 검증 → context-keeper
- **요구사항 협의 루프의 ⇄ 왕복을 시각적으로 확실히 구분** (다른 단계는 단방향 화살표, 여기만 순환). 이 플로우의 핵심 포인트다.
- 에이전트 노드를 **수정 범위로 색 구분**: 코드 / 문서만 / 읽기전용 / git만
- 노드를 클릭하면 그 에이전트의 역할·경계가 패널에 뜨는 정도의 인터랙션
- **"승인 게이트"**(사용자 승인 전에는 spring-dev로 못 넘어감)를 명시적 관문으로 표현
- 라이트/다크 양쪽에서 읽히게, 한국어 UI

## 붙여넣을 프롬프트 (여기까지)
