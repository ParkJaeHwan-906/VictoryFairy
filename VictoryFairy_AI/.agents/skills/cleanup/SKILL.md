---
name: cleanup
description: "지정한 범위의 구버전 흔적을 정리 (죽은 코드·명세 복창 docstring·낡은 문서 삭제 → 검증)"
---

`cruft-sweeper`를 호출해 **더 이상 사실이 아닌 것**을 지워라.

## 범위를 먼저 정한다 (생략 불가)
인자: `사용자가 스킬 호출과 함께 전달한 인자`. 비어 있으면 `git diff --name-only main...HEAD`(없으면 최근 커밋)로 좁혀 **그 범위를 프롬프트에 명시**한다.

**"저장소 전체를 정리해줘"로 태우지 마라.** 삭제는 되돌리기 비싸고, 범위가 넓을수록 근거 없는 삭제가 섞인다. 넓은 요청이면 모듈 단위로 **쪼개서 여러 번** 돌린다.

## 무엇이 대상인지
- **주석·docstring** — 시그니처·명세 복창, 거짓이 된 설명, 주석 처리된 코드, 이력 주석.
- **코드** — 대체된 구버전 구현, 미참조 헬퍼, 미사용 import.
- **문서** — 주인 없는 `docs/**`의 낡은 절(`architecture` · `deployment` · `SCHEDULES` · `question-generation` · `feature-strategy` · `README`).

대상이 **아닌 것** (프롬프트에 명시할 것):
- **`analysis` 모듈** — 배선에서 빠졌을 뿐 **의도적으로 살려 둔 코드**다. "아무도 안 부른다"는 삭제 근거가 아니다.
- **사전 8개 JSON**(`dict-curator`) · **`data/` 산출물**(`crawled_data.txt`는 복구 불가).
- **오탐을 막으려 좁힌 조건의 근거 주석**(`accuracy-tuner`의 측정 결론) — 지우면 다음 사람이 오탐을 부활시킨다.
- `docs/requirements/**`(승인된 계약) · `docs/api/*.md`(`api-documenter`) · `docs/modules/*.md`(`context-keeper`) · `CLAUDE.md`·`.claude/**`(사람). 어긋남을 찾으면 고치지 말고 **보고**하게 한다.

## 전달할 것
- **범위**(파일·모듈)와 **왜 지금 정리하는지**(예: "방금 pipeline-dev가 X 단계를 Y로 교체했다").
- 최근 구현 변경이 있으면 그 커밋 해시 — 무엇이 구버전인지 판별하는 근거가 된다.

## 이어서 할 것
- **코드를 지웠으면 검증은 필수다**: `python3 tests/test_validation.py` 등 stdlib 테스트 → 필요하면 `module-verifier`. 판정 로직 근처를 건드렸으면 **판정 결과가 한 케이스도 바뀌지 않았는지**를 보고에서 확인한다.
- `code-commenter`를 같이 돌릴 거면 **반드시 순차**로, `cruft-sweeper` 다음에.
- 보고의 **"안 지움(확인 필요)"** 항목은 사용자에게 그대로 보여준다 — 공개 라우트·스키마 필드·러너 진입점은 BE·Infra가 밖에서 쓰므로 삭제는 **사용자 결정**이다.
