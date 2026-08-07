# VictoryFairy_AI 문서

이 디렉토리는 프로젝트의 구조·전략·모듈 문서를 담는다.
**Claude 호출 시 하네스(harness) 규칙에 따라 작업할 모듈을 먼저 정하고, 해당 모듈 문서만 로드**해 컨텍스트를 격리한다.

## 문서 지도

| 문서 | 내용 |
|---|---|
| [architecture.md](architecture.md) | 현재까지의 전체 구조 (2개 앱 + 배치 파이프라인, 데이터 흐름, 스택) |
| [harness-strategy.md](harness-strategy.md) | 하네스 전략 (모듈 선택 Hook, 컨텍스트 격리 원칙) |
| [feature-strategy.md](feature-strategy.md) | 기능 전략 (검열 / 추출 / 사전 / 집계 방향과 근거) |
| [deployment.md](deployment.md) | 배포 (모듈별 Docker 컨테이너, docker compose) |
| [modules/validation.md](modules/validation.md) | validation 모듈 — 기능 단위별 구조 |
| [modules/analysis.md](modules/analysis.md) | analysis 모듈 — 기능 단위별 구조 |
| [modules/pipeline.md](modules/pipeline.md) | pipeline 모듈 — 기능 단위별 구조 |

## 모듈 3종 요약

- **validation** — 입력 문장 검열(욕설·비속어 필터). 정규화 + 룰/정규식.
- **analysis** — 검열 통과 문장에서 형태소(명사·동사) + 개체명(이름·지명·기관·날짜) 추출.
- **pipeline** — 파일 기반 배치 러너(검열 → 분석 → 집계).

## 하네스 사용법 (요약)

1. Claude 세션 시작 시 SessionStart Hook이 "어느 모듈에서 작업할지" 묻도록 유도한다.
2. 선택된 모듈의 `docs/modules/<module>.md` **하나만** 읽어 컨텍스트를 로드한다.
3. 다른 모듈의 컨텍스트를 불필요하게 끌어오지 않는다. (상세: [harness-strategy.md](harness-strategy.md))
