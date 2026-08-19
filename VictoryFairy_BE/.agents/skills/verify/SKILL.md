---
name: verify
description: "현재(또는 인자로 지정한) 모듈의 최근 변경을 검증 (gradle → 이미지 기동. 마지막은 항상 docker-runner)"
---

방금 작업한 것이 의도대로 동작하는지 검증 에이전트를 실행하라.

## 원칙 — 검증의 마지막은 **항상 이미지 기동**이다
gradle 검증(컴파일·테스트·bootRun)은 **개발자 머신 환경**에서 돈다. 배포 환경에서만 터지는 문제 — 이미지 빌드 실패, `prod` 프로파일 전용 빈, 컨테이너 네트워크 호스트명(`DB_HOST=mysql`·`SPRING_DATA_REDIS_HOST=redis`), 누락된 환경변수, 실제 스키마 불일치 — 는 구조적으로 못 잡는다.
따라서 **어떤 모듈이든 `docker-runner`로 이미지를 빌드해 띄우는 단계로 끝낸다.** 이 단계를 건너뛰고 "검증 완료"라고 보고하지 마라.

## 절차

### 1단계 — 대상별 1차 검증
인자: `사용자가 스킬 호출과 함께 전달한 인자` (비어 있으면 현재 세션에서 작업 중이던 모듈, 그래도 불명확하면 `git diff --name-only HEAD~1`로 추정)

| 대상 | 1차 검증자 |
|---|---|
| `user` · `quiz` (앱 모듈) | **module-verifier** — gradle 컴파일 → 테스트 → 엔드포인트 대조 → bootRun 후 curl |
| `domain` · `web-support` (포트 없는 공유 모듈) | **module-verifier** — gradle 컴파일 → 테스트까지만. bootRun·curl은 해당 없음 |
| `infra` (Dockerfile · 로컬 compose · CI/CD) | 1차 없음 — 바로 2단계 |

- 어느 쪽인지 불명확하면 `git diff --name-only`로 판단한다: `domain/src/**` → domain, `web-support/src/**` → web-support, 그 외 `*/src/**` → 해당 앱 모듈, `Dockerfile`·`docker-compose.yml`·`.github/workflows/**` → 인프라.

### 2단계 — 이미지 기동 검증 (**생략 불가**)
**docker-runner** — compose config → 이미지 빌드 → 로컬 스택(`--profile prod`) 기동 → actuator readiness + 대상 엔드포인트 curl → 정리.

띄울 대상 판정:
- `user`·`quiz` 변경 → 해당 모듈 이미지.
- `domain`·`web-support` 변경 → **그것을 품는 `user`·`quiz` 이미지**를 띄운다. 포트가 없다고 2단계를 건너뛰지 마라 — 엔티티·공유 필터의 문제는 오히려 여기서 드러난다.
- `infra` 변경 → 바뀐 대상에 맞춰 전체 스택.
- 양쪽에 걸친 변경(예: 컨트롤러 + Dockerfile)이면 1단계와 2단계를 순서대로 다 돈다.

⚠️ 1단계와 2단계는 **순차**다. 병렬로 띄우지 마라 — gradle bootRun과 컨테이너가 같은 포트(8080·8081)를 다툰다.

## 전달할 것
- **무엇을 바꿨는지**(파일/엔드포인트/기대값). 이게 없으면 검증자가 무엇을 확인해야 할지 모른다.
- 1단계면 `module=<user|quiz|domain|web-support>`, 2단계면 **어느 이미지를 띄워야 하는지**.
- 요구사항 문서가 있으면 그 **경로**(본문 복사 금지).

## 보고
- 1단계·2단계 보고서를 **각각** 요약한다. 2단계가 없으면 검증은 끝난 게 아니다.
- **SKIP을 PASS로 뭉뚱그리지 말 것.** 2단계 SKIP 사유는 사실상 셋뿐이다: Docker Desktop 데몬 꺼짐 / macOS 심링크 끊김 / `.env` 없음. **그 외의 이유로 SKIP이 오면 의심하라** — docker·gh·aws·kubectl은 이 환경에서 실제로 동작한다.
- 검증자는 코드를 고치지 않는다(Write/Edit 도구가 없다). 문제가 나오면 담당 에이전트(spring-dev / dockerfile-manager / compose-manager)에 넘길지 사용자에게 물어라.
