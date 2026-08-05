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

# BRK-LLM-6d: 추론 프로파일을 아예 쓰지 않는다.
#
# ⚠️ v2 에서 이 검증의 방향이 **뒤집혔다.** v1 은 apac.* 를 정상 경로로 보고 베어 모델
# ID 에 경고를 냈지만, 조직 SCP `p-meobeew3` 가 서울 외 리전의 bedrock:InvokeModel 을
# 명시적으로 거부한다는 사실이 실측으로 확인됐다 — apac.* 프로파일은 도쿄(ap-northeast-1)
# 등으로 라우팅되므로 **호출이 반드시 실패한다**.
# 즉 v1 검증은 동작하는 설정에 경고를 내고 반드시 실패할 설정을 통과시키고 있었다.
FORBIDDEN_PROFILE_PREFIXES = ("global.", "apac.", "us.", "eu.", "apne1.")


class BedrockSettings(BaseSettings):
    """Bedrock 호출 설정.

    - BEDROCK_MODEL_ID: **베어 모델 ID** `anthropic.claude-3-5-sonnet-20240620-v1:0`
      (BRK-LLM-5/6, v2). 추론 프로파일을 쓰지 않는다 — SCP 가 서울 밖을 막아
      `apac.*` 는 호출 자체가 실패한다(BRK-LLM-6d). 본문·댓글이 같은 모델을 쓴다.
      서울에서 ON_DEMAND 로 부를 수 있는 Anthropic 모델은 이것과 claude-3-haiku
      둘뿐이고, 나머지는 전부 INFERENCE_PROFILE 전용이다.
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
    # 판정 텍스트가 20~50토큰인데 시스템 프롬프트가 약 2,470토큰(실측)이라 호출 비용의
    # 대부분이 접두부다. 여기서는 **캐시 breakpoint 를 시스템 블록 뒤에 두는 구조**만
    # 만든다(판정 텍스트는 그 뒤 user 메시지로 간다 → 프리픽스 매칭 성립).
    #
    # ⚠️ 기본값이 False 인 이유 — 배선을 지우지 않고 꺼두는 것이다.
    # 현행 모델 anthropic.claude-3-5-sonnet-20240620-v1:0 은 Bedrock 프롬프트 캐싱을
    # 지원하지 않는다(실측: cachePoint 를 붙이면 AccessDeniedException "You invoked an
    # unsupported model or your request did not allow prompt caching", 떼면 성공).
    # 이 값이 True 면 **모든 호출이 BedrockFatalError 로 죽는다** — 폴백 통과로 새지는
    # 않지만 러너가 한 건도 처리하지 못한다.
    # 캐싱을 지원하는 Claude 4+ 는 서울에서 INFERENCE_PROFILE 전용이고, apac.* 프로파일은
    # 도쿄로 라우팅돼 SCP p-meobeew3 가 명시적으로 거부한다(BRK-LLM-6d).
    # → 서울에 캐싱 지원 ON_DEMAND 모델이 들어오면 그때 True 로 켜면 된다.
    BEDROCK_PROMPT_CACHE: bool = False

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
            "베어 모델 ID(예: anthropic.claude-3-5-sonnet-20240620-v1:0)를 환경변수로 지정하세요."
        )

    profile_id = _profile_id_part(model_id)

    # BRK-LLM-6b/6d — 추론 프로파일은 어느 것도 허용하지 않는다. 기동 시 거부하고 중단한다.
    #
    # global.* 은 데이터가 APAC 밖으로 나가서 금지고(BRK-LLM-6b), apac.* 를 비롯한 나머지는
    # 서울 밖 리전으로 라우팅돼 SCP p-meobeew3 에 막힌다(BRK-LLM-6d). 후자를 경고로 두면
    # **기동은 되는데 첫 호출에서 전량 AccessDeniedException** 으로 죽으므로, 실패를 배치
    # 시작이 아니라 기동 시점으로 앞당긴다.
    for prefix in FORBIDDEN_PROFILE_PREFIXES:
        if profile_id.startswith(prefix):
            raise BedrockConfigError(
                "BEDROCK_MODEL_ID 로 추론 프로파일을 사용할 수 없습니다(BRK-LLM-6b/6d): "
                "'{model_id}'. 조직 SCP 가 서울(ap-northeast-2) 외 리전의 bedrock:InvokeModel 을 "
                "거부하므로 프로파일 호출은 반드시 실패합니다. 베어 모델 ID 를 지정하세요 "
                "(예: anthropic.claude-3-5-sonnet-20240620-v1:0).".format(model_id=model_id)
            )
