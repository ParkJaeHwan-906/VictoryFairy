# VictoryFairy_AI

한국어 텍스트를 **검열 → 형태소·개체명 추출 → 집계**하는 파이프라인.

## 모듈 (컨텍스트 격리 단위)

- **validation** — 검열(욕설·비속어 필터). 정규화 + 룰/정규식.
- **analysis** — 형태소(명사·동사, Kiwi) + 개체명(이름·지명·기관·날짜, NER) 추출.
- **pipeline** — 파일 기반 배치 러너(검열 → 분석 → 집계).

## 작업 규칙 (하네스)

작업 시작 전에 **어느 모듈에서 작업할지 정하고, 해당 모듈 문서(`docs/modules/<module>.md`)만 로드**해 컨텍스트를 격리한다.
(SessionStart Hook이 이 절차를 유도한다. 상세: `docs/harness-strategy.md`)

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
