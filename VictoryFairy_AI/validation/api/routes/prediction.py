from fastapi import APIRouter

from validation.schemas.prediction import PredictionRequest, PredictionResponse
from validation.services.prediction import prediction_service

router = APIRouter(prefix="/predictions", tags=["predictions"])


@router.post("", response_model=PredictionResponse)
async def create_prediction(request: PredictionRequest) -> PredictionResponse:
    """홈/원정 팀을 받아 승부 예측 결과를 반환한다."""
    return prediction_service.predict(request)
