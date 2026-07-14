# 모듈: validation (검열)

입력 문장에서 욕설·비속어를 탐지해 통과/폐기를 판정한다.
**작업 시 이 문서 범위 안에서 완결**하고, 다른 모듈(analysis/pipeline) 세부는 끌어오지 않는다.

진입점: `validation.main:app` · API prefix: `/api` · 라우트: `POST /api/validations`

## 기능 단위

각 기능은 독립적으로 이해·수정 가능하도록 파일이 분리돼 있다.

### 1. 정규화 (전처리)
- **파일**: `core/preprocess.py`, 데이터 `core/data/normalization.json`
- **역할**: 소문자화 · 다중/단일 문자 치환 · 공백/특수문자 제거로 회피 표기를 표면형으로 복원(`preprocess`).
- **다중 뷰 매칭**(`build_match_views`): 단일 정규화만으로는 한글 사이에 낀 기호·숫자(`씨@발` → `씨a발`)를 놓치므로, 같은 문장을 **5개 뷰로 변환해 각각 매칭**한다.
  | 뷰 | 변환 | 잡는 케이스 |
  |---|---|---|
  | 원문 | 소문자·공백 제거만 | 치환이 훼손할 표기 방어 |
  | 정규화 | `preprocess` 전체 | 표준 우회(`s1b4l`→`sibal`) |
  | 압축 | 정규화본 - 숫자 | 숫자 노이즈 병합 |
  | 한글 | 정규화본 한글(자모)만 | `씨@발`·`시8발`→`씨발`·`시발` |
  | 영어 | 정규화본 라틴만 | 로마자(`sibal`) 격리 |
- **주의**: 한글 자모(ㄱ-ㅎ)는 보존한다 → 초성 욕설(`ㅅㅂ`) 매칭 가능. 검열 전용이며 형태소 추출에는 쓰지 않는다.

### 2. 비속어 패턴·사전
- **파일**: `core/patterns.py`, 로더 `core/resources.py`, 데이터 `core/data/banned_words.json`(카테고리별: general/sexual/parent, 초성 욕설 포함) · `core/data/exceptions.json`(오탐 방지 whitelist)
- **역할**: 사전을 정규화 후 정규식으로 컴파일(길이 내림차순). 사전 파일만 고치면 반영.

### 3. 검열 서비스 (핵심 로직)
- **파일**: `services/validation.py` (`validation_service`)
- **흐름**: `build_match_views`로 5개 뷰 생성 → 각 뷰마다 (예외 표현 제거 → 카테고리별 패턴 검색) → 첫 매칭 시 폐기(카테고리 라벨·매칭어 포함), 어느 뷰에서도 안 걸리면 통과.
- **테스트**: `tests/test_validation.py` (pytest 없이 `python3 tests/test_validation.py`로 실행 가능).

## 한계 · 향후 과제
- **혼용 표기 오탐**: 한글/영어 뷰는 단어 경계를 없애므로, 한글 사이에 라틴/숫자를 억지로 끼운 비정상 표기(`역시b발라드`→`시발`)에서 드물게 오탐. 정상 표현은 `exceptions.json`으로 보정. (자연스러운 한국어 문장에서는 신규 오탐 미발생 — 30문장 측정 기준)
- **로마자는 사전 의존**: `sibal`·`tlbal`은 로마자→한글 변환이 아니라 **`banned_words.json`에 리터럴로 등록돼** 매칭된다. `tlbal`은 사실 `시발`의 두벌식 무변환 표기(`tlqkf`)와 불일치하는 임의 항목 → 검토 대상. 일반적 로마자/키보드 매핑은 별도 정규화 엔진이 필요(미도입).

### 4. 스키마
- **파일**: `schemas/validation.py` — `ValidationRequest{line}` / `ValidationResponse{is_valid, message}`

### 5. API 배선
- **파일**: `api/routes/validation.py`, `api/router.py`, `main.py`

### (참고) 곁가지 기능 — 이 모듈의 검열과 무관
- `services/prediction.py` + `api/routes/prediction.py` (승부 예측 더미), `api/routes/health.py` (헬스체크). 검열 작업 시 건드리지 않는다.

## 데이터 사전 추가 방법
- 비속어 추가: `core/data/banned_words.json` 해당 카테고리에 단어 추가(초성도 가능).
- 오탐 방지: 정상 표현을 `core/data/exceptions.json`에 추가.

## 교차 의존 (명시)
- validation의 **출력(통과 문장)** 이 pipeline `run_validation`을 거쳐 analysis의 입력이 된다. 검열 로직 자체는 validation 안에서 완결.
