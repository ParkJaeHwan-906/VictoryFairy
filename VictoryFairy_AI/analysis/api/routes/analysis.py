from fastapi import APIRouter

from analysis.schemas.analysis import AnalysisRequest, AnalysisResponse
from analysis.services.analysis import analysis_service

router = APIRouter(prefix="/analysis", tags=["analysis"])


@router.post("", response_model=AnalysisResponse)
async def analyze_lines(request: AnalysisRequest) -> AnalysisResponse:
    """검열을 통과한 문장에서 명사·동사·인명을 추출한다."""
    return analysis_service.analyze(request)
