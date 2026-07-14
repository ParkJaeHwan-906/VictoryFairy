from validation.core.patterns import CATEGORY_PATTERNS, EXCEPTION_PATTERN
from validation.core.preprocess import build_match_views
from validation.schemas.validation import ValidationRequest, ValidationResponse

# 카테고리 코드 → 사용자에게 보여줄 한글 라벨
_CATEGORY_LABELS: dict[str, str] = {
    "general": "욕설",
    "sexual": "성적 표현",
    "parent": "패드립",
}


class ValidationService:
    """
    문장 검증 서비스
    - 문장 내 욕설 필터링 (정규 표현식 기반)
    - 입력을 여러 형태(원문/정규화/압축/한글/영어)로 변환해 각각 매칭한다(다중 패스).
      한 형태에서 놓친 우회 표기(예: '씨@발')를 다른 형태('씨발')에서 잡기 위함이다.
    - 매칭 전 예외 표현(정상 표현)을 제거해 오탐을 방지한다.
    """

    def validation(self, request: ValidationRequest) -> ValidationResponse:
        """
        문장 내 비속어를 카테고리별 정규식으로 필터링한다.

        - request: ValidationRequest
        - return: ValidationResponse
        """
        # 입력을 여러 뷰(원문·정규화·압축·한글·영어)로 변환해 순서대로 검사한다.
        for _view_name, view in build_match_views(request.line):
            # 오탐 방지: 정상 표현(예: '보지도 못했다')을 먼저 제거한다.
            cleaned = EXCEPTION_PATTERN.sub("", view)

            # 카테고리별로 비속어 패턴 검색 — 첫 매칭 시 폐기.
            for category, pattern in CATEGORY_PATTERNS.items():
                match = pattern.search(cleaned)
                if match:
                    label = _CATEGORY_LABELS.get(category, category)
                    return ValidationResponse(
                        is_valid=False,
                        message=f"{label}이(가) 감지되었습니다: '{match.group()}'",
                    )

        return ValidationResponse(
            is_valid=True,
            message="검증을 통과했습니다.",
        )


# 라우터에서 주입해 사용할 인스턴스
validation_service = ValidationService()
