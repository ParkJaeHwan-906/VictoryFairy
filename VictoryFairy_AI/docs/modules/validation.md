# 모듈: validation (검열)

입력 문장에서 욕설·비속어를 탐지해 통과/폐기를 판정한다.
**작업 시 이 문서 범위 안에서 완결**하고, 다른 모듈(analysis/pipeline) 세부는 끌어오지 않는다.

진입점: `validation.main:app` · API prefix: `/api` · 라우트: `POST /api/validations`

## 기능 단위

각 기능은 독립적으로 이해·수정 가능하도록 파일이 분리돼 있다.

### 1. 정규화 (전처리)
- **파일**: `core/preprocess.py`, 데이터 `core/data/normalization.json`
- **역할**: 소문자화 · 다중/단일 문자 치환 · 공백/특수문자 제거로 회피 표기를 표면형으로 복원(`preprocess`).
- **다중 뷰 매칭**(`build_match_views`): 단일 정규화만으로는 한글 사이에 낀 기호·숫자(`씨@발` → `씨a발`)를 놓치므로, 같은 문장을 **최대 6개 뷰로 변환해 각각 매칭**한다.
  - ⚠️ **실제 반환 개수는 1~6개로 가변**이다 — 빈 뷰와 **앞선 뷰와 값이 같은 뷰는 버린다**(`preprocess.py`의 `if value and value not in seen`).
  - 실측: `'안녕하세요'` → **1개**(원문만. 정규화본이 원문과 같고 영어·키보드 뷰가 빈 문자열) · `'오늘 삼성 라이온즈 경기 재밌었다'` → **1개** · `'씨@발 이게 뭐야'` → 5개 · `'tlqkf'` → 2개.
  - 즉 **정상 한국어 문장은 대개 1패스로 끝난다.** 오탐률·비용을 논할 때 "모든 문장이 6패스"로 가정하면 틀린다.
  | 뷰 | 변환 | 잡는 케이스 |
  |---|---|---|
  | 원문 | 소문자·공백 제거만 | 치환이 훼손할 표기 방어 |
  | 정규화 | `preprocess` 전체 | 표준 우회(`s1b4l`→`sibal`) |
  | 압축 | 정규화본 - 숫자 | 숫자 노이즈 병합 |
  | 한글 | 정규화본 한글(자모)만 | `씨@발`·`시8발`→`씨발`·`시발` |
  | 영어 | 정규화본 라틴만 | 로마자(`sibal`) 격리 |
  | 키보드 | 영어 뷰를 두벌식 자판 복원(`keyboard_to_hangul`) | 한/영 키 미전환(`tlqkf`→`시발`) — **완성 음절만** 매칭 |
- **주의**: 한글 자모(ㄱ-ㅎ)는 보존한다 → 초성 욕설(`ㅅㅂ`) 매칭 가능. 검열 전용이며 형태소 추출에는 쓰지 않는다.

### 2. 비속어 패턴·사전
- **파일**: `core/patterns.py`, 로더 `core/resources.py`, 데이터 `core/data/banned_words.json`(카테고리별: general/sexual/parent, 초성 욕설 포함) · `core/data/exceptions.json`(오탐 방지 whitelist)
- **역할**: 사전을 정규화 후 정규식으로 컴파일(길이 내림차순). 사전 파일만 고치면 반영.

### 3. 검열 서비스 (핵심 로직)
- **파일**: `services/validation.py` (`validation_service`)
- **흐름**: `build_match_views`로 뷰 생성(최대 6개, 중복·빈 뷰 제외) → 각 뷰마다 (예외 표현 제거 → 패턴 검색) → 첫 매칭 시 폐기. **키보드 뷰는 `KEYBOARD_PATTERNS`(완성 음절만)**, 나머지는 `CATEGORY_PATTERNS` 사용. 어느 뷰에서도 안 걸리면 통과.
- **테스트**: `tests/test_validation.py` (pytest 없이 `python3 tests/test_validation.py`로 실행 가능).

### 4. 스키마
- **파일**: `schemas/validation.py` — `ValidationRequest{line}` / `ValidationResponse{is_valid, message}`

### 5. API 배선
- **파일**: `api/routes/validation.py`, `api/router.py`, `main.py`

### (참고) 곁가지 기능 — 이 모듈의 검열과 무관
- `services/prediction.py` + `api/routes/prediction.py` (승부 예측 더미), `api/routes/health.py` (헬스체크). 검열 작업 시 건드리지 않는다.

## 한계 · 향후 과제
- **혼용 표기 오탐**: 한글/영어 뷰는 단어 경계를 없애므로, 한글 사이에 라틴/숫자를 억지로 끼운 비정상 표기(`역시b발라드`→`시발`)에서 드물게 오탐. 정상 표현은 `exceptions.json`으로 보정. (자연스러운 한국어 문장에서는 신규 오탐 미발생 — 30문장 측정 기준)
- **키보드 뷰 오탐 방지**: 영단어를 자판 복원하면 낱자 초성(`ㅁㅊ`,`ㅗ`)이 남아 초성 욕설과 대량 오탐 → 키보드 뷰는 **완성형 음절 욕설만** 매칭(`KEYBOARD_PATTERNS`). Shift 조합(`ㄲㅆㅃ`, `ㅒㅖ`)은 소문자에서 구분 불가라 미지원(예: `개새끼`의 `ㄲ`).
- **로마자(발음) 표기는 사전 의존**: `sibal` 같은 **발음 로마자**는 로마자→한글 변환이 아니라 `banned_words.json` 리터럴로 매칭된다(표기가 제각각이라 재현율 한계). 반면 **키보드 미전환(`tlqkf`)은 키보드 뷰가 결정적으로 복원**한다. 발음 로마자의 일반화는 사전을 로마자로 정변환해 확장하는 방식이 대안(미도입).

## 데이터 사전 추가 방법
- 비속어 추가: `core/data/banned_words.json` 해당 카테고리에 단어 추가(초성도 가능).
- 오탐 방지: 정상 표현을 `core/data/exceptions.json`에 추가.

## 교차 의존 (명시)
- pipeline `run_validation`이 `validation_service`를 import해 S3 게시글(`community/{source}/{date}/*.json`)을 검열하고 결과를 S3(`validation/pattern/...`)에 쓴다(로컬 파일 아님 — 상세는 `docs/modules/pipeline.md`). 검열 로직 자체는 validation 안에서 완결. 이 S3 출력이 analysis 입력으로 자동 연결되지는 않는다(배선 끊김, `pipeline.md` "한계" 참고).
