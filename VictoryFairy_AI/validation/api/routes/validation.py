from fastapi import APIRouter

from validation.schemas.validation import ValidationRequest, ValidationResponse
from validation.services.validation import validation_service

router = APIRouter(prefix="/validations", tags=["validations"])


@router.post("", response_model=ValidationResponse)
async def validate_line(request: ValidationRequest) -> ValidationResponse:
    """문장을 받아 비속어 포함 여부를 검증한다."""
    return validation_service.validation(request)
