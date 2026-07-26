"""bedrock 모듈(judge 서비스 · 파서 · 설정 · 클라이언트 예외 분류) 단위 테스트.

pytest 없이 stdlib 로만 실행:  `.venv/bin/python3 tests/test_bedrock.py`
(이 파일은 `pydantic`·`pydantic_settings`·`boto3`/`botocore` 를 import 하는 경로를
거친다 — 로컬 시스템 python3(3.9)에는 없을 수 있으니 `.venv/bin/python3` 로 실행할 것.
`boto3.client(...)` 를 실제로 호출하지는 않는다 — `bedrock.core.client.converse`/
`build_bedrock_client` 를 이 파일이 직접 몽키패치해 실제 네트워크 호출을 대체한다.)

⚠️ 이 파일은 실제 Bedrock 을 호출하지 않는다. `bedrock.core.client` 모듈의
`converse`/`build_bedrock_client` 를 페이크로 교체해 서비스 계층(재시도·폴백·
라우팅) 로직만 검증한다. `BedrockJudgeService` 는 매 테스트마다 **독립된
설정 인스턴스**로 새로 만들어 전역 싱글턴(`bedrock.bedrock_settings`/
`bedrock.judge_service`)을 건드리지 않는다 — 테스트 간 전역 상태 오염이 없다.

요구사항 ID 대응 (docs/requirements/bedrock/llm-validation.md):
  BRK-LLM-1/12   test_judge_batch_preserves_length_and_reorders_by_response_index
  BRK-LLM-19     test_offtopic_axis_on_comment_is_reverted_to_pass
  BRK-LLM-11b    test_parser_rejects_undefined_axis_value,
                 test_judge_batch_undefined_axis_retries_then_falls_back
  BRK-LLM-13     test_parser_rejects_item_count_mismatch,
                 test_judge_batch_recovers_on_retry_after_schema_mismatch
  BRK-LLM-14     test_judge_batch_transient_errors_retry_with_backoff_then_fallback
  BRK-LLM-15/15b test_judge_batch_transient_errors_retry_with_backoff_then_fallback (fallback=True),
                 test_judge_batch_undefined_axis_retries_then_falls_back (message == FALLBACK_MESSAGE)
  BRK-LLM-17     test_judge_batch_fatal_error_propagates_without_retry_or_fallback
                 (fail-open(-15) 과 fail-closed(-17) 이 섞이지 않는지는 위 두 테스트를
                 나란히 실행해 대조 확인한다 — 하나는 폴백, 하나는 예외 전파)
  BRK-LLM-16/16b test_dry_run_returns_all_pass_without_calling_model
  BRK-LLM-48b    test_validate_startup_rejects_dry_run_and_shadow_together
  BRK-LLM-6b     test_validate_startup_rejects_global_profile,
                 test_validate_startup_accepts_apac_profile
  BRK-LLM-11b/17 test_classify_error_distinguishes_fatal_from_transient (client.py 예외 분류)
  BRK-LLM-4      test_parser_fills_default_message_when_blank
  (parser 순수 로직 보강) test_parser_strips_code_fence_wrapped_json,
                 test_parser_accepts_bare_array_response
  BRK-LLM-1      test_judge_item_rejects_unknown_unit_kind (스키마 계약)

⚠️ 커버하지 않은 것: BRK-LLM-1c(두 축 겹치면 abuse 우선)는 응답 스키마가 축을
하나만 담아 이 계층에서 관측·강제가 불가능하다(`services/judge.py` 의
`_to_result` 주석이 이미 이렇게 명시함) — 프롬프트 계약으로만 보장되므로
`accuracy-tuner` 영역이고 이 테스트로는 검증할 수 없다. 목표치(BRK-LLM-20~27,
30~35, 40~45)는 실측·대표 케이스 확정 전이라(BRK-LLM-46/47) 여기서 다루지 않는다.
"""

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import bedrock.core.client as bedrock_client_module  # noqa: E402
from bedrock.core.config import BedrockSettings, validate_startup  # noqa: E402
from bedrock.core.errors import (  # noqa: E402
    BedrockConfigError,
    BedrockFatalError,
    BedrockSchemaError,
    BedrockTransientError,
)
from bedrock.core.parser import parse_judgements  # noqa: E402
from bedrock.schemas.judgement import (  # noqa: E402
    DRY_RUN_MESSAGE,
    FALLBACK_MESSAGE,
    PASS_MESSAGE,
    JudgeItem,
)
from bedrock.services.judge import BedrockJudgeService  # noqa: E402


# ---------------------------------------------------------------------------
# 헬퍼
# ---------------------------------------------------------------------------

def _settings(**overrides):
    defaults = dict(
        BEDROCK_MODEL_ID="apac.anthropic.claude-sonnet-4-20250514-v1:0",
        BEDROCK_REGION="ap-northeast-2",
        BEDROCK_SCHEMA_RETRIES=2,
        BEDROCK_TRANSIENT_RETRIES=3,
        BEDROCK_BACKOFF_BASE_SECONDS=0.0,  # 테스트 속도용 — 실제 재시도 로직은 그대로 탄다.
        BEDROCK_DRY_RUN=False,
        BEDROCK_SHADOW=False,
    )
    defaults.update(overrides)
    return BedrockSettings(**defaults)


def _response(results_obj, usage=None):
    usage = usage or {
        "inputTokens": 10,
        "outputTokens": 5,
        "cacheReadInputTokens": 0,
        "cacheWriteInputTokens": 0,
    }
    return {
        "output": {"message": {"content": [{"text": json.dumps(results_obj)}]}},
        "usage": usage,
    }


class _sequence_converse:
    """converse() 호출마다 entries 를 순서대로 소비하는 페이크.

    entries 의 각 원소는 dict(성공 응답) 또는 Exception 인스턴스(그 예외를 발생).
    entries 를 다 쓰면 마지막 원소를 반복한다(재시도 횟수를 넉넉히 채우기 위함).
    """

    def __init__(self, entries):
        self._entries = entries
        self.calls = 0

    def __call__(self, client, model_id, system_blocks, user_text, settings=None):
        idx = min(self.calls, len(self._entries) - 1)
        self.calls += 1
        entry = self._entries[idx]
        if isinstance(entry, Exception):
            raise entry
        return entry


class _patched_client:
    """`bedrock.core.client` 모듈의 converse/build_bedrock_client 를 일시 교체한다.

    `services/judge.py` 는 `from bedrock.core import client as bedrock_client` 로
    모듈 객체를 들고 있으므로, 이 모듈의 속성을 바꿔치기하면 서비스 쪽 호출에
    그대로 반영된다(실제 boto3 는 전혀 필요 없다).
    """

    def __init__(self, converse_fn, build_fn=None):
        self._converse_fn = converse_fn
        self._build_fn = build_fn or (lambda settings=None: object())

    def __enter__(self):
        self._orig_converse = bedrock_client_module.converse
        self._orig_build = bedrock_client_module.build_bedrock_client
        bedrock_client_module.converse = self._converse_fn
        bedrock_client_module.build_bedrock_client = self._build_fn
        return self

    def __exit__(self, *exc):
        bedrock_client_module.converse = self._orig_converse
        bedrock_client_module.build_bedrock_client = self._orig_build


# ---------------------------------------------------------------------------
# BRK-LLM-1/12 — 입력과 같은 길이·같은 순서
# ---------------------------------------------------------------------------

def test_judge_batch_preserves_length_and_reorders_by_response_index():
    items = [
        JudgeItem(text="본문 인신공격", unit_kind="body"),
        JudgeItem(text="댓글1 통과", unit_kind="comment"),
        JudgeItem(text="댓글2 통과", unit_kind="comment"),
    ]
    # 응답 순서를 일부러 섞어 index 로 재정렬되는지 확인한다(BRK-LLM-12).
    scrambled = _response(
        {
            "results": [
                {"index": 3, "is_valid": True, "axis": None, "message": ""},
                {"index": 1, "is_valid": False, "axis": "abuse", "message": "인신공격"},
                {"index": 2, "is_valid": True, "axis": None, "message": ""},
            ]
        }
    )
    converse = _sequence_converse([scrambled])
    service = BedrockJudgeService(settings=_settings())

    with _patched_client(converse):
        results = service.judge(items)

    assert len(results) == 3, "입력 3개 -> 결과 3개(BRK-LLM-1)"
    assert results[0].is_valid is False and results[0].axis == "abuse" and results[0].message == "인신공격"
    assert results[1].is_valid is True
    assert results[2].is_valid is True
    assert converse.calls == 1


# ---------------------------------------------------------------------------
# BRK-LLM-19 — offtopic 은 body 에만, 댓글에 붙으면 통과로 되돌린다
# ---------------------------------------------------------------------------

def test_offtopic_axis_on_comment_is_reverted_to_pass():
    items = [
        JudgeItem(text="본문 주제무관", unit_kind="body"),
        JudgeItem(text="댓글 주제무관", unit_kind="comment"),
    ]
    response = _response(
        {
            "results": [
                {"is_valid": False, "axis": "offtopic", "message": "야구와 무관"},
                {"is_valid": False, "axis": "offtopic", "message": "댓글도 무관(모델이 규칙을 어김)"},
            ]
        }
    )
    converse = _sequence_converse([response])
    service = BedrockJudgeService(settings=_settings())

    with _patched_client(converse):
        results = service.judge(items)

    assert results[0].is_valid is False and results[0].axis == "offtopic", "본문은 offtopic 유지"
    assert results[1].is_valid is True, "댓글에 붙은 offtopic 은 통과로 되돌려야 함(BRK-LLM-19)"
    assert results[1].axis is None
    assert results[1].message == PASS_MESSAGE
    assert results[1].fallback is False, "이건 폴백이 아니라 규칙 위반 복구다"


# ---------------------------------------------------------------------------
# BRK-LLM-11b/13 — 스키마 불일치(정의 밖 axis / 항목 수 불일치)는 재시도, 소진 시 폴백
# ---------------------------------------------------------------------------

def test_judge_batch_undefined_axis_retries_then_falls_back():
    item = [JudgeItem(text="애매한 문장", unit_kind="body")]
    bad = _response({"results": [{"index": 1, "is_valid": False, "axis": "weird_axis", "message": "x"}]})
    # BEDROCK_SCHEMA_RETRIES=2 -> 최초 시도 + 재시도 2회 = 3회 호출 후 소진.
    converse = _sequence_converse([bad, bad, bad])
    service = BedrockJudgeService(settings=_settings(BEDROCK_SCHEMA_RETRIES=2))

    with _patched_client(converse):
        results = service.judge(item)

    assert converse.calls == 3, f"스키마 재시도 2회(초기+2) 소진 후 폴백해야 하는데 호출 {converse.calls}회"
    assert len(results) == 1
    assert results[0].is_valid is True and results[0].fallback is True
    assert results[0].message == FALLBACK_MESSAGE, "재시도 소진 = fail-open(BRK-LLM-15), DRY_RUN 메시지와 달라야 함"


def test_judge_batch_recovers_on_retry_after_schema_mismatch():
    # BRK-LLM-13: 부분 응답을 짜맞추지 않고 "호출 전체"를 재시도한다 — 재시도가
    # 성공하면 그 결과를 그대로 쓰고(짜맞추기 없음), 첫 시도의 흔적이 남지 않는다.
    item = [JudgeItem(text="문장", unit_kind="body")]
    too_many = _response(
        {"results": [{"index": 1, "is_valid": True}, {"index": 2, "is_valid": True}]}
    )  # 요청 1개인데 응답 2개 -> 항목 수 불일치(BRK-LLM-13).
    recovered = _response({"results": [{"index": 1, "is_valid": False, "axis": "spam", "message": "광고"}]})
    converse = _sequence_converse([too_many, recovered])
    service = BedrockJudgeService(settings=_settings(BEDROCK_SCHEMA_RETRIES=2))

    with _patched_client(converse):
        results = service.judge(item)

    assert converse.calls == 2
    assert len(results) == 1
    assert results[0].is_valid is False and results[0].axis == "spam" and results[0].message == "광고"
    assert results[0].fallback is False, "재시도로 정상 응답을 받았으면 폴백이 아니다"


# ---------------------------------------------------------------------------
# BRK-LLM-14/15 vs BRK-LLM-17 — 재시도 소진은 fail-open, 구조적 실패는 즉시 중단
# (이 둘이 섞이면 안 된다 — 아래 두 테스트를 나란히 봐야 대조가 된다)
# ---------------------------------------------------------------------------

def test_judge_batch_transient_errors_retry_with_backoff_then_fallback():
    item = [JudgeItem(text="문장", unit_kind="body")]
    err = BedrockTransientError("일시적 오류")
    # BEDROCK_TRANSIENT_RETRIES=3 -> 최초 시도 + 재시도 3회 = 4회 호출 후 소진.
    converse = _sequence_converse([err, err, err, err])
    service = BedrockJudgeService(settings=_settings(BEDROCK_TRANSIENT_RETRIES=3, BEDROCK_BACKOFF_BASE_SECONDS=0.0))

    with _patched_client(converse):
        results = service.judge(item)

    assert converse.calls == 4, f"일시적 오류 재시도 3회(초기+3) 소진 후 폴백해야 하는데 호출 {converse.calls}회"
    assert results[0].is_valid is True and results[0].fallback is True
    assert results[0].message == FALLBACK_MESSAGE


def test_judge_batch_fatal_error_propagates_without_retry_or_fallback():
    item = [JudgeItem(text="문장", unit_kind="body")]
    converse = _sequence_converse([BedrockFatalError("AccessDeniedException")])
    service = BedrockJudgeService(settings=_settings())

    raised = None
    with _patched_client(converse):
        try:
            service.judge(item)
        except BedrockFatalError as exc:
            raised = exc

    assert raised is not None, "구조적 실패(BRK-LLM-17)는 폴백 통과로 흡수되면 안 되고 그대로 전파돼야 한다"
    assert converse.calls == 1, "구조적 실패는 재시도하지 않는다(fail-open 대상이 아니다)"


# ---------------------------------------------------------------------------
# BRK-LLM-16/16b — DRY_RUN 은 호출 없이 전건 통과
# ---------------------------------------------------------------------------

def test_dry_run_returns_all_pass_without_calling_model():
    def _should_not_be_called(*args, **kwargs):
        raise AssertionError("DRY_RUN 모드에서 실제 모델 호출이 발생함(BRK-LLM-16 위반)")

    items = [JudgeItem(text="문장1", unit_kind="body"), JudgeItem(text="문장2", unit_kind="comment")]
    service = BedrockJudgeService(settings=_settings(BEDROCK_DRY_RUN=True))

    with _patched_client(_should_not_be_called, build_fn=_should_not_be_called):
        batch = service.judge_batch(items)

    assert len(batch.results) == 2
    for result in batch.results:
        assert result.is_valid is True
        assert result.fallback is True
        assert result.message == DRY_RUN_MESSAGE, "DRY_RUN 은 -15 폴백과 메시지로 구분돼야 한다(BRK-LLM-16b)"
        assert result.axis is None
    assert batch.usage.call_count == 0, "DRY_RUN 은 실제 호출이 없으므로 usage 도 0이어야 한다"


# ---------------------------------------------------------------------------
# BRK-LLM-48b / 6b — 기동 거부 계약
# ---------------------------------------------------------------------------

def test_validate_startup_rejects_dry_run_and_shadow_together():
    settings = _settings(BEDROCK_DRY_RUN=True, BEDROCK_SHADOW=True)
    try:
        validate_startup(settings)
        raised = False
    except BedrockConfigError:
        raised = True
    assert raised, "DRY_RUN 과 SHADOW 동시 사용은 기동을 거부해야 한다(BRK-LLM-48b)"


def test_validate_startup_rejects_global_profile():
    settings = _settings(BEDROCK_MODEL_ID="global.anthropic.claude-sonnet-4-20250514-v1:0")
    try:
        validate_startup(settings)
        raised = False
    except BedrockConfigError:
        raised = True
    assert raised, "global.* 프로파일은 기동을 거부해야 한다(BRK-LLM-6b — 데이터 리전 제약)"


def test_validate_startup_accepts_apac_profile():
    settings = _settings(BEDROCK_MODEL_ID="apac.anthropic.claude-sonnet-4-20250514-v1:0")
    validate_startup(settings)  # 예외가 나지 않아야 통과.


# ---------------------------------------------------------------------------
# 클라이언트 예외 분류 (BRK-LLM-17 vs 14 의 경계) — 순수, boto3 네트워크 불필요
# ---------------------------------------------------------------------------

def test_classify_error_distinguishes_fatal_from_transient():
    from botocore.exceptions import ClientError, NoCredentialsError

    from bedrock.core.client import classify_error

    access_denied = ClientError({"Error": {"Code": "AccessDeniedException", "Message": "no"}}, "Converse")
    assert isinstance(classify_error(access_denied), BedrockFatalError)

    validation_err = ClientError({"Error": {"Code": "ValidationException", "Message": "bad profile"}}, "Converse")
    assert isinstance(classify_error(validation_err), BedrockFatalError)

    throttling = ClientError({"Error": {"Code": "ThrottlingException", "Message": "slow down"}}, "Converse")
    assert isinstance(classify_error(throttling), BedrockTransientError)

    assert isinstance(classify_error(NoCredentialsError()), BedrockFatalError), (
        "자격증명 없음은 재시도해도 달라지지 않으므로 즉시 중단 대상이어야 한다(BRK-LLM-17)"
    )


# ---------------------------------------------------------------------------
# 파서(core/parser.py) 순수 로직 — 서비스 계층 없이 직접 검증
# ---------------------------------------------------------------------------

def test_parser_rejects_undefined_axis_value():
    raw = json.dumps({"results": [{"is_valid": False, "axis": "not_a_real_axis", "message": "x"}]})
    try:
        parse_judgements(raw, expected_count=1)
        raised = False
    except BedrockSchemaError:
        raised = True
    assert raised, "정의 밖 axis 는 스키마 불일치여야 한다(BRK-LLM-11b)"


def test_parser_rejects_item_count_mismatch():
    raw = json.dumps({"results": [{"is_valid": True}, {"is_valid": True}]})
    try:
        parse_judgements(raw, expected_count=1)
        raised = False
    except BedrockSchemaError:
        raised = True
    assert raised, "요청 수와 응답 수가 다르면 스키마 불일치여야 한다(BRK-LLM-13)"


def test_parser_fills_default_message_when_blank():
    raw = json.dumps({"results": [{"is_valid": False, "axis": "spam", "message": ""}]})
    parsed = parse_judgements(raw, expected_count=1)
    assert parsed[0].message, "빈 사유는 축별 기본 문구로 채워져야 한다(BRK-LLM-4)"
    assert parsed[0].message == "광고·홍보성 내용으로 판정"


def test_parser_strips_code_fence_wrapped_json():
    raw = "```json\n" + json.dumps({"results": [{"is_valid": True}]}) + "\n```"
    parsed = parse_judgements(raw, expected_count=1)
    assert parsed[0].is_valid is True


def test_parser_accepts_bare_array_response():
    raw = json.dumps([{"is_valid": False, "axis": "abuse", "message": "욕설"}])
    parsed = parse_judgements(raw, expected_count=1)
    assert parsed[0].is_valid is False and parsed[0].axis == "abuse"


# ---------------------------------------------------------------------------
# 스키마 계약 — unit_kind 는 열거형이지 자유 문자열이 아니다
# ---------------------------------------------------------------------------

def test_judge_item_rejects_unknown_unit_kind():
    from pydantic import ValidationError

    try:
        JudgeItem(text="x", unit_kind="paragraph")
        raised = False
    except ValidationError:
        raised = True
    assert raised, "unit_kind 는 'body'|'comment' 외 값을 허용하면 안 된다(BRK-LLM-1)"


if __name__ == "__main__":
    tests = [fn for name, fn in sorted(globals().items())
             if name.startswith("test_") and callable(fn)]
    passed = 0
    failed_count = 0
    for fn in tests:
        try:
            fn()
            passed += 1
            print(f"PASS  {fn.__name__}")
        except AssertionError as exc:
            failed_count += 1
            print(f"FAIL  {fn.__name__}: {exc}")
        except Exception as exc:  # noqa: BLE001 — 예상 못 한 예외도 이 테스트만 FAIL 처리하고 계속 진행.
            failed_count += 1
            print(f"FAIL  {fn.__name__}: {type(exc).__name__}: {exc}")
    print(f"\n{passed}/{len(tests)} passed")
    sys.exit(1 if failed_count else 0)
