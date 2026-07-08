from fastapi import APIRouter

from validation.core.config import settings
from validation.schemas.health import HealthResponse

router = APIRouter(tags=["health"])


@router.get("/health", response_model=HealthResponse)
async def health_check() -> HealthResponse:
    """서비스 상태 확인용 헬스 체크 엔드포인트."""
    return HealthResponse(
        status="ok",
        app=settings.APP_NAME,
        version=settings.APP_VERSION,
    )
