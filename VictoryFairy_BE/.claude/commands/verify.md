---
description: 현재(또는 인자로 지정한) 모듈의 최근 변경을 검증 (gradle 까지 / 이미지 기동까지 — 깊이는 사용자가 고른다)
argument-hint: "[user|quiz|domain|web-support|infra] (생략 시 현재 작업 모듈/diff로 추정)"
---

방금 작업한 것이 의도대로 동작하는지 검증 에이전트를 실행하라.

## 원칙 — 검증 깊이는 **사용자가 고른다**
gradle 검증(컴파일·테스트·bootRun)은 **개발자 머신 환경**에서 돈다. 배포 환경에서만 터지는 문제 — 이미지 빌드 실패, `prod` 프로파일 전용 빈, 컨테이너 네트워크 호스트명(`DB_HOST=mysql`·`SPRING_DATA_REDIS_HOST=redis`), 누락된 환경변수, 실제 스키마 불일치 — 는 구조적으로 못 잡는다. 그래서 이미지 기동이 배포 전 마지막 방어선이지만, **수 분에서 십수 분이 든다.**

**어디까지 갈지 이미 이 세션에서 정해졌으면 그 결정을 따르고, 안 정해졌으면 시작 전에 묻는다** — ① gradle 까지(1단계만) / ② 컨테이너 기동까지(1→2단계). 기본 추천은 ②.
①이면 **2단계를 부르지 말고**, 보고에 "컨테이너 검증은 사용자 선택으로 생략" 과 위에 적힌 미검증 범위를 명시한다. 그냥 "검증 완료"라고 쓰지 마라.
`infra` 대상은 이 선택에서 빠진다 — 2단계가 **유일한** 검증이라 생략하면 검증이 0이 된다.

## 절차

### 1단계 — 대상별 1차 검증
인자: `$ARGUMENTS` (비어 있으면 현재 세션에서 작업 중이던 모듈, 그래도 불명확하면 `git diff --name-only HEAD~1`로 추정)

| 대상 | 1차 검증자 |
|---|---|
| `user` · `quiz` (앱 모듈) | **module-verifier** — gradle 컴파일 → 테스트 → 엔드포인트 대조 → bootRun 후 curl |
| `domain` · `web-support` (포트 없는 공유 모듈) | **module-verifier** — gradle 컴파일 → 테스트까지만. bootRun·curl은 해당 없음 |
| `infra` (Dockerfile · 로컬 compose · CI/CD) | 1차 없음 — 바로 2단계 |

- 어느 쪽인지 불명확하면 `git diff --name-only`로 판단한다: `domain/src/**` → domain, `web-support/src/**` → web-support, 그 외 `*/src/**` → 해당 앱 모듈, `Dockerfile`·`docker-compose.yml`·`.github/workflows/**` → 인프라.

### 2단계 — 이미지 기동 검증 (**사용자가 ②를 택했을 때**, `infra`는 항상)
**docker-runner** — compose config → 이미지 빌드 → 로컬 스택(`--profile prod`) 기동 → actuator readiness + 대상 엔드포인트 curl → 정리.

띄울 대상 판정:
- `user`·`quiz` 변경 → 해당 모듈 이미지.
- `domain`·`web-support` 변경 → **그것을 품는 `user`·`quiz` 이미지**를 띄운다. 포트가 없다는 이유로 범위에서 빼지 마라 — 엔티티·공유 필터의 문제는 오히려 여기서 드러난다.
- `infra` 변경 → 바뀐 대상에 맞춰 전체 스택.
- 양쪽에 걸친 변경(예: 컨트롤러 + Dockerfile)이면 1단계와 2단계를 순서대로 다 돈다.

⚠️ 1단계와 2단계는 **순차**다. 병렬로 띄우지 마라 — gradle bootRun과 컨테이너가 같은 포트(8080·8081)를 다툰다.

## 전달할 것
- **무엇을 바꿨는지**(파일/엔드포인트/기대값). 이게 없으면 검증자가 무엇을 확인해야 할지 모른다.
- 1단계면 `module=<user|quiz|domain|web-support>`, 2단계면 **어느 이미지를 띄워야 하는지**.
- 요구사항 문서가 있으면 그 **경로**(본문 복사 금지).

## 보고
- 1단계·2단계 보고서를 **각각** 요약한다. 2단계를 안 돌았으면 그게 **사용자 선택 때문인지 SKIP 때문인지 구별해서** 적는다 — 둘은 전혀 다른 사실이다.
- **SKIP을 PASS로 뭉뚱그리지 말 것.** 2단계 SKIP 사유는 사실상 셋뿐이다: Docker Desktop 데몬 꺼짐 / macOS 심링크 끊김 / `.env` 없음. **그 외의 이유로 SKIP이 오면 의심하라** — docker·gh·aws·kubectl은 이 환경에서 실제로 동작한다.
- 검증자는 코드를 고치지 않는다(Write/Edit 도구가 없다). 문제가 나오면 담당 에이전트(spring-dev / dockerfile-manager / compose-manager)에 넘길지 사용자에게 물어라.
