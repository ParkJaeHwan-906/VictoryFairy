from fastapi import APIRouter

from validation.schemas.validation import ValidationRequest, ValidationResponse
from validation.services.validation import validation_service

router = APIRouter(prefix="/validations", tags=["validations"])


@router.post("", response_model=ValidationResponse)
async def validate_line(request: ValidationRequest) -> ValidationResponse:
    return validation_service.validation(request)
