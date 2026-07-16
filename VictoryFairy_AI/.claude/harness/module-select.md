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
- **requirements-writer** — 구현 전 EARS 요구사항 정의 (`docs/requirements/<module>/<feature>.md`). 문서만, 코드·사전 안 씀. 사용자에게 직접 질문하지 못하니 '미해결 질문'을 돌려주고, 묻는 건 네 몫이다
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
- **commit-writer** — 워킹 트리 변경을 의도 단위로 쪼개 커밋 (사용자가 커밋을 요청했을 때만 호출. push 는 하지 않는다)

의존 없는 작업은 한 메시지에서 병렬로 띄운다. 단순 질문·읽기·한 줄 수정은 위임 없이 직접 처리한다.

## 3. 표준 흐름

- **기능 구현**: requirements-writer → (사용자 승인) → fastapi-dev(또는 pipeline-dev) → test-writer(+필요시 test-data) → module-verifier → API면 api-documenter → context-keeper
- **정확도 작업**: accuracy-tuner 가 측정·판단 → 사전으로 풀 것은 dict-curator 위임 → test-writer 로 회귀 확인 → context-keeper 로 "한계" 갱신
  - 단, **판정 규칙 자체를 새로/다르게 정의**하는 요청(무엇을 잡고 무엇을 안 잡을지가 바뀜)이면 accuracy-tuner 앞에 requirements-writer 를 태운다. 튜닝은 목표가 있어야 한다.

### 3-1. 요구사항 단계 — 코드보다 먼저

**언제 태우나**: 새 라우트·판정 규칙·러너 단계가 생기는 '기능 구현' 요청. 버그 수정·오타·한 줄 수정·리팩터링·질문·**사전에 단어 몇 개 추가(→dict-curator)** 는 태우지 않는다 — 자명한 일에 계약을 쓰는 건 하네스가 막으려는 그 비대함이다. 애매하면 지어내 판단하지 말고 물어라("요구사항부터 정리할까요, 바로 구현할까요?").

**루프**: requirements-writer 호출 → 돌아온 '미해결 질문'과 '(가정)' 항목을 AskUserQuestion 으로 사용자에게 묻는다(네가 지어내 답하면 이 단계의 존재 이유가 사라진다) → 답을 들고 SendMessage 로 같은 에이전트를 다시 불러 개정(새 Agent 호출은 문맥을 잃는다) → 미해결이 없어지고 사용자가 승인할 때까지 반복. **승인 없이 fastapi-dev·pipeline-dev 를 부르지 마라. 승인은 사용자만 한다.**

**이 프로젝트에서 특히 볼 것**:
- 요구사항은 **결정적 계약**(라우트·스키마·응답 — EARS 그대로)과 **판정 요구사항**("이건 잡아야 / 이건 잡히면 안 됨" — 케이스 + 목표치)으로 갈라 쓴다. 검열·NER 에 "모든 우회 표기를 탐지한다"고 쓰면 **달성도 검증도 불가능한 거짓 계약**이다.
- **판정 요구사항에 오탐 쪽이 비어 있으면 반려하라.** 재현율만 있으면 "오탐은 얼마든 늘려도 된다"는 뜻이 되고, 뷰 매칭이 단어 경계를 없애는 이 시스템은 **구조적으로** 그 방향으로 무너진다.
- **모듈 문서 "한계"와 충돌하면 최우선 보고 대상.** 과거에 대량 오탐으로 기각된 방식을 계약으로 되살리는 것일 수 있다 — "예전에 이래서 기각됐는데 그래도 갈까요"를 사용자에게 물어라.
- 사용자만 답할 수 있는 대표 질문: **오탐과 미탐 중 어느 쪽이 더 비싼가.** 이게 없으면 목표치를 정할 수 없다.

**인계**: 승인된 문서 '경로'를 하류에 넘긴다(본문을 프롬프트에 복사하지 말 것 — 사본은 낡는다).
- **판정 요구사항이 있으면 test-data 를 먼저** — 대표 케이스만으론 측정이 안 된다. "각 ID의 케이스 클래스를 본격 세트로 확장하라".
- test-writer: "요구사항 ID와 테스트를 1:1로 대응시키고 미커버 ID를 보고하라"
- module-verifier: "인수 기준과 대조하라"
- 목표치에 못 미치면 accuracy-tuner 에 문서 경로를 주고 달성을 맡긴다(요구사항이 목표, 달성은 그쪽 일).

사용자가 `/requirements` 로 직접 태울 수도 있다.
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
