"""pipeline 모듈 전용 환경 설정.

⚠️ S3 관련 설정(버킷명·리전 등)은 여기(pipeline)에만 둔다.
validation·analysis 모듈은 순수 검열/분석 모듈이라 S3 를 몰라야 하므로
`validation/core/config.py`·`analysis` 쪽 설정에는 절대 섞지 않는다.

버킷명·자격증명은 코드에 하드코딩하지 않고 환경변수/.env 에서만 읽는다(PIPE-S3IO-15).
"""

from typing import Optional

from pydantic_settings import BaseSettings, SettingsConfigDict


class PipelineSettings(BaseSettings):
    """배치 러너(pipeline)가 사용하는 환경 설정.

    - S3_BUCKET: 입력·출력에 공통으로 쓰는 단일 버킷명(PIPE-S3IO-1). 값이 없으면
      러너가 실행 중단 + 명확한 에러를 낸다(러너 쪽에서 명시적으로 검사한다 —
      여기서 필수값으로 강제하면 pydantic 기본 에러 메시지만 나와 불친절하다).
    - AWS_REGION: S3 클라이언트 리전(PIPE-S3IO-5). 기본값은 ap-northeast-2.
    """

    S3_BUCKET: Optional[str] = None
    AWS_REGION: str = "ap-northeast-2"
    # S3 접근 엔드포인트 override. 비우면(None) boto3 기본 AWS 리전 엔드포인트를 쓴다.
    # VPC 게이트웨이 엔드포인트나 MinIO 등 S3 호환 스토리지에 붙일 때만 지정한다.
    S3_ENDPOINT_URL: Optional[str] = None

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )


pipeline_settings = PipelineSettings()
