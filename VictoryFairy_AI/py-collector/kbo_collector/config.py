from functools import lru_cache

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    # --- S3 ---
    s3_bucket: str = Field(validation_alias="COLLECTOR_S3_BUCKET")
    s3_region: str = Field(default="ap-northeast-2", validation_alias="COLLECTOR_S3_REGION")
    s3_endpoint: str | None = Field(default=None, validation_alias="COLLECTOR_S3_ENDPOINT")
    s3_path_style: bool = Field(default=False, validation_alias="COLLECTOR_S3_PATH_STYLE")

    # --- Naver source ---
    naver_base_url: str = Field(
        default="https://api-gw.sports.naver.com", validation_alias="COLLECTOR_NAVER_BASE_URL"
    )
    naver_referer: str = Field(
        default="https://m.sports.naver.com/", validation_alias="COLLECTOR_NAVER_REFERER"
    )
    schedule_url_template: str = Field(
        default="{base}/schedule/games?fields=basic,statusNum,statusInfo"
        "&upperCategoryId=kbaseball&fromDate={date}&toDate={date}",
        validation_alias="COLLECTOR_SCHEDULE_URL_TEMPLATE",
    )
    result_url_template: str = Field(
        default="{base}/schedule/games/{gameId}", validation_alias="COLLECTOR_RESULT_URL_TEMPLATE"
    )
    relay_url_template: str = Field(
        default="{base}/schedule/games/{gameId}/relay?inning={inning}",
        validation_alias="COLLECTOR_RELAY_URL_TEMPLATE",
    )

    # --- community ---
    targets_file: str = Field(default="config/targets.yaml", validation_alias="COLLECTOR_TARGETS_FILE")
    top_comments: int = Field(default=20, validation_alias="COLLECTOR_TOP_COMMENTS")
    fetch_delay_ms: int = Field(default=800, validation_alias="COLLECTOR_FETCH_DELAY_MS")
    list_pages: int = Field(default=1, validation_alias="COLLECTOR_LIST_PAGES")
    # Safety backstop when paging a list back to a target date; the date-based
    # early-stop normally halts long before this.
    community_max_pages: int = Field(default=2000, validation_alias="COLLECTOR_COMMUNITY_MAX_PAGES")
    # Max posts to detail-fetch per target on a date-filtered crawl (newest-first).
    # 0 = unlimited. Bounds otherwise-huge high-traffic galleries.
    community_per_target_cap: int = Field(
        default=0, validation_alias="COLLECTOR_COMMUNITY_PER_TARGET_CAP")
    # Concurrent detail fetches per target (1 = serial). Higher = faster but
    # raises the chance of a 403/429 rate-limit from the source.
    community_concurrency: int = Field(
        default=1, validation_alias="COLLECTOR_COMMUNITY_CONCURRENCY")
    # Re-attempts for a single list page before giving up on the target. Deep
    # walks (e.g. FMKorea's ~700 pages) periodically hit an intermittent 430
    # rate-limit; retrying the page with a pause survives it.
    community_list_retries: int = Field(
        default=5, validation_alias="COLLECTOR_COMMUNITY_LIST_RETRIES")
    # Popularity filter (OR): a post is detail-fetched when its list recommend
    # count >= min_recommend, OR its list views >= view_factor * (avg views of
    # that target's posts on the date). 0 disables that arm. A post with neither
    # signal in the list (e.g. FMKorea has no view count) is judged on whatever
    # signal it does have; a post with no signals at all is kept.
    community_min_recommend: int = Field(
        default=10, validation_alias="COLLECTOR_COMMUNITY_MIN_RECOMMEND")
    community_view_factor: float = Field(
        default=3.0, validation_alias="COLLECTOR_COMMUNITY_VIEW_FACTOR")
    # Pages to scan for popularity-ordered targets (order: popular), which have no
    # usable date order, so we scan a bounded window instead of walking to a date.
    community_popular_scan_pages: int = Field(
        default=15, validation_alias="COLLECTOR_COMMUNITY_POPULAR_SCAN_PAGES")
    pii_salt: str = Field(validation_alias="COLLECTOR_PII_SALT")

    # --- MySQL sink (선수/구단 DB 적재) ---
    db_host: str = Field(default="127.0.0.1", validation_alias="COLLECTOR_DB_HOST")
    db_port: int = Field(default=3306, validation_alias="COLLECTOR_DB_PORT")
    db_name: str = Field(default="victoryfairy", validation_alias="COLLECTOR_DB_NAME")
    db_user: str = Field(default="vf", validation_alias="COLLECTOR_DB_USER")
    db_password: str = Field(default="vfpass", validation_alias="COLLECTOR_DB_PASSWORD")

    # --- KBO source (선수 로스터) ---
    kbo_base_url: str = Field(
        default="https://www.koreabaseball.com", validation_alias="COLLECTOR_KBO_BASE_URL"
    )

    # --- question-source (질문 생성 인계) ---
    memes_file: str = Field(default="config/memes.yaml", validation_alias="COLLECTOR_MEMES_FILE")

    # --- retry / local paths ---
    retry_attempts: int = Field(default=3, validation_alias="RETRY_ATTEMPTS")
    retry_backoff_base: float = Field(default=0.5, validation_alias="RETRY_BACKOFF_BASE")
    # Long cooldown (seconds) applied between retries of a rate-limited response
    # (HTTP 429/430), instead of the fast exponential backoff used for 5xx/transport.
    rate_limit_cooldown_s: float = Field(default=15.0, validation_alias="RATE_LIMIT_COOLDOWN_S")
    http_timeout_s: float = Field(default=10.0, validation_alias="HTTP_TIMEOUT_S")
    log_dir: str = Field(default="./logs", validation_alias="LOG_DIR")
    journal_dir: str = Field(default="./journal", validation_alias="JOURNAL_DIR")


@lru_cache
def get_settings() -> Settings:
    return Settings()
