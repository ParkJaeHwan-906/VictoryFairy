"""Bedrock 2차 검열 판정 스키마 (BRK-LLM-1/1b/4/15b/16b/18).

`judge(items)` 의 입출력 계약을 여기에 모은다. 러너(pipeline)는 이 스키마만 보고
success/failed 라우팅을 결정한다(PIPE-2SB-16).
"""

from typing import Literal, Optional

from pydantic import BaseModel, ConfigDict, Field

# 판정 단위 종류(BRK-LLM-1). title 을 본문 자리로 판정할 때도 "body" 다(PIPE-2SB-8b).
UnitKind = Literal["body", "comment"]

# 판정 축(BRK-LLM-1b). 열린 문자열 금지 — accuracy-tuner 가 축별로 분리 측정한다.
Axis = Literal["abuse", "spam", "offtopic"]

# 파서가 모델 응답을 검증할 때 쓰는 런타임 값 목록(BRK-LLM-11b).
AXIS_VALUES = ("abuse", "spam", "offtopic")

# 본문 단위에만 적용되는 축(BRK-LLM-19). 댓글은 짧고 부모 글 맥락에 의존해
# 단독으로 주제 관련성을 판정할 수 없다.
BODY_ONLY_AXES = ("offtopic",)

# 고정 메시지 — 산출물에서 서로 구분돼야 하므로 상수로 못박는다(BRK-LLM-16b).
PASS_MESSAGE = "2차 검열을 통과했습니다."
FALLBACK_MESSAGE = "LLM 판정 실패(폴백 통과)"  # BRK-LLM-15
DRY_RUN_MESSAGE = "DRY_RUN(호출 없음)"  # BRK-LLM-16b


class JudgeItem(BaseModel):
    """판정 대상 단위 하나(BRK-LLM-1).

    한 배치에 본문 1 + 댓글 N 이 섞여 들어오므로 `unit_kind` 는 배치 인자가 아니라
    항목별 인자다(PIPE-2SB-14).
    """

    text: str = Field(..., description="판정할 텍스트(본문·제목·댓글 본문)")
    unit_kind: UnitKind = Field(
        ...,
        description='판정 단위 종류. "body"=본문 자리(빈 본문 글의 제목 포함), "comment"=댓글',
    )


class JudgeResult(BaseModel):
    """단위 하나의 판정 결과(BRK-LLM-1/1b/15b/18)."""

    # `model_id` 는 pydantic 의 보호 네임스페이스(`model_`)와 이름이 겹쳐 경고가 난다.
    # 필드명은 BRK-LLM-18 의 `modelId` 에 맞춘 것이라 바꾸지 않고 보호 네임스페이스를 해제한다.
    model_config = ConfigDict(protected_namespaces=())

    is_valid: bool = Field(..., description="통과 여부. 러너의 라우팅 코드는 이 값만 본다")
    axis: Optional[Axis] = Field(
        default=None,
        description='폐기 축("abuse"·"spam"·"offtopic"). 통과일 때는 null(BRK-LLM-1b)',
    )
    message: str = Field(..., description="사람이 읽을 수 있는 판정 사유(BRK-LLM-4)")
    fallback: bool = Field(
        default=False,
        description="폴백 통과 여부. LLM 판정 실패(BRK-LLM-15) 또는 DRY_RUN(BRK-LLM-16b)",
    )
    model_id: Optional[str] = Field(
        default=None,
        description="폐기 판정에 사용한 추론 프로파일 ID(BRK-LLM-18). 통과 시 null",
    )
    prompt_version: Optional[str] = Field(
        default=None,
        description="폐기 판정에 사용한 프롬프트 버전(BRK-LLM-18). 통과 시 null",
    )


class BatchUsage(BaseModel):
    """한 배치 호출에서 소비한 토큰 집계.

    러너가 단가를 곱해 `bedrock_spend_usd` 에 누적하고(PIPE-2SB-61) 캐시 적중
    토큰 수를 요약 로그로 남긴다(PIPE-2SB-44). ⚠️ 재시도한 호출의 토큰도 모두
    더한다 — 실패한 호출도 과금되기 때문이다.
    """

    call_count: int = Field(default=0, description="실제로 발생한 모델 호출 횟수(재시도 포함)")
    input_tokens: int = Field(default=0, description="입력 토큰 수")
    output_tokens: int = Field(default=0, description="출력 토큰 수")
    cache_read_input_tokens: int = Field(default=0, description="캐시에서 읽은 입력 토큰 수")
    cache_write_input_tokens: int = Field(default=0, description="캐시에 쓴 입력 토큰 수")


class BatchJudgement(BaseModel):
    """배치 판정 결과 묶음.

    `judge()` 의 계약(BRK-LLM-1)은 결과 목록만이지만, 러너는 비용 누적을 위해
    호출 단위 `usage` 가 필요하다(PIPE-2SB-61). 계약을 바꾸지 않으려고 결과 목록을
    반환하는 `judge()` 와 usage 를 함께 주는 `judge_batch()` 를 나눠 뒀다.
    """

    results: list[JudgeResult] = Field(
        default_factory=list, description="입력과 같은 길이·같은 순서의 판정 결과(BRK-LLM-12)"
    )
    usage: BatchUsage = Field(default_factory=BatchUsage, description="토큰 소비 집계")
    truncated_count: int = Field(
        default=0, description="길이 상한으로 절단된 항목 수(BRK-LLM-10)"
    )
