# analysis 사전(dictionary) 파일

NER(개체명 인식) 결과를 보정·집계하는 데 쓰는 사전들이다.
**코드 수정 없이 이 JSON 파일에 값만 추가하면** 파이프라인에 즉시 반영된다.
(로더: `analysis/core/resources.py` — 파일이 없거나 비어 있어도 안전하게 동작한다.)

## 사전 목록

| 파일 | 형식 | 역할 | 효과 |
|---|---|---|---|
| `persons.json` | `list[str]` | 등록 인명 whitelist | NER이 놓친 인명을 **강제 추가**(재현율↑), 신뢰도 무시하고 인정 |
| `surnames.json` | `list[str]` | 한국 성씨 | 인명 후보 첫 글자가 성씨인지 **교차검증**(오탐 억제, 정밀도↑) |
| `person_stopwords.json` | `list[str]` | 인명 오탐 blocklist | `김밥`처럼 인명으로 오인되는 단어를 **강제 제외** |
| `organizations.json` | `list[str]` | 등록 기관 whitelist | NER이 모르는 신규·도메인 기관(예: `SSG 랜더스`)을 **강제 추가** |
| `aliases.json` | `dict[str,str]` | 표면형 → 정규 인명 | 집계 시 `길동`→`홍길동`처럼 **동일 인물 통합** |

## 추가 방법 (예시)

- 인명 추가: `persons.json` 에 `"손흥민"` 한 줄 추가.
- 기관 추가: `organizations.json` 에 `"신한은행"` 추가.
- 별칭 추가: `aliases.json` 에 `"흥민": "손흥민"` 추가.

## 주의

- `aliases.json` 과 유사도 클러스터링은 **동명이인**을 구분하지 못한다. 잘못 묶을 위험이 있으면 별칭 사전(명시적 규칙)을 우선한다.
- 성씨 교차검증은 한 글자 인명 노이즈(`홍`, `연`)를 줄이는 보조 수단이며, 완벽하지 않다.
