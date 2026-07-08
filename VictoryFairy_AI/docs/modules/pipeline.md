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
- **중복 제거**: 출력 포매터는 카테고리별로 비어있지 않은 것만 표시.

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

## 교차 의존 (명시)
- pipeline은 `validation.services.validation`·`analysis.services.analysis`·`analysis.services.normalize`를 import한다. 로직 변경은 각 모듈에서, pipeline은 **오케스트레이션(입출력 파일 배선)만** 담당.
