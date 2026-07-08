from pydantic import BaseModel, Field


class AnalysisRequest(BaseModel):
    """형태소 분석 요청 스키마.

    검열(1차 필터링)을 통과한 문장 목록을 입력으로 받는다.
    """

    lines: list[str] = Field(..., description="검열을 통과한 문장 목록")


class SentenceKeywords(BaseModel):
    """문장 1개의 키워드 추출 결과.

    - 개체명(이름·지명·기관·날짜)은 NER 모델(PS/LC/OG/DT)에서 추출한다.
    - 명사·동사는 Kiwi 형태소 분석(NNG/VV)에서 추출한다.
    """

    sent: str = Field(..., description="원본 문장")
    names: list[str] = Field(default_factory=list, description="사람 이름(NER PS)")
    locations: list[str] = Field(default_factory=list, description="지명(NER LC)")
    organizations: list[str] = Field(default_factory=list, description="기관(NER OG)")
    dates: list[str] = Field(default_factory=list, description="날짜(NER DT)")
    nouns: list[str] = Field(default_factory=list, description="명사(Kiwi NNG)")
    verbs: list[str] = Field(default_factory=list, description="동사(Kiwi VV, 원형)")


class AnalysisResponse(BaseModel):
    """형태소 분석 응답 스키마."""

    results: list[SentenceKeywords] = Field(default_factory=list)
