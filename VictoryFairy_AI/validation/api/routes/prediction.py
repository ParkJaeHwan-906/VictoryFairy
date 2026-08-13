from fastapi import APIRouter

from validation.schemas.prediction import PredictionRequest, PredictionResponse
from validation.services.prediction import prediction_service

router = APIRouter(prefix="/predictions", tags=["predictions"])


@router.post("", response_model=PredictionResponse)
async def create_prediction(request: PredictionRequest) -> PredictionResponse:
    return prediction_service.predict(request)
