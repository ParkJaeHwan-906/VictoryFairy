# 모듈: analysis (형태소 + 개체명 추출)

검열을 통과한 **원문** 문장에서 명사·동사(Kiwi)와 이름·지명·기관·날짜(NER)를 추출한다.
**작업 시 이 문서 범위 안에서 완결**한다.

진입점: `analysis.main:app` · API prefix: `/api` · 라우트: `POST /api/analysis`

## 기능 단위 (서로 침범하지 않게 분리)

### 1. 형태소 분석 (Kiwi)
- **파일**: `services/analysis.py` (`analysis_service`)
- **역할**: `split_into_sents`로 문장 분리 → 문장별 `analyze()`로 **명사(NNG)·동사(VV)** 추출. 동사는 어간에 '다'를 붙여 원형 복원.
- **원칙**: 원문 기준(검열 정규화본 재사용 금지). 개체명은 이 파일이 아니라 NER(2)에 위임.

### 2. NER (개체명) + 사전 후처리
- **파일**: `services/ner.py` (`ner_service`), 로더 `core/resources.py`, 데이터 `core/data/*.json`
- **모델**: `Leo97/KoELECTRA-small-v3-modu-ner` (PS 사람 / LC 지명 / OG 기관 / DT 날짜). 모듈 로드 시 싱글턴.
- **후처리(gazetteer)**:
  - 인명 정제: 등록 인명 whitelist(`persons.json`) · 성씨 교차검증(`surnames.json`) · 오탐 blocklist(`person_stopwords.json`) · 한 글자 인명 제거.
  - 기관 강제 추가: `organizations.json` (모델이 모르는 신규 기관, 예: `SSG 랜더스`).
  - 겹침 정리: 등록 기관 span 안의 조각 개체명 제거.
- **사전 추가법**: `core/data/README.md` 참고. JSON에 값만 추가하면 반영.

### 3. 정규화·집계 (인명 통합)
- **파일**: `services/normalize.py` (`aggregate_persons`), 데이터 `core/data/aliases.json`
- **역할**: 별칭 사전 매핑 + 부분문자열 클러스터링으로 동일 인물 통합 → `{정규화_인명, 표면형_리스트, 출현_문장_id, 빈도}` 집계. (실제 집계 실행은 pipeline `run_aggregate`)
- **주의**: 보수적 병합(한 글자 제외), 동명이인 구분 불가. → 아래 "한계" 참고.

### 4. 스키마
- **파일**: `schemas/analysis.py` — `AnalysisRequest{lines}` / `SentenceKeywords{sent, names, locations, organizations, dates, nouns, verbs}` / `AnalysisResponse{results}`

### 5. API 배선
- **파일**: `api/routes/analysis.py`, `api/router.py`, `main.py`

## 카테고리 매핑 (한눈에)
| 결과 필드 | 출처 | 태그 |
|---|---|---|
| names | NER | PS |
| locations | NER | LC |
| organizations | NER | OG |
| dates | NER | DT |
| nouns | Kiwi | NNG |
| verbs | Kiwi | VV(원형) |

## 한계 · 향후 과제

- **모델이 모르는 개체명은 사전 의존**: KoELECTRA 는 학습 코퍼스(MODU) 기준이라 신규 인명·기관을 놓친다. `persons.json`·`organizations.json` 에 등록해 강제 추가하는 구조 — 즉 **재현율이 사전 관리에 묶여 있다**. 사전 없이 일반화하는 방법은 미도입.
- **한 글자 인명은 등록된 것만 인정**(`services/ner.py`): `홍`·`연` 같은 낱자는 노이즈가 압도적이라 `persons.json` 에 있을 때만 남긴다. 등록 안 된 한 글자 이름은 **의도적으로 버려진다**.
- **인명 병합은 보수적**(`services/normalize.py`): 부분문자열 클러스터링(`'길동' ⊂ '홍길동'`)으로 통합하되, **한 글자 표면형은 클러스터링에서 제외**한다(`_MIN_CLUSTER_LEN`). 오병합을 막으려 미병합을 감수한 선택.
- **동명이인 구분 불가**: 같은 표기는 한 인물로 합쳐진다. 문맥 기반 구분은 미도입.
- **개체명/명사 중복 제거는 통째 비교**(`services/analysis.py`): NER 이 잡은 표면형을 Kiwi 명사에서 빼되, **다중 단어 개체명(`'삼성 라이온즈'`)은 쪼개지 않고 통째로만 비교**한다 → 구성 낱말(`'삼성'`)이 명사에 남을 수 있다.
- **모델 출력은 버전 의존**: `transformers`·모델 갱신 시 결과가 달라질 수 있다. 테스트는 정확한 출력 일치보다 **gazetteer 후처리 규칙**을 검증하는 편이 안정적이다.

## 교차 의존 (명시)
- 입력: pipeline `run_analysis`가 `data/processed_data.txt`(검열 통과분)를 넘긴다 — 단, `run_validation`이 S3 기반으로 바뀌면서 이 파일의 자동 공급자가 없어졌다(수동 배치 필요, 상세는 `pipeline.md` "한계").
- 출력: `finished_data.jsonl`이 `run_aggregate`(집계)의 입력.
