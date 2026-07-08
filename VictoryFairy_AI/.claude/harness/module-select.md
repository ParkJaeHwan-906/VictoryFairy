# VictoryFairy_AI — 모듈 컨텍스트 격리 규칙 (SessionStart 주입)

이 프로젝트는 3개 모듈로 구성된다: **validation**(검열), **analysis**(형태소·NER 추출), **pipeline**(배치 러너).

본격적인 코드 작업을 시작하기 전에:
1. `AskUserQuestion` 으로 사용자에게 **어느 모듈에서 작업할지** 묻는다 (validation / analysis / pipeline / 여러 모듈).
2. 선택된 모듈의 문서 `docs/modules/<module>.md` **하나만** 먼저 읽어 컨텍스트를 로드한다.
3. 그 모듈·기능 범위 밖의 파일은 꼭 필요할 때만 참조하고, 다른 모듈 컨텍스트를 불필요하게 끌어오지 않는다.

전체 구조·전략은 `docs/README.md`, `docs/architecture.md`, `docs/harness-strategy.md`, `docs/feature-strategy.md` 참고.

예외: 사용자가 **이미 특정 파일/모듈을 지목**했거나 **단순 질문**이면 이 절차를 생략하고 바로 진행한다.
