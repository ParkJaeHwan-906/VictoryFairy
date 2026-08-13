from pydantic import BaseModel, Field

class ValidationRequest(BaseModel):
    line: str = Field(..., description="검증할 데이터")

class ValidationResponse(BaseModel):
    is_valid: bool = Field(..., description="검증 결과")
    message: str = Field(..., description="검증 메시지")