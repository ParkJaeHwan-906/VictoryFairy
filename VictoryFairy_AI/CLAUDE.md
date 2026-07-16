# VictoryFairy_AI

한국어 텍스트를 **검열 → 형태소·개체명 추출 → 집계**하는 파이프라인.

## 모듈 (컨텍스트 격리 단위)

- **validation** — 검열(욕설·비속어 필터). 정규화 + 룰/정규식.
- **analysis** — 형태소(명사·동사, Kiwi) + 개체명(이름·지명·기관·날짜, NER) 추출.
- **pipeline** — 파일 기반 배치 러너(검열 → 분석 → 집계).

## 작업 규칙 (하네스)

1. **컨텍스트 격리** — 작업 시작 전에 **어느 모듈에서 작업할지 정하고, 해당 모듈 문서(`docs/modules/<module>.md`)만 로드**한다.
2. **역할 분할** — 메인은 오케스트레이터만 하고, 실제 작업은 **역할별 서브에이전트 15개**(`.claude/agents/`)에 위임한다.
   - 코드: `requirements-writer` · `fastapi-dev` · `pipeline-dev` · `dict-curator` · `accuracy-tuner` · `perf-optimizer` · `test-writer` · `test-data` · `module-verifier` · `api-documenter` · `code-commenter`
   - 인프라: `dockerfile-manager` · `compose-manager` · `docker-runner`
   - 공통: `context-keeper`
3. **진실의 출처** — 모듈 사실은 `docs/modules/<module>.md` 가 유일한 출처이고 `context-keeper` 가 유지한다. 에이전트 정의엔 역할 지침만 둔다(사본을 두면 낡는다).
4. **시점 분할** — 새 기능은 코드보다 **계약**이 먼저다. `requirements-writer` 가 `docs/requirements/<module>/<feature>.md` 에 EARS로 쓰고 **사용자가 승인해야** 구현이 시작된다(`/requirements`).
   - 검열·NER은 **100%가 없는 영역**이라 요구사항을 **결정적 계약**(라우트·스키마 — EARS 그대로)과 **판정 요구사항**("이건 잡아야/이건 잡히면 안 됨" — 케이스 + 목표치)으로 갈라 쓴다. "모든 우회 표기를 탐지한다"는 거짓 계약이다.
   - **재현율 요구사항엔 짝이 되는 오탐 요구사항이 반드시 붙는다.** 한쪽만 있으면 "오탐은 얼마든 늘려도 된다"는 뜻이 된다.

(SessionStart Hook이 이 절차를 유도한다. 상세: `docs/harness-strategy.md`)

## 개발 환경 주의

- **pytest 없이** stdlib 로 테스트한다: `python3 tests/test_validation.py` (의도된 설계 — 임의로 pytest 설치 금지)
- 로컬 `.venv` 는 **Python 3.9.6**, Dockerfile 은 **3.11**. 3.10+ 문법(`X | None`, `match`)을 쓰면 로컬에서만 깨진다.
- `analysis` 는 Kiwi + KoELECTRA(torch) 로딩 때문에 **무겁고 첫 실행에 모델을 다운로드**한다.
- `data/` 는 파이프라인의 실제 산출물이다. 특히 `crawled_data.txt` 는 **입력 원본이라 복구 불가**.

## 문서

- 전체 구조: `docs/architecture.md`
- 하네스 전략: `docs/harness-strategy.md`
- 기능 전략: `docs/feature-strategy.md`
- 모듈별 상세: `docs/modules/{validation,analysis,pipeline}.md`

## 실행

```bash
python -m pipeline.run_validation   # 검열
python -m pipeline.run_analysis     # 형태소+NER
python -m pipeline.run_aggregate    # 인명 집계
uvicorn validation.main:app --port 8000
uvicorn analysis.main:app --port 8001
```
