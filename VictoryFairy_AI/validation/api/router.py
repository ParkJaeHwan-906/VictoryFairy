from fastapi import APIRouter

from validation.api.routes import health, prediction, validation

api_router = APIRouter()
api_router.include_router(health.router)
api_router.include_router(prediction.router)
api_router.include_router(validation.router)
