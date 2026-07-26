"""판정 프롬프트 골격 + 응답 스키마 지시.

⚠️ **프롬프트 문안 자체는 `accuracy-tuner` 소관이다**(요구사항 "범위 - 제외").
이 파일이 계약으로 고정하는 것은 다음 셋뿐이고, 나머지 판정 기준 문안은 자리만
잡아 둔 **플레이스홀더**다:

1. **출력 JSON 스키마** — 파서(`core/parser.py`)가 이 형태로만 파싱한다(BRK-LLM-11).
2. **축 식별자 3개** — `abuse`·`spam`·`offtopic` 외 값 금지(BRK-LLM-1b/11b).
3. **`offtopic` 은 본문 단위에만**(BRK-LLM-19) · **두 축이 겹치면 `abuse` 우선**(BRK-LLM-1c).

문안을 고치면 `PROMPT_VERSION` 을 **반드시 함께 올린다**. 이 값이 폐기 판정 결과에
실려 나가고(BRK-LLM-18) `accuracy-tuner` 가 과거 산출물을 구간으로 갈라 측정하는
근거가 된다 — 버전을 안 올리면 서로 다른 기준의 판정이 한 구간에 섞인다.
"""

from typing import Sequence

from bedrock.schemas.judgement import JudgeItem

# 프롬프트 문안이 바뀌면 반드시 올린다(BRK-LLM-18).
# v0 = 구조만 잡은 초기 골격. 실제 판정 기준 튜닝 전이다.
PROMPT_VERSION = "v0"

# 캐시 breakpoint 앞에 놓이는 고정 접두부(PIPE-2SB-64).
# ⚠️ 여기에 판정 대상 텍스트를 넣으면 프리픽스가 매 호출 달라져 캐시가 죽는다(PIPE-2SB-65).
SYSTEM_PROMPT = """당신은 한국 야구 커뮤니티 게시글을 검열하는 판정기다.
입력은 이미 사전·정규식 기반 1차 검열을 통과한 텍스트이며, 당신은 2차 판정만 한다.

# 판정 축
- abuse: 욕설·비속어·인신공격·혐오. 문맥 의존 비하와 사전에 없는 신조 표기를 포함한다.
- spam: 상품·서비스 판매 유도, 외부 사이트 가입 유도, 도박·불법 홍보.
- offtopic: 야구·야구 인물·야구 문화와 전혀 관련이 없는 내용.

# 축 적용 범위
- abuse, spam: 본문(body)과 댓글(comment) 모두에 적용한다.
- offtopic: **본문(body)에만 적용한다. 댓글(comment)에는 절대 적용하지 않는다.**
  댓글은 짧고 부모 글 맥락에 의존하므로 단독으로 주제 관련성을 판정할 수 없다.
- 한 단위가 두 축에 모두 해당하면(예: 욕설이 섞인 광고) axis 는 abuse 로 기록한다.

# 판정 원칙
- 이 단계는 통과율을 깎는 방향으로만 작동한다. 애매하면 통과시킨다.
- 야구 커뮤니티의 정상적인 잡담·밈·한 줄 응원·구매 문의·경기력 비판·관용적 과장
  표현은 폐기 대상이 아니다.
- 짧다는 이유만으로 offtopic 판정하지 않는다.

<!-- TODO(accuracy-tuner): 아래 두 절이 실제 판정 품질을 결정하는 자리다.
     - 축 A/B 의 폐기 케이스 예시(BRK-LLM-20~27)
     - 축 A/B 의 통과 케이스 예시(BRK-LLM-30~35, 40~45)
     대표 케이스는 test-data 가 실표본(validation/pattern/success/)에서 확정한다.
     지금은 비어 있으며, 이 상태의 판정 품질은 측정된 바 없다. -->

# 폐기 케이스 (튜닝 대상 — 미작성)

# 통과 케이스 (튜닝 대상 — 미작성)

# 출력 형식
반드시 아래 JSON 만 출력한다. 설명·코드펜스·다른 텍스트를 덧붙이지 않는다.
{"results": [{"index": 1, "is_valid": true, "axis": null, "message": ""}]}

- results 의 항목 수는 입력 항목 수와 정확히 같아야 하고, index 는 입력 번호와 1:1로 대응한다.
- is_valid 가 false 일 때만 axis 를 "abuse" | "spam" | "offtopic" 중 하나로 채운다.
- is_valid 가 true 이면 axis 는 null 이고 message 는 빈 문자열로 둔다.
- 폐기일 때 message 는 사람이 읽을 수 있는 한 줄 사유를 한국어로 쓴다(예: "특정 인물 인신공격").
- 정의된 세 값 외의 axis 를 만들어내지 않는다.
"""

# unit_kind → 프롬프트에 노출할 라벨.
_UNIT_LABELS = {
    "body": "body(본문)",
    "comment": "comment(댓글)",
}


def build_user_message(items: Sequence[JudgeItem]) -> str:
    """판정 대상 항목들을 번호가 붙은 사용자 메시지로 렌더한다.

    캐시 breakpoint **뒤에** 놓이는 가변부다(PIPE-2SB-65). 게시글 메타(engagement·
    sourceUrl·author 등)는 담지 않는다 — 판정 대상 텍스트와 `unit_kind` 뿐이다
    (PIPE-2SB-45).
    """
    lines = ["다음 {count}개 단위를 각각 독립적으로 판정하라.".format(count=len(items)), ""]
    for index, item in enumerate(items, start=1):
        label = _UNIT_LABELS.get(item.unit_kind, item.unit_kind)
        lines.append("[{index}] unit_kind={label}".format(index=index, label=label))
        lines.append(item.text)
        lines.append("")
    lines.append('결과를 {"results": [...]} JSON 하나로만 출력하라.')
    return "\n".join(lines)


def build_system_blocks(use_cache: bool = True) -> list:
    """Converse API 의 system 블록을 만든다.

    캐시 breakpoint 를 시스템 프롬프트 **뒤**에 둬 접두부가 캐싱되게 한다
    (PIPE-2SB-64/65). 판정 대상 텍스트는 그 뒤 user 메시지로 가므로 프리픽스가
    호출마다 동일하다.
    """
    blocks = [{"text": SYSTEM_PROMPT}]
    if use_cache:
        blocks.append({"cachePoint": {"type": "default"}})
    return blocks
