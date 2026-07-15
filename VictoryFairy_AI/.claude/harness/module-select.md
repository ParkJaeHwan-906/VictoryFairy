# VictoryFairy_AI — 모듈 컨텍스트 격리 + 에이전트 분배 규칙 (SessionStart 주입)

## 1. 모듈 선택

이 프로젝트는 3개 모듈로 구성된다: **validation**(검열), **analysis**(형태소·NER 추출), **pipeline**(배치 러너).

본격적인 코드 작업을 시작하기 전에:
1. `AskUserQuestion` 으로 사용자에게 **어느 모듈에서 작업할지** 묻는다 (validation / analysis / pipeline / 여러 모듈).
2. 선택된 모듈의 문서 `docs/modules/<module>.md` **하나만** 먼저 읽어 컨텍스트를 로드한다.
3. 그 모듈·기능 범위 밖의 파일은 꼭 필요할 때만 참조하고, 다른 모듈 컨텍스트를 불필요하게 끌어오지 않는다.

전체 구조·전략은 `docs/README.md`, `docs/architecture.md`, `docs/harness-strategy.md`, `docs/feature-strategy.md` 참고.

예외: 사용자가 **이미 특정 파일/모듈을 지목**했거나 **단순 질문**이면 이 절차를 생략하고 바로 진행한다.

## 2. 작업 분배 — 멀티 에이전트

너는 오케스트레이터다. 직접 다 하지 말고 Agent 도구로 전문 에이전트에 위임한다(`subagent_type=<이름>`).

**중요**: 서브에이전트는 네 컨텍스트를 물려받지 않는다. 다만 각 에이전트는 자기 정의에 "작업 전 `docs/modules/<module>.md` 를 먼저 Read하라"고 지시받았으므로, 너는 프롬프트에 **"어느 모듈인지 + 무엇을/왜"**만 명확히 주면 된다. 모듈 사실을 프롬프트에 길게 복사하지 마라 — 에이전트가 직접 읽는 게 항상 최신이다.

**진실의 출처**: 모듈 사실(진입점·라우트·기능 단위·한계)은 `docs/modules/<module>.md` 가 유일한 출처이고 `context-keeper` 가 유지한다. 에이전트 정의(`.claude/agents/*.md`)에는 역할 지침만 있다. 이 분리를 깨고 사실을 여기저기 복사하지 마라.

### 코드
- **fastapi-dev** — validation·analysis 의 FastAPI 기능 구현 (라우트·스키마·서비스·core)
- **pipeline-dev** — pipeline 배치 러너 (파일 입출력 배선. 로직은 각 모듈 소관)
- **dict-curator** — 사전 8개 JSON 관리 (banned_words·exceptions·normalization / persons·surnames·person_stopwords·organizations·aliases)
- **accuracy-tuner** — 오탐/미탐 측정·분석, 뷰 매칭 전략, gazetteer 후처리. **이 프로젝트의 핵심 관심사**
- **perf-optimizer** — 모델 로딩·async 블로킹·torch 메모리·배치 (결과가 바뀌면 accuracy-tuner 일)
- **test-writer** — 테스트 코드 (⚠️ pytest 없이 stdlib 로 돌린다)
- **test-data** — 테스트 문장 세트 (잡혀야 하는 것 + 잡히면 안 되는 것)
- **module-verifier** — uvicorn 기동→엔드포인트 호출 / 러너 실행→산출물 검증 (읽기전용)
- **api-documenter** — `docs/api/<module>.md` 명세 생성·갱신 (마크다운만)
- **code-commenter** — 로직 의도('왜') 주석·docstring (로직 변경 금지)

### 인프라
- **dockerfile-manager** — 3개 Dockerfile (validation 경량 유지 / analysis torch CPU 분리)
- **compose-manager** — docker-compose.yml (pipeline 은 profile: batch 로 격리)
- **docker-runner** — 실제 빌드·기동·검증 후 정리 (읽기전용, 인프라 검증 담당)

### 공통
- **context-keeper** — `docs/modules/<module>.md` 를 코드와 일치하게 유지

의존 없는 작업은 한 메시지에서 병렬로 띄운다. 단순 질문·읽기·한 줄 수정은 위임 없이 직접 처리한다.

## 3. 표준 흐름

- **기능 구현**: fastapi-dev(또는 pipeline-dev) → test-writer(+필요시 test-data) → module-verifier → API면 api-documenter → context-keeper
- **정확도 작업**: accuracy-tuner 가 측정·판단 → 사전으로 풀 것은 dict-curator 위임 → test-writer 로 회귀 확인 → context-keeper 로 "한계" 갱신
- **인프라**: dockerfile-manager / compose-manager → docker-runner 로 검증
- **주의**: 여러 에이전트가 같은 파일을 동시에 고치면 충돌한다. 파일이 겹치면 순차로 돌린다.

작업이 끝나면 규모에 맞게 검증(코드=module-verifier, 인프라=docker-runner)하고, 기능·한계가 바뀌었으면 context-keeper 로 모듈 문서를 갱신한다. 사용자가 `/verify` 로 직접 검증할 수도 있다.

## 4. 이 환경의 제약 — 검증을 지어내지 말 것

- **pytest·ruff 가 없다.** 테스트는 `python3 tests/test_validation.py` 로 stdlib 만으로 돌아간다. **의도된 설계다** — 임의로 설치하지 마라.
- **로컬 `.venv` 는 Python 3.9.6, Dockerfile 은 3.11.** 현재 코드는 3.9 호환이지만 3.10+ 문법(`X | None`, `match`)을 쓰면 로컬에서만 깨진다.
- 로컬 패키지는 `.venv` 에만 있다(fastapi 0.111.0 / kiwipiepy / torch). 인터프리터는 `.venv/bin/python`.
- **docker**: Docker.app 이 `~/Desktop` → `/Applications` 로 이동되어 심링크(`/usr/local/bin/docker`, `~/.docker/cli-plugins/*`)가 끊어져 있다. 전체 경로로 실행하거나 심링크를 고쳐야 한다. 데몬이 꺼져 있을 수 있고(`open -a Docker` 는 사용자에게 요청), Claude Code 샌드박스가 소켓을 막으므로 `dangerouslyDisableSandbox` 가 필요하다.
- **analysis 는 무겁다** — Kiwi + KoELECTRA(torch) 로딩 + 첫 실행 시 모델 다운로드. 느리다고 실패로 단정하지 마라. 네트워크가 없으면 모델을 못 받는다.
- **AI 를 배포하는 CI 워크플로가 없다.** 저장소 루트 `.github/workflows/deploy.yml` 은 BE 만 다룬다.
- **`data/` 는 실제 산출물이다.** 특히 `crawled_data.txt` 는 입력 원본이라 러너가 만들지 못한다 — 덮어쓰기 전에 확인하라.
