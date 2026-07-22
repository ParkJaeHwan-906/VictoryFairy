# 모듈: pipeline (배치 러너)

**검열 → 분석 → 집계** 단계를 실행한다. 검열 러너는 S3 게시글 객체를 in/out으로 쓰고,
분석·집계 러너는 로컬 `data/` 파일을 잇는다 — 단, 현재 이 둘 사이 배선은 끊겨 있다(아래
"한계" 참고). 각 러너는 해당 서비스를 HTTP 없이 **직접 import**해 재사용한다.
**작업 시 이 문서 범위 안에서 완결**한다.

실행(프로젝트 루트): `python -m pipeline.<러너>`

## 기능 단위 (러너별로 분리)

### 1. 검열 러너 (S3 게시글 in/out)
- **파일**: `run_validation.py` · `s3_io.py`(S3 리스팅/읽기/쓰기 + 키 규칙, **판정 로직 없음**) · `core/config.py`(`PipelineSettings`: `S3_BUCKET`·`AWS_REGION`·`S3_ENDPOINT_URL`(선택))
- **흐름**: S3 `community/{source}/{date}/*.json`(source ∈ {`dcinside`, `fmkorea`}, date=실행 당일 KST)을 게시글별로 리스팅해 읽고, 각 게시글의 `body` + `topComments[].body`를 **독립 검열 단위**로 기존 `validation_service`에 그대로 위임(분할·변형 없음). 판정 로직은 러너·`s3_io.py` 어디에도 없다.
- **산출물(S3 키)** — 경로의 `pattern` 세그먼트는 향후 `bedrock` 검열 확장을 위한 자리:
  - 입력(읽기전용): `community/{source}/{date}/{postExternalId}.json`
  - 성공(정화 객체): `validation/pattern/success/{source}/{date}/{postExternalId}.json` — 통과한 단위만 남기고 원본 필드는 보존. 본문이 폐기됐으면 `body:""` + 통과 댓글만 유지.
  - 실패(폐기 사유): `validation/pattern/failed/{source}/{date}/{postExternalId}.json` — `reasons: [{unit, commentIndex, author, text, message}]`(`text`=걸린 원문).
  - 완결 마커(멱등 skip 판정용): `validation/pattern/_manifest/{source}/{date}/{postExternalId}.json` — success/failed(해당하는 것만) 기록을 모두 마친 **마지막**에 쓴다. 마커가 없으면 "미완결"로 보고 재실행 시 다시 처리한다.
- **인증/의존성**: boto3 기본 자격증명 체인(env, 임시 STS 가능) · 버킷명 env `S3_BUCKET`(입출력 동일 버킷) · 리전 `AWS_REGION`(기본 `ap-northeast-2`) · 엔드포인트 override env `S3_ENDPOINT_URL`(선택 — 비우면 기본 AWS 리전 엔드포인트, VPC 엔드포인트/MinIO 등에 붙일 때만 지정). `boto3`는 pipeline 전용 의존성(`pipeline/requirements.txt`).
- ⚠️ **S3 설정은 pipeline 전용**: `PipelineSettings`(`S3_BUCKET`·`AWS_REGION`)는 pipeline 모듈에만 둔다. validation·analysis 모듈은 S3를 몰라야 하므로 여기 섞지 말 것.

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

## 실행 방법

컨테이너 기본 CMD(pipeline Dockerfile)는 `run_validation` 단독이다. 로컬 개별 실행은
`python -m pipeline.<러너>` — `run_validation`은 `S3_BUCKET` 등 env가 필요하다.
`run_analysis`·`run_aggregate`는 파일·코드가 살아있어 개별 실행은 가능하지만, 아래
"한계"대로 입력 공급이 끊겨 있어 `data/processed_data.txt`를 별도로 채워야 돈다.

```bash
python -m pipeline.run_validation   # S3: community/{source}/{date}/*.json → validation/pattern/{success|failed}/...
python -m pipeline.run_analysis     # data/processed_data.txt → finished + finished.jsonl (입력 공급 끊김, 아래 한계 참고)
python -m pipeline.run_aggregate    # finished.jsonl → persons_aggregated.json
```

## data/ 산출물 지도 (분석·집계 전용)

검열 러너는 더 이상 `data/`를 쓰지 않는다(S3 in/out, 위 "1. 검열 러너" 참고). 아래는
분석(②)·집계(③) 단계가 여전히 쓰는 로컬 파일이다.

| 파일 | 생성 단계 | 내용 |
|---|---|---|
| `processed_data.txt` | (입력, 현재 공급자 없음) | 검열 통과 문장 — 예전엔 `run_validation`이 채웠으나 지금은 수동 배치 필요 |
| `finished_data.txt` | ② | 표시용 키워드 |
| `finished_data.jsonl` | ② | 집계용 구조화 데이터 |
| `persons_aggregated.json` | ③ | 인명 집계 |

## 한계 · 향후 과제

- **오케스트레이션만 담당**: 러너는 각 모듈 서비스를 import 해 쓸 뿐 판정 로직을 갖지 않는다. **러너 안에서 로직을 재구현하지 말 것** — 결과가 API 경로와 갈라진다. `run_validation`도 마찬가지로 `s3_io.py`는 리스팅/읽기/쓰기만 하고 판정은 전부 `validation_service`에 위임한다.
- **배선 정리(현재 이터레이션)**: pipeline 흐름은 `run_validation` 단독(컨테이너 CMD 기준). `run_analysis`·`run_aggregate`는 파일·코드는 보존되지만 배선에서 빠졌고, analysis 입력이던 `data/processed_data.txt`의 공급원(예전엔 `run_validation`)이 끊긴 상태다. SQS 트리거나 로컬 폴백 같은 재연결은 미도입.
- **재실행=덮어쓰기 한계는 `run_validation`에 한해 개선됨**: 완결 마커(`validation/pattern/_manifest/...`) 기반 원자적 멱등 skip을 도입해, 완결된 게시글은 재처리하지 않고 미완결(부분 산출물)만 재처리한다. 다만 **`run_analysis`/`run_aggregate`는 여전히 옛 한계 그대로**다 — 산출물에 버전·타임스탬프가 없어 다시 돌리면 이전 결과(`finished_data.*`·`persons_aggregated.json`)가 그냥 덮인다. 이력 보존은 미도입.
- **`run_analysis` 는 무겁다**: Kiwi + KoELECTRA(torch) 로딩 + 첫 실행 시 모델 다운로드. 전체 입력으로 돌리기 전에 **작은 샘플로 확인**하는 게 안전하다. 진행률 표시나 중단 재개는 미도입.
- **검열 러너의 S3 통합 테스트는 비결정적**: dev 버킷 실입출력을 쓰므로 자격증명·네트워크에 의존하고 임시 STS 만료 시 실패할 수 있다. 로컬 격리(moto/페이크)는 미도입(의도적 제외).

## 교차 의존 (명시)
- pipeline은 `validation.services.validation`·`analysis.services.analysis`·`analysis.services.normalize`를 import한다. 로직 변경은 각 모듈에서, pipeline은 **오케스트레이션만** 담당.
- `run_validation`은 S3 게시글(`community/{source}/{date}/*.json`)을 **읽기전용**으로 소비한다 — 크롤러가 채우는 입력이며 pipeline은 절대 쓰지 않는다. 출력은 `validation/pattern/...` prefix 전용.
- S3 설정(`S3_BUCKET`·`AWS_REGION`, `pipeline/core/config.py`)과 `boto3` 의존성은 **pipeline에만** 있다 — validation·analysis 모듈에는 없고, 앞으로도 그 두 모듈은 S3를 몰라야 한다.
