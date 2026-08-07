"""키워드 추출 서비스 (Kiwi 형태소 + NER 하이브리드).

검열을 통과한 '원문' 문장에서 키워드를 추출한다.
    - 개체명(이름·지명·기관·날짜·시간): NER 모델(PS/LC/OG/DT/TI)
    - 명사·동사: Kiwi 형태소 분석(NNG/VV)

설계 원칙(§178):
    - 추출은 반드시 '원문' 기준으로 수행한다.
    - validation 의 preprocess()(정규화본)를 재사용하지 않는다.
      정규화는 표면형을 훼손해 명사·이름을 깨뜨리기 때문이다.
"""

from kiwipiepy import Kiwi

from analysis.schemas.analysis import (
    AnalysisRequest,
    AnalysisResponse,
    SentenceKeywords,
)
from analysis.services.ner import ner_service

# Kiwi 인스턴스는 초기화 비용이 크므로 모듈 로드 시 1회만 생성한다(싱글턴).
_kiwi = Kiwi()

# Kiwi 에서 추출할 품사 태그(개체명은 NER 이 담당하므로 명사·동사만 Kiwi 로 뽑는다).
_NOUN_TAG = "NNG"  # 일반명사
_VERB_TAG = "VV"   # 동사


class AnalysisService:
    """Kiwi 형태소 분석으로 명사·동사·인명을 추출하는 서비스."""

    def analyze(self, request: AnalysisRequest) -> AnalysisResponse:
        """문장 목록을 받아 문장별 형태소 추출 결과를 반환한다.

        - request: AnalysisRequest
        - return: AnalysisResponse
        """
        results: list[SentenceKeywords] = []
        for line in request.lines:
            results.extend(self._analyze_line(line))
        return AnalysisResponse(results=results)

    def _analyze_line(self, line: str) -> list[SentenceKeywords]:
        """한 줄(문장 여러 개 가능)을 문장 단위로 분리해 각각 분석한다."""
        keywords: list[SentenceKeywords] = []

        # 한 줄에 여러 문장이 섞여 있을 수 있으므로 먼저 문장을 분리한다.
        for sent in _kiwi.split_into_sents(line):
            # 명사·동사는 Kiwi 형태소에서 추출한다.
            nouns: list[str] = []
            verbs: list[str] = []
            for tok in _kiwi.analyze(sent.text)[0][0]:
                if tok.tag == _NOUN_TAG:
                    nouns.append(tok.form)
                elif tok.tag == _VERB_TAG:
                    # Kiwi 는 동사를 어간(예: '가')으로 반환하므로 '다'를 붙여 원형 복원.
                    verbs.append(tok.form + "다")

            # 개체명(이름·지명·기관·날짜)은 NER 에서 추출한다.
            entities = ner_service.extract(sent.text)

            # 중복 제거: NER 이 개체명으로 잡은 표면형은 Kiwi 명사 목록에서 뺀다.
            # (예: '오늘'이 [날짜]와 [명사]에 동시 등장하는 것을 방지)
            # 다중 단어 개체명('삼성 라이온즈')은 쪼개지 않고 통째로만 비교한다.
            entity_words: set[str] = {
                word for words in entities.values() for word in words
            }
            nouns = [noun for noun in nouns if noun not in entity_words]

            keywords.append(
                SentenceKeywords(
                    sent=sent.text,
                    names=entities["names"],
                    locations=entities["locations"],
                    organizations=entities["organizations"],
                    dates=entities["dates"],
                    nouns=nouns,
                    verbs=verbs,
                )
            )
        return keywords


# 라우터·배치 러너에서 주입해 사용할 인스턴스
analysis_service = AnalysisService()
