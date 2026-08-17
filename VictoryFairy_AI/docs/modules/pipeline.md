# 모듈: pipeline (정제 파이프라인)

**패턴 검열 → Bedrock 2차 검열** 2단계를 실행한다. 두 단계 모두 S3 게시글 객체를 in/out으로
쓴다. 분석·집계 러너는 코드가 남아 있으나 배선에서 빠져 있다(아래 "한계" 참고).
각 단계는 해당 서비스를 HTTP 없이 **직접 import**해 재사용한다.
**작업 시 이 문서 범위 안에서 완결**한다.

요구사항 정본: `docs/requirements/pipeline/{s3-io,two-stage-batch,backfill}.md`

## 전체 흐름 — 이벤트 구동

```
kbo-collector (Lambda, 상시)  ──▶ S3 community/{source}/{date}/{postId}.json
   EventBridge rate(10 min)              │  S3 ObjectCreated 이벤트
                                         ▼
                          lambda_pattern.handler   게시글 1건 · 사전/정규식
                                         │  통과분만
                                         ▼
                                  SQS (+ DLQ)   batch_size = 5
                                         ▼
                          lambda_bedrock.handler   5건 묶어 모델 1회 호출
                                         ▼
                              S3 validation/bedrock/{success,failed}
```

**"완료 신호"라는 것이 없다.** 크롤러는 정제 단계를 모르고 S3에 쓰기만 한다 — 그 객체 생성이
곧 트리거다. 패턴 단계도 "끝났다"고 알리지 않고 게시글 하나를 판정해 큐에 넣고 끝난다.
**한 게시글 기준으로는 크롤 → 패턴 → LLM 순서가 지켜지지만, 전체로는 세 단계가 항상 동시에
돌고 있다.**

| 트리거 | 수단 |
|---|---|
| 패턴 검열 | S3 `ObjectCreated` 이벤트 (게시글 1건 단위) |
| Bedrock 검열 | SQS 이벤트 소스 매핑 (`batch_size`만큼 모아서) |

⚠️ **SQS는 선택이 아니라 필수다.** 게시글 1건씩 Bedrock을 부르면 시스템 프롬프트
2,470토큰이 호출마다 붙어 하루 $44.8로 상한 $30을 넘긴다. 5건 묶으면 $8.97이다.

**S3 마커가 진실의 원천이다**(`PIPE-2SB-73`). S3 이벤트·SQS 재배달·Lambda 재시도가 모두
**최소 한 번(at-least-once)** 배달이라 같은 게시글이 두 번 이상 도착하는 것이 정상 경로다 —
완결 마커가 그것을 흡수한다. Bedrock 쪽에서는 이게 곧 **중복 과금 방지**다.

## 진입점 — 운영과 로컬이 갈린다

| 진입점 | 실행 위치 | 용도 |
|---|---|---|
| `lambda_pattern.handler` | **Lambda** | 운영 — S3 이벤트마다 |
| `lambda_bedrock.handler` | **Lambda** | 운영 — SQS 배치마다 |
| `run_validation.main()` | 로컬 `.venv` | 수동 재처리·디버깅 |
| `run_bedrock.main()` | 로컬 `.venv` | 수동 재처리·디버깅 |
| `run_backfill.main()` | 로컬 `.venv` | 누적분 백필 |
| `run_analysis`·`run_aggregate` | 로컬 | 배선에서 빠진 유산 |

⚠️ **핸들러는 배선만 한다.** 판정·라우팅·S3 기록은 `process_post()`·`_route_post()`·
`judge_batch()`·`_finalize_post()`를 그대로 호출한다 — **로컬 경로와 같은 함수를 쓰므로
판정 기준이 갈리지 않는다.** 핸들러에 로직을 다시 쓰면 로컬에서 검증한 것과 운영에서 도는
것이 달라진다.

⚠️ **이미지가 Lambda 전용이라 세 `main()`은 컨테이너로 돌지 않는다.** 진입점이 Lambda
핸들러로 고정되기 때문이다. 로컬 `.venv`에서 실행한다.

## 기능 단위 (러너별로 분리)

### 1. 패턴 러너 (`run_validation.py`)

- **파일**: `run_validation.py` · `s3_io.py`(리스팅/읽기/쓰기 + 키 규칙, **판정 로직 없음**) ·
  `core/config.py`(`PipelineSettings`: `S3_BUCKET`·`AWS_REGION`·`S3_ENDPOINT_URL`(선택))
- **흐름**: `community/{source}/{date}/*.json`(source ∈ {`dcinside`, `fmkorea`})을 게시글별로
  읽어 `validation_service`에 위임한다. 판정 로직은 러너·`s3_io.py` 어디에도 없다.

**판정 단위 결정 (PIPE-2SB-8b · PIPE-S3IO-32~35 · -39)**

```
빈 본문이면 title 을 본문 자리로 판정   (unit_kind 는 그래도 "body")
본문·제목이 모두 비었으면 → 실패("빈 본문·빈 제목")
```

실측 13.7%가 빈 본문인데 **제목이 실제 내용을 담고 있어서** 버리지 않고 제목을 판정한다.

**"비었다"의 정의에 크롤러 서명이 포함된다**(`PIPE-S3IO-39`, `pipeline/text_normalize.py`).
`- dc official App` 같은 앱 자동 서명은 글쓴이가 쓴 내용이 아닌데 `str.strip()` 이 이를
비었다고 보지 않아, 서명뿐인 본문에서 title 대체가 발동하지 않았다. 실측 337건 중 **15건**이
이 경우였고 Bedrock 폐기 8건이 서명을 판정한 결과였다.

⚠️ **두 러너가 같은 구현(`text_normalize`)을 공유해야 한다.** 각자 판단하면 1차가 통과시킨
것과 2차가 보는 것이 어긋난다. 저장되는 본문은 바꾸지 않는다 — 판정 대상을 정하는 데만 쓴다.

**라우팅 (PIPE-S3IO-19 · -19b)**

```
본문 자리가 걸리면  → 게시글 전체 failed  (댓글도 마저 판정해 사유를 모은다)
본문 자리가 통과    → 댓글 개별 판정, 걸린 댓글만 제거
                   → 통과 본문 + 통과 댓글을 원래 스키마로 재조립해 success
```

⚠️ **`body:""` success는 폐기됐다(구 PIPE-S3IO-17).** 본문이 걸린 글은 부분 보존하지 않는다.
폐기된 텍스트는 **success 객체의 어느 필드에도 남기지 않는다**(PIPE-2SB-10b).

**완결 순서 (PIPE-2SB-72 · -72b)**

```
산출물 기록 → 마커 기록 → (success 면) SQS 전송
```

마커가 **먼저** 찍히면 산출물이 없는데 완결로 보여 재처리가 영영 막힌다. 큐 전송이 마커보다
**먼저** 가면 그 사이 실패 시 재시도가 같은 글을 또 넣어 **중복 판정·중복 과금**이 된다.
반대 순서의 대가(큐 전송 실패 → 2단계 누락)는 **백필로 복구되지만 중복 과금은 되돌릴 수 없다.**

| 설정 | 기본값 | 비고 |
|---|---|---|
| `BATCH_DATE` | (없음→당일 KST) | **로컬 실행 전용.** Lambda 는 S3 키에서 파싱한다 |
| `BEDROCK_QUEUE_URL` | (없음) | Lambda 전용. 없으면 예외 — 2단계로 넘어갈 방법이 없다 |

⚠️ **패턴 단계엔 비용 카운터가 없다** — LLM 을 부르지 않는다.

### 2. Bedrock 러너 (`run_bedrock.py`)

- **흐름**: `validation/pattern/success/{source}/{date}/*.json` → `bedrock.judge_batch()` →
  `validation/bedrock/{success|failed}/...`
- **배치 단위는 "게시글 N개분"**(`BEDROCK_BATCH_POST_SIZE`, 기본 5). 단위 N개로 세면 게시글이
  배치 경계에 걸쳐 잘려 **게시글 원자성과 예산 확인 시점이 둘 다 깨진다**(PIPE-2SB-71).
- **3분기 라우팅** (`_route_post`) — 본문 폐기 시 전체 failed, 댓글만 폐기 시 나머지로 재조립.

**예산 통제 (PIPE-2SB-60~63 · -74/75)**

```
배치 호출 직전 → DynamoDB GetItem(batch_date) → 상한 이상이면 호출하지 않음
호출 후        → usage × 단가 → UpdateItem ... ADD
```

**호출 전에 확인하는 것이 핵심이다.** 사후 집계면 상한을 넘긴 뒤에야 안다.
파티션 키가 `batch_date`라 **일 단위 리셋이 구조적으로 성립**하고, TTL은 30일이다.

⚠️ **원자적 증분만으로는 상한을 못 지킨다.** 동시에 뜬 함수들이 각자 "아직 여유 있음"을 읽고
함께 넘길 수 있다 — **Bedrock Lambda 예약 동시성을 1로 묶어** 직렬화한다(`PIPE-2SB-83`).
처리량이 부족하면 `batch_size`를 키우지 동시성을 올리지 말 것.

⚠️ **카운터 접근 실패는 "상한 도달"과 다르다.** 전자는 `BedrockCostCounterUnavailable`로
**중단**한다 — 0으로 눙기면 상한이 조용히 사라진다. 후자는 전건을 큐에 남긴다(`PIPE-2SB-74`).
`DRY_RUN`만 면제이고 **`SHADOW`는 면제가 아니다**(실제로 호출하므로 과금된다).

| 설정 | 기본값 | 비고 |
|---|---|---|
| `BATCH_DATE` | (없음→당일 KST) | |
| `BEDROCK_SPEND_LIMIT_USD` | `30.0` | 하루 누적 상한 |
| `BEDROCK_PRICE_INPUT_PER_1K_USD` | `0.003` | $3/1M — 약관 요율표로 확인됨 |
| `BEDROCK_PRICE_OUTPUT_PER_1K_USD` | `0.015` | $15/1M |
| `BEDROCK_BATCH_POST_SIZE` | `5` | 게시글 N개분 |
| `BEDROCK_SHADOW_SAMPLE_SIZE` | `500` | 섀도는 전건이 아니라 표본만 |

### 3. 백필 러너 (`run_backfill.py`)

정책이 바뀌면 기존 산출물을 폐기하고 다시 돌린다. `BACKFILL_FROM`~`BACKFILL_TO`를 오름차순
순회하며 날짜마다 **패턴 완결 → Bedrock** 순으로 처리한다.

- 진행점과 소비액은 `_backfill/{run_id}/cursor.json`에 따로 기록한다. **`Cursor`가 정규
  카운터(`spend_counter.py`)와 같은 인터페이스로 `run_bedrock.main(cost_tracker=)`에 주입돼
  정규 DynamoDB 카운터를 읽지도 쓰지도 않는다**(PIPE-BF-15) — 정규 카운터는 `batch_date`가
  파티션 키라 여러 날에 걸치는 백필의 누적 예산을 담지 못한다.
- **예산이 날짜 경계보다 우선한다**(PIPE-BF-18) — 날짜 중간에도 예산이 다하면 멈춘다.
- **마커는 스스로 지우지 않는다**(PIPE-BF-25). 재처리 대상 선정은 운영자 몫이다.

| 설정 | 기본값 |
|---|---|
| `BACKFILL_FROM` / `BACKFILL_TO` | (필수) |
| `BACKFILL_RUN_ID` | 자동 생성 |
| `BACKFILL_BUDGET_USD` | `80.0` |
| `BACKFILL_MODE` | `backfill` \| `reapply` |

### 4. 분석 러너 · 집계 러너 (배선에서 빠짐)

- `run_analysis.py` — `data/processed_data.txt` → `analysis_service`(Kiwi+NER) →
  `data/finished_data.txt`(표시용) · `data/finished_data.jsonl`(집계용)
- `run_aggregate.py` — `finished_data.jsonl` → `normalize.aggregate_persons` →
  `data/persons_aggregated.json`
- ⚠️ **개체명/명사 중복 제거는 여기가 아니다** — `analysis/services/analysis.py` 소관이다.

## S3 키 규약 (`s3_io.py`)

```
community/{source}/{date}/{postExternalId}.json                     ← 입력(읽기전용)
validation/{method}/{status}/{source}/{date}/{postExternalId}.json  method ∈ pattern | bedrock
validation/{method}/_manifest/{source}/{date}/{postExternalId}.json ← 완결 마커(멱등 skip)
validation/{method}/_shadow/{source}/{date}/{postExternalId}.json   ← 섀도 측정용
_backfill/{run_id}/cursor.json                                      ← 백필 진행점
```

`method`를 키에 넣어 **패턴 산출물과 Bedrock 산출물이 같은 구조로 2층**을 이룬다.
패턴 `success`가 Bedrock의 입력이다.

- `failed` 객체: `reasons: [{unit, commentIndex, author, text, message}]` (`text`=걸린 원문)
- **마커는 항상 마지막에 쓴다.** 마커가 없으면 "미완결"로 보고 재실행 시 다시 처리한다.

## 실행 방법

```bash
BATCH_DATE=2026-07-25 python -m pipeline.run_validation   # community/ → validation/pattern/
BATCH_DATE=2026-07-25 python -m pipeline.run_bedrock      # validation/pattern/success/ → validation/bedrock/
BACKFILL_FROM=2026-07-09 BACKFILL_TO=2026-07-25 python -m pipeline.run_backfill

python -m pipeline.run_analysis     # 입력 공급 끊김 — 아래 한계 참고
python -m pipeline.run_aggregate
```

`run_validation`·`run_bedrock`은 `S3_BUCKET` 등 env가 필요하다. `run_bedrock`은 추가로
`BEDROCK_MODEL_ID`가 필요하며, 없으면 기동 시 거부된다(`DRY_RUN` 제외).
⚠️ **위는 전부 로컬 실행이다.** 운영은 Lambda 핸들러가 이벤트로 돌며, 그쪽은
`BUDGET_TABLE_NAME`(DynamoDB)·`BEDROCK_QUEUE_URL`(SQS)을 추가로 요구한다.
`run_bedrock` 로컬 실행 시 `BUDGET_TABLE_NAME` 이 없으면 예산 확인 단계에서 중단된다
(`BEDROCK_DRY_RUN=true` 면 면제).

## data/ 산출물 지도 (분석·집계 전용)

검열 러너들은 `data/`를 쓰지 않는다(전부 S3 in/out).

| 파일 | 생성 단계 | 내용 |
|---|---|---|
| `processed_data.txt` | (입력, 현재 공급자 없음) | 예전엔 `run_validation`이 채웠으나 지금은 수동 배치 필요 |
| `finished_data.txt` | 분석 | 표시용 키워드 |
| `finished_data.jsonl` | 분석 | 집계용 구조화 데이터 |
| `persons_aggregated.json` | 집계 | 인명 집계 |

## 한계 · 향후 과제

- **오케스트레이션만 담당**: 러너는 각 모듈 서비스를 import 해 쓸 뿐 판정 로직을 갖지 않는다.
  **러너 안에서 로직을 재구현하지 말 것** — 결과가 API 경로와 갈라진다.
- **예산으로 시작하지 못한 게시글은 큐에 남는다.** 전건을 `batchItemFailures`로 돌려주므로
  SQS가 다시 배달하고, 카운터가 날짜별이라 **다음 날 자연히 재개된다**(`PIPE-2SB-74`).
  ⚠️ 다만 큐 보존 기간(4일) 안에 예산이 풀리지 않으면 DLQ로 간다.
- **상한을 마지막 배치 1회분만큼 초과할 수 있다.** 배치 시작 전에 확인하므로 그 배치가
  얼마를 쓸지는 끝나야 안다. 배치 크기를 키울수록 초과 폭도 커진다.
- **⚠️ 비용 여유가 크롤러의 미구현에 기대고 있다 — 모듈 간 숨은 결합.** 하루 약 $13.79는
  dcinside(97%)의 **댓글이 수집되지 않는다**는 사실에서 나온다(`community.py:263`, AJAX 미구현).
  댓글 수집이 추가되면 정제 비용이 **약 12배** 뛴다 — 정제 코드는 한 줄도 안 바뀌었는데
  상한($30)을 크게 넘는다. 크롤러 변경 시 비용 전제를 **반드시 재계산**할 것.
- **누적 소비액은 추정치이지 청구액이 아니다.** 토큰 집계 × 단가다. 단가
  자체는 확인됐지만(입력 $3 / 출력 $15 per 1M) **첫 수일간 Cost Explorer 실청구액과 대조**할 것.
- **모델·프롬프트 교체 후에도 기존 마커는 유지된다** → 같은 날짜 prefix에 서로 다른 기준의
  판정이 섞인다. 측정 시 `modelId`·`promptVersion`으로 구간을 갈라야 한다.
- **Lambda 15분 상한이 백필에 맞지 않는다.** 누적분 순회는 대량 반복이라 이 모델에 담기지
  않는다 — `run_backfill`은 로컬 `.venv`로 돌린다. 실행 방식은 **미결정**으로 열려 있다
  (Step Functions + Map / EKS Job / 로컬 중 택일).
- **이미지가 약 1.09GB다.** 우리 코드·의존성은 39MB이고 나머지는 Lambda 베이스 이미지다.
  Lambda는 이미지를 캐시·지연 로딩하므로 콜드 스타트를 크게 좌우하지 않는다 —
  노드가 뜰 때마다 pull하던 EKS Spot과는 상황이 다르다.
- **아키텍처가 `linux/arm64`로 고정돼 있다.** Terraform의 `architectures`와 **반드시 일치**
  해야 하고, 다르면 **함수 생성 자체가 실패**한다. 빌드 장소에 따라 갈리므로(맥=arm64 /
  CI=amd64) Dockerfile에 `--platform`을 박아 뒀다.
- **분석·집계는 배선에서 빠져 있다.** `data/processed_data.txt`의 공급원(예전엔
  `run_validation`)이 끊긴 상태다. 재연결은 미도입. 두 러너는 **재실행=덮어쓰기** 한계도
  그대로다 — 산출물에 버전·타임스탬프가 없다.
- **`run_analysis`·`run_aggregate`는 배치 이미지로 돌지 않는다**(`PIPE-S3IO-40`). 배치 흐름이
  쓰지 않는 torch·KoELECTRA 모델이 이미지를 **1.63GB**로 부풀리고 있었고, Spot 노드는 뜰 때마다
  이미지를 pull 하므로 그 무게가 기동 시간에 그대로 붙는다. 분리 후 **274MB**(83% 감소).
  두 러너를 돌리려면 `analysis/Dockerfile` 이미지나 로컬 `.venv`를 쓴다 — **코드는 저장소에
  그대로 보존된다**(`PIPE-S3IO-29`). analysis가 배선에 복귀하면 이 결정을 재검토한다.
- **`run_analysis`는 무겁다**: Kiwi + KoELECTRA(torch) 로딩 + 첫 실행 시 모델 다운로드.
- **S3 통합 테스트는 비결정적**: dev 버킷 실입출력을 쓰므로 자격증명·네트워크에 의존한다.
  로컬 격리(moto/페이크)는 **의도적 미도입**.

## 교차 의존 (명시)

- pipeline은 `validation.services.validation` · `bedrock.services.judge` ·
  `analysis.services.{analysis,normalize}`를 import한다. 로직 변경은 각 모듈에서,
  pipeline은 **오케스트레이션만** 담당.
- **S3 설정과 `boto3` S3 의존성은 pipeline에만 있다.** validation·analysis·bedrock 세 모듈은
  S3를 몰라야 한다 — 앞으로도 여기 섞지 말 것.
- `run_validation`·`run_bedrock`은 `community/`를 **읽기전용**으로 소비한다. 크롤러가 채우는
  입력이며 pipeline은 절대 쓰지 않는다.
- **비용 통제는 pipeline 소관이다.** bedrock 모듈은 자기가 얼마를 썼는지 모르고 `usage`만
  돌려준다 — 단가를 곱해 누적하고 상한을 거는 것은 러너다.
