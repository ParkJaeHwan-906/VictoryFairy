from pydantic import BaseModel, Field


class PredictionRequest(BaseModel):
    home_team: str = Field(..., description="홈 팀 이름")
    away_team: str = Field(..., description="원정 팀 이름")


class PredictionResponse(BaseModel):
    home_team: str
    away_team: str
    home_win_probability: float = Field(..., ge=0.0, le=1.0)
    away_win_probability: float = Field(..., ge=0.0, le=1.0)
