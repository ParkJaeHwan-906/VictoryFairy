"""모델 응답 파서 (BRK-LLM-11 · 11b · 12 · 13).

**지정된 JSON 스키마로만 파싱한다.** 자유 텍스트에서 "폐기"/"통과" 같은 단어를 찾아
해석하지 않는다 — 그런 해석은 판정이 아니라 추측이고, 재현 불가능한 오탐을 만든다.

어긋나면 `BedrockSchemaError` 를 올리고, 서비스가 **호출 전체를 재시도**한다
(부분 응답을 억지로 짜맞추지 않는다 — BRK-LLM-13).
"""

import json
import logging
import re
from typing import Any, Optional

from bedrock.core.errors import BedrockSchemaError
from bedrock.schemas.judgement import AXIS_VALUES

logger = logging.getLogger(__name__)

# 모델이 코드펜스로 감싸는 경우가 잦다. **JSON 을 꺼내는 것**까지는 형식 처리이고,
# 자유 텍스트 해석과는 다르다(BRK-LLM-11 은 후자를 금지한 것이다).
_CODE_FENCE = re.compile(r"^\s*```(?:json)?\s*(.*?)\s*```\s*$", re.DOTALL)

# 폐기인데 사유가 비어 있을 때 쓰는 축별 기본 문구.
# BRK-LLM-4 는 빈 문자열을 금지하는데, 이걸 스키마 불일치로 올리면 배치 전체가
# 재시도돼(BRK-LLM-13) 비용이 배치 크기만큼 든다. 모델이 내린 폐기 판정 자체는
# 유효하므로 사유만 보충하고 경고를 남긴다.
_DEFAULT_MESSAGES = {
    "abuse": "부적절한 표현(욕설·비하)으로 판정",
    "spam": "광고·홍보성 내용으로 판정",
    "offtopic": "야구와 무관한 내용으로 판정",
}


class ParsedJudgement:
    """파싱된 판정 항목 하나(스키마 검증을 통과한 상태)."""

    def __init__(self, is_valid: bool, axis: Optional[str], message: str):
        self.is_valid = is_valid
        self.axis = axis
        self.message = message


def _strip_code_fence(raw_text: str) -> str:
    match = _CODE_FENCE.match(raw_text or "")
    if match:
        return match.group(1)
    return (raw_text or "").strip()


def _load_json(raw_text: str) -> Any:
    text = _strip_code_fence(raw_text)
    if not text:
        raise BedrockSchemaError("모델 응답이 비어 있습니다.")
    try:
        return json.loads(text)
    except (ValueError, TypeError) as exc:
        raise BedrockSchemaError("모델 응답이 JSON 이 아닙니다: {exc}".format(exc=exc)) from exc


def _extract_result_list(payload: Any) -> list:
    """`{"results": [...]}` 를 기대하되 최상위 배열도 받는다.

    최상위 배열을 받아 주는 것은 **스키마 해석의 확장이 아니라 껍데기 차이**이고,
    이걸 거절하면 배치 전체 재시도 비용만 든다(BRK-LLM-13 의 알려진 대가).
    그 외 형태는 전부 스키마 불일치다.
    """
    if isinstance(payload, dict):
        results = payload.get("results")
        if not isinstance(results, list):
            raise BedrockSchemaError("응답에 results 배열이 없습니다.")
        return results
    if isinstance(payload, list):
        return payload
    raise BedrockSchemaError("응답 최상위 타입이 객체도 배열도 아닙니다.")


def _order_by_index(results: list, expected_count: int) -> list:
    """`index` 가 있으면 그 값으로 1:1 대응시키고, 없으면 응답 순서를 쓴다(BRK-LLM-12).

    `index` 가 1..N 을 정확히 한 번씩 덮지 않으면 스키마 불일치다 — 순서를 추측해
    맞추면 **다른 텍스트의 판정이 다른 단위에 붙는다.**
    """
    indices = []
    for item in results:
        if not isinstance(item, dict):
            raise BedrockSchemaError("응답 항목이 객체가 아닙니다.")
        indices.append(item.get("index"))

    if all(value is None for value in indices):
        return results

    ordered: dict[int, dict] = {}
    for raw_index, item in zip(indices, results):
        try:
            index = int(raw_index)
        except (TypeError, ValueError):
            raise BedrockSchemaError("응답 항목의 index 가 정수가 아닙니다: {value!r}".format(value=raw_index))
        if index in ordered:
            raise BedrockSchemaError("응답 항목의 index 가 중복됩니다: {index}".format(index=index))
        ordered[index] = item

    if sorted(ordered.keys()) != list(range(1, expected_count + 1)):
        raise BedrockSchemaError(
            "응답 index 집합이 1..{expected} 와 일치하지 않습니다: {actual}".format(
                expected=expected_count, actual=sorted(ordered.keys())
            )
        )
    return [ordered[index] for index in range(1, expected_count + 1)]


def _parse_item(item: dict) -> ParsedJudgement:
    is_valid = item.get("is_valid")
    if not isinstance(is_valid, bool):
        raise BedrockSchemaError("is_valid 가 boolean 이 아닙니다: {value!r}".format(value=is_valid))

    axis = item.get("axis")
    message = item.get("message")
    if message is not None and not isinstance(message, str):
        raise BedrockSchemaError("message 가 문자열이 아닙니다: {value!r}".format(value=message))

    if is_valid:
        # 통과일 때 axis 는 null 이어야 한다(BRK-LLM-1b). 모델이 값을 붙여 보냈어도
        # 통과 결과에서는 의미가 없으므로 버린다(호출 전체를 재시도할 사안이 아니다).
        if axis is not None:
            logger.warning("통과 판정에 axis 가 붙어 있어 무시합니다: %r", axis)
        return ParsedJudgement(is_valid=True, axis=None, message="")

    # BRK-LLM-11b — 정의된 세 값이 아니면 스키마 불일치. 모델이 지어낸 축 이름을
    # 산출물에 그대로 흘리지 않는다.
    if axis not in AXIS_VALUES:
        raise BedrockSchemaError("정의되지 않은 axis 값입니다: {value!r}".format(value=axis))

    text = (message or "").strip()
    if not text:
        # BRK-LLM-4 — 빈 사유 금지. 축별 기본 문구로 보충한다(위 주석 참조).
        logger.warning("폐기 판정에 사유가 비어 있어 기본 문구로 채웁니다(axis=%s)", axis)
        text = _DEFAULT_MESSAGES[axis]

    return ParsedJudgement(is_valid=False, axis=axis, message=text)


def parse_judgements(raw_text: str, expected_count: int) -> list:
    """모델 응답 텍스트를 판정 목록으로 파싱한다.

    - 항목 수가 요청 수와 다르면 스키마 불일치(BRK-LLM-13).
    - 정의 밖의 axis 는 스키마 불일치(BRK-LLM-11b).
    - 요청 순서와 1:1 로 대응시킨다(BRK-LLM-12).
    """
    results = _extract_result_list(_load_json(raw_text))

    if len(results) != expected_count:
        raise BedrockSchemaError(
            "응답 항목 수가 요청 단위 수와 다릅니다: 요청 {expected} / 응답 {actual}".format(
                expected=expected_count, actual=len(results)
            )
        )

    ordered = _order_by_index(results, expected_count)
    return [_parse_item(item) for item in ordered]
