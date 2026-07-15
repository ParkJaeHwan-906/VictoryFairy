from fastapi import FastAPI

from validation.api.router import api_router
from validation.core.config import settings


def create_app() -> FastAPI:
    """FastAPI 애플리케이션 인스턴스를 생성한다."""
    app = FastAPI(
        title=settings.APP_NAME,
        version=settings.APP_VERSION,
        debug=settings.DEBUG,
    )

    app.include_router(api_router, prefix=settings.API_PREFIX)

    @app.get("/")
    async def root() -> dict[str, str]:
        return {"message": f"{settings.APP_NAME} is running"}

    return app


app = create_app()
