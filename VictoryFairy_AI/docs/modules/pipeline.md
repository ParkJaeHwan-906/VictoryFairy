# 모듈: pipeline (배치 러너)

파일 기반으로 **검열 → 분석 → 집계** 단계를 순차 실행한다. 각 러너는 해당 서비스를
HTTP 없이 **직접 import**해 재사용한다. **작업 시 이 문서 범위 안에서 완결**한다.

실행(프로젝트 루트): `python -m pipeline.<러너>`

## 기능 단위 (러너별로 분리)

### 1. 검열 러너
- **파일**: `run_validation.py`
- **흐름**: `data/crawled_data.txt` → `validation_service`로 문장별 검열 → 통과분 `data/processed_data.txt`, 폐기분(+사유) `data/discarded_data.txt`.

### 2. 분석 러너
- **파일**: `run_analysis.py`
- **흐름**: `data/processed_data.txt` → `analysis_service`(Kiwi+NER) → 두 산출물:
  - `data/finished_data.txt` — 사람이 읽는 표시용(`원본 : [이름]|[지명]|[기관]|[날짜]|[명사]|[동사]`)
  - `data/finished_data.jsonl` — 집계용 구조화(문장마다 `문장_id` 부여)
- **빈 카테고리 생략**: 출력 포매터는 카테고리별로 비어있지 않은 것만 표시한다.
- ⚠️ **개체명/명사 중복 제거는 여기가 아니다.** NER이 잡은 표면형을 Kiwi 명사 목록에서 빼는 처리(`'오늘'`이 [날짜]와 [명사]에 중복 등장 방지)는 **analysis 모듈**(`analysis/services/analysis.py`) 소관이다 — pipeline 포매터를 뒤지지 말 것.

### 3. 집계 러너
- **파일**: `run_aggregate.py`
- **흐름**: `data/finished_data.jsonl` → `normalize.aggregate_persons` → `data/persons_aggregated.json`(인물별 통합 집계).

## 실행 순서

```bash
python -m pipeline.run_validation   # ① crawled → processed / discarded
python -m pipeline.run_analysis     # ② processed → finished + finished.jsonl
python -m pipeline.run_aggregate    # ③ finished.jsonl → persons_aggregated.json
```

## data/ 산출물 지도

| 파일 | 생성 단계 | 내용 |
|---|---|---|
| `crawled_data.txt` | (입력) | 원본 무작위 문장 |
| `processed_data.txt` | ① | 검열 통과 문장 |
| `discarded_data.txt` | ① | 폐기 문장 + 사유 |
| `finished_data.txt` | ② | 표시용 키워드 |
| `finished_data.jsonl` | ② | 집계용 구조화 데이터 |
| `persons_aggregated.json` | ③ | 인명 집계 |

## 한계 · 향후 과제

- **오케스트레이션만 담당**: 러너는 각 모듈 서비스를 import 해 쓸 뿐 판정 로직을 갖지 않는다. **러너 안에서 로직을 재구현하지 말 것** — 결과가 API 경로와 갈라진다.
- **단계 간 결합이 파일**: 앞 단계 산출물이 없으면 다음 단계가 못 돈다. 러너는 입력 부재를 검사해 멈춘다(`run_validation.py` 의 `path.exists()`).
- **`crawled_data.txt` 는 복구 불가**: 파이프라인의 **입력 원본**이라 어떤 러너도 만들어내지 못한다. 덮어쓰면 끝이다.
- **재실행이 곧 덮어쓰기**: 산출물에 버전·타임스탬프가 없어 러너를 다시 돌리면 이전 결과가 사라진다. 이력 보존은 미도입.
- **`run_analysis` 는 무겁다**: Kiwi + KoELECTRA(torch) 로딩 + 첫 실행 시 모델 다운로드. 전체 입력으로 돌리기 전에 **작은 샘플로 확인**하는 게 안전하다. 진행률 표시나 중단 재개는 미도입.

## 교차 의존 (명시)
- pipeline은 `validation.services.validation`·`analysis.services.analysis`·`analysis.services.normalize`를 import한다. 로직 변경은 각 모듈에서, pipeline은 **오케스트레이션(입출력 파일 배선)만** 담당.
