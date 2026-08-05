from fastapi import APIRouter

from analysis.api.routes import analysis

api_router = APIRouter()
api_router.include_router(analysis.router)
