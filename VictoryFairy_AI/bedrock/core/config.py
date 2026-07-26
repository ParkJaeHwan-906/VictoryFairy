"""bedrock 모듈 전용 환경 설정 (BRK-LLM-5~10 · 16 · 48).

⚠️ 이 모듈은 S3·버킷·키 규약을 모른다(BRK-LLM-2). S3 설정은 `pipeline/core/config.py`
에만 두고 여기엔 절대 섞지 않는다. 텍스트 in / 판정 out 이 이 모듈의 전부다.

모델 식별자·리전은 코드에 하드코딩하지 않고 환경변수/.env 에서만 읽는다(BRK-LLM-5).
"""

import logging
from typing import Optional

from pydantic_settings import BaseSettings, SettingsConfigDict

from bedrock.core.errors import BedrockConfigError

logger = logging.getLogger(__name__)

# BRK-LLM-6b: 데이터가 APAC 밖으로 나가지 않아야 하므로 global.* 프로파일을 금지한다.
FORBIDDEN_PROFILE_PREFIX = "global."
# BRK-LLM-6: 정상 경로는 apac.* 추론 프로파일. 다만 ARN 형태 지정도 있을 수 있어
# 하드 실패로 두지 않고 경고만 남긴다(금지 계약은 global.* 쪽에만 걸려 있다).
EXPECTED_PROFILE_PREFIX = "apac."


class BedrockSettings(BaseSettings):
    """Bedrock 호출 설정.

    - BEDROCK_MODEL_ID: `apac.*` Claude Sonnet 4 **추론 프로파일 ID**(BRK-LLM-5/6).
      베어 모델 ID 를 조립하지 않는다 — Sonnet 4 는 INFERENCE_PROFILE 전용이라
      베어 ID 호출이 실제로 실패한다. 본문·댓글이 같은 모델을 쓴다.
    - BEDROCK_REGION: Bedrock 리전(BRK-LLM-7). S3 리전(AWS_REGION)과 **별도 키**다 —
      모델 가용 리전이 다를 수 있다.
    - BEDROCK_DRY_RUN / BEDROCK_SHADOW: 두 모드는 양립 불가(BRK-LLM-48b).
    """

    # --- 모델 · 리전 (BRK-LLM-5/6/7) ---
    BEDROCK_MODEL_ID: Optional[str] = None
    BEDROCK_REGION: str = "ap-northeast-2"

    # --- 결정성 파라미터 (BRK-LLM-8) ---
    # temperature 0 · top_p 고정 · max_tokens 상한. 같은 입력 재실행 시 판정이
    # 요동치지 않아야 accuracy-tuner 가 측정할 수 있다(완전 재현은 보장 못 한다).
    BEDROCK_TEMPERATURE: float = 0.0
    BEDROCK_TOP_P: float = 1.0
    BEDROCK_MAX_TOKENS: int = 2048

    # --- 타임아웃 (BRK-LLM-9, 가정치) ---
    # NAT(단일 AZ 2a) 경유라 지연이 붙는다. 무한 대기 금지.
    BEDROCK_READ_TIMEOUT_SECONDS: float = 30.0
    BEDROCK_CONNECT_TIMEOUT_SECONDS: float = 5.0

    # --- 길이 상한 (BRK-LLM-10, 가정치) ---
    # 초과분은 절단하고 그 사실을 로그로 남긴다. 절단분 미판정은 알려진 한계.
    BEDROCK_MAX_TEXT_CHARS: int = 4000

    # --- 재시도 (BRK-LLM-13/14, 가정치) ---
    # 두 재시도 예산은 **분리**돼 있다. 스키마 불일치 2회 · 일시적 오류 3회.
    BEDROCK_SCHEMA_RETRIES: int = 2
    BEDROCK_TRANSIENT_RETRIES: int = 3
    # 지수 백오프 1s·2s·4s = base * 2**n.
    BEDROCK_BACKOFF_BASE_SECONDS: float = 1.0

    # --- 모드 (BRK-LLM-16/48) ---
    BEDROCK_DRY_RUN: bool = False
    BEDROCK_SHADOW: bool = False

    # --- 프롬프트 캐싱 (PIPE-2SB-64/65) ---
    # 판정 텍스트가 20~50토큰인데 시스템 프롬프트가 약 1,500토큰이라 호출 비용의
    # 대부분이 접두부다. 여기서는 **캐시 breakpoint 를 시스템 블록 뒤에 두는 구조**만
    # 만든다(판정 텍스트는 그 뒤 user 메시지로 간다 → 프리픽스 매칭 성립).
    BEDROCK_PROMPT_CACHE: bool = True

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )


bedrock_settings = BedrockSettings()


def _profile_id_part(model_id: str) -> str:
    """ARN 으로 지정된 경우에도 프로파일 ID 부분만 떼어낸다.

    `arn:aws:bedrock:...:inference-profile/global.anthropic...` 처럼 ARN 뒤에 숨은
    global.* 도 BRK-LLM-6b 로 걸러야 하므로 마지막 `/` 뒤를 본다.
    """
    return model_id.rsplit("/", 1)[-1].strip()


def validate_startup(settings: Optional[BedrockSettings] = None) -> None:
    """기동 시 설정 계약을 검사한다. 위반 시 `BedrockConfigError` 로 중단한다.

    러너는 실행 시작 시점에 이 함수를 직접 호출해 **호출 이전에** 중단시키는 것이
    맞다. 서비스도 첫 판정 직전에 한 번 호출하므로 우회 경로는 없다.

    검사 항목:
    - BRK-LLM-48b: DRY_RUN 과 SHADOW 동시 사용 금지(호출 안 함 ↔ 호출함).
    - BRK-LLM-6b: `global.*` 추론 프로파일 금지(데이터 리전 제약).
    - BRK-LLM-5: 실제 호출 모드에서는 모델 식별자가 반드시 있어야 한다.
    """
    settings = settings or bedrock_settings

    # BRK-LLM-48b — 조용히 한쪽을 이기게 두면 "비용이 0인 줄 알았는데 청구되는" 사고가 난다.
    if settings.BEDROCK_DRY_RUN and settings.BEDROCK_SHADOW:
        raise BedrockConfigError(
            "BEDROCK_DRY_RUN 과 BEDROCK_SHADOW 를 동시에 켤 수 없습니다(BRK-LLM-48b). "
            "DRY_RUN 은 모델을 호출하지 않고 SHADOW 는 실제로 호출하므로 두 모드는 양립할 수 없습니다. "
            "둘 중 하나만 true 로 두세요."
        )

    model_id = (settings.BEDROCK_MODEL_ID or "").strip()

    if not model_id:
        # DRY_RUN 은 호출 자체를 하지 않으므로 모델 식별자가 없어도 배선 검증이 가능하다.
        if settings.BEDROCK_DRY_RUN:
            return
        raise BedrockConfigError(
            "BEDROCK_MODEL_ID 가 비어 있습니다(BRK-LLM-5). "
            "apac.* Claude Sonnet 4 추론 프로파일 ID 를 환경변수로 지정하세요."
        )

    profile_id = _profile_id_part(model_id)

    # BRK-LLM-6b — 데이터 리전 제약. 기동 시 거부하고 중단한다.
    if profile_id.startswith(FORBIDDEN_PROFILE_PREFIX):
        raise BedrockConfigError(
            "BEDROCK_MODEL_ID 로 global.* 추론 프로파일을 사용할 수 없습니다(BRK-LLM-6b): "
            "'{model_id}'. 데이터가 APAC 밖으로 나가지 않아야 하므로 apac.* 프로파일만 허용합니다.".format(
                model_id=model_id
            )
        )

    # BRK-LLM-6 — 금지 계약은 global.* 에만 있으므로 여기서는 경고까지만 한다.
    if not profile_id.startswith(EXPECTED_PROFILE_PREFIX):
        logger.warning(
            "BEDROCK_MODEL_ID 가 apac.* 추론 프로파일 형태가 아닙니다(BRK-LLM-6): %s. "
            "베어 모델 ID 는 Sonnet 4 에서 호출이 실패합니다.",
            model_id,
        )
