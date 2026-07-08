from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """애플리케이션 환경 설정.

    .env 파일 또는 환경 변수에서 값을 읽어온다.
    """

    APP_NAME: str = "VictoryFairy AI"
    APP_VERSION: str = "0.1.0"
    DEBUG: bool = False

    API_PREFIX: str = "/api"

    model_config = SettingsConfigDict(
        env_file=".env",
        env_file_encoding="utf-8",
        extra="ignore",
    )


settings = Settings()
