from pydantic import BaseModel, Field

class ValidationRequest(BaseModel):
    """검증 요청 스키마"""

    line: str = Field(..., description="검증할 데이터")

class ValidationResponse(BaseModel):
    """검증 응답 스키마"""

    is_valid: bool = Field(..., description="검증 결과")
    message: str = Field(..., description="검증 메시지")