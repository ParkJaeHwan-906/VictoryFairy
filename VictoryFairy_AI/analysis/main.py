from fastapi import FastAPI

from analysis.api.router import api_router
from analysis.core.config import settings


def create_app() -> FastAPI:
    """analysis(형태소 분석) FastAPI 애플리케이션 인스턴스를 생성한다."""
    app = FastAPI(
        title=f"{settings.APP_NAME} - Analysis",
        version=settings.APP_VERSION,
        debug=settings.DEBUG,
    )

    app.include_router(api_router, prefix=settings.API_PREFIX)

    @app.get("/")
    async def root() -> dict[str, str]:
        return {"message": f"{settings.APP_NAME} analysis is running"}

    return app


app = create_app()
