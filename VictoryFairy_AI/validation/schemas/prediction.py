from pydantic import BaseModel, Field


class PredictionRequest(BaseModel):
    """예측 요청 입력 스키마."""

    home_team: str = Field(..., description="홈 팀 이름")
    away_team: str = Field(..., description="원정 팀 이름")


class PredictionResponse(BaseModel):
    """예측 결과 응답 스키마."""

    home_team: str
    away_team: str
    home_win_probability: float = Field(..., ge=0.0, le=1.0)
    away_win_probability: float = Field(..., ge=0.0, le=1.0)
