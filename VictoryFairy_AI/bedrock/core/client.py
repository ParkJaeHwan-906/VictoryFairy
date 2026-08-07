"""Bedrock Runtime 호출 계층 (BRK-LLM-7~10 · 14 · 17).

판정 로직은 없다 — 클라이언트 생성 · Converse 호출 · **예외 분류**만 한다.
예외 분류가 이 파일의 핵심이다: 어떤 오류가 재시도 대상이고(BRK-LLM-14) 어떤
오류가 즉시 중단 대상인지(BRK-LLM-17)를 여기서 한 번만 정한다.

⚠️ boto3 는 로컬 .venv(3.9)에 없을 수 있다. `import bedrock.core.client` 만으로
깨지지 않도록 boto3/botocore 는 **실제로 필요한 시점에 지연 import** 한다
(`pipeline/s3_io.py` 와 같은 이유·같은 방식).
"""

import logging
from typing import Any, Optional

from bedrock.core.errors import BedrockFatalError, BedrockTransientError

logger = logging.getLogger(__name__)

# BRK-LLM-17: 구조적 실패 — fail-open 대상이 **아니다**. 즉시 중단한다.
FATAL_ERROR_CODES = (
    "AccessDeniedException",  # 모델 접근 권한 미부여
    "ValidationException",  # 잘못된 프로파일 ID·잘못된 요청
    "ResourceNotFoundException",  # 존재하지 않는 프로파일
    "UnrecognizedClientException",  # 자격증명 무효
    "InvalidSignatureException",
)

# BRK-LLM-14: 지수 백오프 재시도 대상.
TRANSIENT_ERROR_CODES = (
    "ThrottlingException",
    "TooManyRequestsException",
    "ServiceUnavailableException",
    "ServiceUnavailable",
    "InternalServerException",
    "ModelTimeoutException",
    "ModelNotReadyException",
)


def build_bedrock_client(settings=None):
    """bedrock-runtime 클라이언트를 생성한다.

    - 자격증명은 boto3 기본 체인(환경변수·인스턴스 프로파일 등)에서 획득한다.
    - 리전은 `BEDROCK_REGION`(BRK-LLM-7) — S3 리전과 별도 키다.
    - 타임아웃은 읽기 30초 / 연결 5초(BRK-LLM-9). 무한 대기 금지.
    - botocore 자체 재시도는 끈다 — 재시도 정책은 서비스가 직접 관리한다
      (BRK-LLM-13/14 의 두 예산이 분리돼 있어 botocore 에 맡길 수 없다).
    """
    import boto3  # 지연 import: 로컬 3.9 venv 에 boto3 미설치 상태에서도 파일 import 는 깨지지 않게 한다.
    from botocore.config import Config

    from bedrock.core.config import bedrock_settings

    settings = settings or bedrock_settings

    boto_config = Config(
        read_timeout=settings.BEDROCK_READ_TIMEOUT_SECONDS,
        connect_timeout=settings.BEDROCK_CONNECT_TIMEOUT_SECONDS,
        retries={"max_attempts": 1, "mode": "standard"},
    )
    return boto3.client(
        "bedrock-runtime",
        region_name=settings.BEDROCK_REGION,
        config=boto_config,
    )


def classify_error(exc: BaseException) -> Exception:
    """예외를 `BedrockFatalError` 또는 `BedrockTransientError` 로 분류한다.

    - 자격증명 없음/불완전 · AccessDenied · Validation → Fatal(BRK-LLM-17).
    - Throttling · ServiceUnavailable · 타임아웃/연결 오류 → Transient(BRK-LLM-14).
    - 그 밖의 알 수 없는 오류 → Transient 로 본다. 재시도를 소진하면 폴백 통과
      (BRK-LLM-15)로 흡수되는데, 이는 이 단계의 기본 태도(fail-open)와 같다.
      **알 수 없는 오류를 Fatal 로 올리면 배치 전체가 죽는다.**
    """
    from botocore.exceptions import (
        ClientError,
        ConnectionError as BotoConnectionError,
        ConnectTimeoutError,
        EndpointConnectionError,
        NoCredentialsError,
        PartialCredentialsError,
        ReadTimeoutError,
    )

    # 자격증명 자체가 없거나 불완전 — 재시도해도 달라지지 않는다(BRK-LLM-17).
    if isinstance(exc, (NoCredentialsError, PartialCredentialsError)):
        return BedrockFatalError(
            "AWS 자격증명을 찾을 수 없습니다(BRK-LLM-17): {exc}. "
            "전건 폴백 통과로 조용히 끝내지 않고 즉시 중단합니다.".format(exc=exc)
        )

    if isinstance(exc, ClientError):
        code = exc.response.get("Error", {}).get("Code", "")
        if code in FATAL_ERROR_CODES:
            return BedrockFatalError(
                "Bedrock 구조적 실패로 중단합니다(BRK-LLM-17): {code} — {exc}".format(code=code, exc=exc)
            )
        if code in TRANSIENT_ERROR_CODES:
            return BedrockTransientError("Bedrock 일시적 실패({code}): {exc}".format(code=code, exc=exc))
        return BedrockTransientError("Bedrock 오류({code}): {exc}".format(code=code, exc=exc))

    if isinstance(
        exc,
        (ReadTimeoutError, ConnectTimeoutError, EndpointConnectionError, BotoConnectionError),
    ):
        return BedrockTransientError("Bedrock 호출 타임아웃/연결 실패: {exc}".format(exc=exc))

    return BedrockTransientError("Bedrock 호출 실패(분류되지 않은 오류): {exc}".format(exc=exc))


def converse(client, model_id: str, system_blocks: list, user_text: str, settings=None) -> dict:
    """Converse API 로 한 번 호출하고 원본 응답 dict 를 돌려준다.

    호출 파라미터는 결정성 우선으로 고정한다(BRK-LLM-8) — temperature 0,
    top_p 고정, max_tokens 상한. 예외는 `classify_error` 로 분류해 올린다.
    """
    from bedrock.core.config import bedrock_settings

    settings = settings or bedrock_settings

    try:
        return client.converse(
            modelId=model_id,
            system=system_blocks,
            messages=[{"role": "user", "content": [{"text": user_text}]}],
            inferenceConfig={
                "maxTokens": settings.BEDROCK_MAX_TOKENS,
                "temperature": settings.BEDROCK_TEMPERATURE,
                "topP": settings.BEDROCK_TOP_P,
            },
        )
    except Exception as exc:  # noqa: BLE001 - 분류해서 다시 올린다
        raise classify_error(exc) from exc


def extract_text(response: dict) -> str:
    """Converse 응답에서 모델이 낸 텍스트를 뽑는다.

    응답 구조가 예상과 다르면 빈 문자열을 돌려주고, 파서가 스키마 불일치로
    처리하게 둔다(BRK-LLM-13 → 호출 재시도).
    """
    content = (response.get("output") or {}).get("message", {}).get("content") or []
    parts = [block.get("text", "") for block in content if isinstance(block, dict) and "text" in block]
    return "\n".join(part for part in parts if part)


def extract_usage(response: dict) -> dict:
    """Converse 응답의 토큰 사용량을 뽑는다(PIPE-2SB-61/44 의 비용·캐시 집계용)."""
    usage: dict[str, Any] = response.get("usage") or {}

    def _as_int(value: Optional[Any]) -> int:
        try:
            return int(value or 0)
        except (TypeError, ValueError):
            return 0

    return {
        "input_tokens": _as_int(usage.get("inputTokens")),
        "output_tokens": _as_int(usage.get("outputTokens")),
        "cache_read_input_tokens": _as_int(usage.get("cacheReadInputTokens")),
        "cache_write_input_tokens": _as_int(usage.get("cacheWriteInputTokens")),
    }
