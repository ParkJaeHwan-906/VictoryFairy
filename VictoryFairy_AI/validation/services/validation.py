from validation.core.patterns import (
    CATEGORY_PATTERNS,
    EXCEPTION_PATTERN,
    KEYBOARD_PATTERNS,
)
from validation.core.preprocess import build_match_views
from validation.schemas.validation import ValidationRequest, ValidationResponse

# 카테고리 코드 → 사용자에게 보여줄 한글 라벨
#
# 비하 표현은 어휘마다 오탐 성향이 크게 달라(폐급·대가리·쳐먹은 기량 비판과 형태가
# 겹치고, 정신병자·장애인은 거의 겹치지 않는다) 한 덩어리로 묶지 않고 단어별
# 카테고리로 나눠 둔다. 그래야 특정 표현만 빼거나 되돌릴 때 나머지 판정이 흔들리지 않는다.
_CATEGORY_LABELS: dict[str, str] = {
    "general": "욕설",
    "sexual": "성적 표현",
    "parent": "패드립",
    "pyegeup": "비하 표현(폐급)",
    "disabled": "장애 비하",
    "daegari": "비하 표현(대가리)",
    "mental": "정신질환 비하",
    "gaessip": "비하 표현(개씹)",
    "aemchang": "패드립(앰창)",
}


class ValidationService:
    def validation(self, request: ValidationRequest) -> ValidationResponse:
        # 입력을 여러 뷰(원문·정규화·압축·한글·영어·키보드)로 변환해 순서대로 검사한다.
        for view_name, view in build_match_views(request.line):
            # 오탐 방지: 정상 표현(예: '보지도 못했다')을 먼저 제거한다.
            cleaned = EXCEPTION_PATTERN.sub("", view)

            # '키보드' 뷰는 오탐이 커서 완성형 음절 욕설만 검사한다(초성 욕설 제외).
            patterns = KEYBOARD_PATTERNS if view_name == "키보드" else CATEGORY_PATTERNS

            # 카테고리별로 비속어 패턴 검색 — 첫 매칭 시 폐기.
            for category, pattern in patterns.items():
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
