"""한국어 NER(개체명 인식) 서비스 + 사전 후처리.

기성 사전학습 모델(KoELECTRA, MODU 코퍼스)로 문장에서 개체명을 추출하고,
사전(gazetteer)으로 결과를 보정한다.
    - PS(사람)·LC(지명)·OG(기관)·DT(날짜)를 우리 카테고리로 매핑한다.
    - 인명: 성씨 사전 교차검증 + 등록 인명 whitelist + 오탐 blocklist 로 정제.
    - 기관/인명: 등록 사전에 있는데 모델이 놓친 것은 강제 추가.
    - 명사·동사 추출은 NER 이 아니라 Kiwi(형태소)가 담당한다(analysis.py).

설계 원칙(§178): NER 입력도 '원문' 기준으로 처리한다(정규화본 사용 금지).
사전 파일은 core/data/*.json 이며, 값만 추가하면 코드 변경 없이 반영된다.
"""

from transformers import pipeline

from analysis.core.resources import (
    load_organizations,
    load_person_stopwords,
    load_persons,
    load_surnames,
)

# MODU NER 코퍼스로 학습된 소형 KoELECTRA 모델(PS/LC/OG/DT 등 15종 태그).
_MODEL_NAME = "Leo97/KoELECTRA-small-v3-modu-ner"

# NER 엔티티 태그 → 우리 결과 카테고리
_TAG_TO_CATEGORY: dict[str, str] = {
    "PS": "names",          # 사람
    "LC": "locations",      # 지명
    "OG": "organizations",  # 기관
    "DT": "dates",          # 날짜
}

# 추출 카테고리 목록(빈 결과 초기화용)
CATEGORIES: tuple[str, ...] = tuple(dict.fromkeys(_TAG_TO_CATEGORY.values()))

# --- 신뢰도 임계값 ---
_DEFAULT_THRESHOLD = 0.5   # 지명·기관·날짜: 이 이상만 채택
_PERSON_FLOOR = 0.3        # 인명 후보로 볼 최소 신뢰도(이하는 무시)
_PERSON_HIGH = 0.7         # 인명 고신뢰: 즉시 인정
_PERSON_SURNAME_MIN = 0.5  # 성씨 + 이 이상 신뢰도면 인정

# --- 사전(gazetteer) 로드: 모듈 로드 시 1회 ---
_REGISTERED_PERSONS = load_persons()
_REGISTERED_ORGS = load_organizations()
_SURNAMES = load_surnames()
_PERSON_STOPWORDS = load_person_stopwords()

# 모델·토크나이저는 로드 비용이 크므로 모듈 로드 시 1회만 생성한다(싱글턴).
# aggregation_strategy="simple" 로 subword 토큰(B-/I-)을 하나의 개체명 span으로 병합한다.
_ner = pipeline("ner", model=_MODEL_NAME, aggregation_strategy="simple")


class NerService:
    """문장에서 개체명을 추출하고 사전으로 보정하는 서비스."""

    def extract(self, text: str) -> dict[str, list[str]]:
        """원문 문장에서 개체명을 추출·보정해 카테고리별 목록으로 반환한다.

        반환: {"names": [...], "locations": [...],
               "organizations": [...], "dates": [...]}
        """
        result: dict[str, list[str]] = {category: [] for category in CATEGORIES}
        person_candidates: list[tuple[str, float]] = []

        for entity in _ner(text):
            category = _TAG_TO_CATEGORY.get(entity["entity_group"])
            if category is None:
                continue  # 수집하지 않는 태그(CV/QT/TM 등)는 건너뛴다.
            word = entity["word"].strip()
            if not word:
                continue
            score = float(entity["score"])

            if category == "names":
                # 인명은 신뢰도가 필요하므로 후보로 모아 두었다가 사전으로 정제한다.
                if score >= _PERSON_FLOOR:
                    person_candidates.append((word, score))
            elif score >= _DEFAULT_THRESHOLD and word not in result[category]:
                result[category].append(word)

        # 인명 정제(성씨/등록/불용어) + 등록 인명 강제 추가
        result["names"] = self._refine_persons(text, person_candidates)
        # 등록 기관 강제 추가(모델이 모르는 신규 기관, 예: SSG 랜더스)
        self._force_add(text, _REGISTERED_ORGS, result["organizations"])
        # 겹치는 span 정리('SSG 랜더스' 안의 'SSG'·'랜더스' 조각 제거)
        self._remove_overlaps(result)

        return result

    @staticmethod
    def _remove_overlaps(result: dict[str, list[str]]) -> None:
        """등록 기관이 커버하는 span 안의 조각 개체명을 제거한다.

        - 기관 목록 내 부분문자열 제거: 'SSG' ⊂ 'SSG 랜더스' → 'SSG' 삭제.
        - 기관에 포함된 인명·지명·날짜 제거: 'SSG 랜더스' 안의 '랜더스'(인명 오분류) 삭제.
        """
        orgs = result["organizations"]
        result["organizations"] = [
            org for org in orgs
            if not any(org != other and org in other for other in orgs)
        ]
        kept_orgs = result["organizations"]
        for category in ("names", "locations", "dates"):
            result[category] = [
                word for word in result[category]
                if not any(word != org and word in org for org in kept_orgs)
            ]

    @staticmethod
    def _refine_persons(text: str, candidates: list[tuple[str, float]]) -> list[str]:
        """인명 후보를 사전 규칙으로 정제한다."""
        names: list[str] = []
        for word, score in candidates:
            if word in _PERSON_STOPWORDS:
                continue  # 명시적 오탐 제거(김밥 등)
            # 한 글자 인명은 노이즈(홍/연 등)가 많으므로 등록 인명일 때만 인정.
            if len(word) < 2 and word not in _REGISTERED_PERSONS:
                continue
            keep = (
                word in _REGISTERED_PERSONS                        # 등록 인명: 무조건 인정
                or score >= _PERSON_HIGH                            # 고신뢰
                or (word[0] in _SURNAMES and score >= _PERSON_SURNAME_MIN)  # 성씨+중신뢰
            )
            if keep and word not in names:
                names.append(word)

        NerService._force_add(text, _REGISTERED_PERSONS, names)
        return names

    @staticmethod
    def _force_add(text: str, registered: set[str], target: list[str]) -> None:
        """등록 사전 항목이 문장에 있는데 결과에 없으면 강제로 추가한다."""
        for item in registered:
            if item and item in text and item not in target:
                target.append(item)


# 서비스에서 주입해 사용할 인스턴스
ner_service = NerService()
