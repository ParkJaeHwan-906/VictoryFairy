"""bedrock 모듈 예외 계층.

세 종류를 구분하는 것이 이 파일의 전부이고, 그 구분이 곧 계약이다.

- `BedrockConfigError`  — 기동 거부(BRK-LLM-6b/48b). 설정이 틀렸다.
- `BedrockFatalError`   — 구조적 실패(BRK-LLM-17). **fail-open 대상이 아니다.**
- `BedrockTransientError` / `BedrockSchemaError` — 재시도 대상(BRK-LLM-13/14).
  재시도를 소진하면 폴백 통과(BRK-LLM-15)로 흡수된다.

⚠️ `BedrockFatalError` 를 서비스가 삼키면 안 된다. 전건 폴백 통과로 조용히 끝나는
것이 최악이라는 게 BRK-LLM-17 의 취지다.
"""


class BedrockError(Exception):
    """bedrock 모듈의 모든 예외 공통 조상."""


class BedrockConfigError(BedrockError):
    """설정 위반 — 기동을 거부한다(BRK-LLM-6b, BRK-LLM-48b)."""


class BedrockFatalError(BedrockError):
    """자격증명 없음·AccessDenied·Validation 등 구조적 실패(BRK-LLM-17).

    재시도하지 않고 즉시 전파한다. 러너는 이 예외로 0이 아닌 종료 코드를 낸다.
    """


class BedrockTransientError(BedrockError):
    """Throttling·ServiceUnavailable·타임아웃 등 일시적 실패(BRK-LLM-14)."""


class BedrockSchemaError(BedrockError):
    """응답 항목 수 불일치·스키마 어긋남(BRK-LLM-13, BRK-LLM-11b).

    부분 응답을 짜맞추지 않고 호출 전체를 재시도한다.
    """
