"""bedrock — LLM 기반 2차 검열 모듈.

`validation`(사전·정규식 1차 검열)이 통과시킨 텍스트를 Amazon Bedrock 모델로 다시
판정한다. **텍스트 in / 판정 out** 이 전부이며 S3·버킷·키 규약(BRK-LLM-2)도,
validation 모듈의 사전·정규식(BRK-LLM-3)도 참조하지 않는다.

FastAPI 라우트가 없다 — 배치 러너(`pipeline/`) 전용 모듈이다.

`bedrock/__init__.py` 가 비어 있지 않은 이유: 이 모듈은 `main.py` 같은 진입점이
없어서 공개 표면을 여기서 명시해 두는 편이 러너 쪽 import 를 안정시킨다.
"""

from bedrock.core.config import BedrockSettings, bedrock_settings, validate_startup
from bedrock.core.errors import (
    BedrockConfigError,
    BedrockError,
    BedrockFatalError,
    BedrockSchemaError,
    BedrockTransientError,
)
from bedrock.core.prompt import PROMPT_VERSION
from bedrock.schemas.judgement import (
    AXIS_VALUES,
    BODY_ONLY_AXES,
    DRY_RUN_MESSAGE,
    FALLBACK_MESSAGE,
    PASS_MESSAGE,
    BatchJudgement,
    BatchUsage,
    JudgeItem,
    JudgeResult,
)
from bedrock.services.judge import BedrockJudgeService, judge_service

__all__ = [
    "AXIS_VALUES",
    "BODY_ONLY_AXES",
    "DRY_RUN_MESSAGE",
    "FALLBACK_MESSAGE",
    "PASS_MESSAGE",
    "PROMPT_VERSION",
    "BatchJudgement",
    "BatchUsage",
    "BedrockConfigError",
    "BedrockError",
    "BedrockFatalError",
    "BedrockJudgeService",
    "BedrockSchemaError",
    "BedrockSettings",
    "BedrockTransientError",
    "JudgeItem",
    "JudgeResult",
    "bedrock_settings",
    "judge_service",
    "validate_startup",
]
