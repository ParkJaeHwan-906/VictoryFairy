from validation.schemas.prediction import PredictionRequest, PredictionResponse


class PredictionService:
    """승부 예측 비즈니스 로직.

    실제 모델 추론 로직은 이곳에 구현한다.
    지금은 구조 예시를 위한 더미(dummy) 구현이다.
    """

    def predict(self, request: PredictionRequest) -> PredictionResponse:
        # TODO: 실제 모델 추론으로 교체
        home_prob = 0.5
        away_prob = 1.0 - home_prob

        return PredictionResponse(
            home_team=request.home_team,
            away_team=request.away_team,
            home_win_probability=home_prob,
            away_win_probability=away_prob,
        )


# 라우터에서 주입해 사용할 인스턴스
prediction_service = PredictionService()
