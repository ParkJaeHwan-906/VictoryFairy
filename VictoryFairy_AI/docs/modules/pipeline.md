# 모듈: pipeline (배치 러너)

**패턴 검열 → Bedrock 2차 검열** 2단계를 실행한다. 두 단계 모두 S3 게시글 객체를 in/out으로
쓰고, 작업 상태만 `batch-redis`로 주고받는다. 분석·집계 러너는 코드가 남아 있으나 배선에서
빠져 있다(아래 "한계" 참고). 각 러너는 해당 서비스를 HTTP 없이 **직접 import**해 재사용한다.
**작업 시 이 문서 범위 안에서 완결**한다.

실행(프로젝트 루트): `python -m pipeline.<러너>`

요구사항 정본: `docs/requirements/pipeline/{s3-io,two-stage-batch,backfill}.md`

## 전체 흐름

```
크롤러 ──▶ S3 community/ ──▶ run_validation ──▶ S3 validation/pattern/ ──▶ run_bedrock ──▶ S3 validation/bedrock/
              │                    │                                            │
              └──── SADD ──────────┴──── batch-redis (작업 상태) ────────────────┘
```

세 프로세스가 **서로를 직접 호출하지 않는다.** 연결 고리는 Redis 작업 집합의 크기뿐이다.

| 트리거 | 조건 |
|---|---|
| 크롤링 | 02:00 KST CronJob |
| 패턴 검열 | `SCARD pending:pattern` ≥ 1000 |
| Bedrock 검열 | `SCARD pending:bedrock` ≥ 1000 |

**S3가 진실의 원천, Redis는 최적화 수단이다**(PIPE-2SB-73). Redis가 통째로 죽어도 데이터
유실이 아니다 — 대상은 S3 prefix 리스팅 + 마커 확인으로 복구된다(`O(N)`이라 느릴 뿐).
Spot 회수로 Redis가 소멸되는 것을 **전제로** 한 설계다.

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

**Redis 갱신 순서 (PIPE-2SB-47 · -72b)**

```
마커 기록 → (success 면) SADD pending:bedrock → SREM pending:pattern
```

**`SADD`는 조건부, `SREM`은 무조건이다.** 전건 폐기된 게시글은 `pending:bedrock`에 넣지
않지만 `pending:pattern`에서는 반드시 뺀다 — 빼지 않으면 실패분(실측 39.5%)이 영원히 남아
`SCARD`가 0에 수렴하지 못하고 **종료 조건이 성립하지 않는다.**

| 설정 | 기본값 | 비고 |
|---|---|---|
| `BATCH_DATE` | (없음→당일 KST) | 배치 시작일 주입(PIPE-2SB-37/38) |
| `BATCH_REDIS_URL` | (없음) | 비우면 작업 집합 갱신 없이 동작 |
| `PENDING_PATTERN_KEY` / `PENDING_BEDROCK_KEY` | `pending:pattern` / `pending:bedrock` | |

⚠️ **패턴 단계엔 비용 카운터가 없다** — `BedrockRunnerSettings`와 대칭이 아니다.

### 2. Bedrock 러너 (`run_bedrock.py`)

- **흐름**: `validation/pattern/success/{source}/{date}/*.json` → `bedrock.judge_batch()` →
  `validation/bedrock/{success|failed}/...`
- **배치 단위는 "게시글 N개분"**(`BEDROCK_BATCH_POST_SIZE`, 기본 5). 단위 N개로 세면 게시글이
  배치 경계에 걸쳐 잘려 **게시글 원자성과 예산 확인 시점이 둘 다 깨진다**(PIPE-2SB-71).
- **3분기 라우팅** (`_route_post`) — 본문 폐기 시 전체 failed, 댓글만 폐기 시 나머지로 재조립.

**예산 통제 (PIPE-2SB-60~63 · -74/75)**

```
배치 호출 직전 → GET bedrock_spend_usd → 상한 이상이면 호출하지 않음
호출 후        → usage × 단가 → INCRBYFLOAT
```

**호출 전에 확인하는 것이 핵심이다.** 사후 집계면 상한을 넘긴 뒤에야 안다.

⚠️ **Redis 두 용도는 실패 취급이 다르다.** 작업 집합(`pending:bedrock`) 실패는 로그만 남기고
진행하지만(S3 마커가 정본), 비용 카운터(`bedrock_spend_usd`) 접근 실패는
`BedrockCostCounterUnavailable`로 **중단**한다 — 카운터를 잃으면 상한이 조용히 사라진다.
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

- **`Cursor`가 `BatchRedis` 인터페이스를 흉내 내되 `SADD`/`SREM`을 하지 않는다**(PIPE-BF-3b) —
  백필이 야간 배치의 작업 집합을 오염시키면 안 된다.
- 진행점과 소비액은 `_backfill/{run_id}/cursor.json`에 따로 기록한다. **`bedrock_spend_usd`를
  건드리지 않는다**(PIPE-BF-15).
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
- **예산으로 시작하지 못한 게시글은 영구 미판정이다.** 마커 없이 `pending:bedrock`에 남지만
  배치가 끝나면 Redis가 사라지고 `BATCH_DATE`도 바뀌어 **다음 배치의 대상 prefix 밖**이다.
  누적 재처리 잡은 미도입.
- **상한을 마지막 배치 1회분만큼 초과할 수 있다.** 배치 시작 전에 확인하므로 그 배치가
  얼마를 쓸지는 끝나야 안다. 배치 크기를 키울수록 초과 폭도 커진다.
- **⚠️ 비용 여유가 크롤러의 미구현에 기대고 있다 — 모듈 간 숨은 결합.** 하루 약 $13.79는
  dcinside(97%)의 **댓글이 수집되지 않는다**는 사실에서 나온다(`community.py:263`, AJAX 미구현).
  댓글 수집이 추가되면 정제 비용이 **약 12배** 뛴다 — 정제 코드는 한 줄도 안 바뀌었는데
  상한($30)을 크게 넘는다. 크롤러 변경 시 비용 전제를 **반드시 재계산**할 것.
- **`bedrock_spend_usd`는 추정 소비액이지 청구액이 아니다.** 토큰 집계 × 단가다. 단가
  자체는 확인됐지만(입력 $3 / 출력 $15 per 1M) **첫 수일간 Cost Explorer 실청구액과 대조**할 것.
- **모델·프롬프트 교체 후에도 기존 마커는 유지된다** → 같은 날짜 prefix에 서로 다른 기준의
  판정이 섞인다. 측정 시 `modelId`·`promptVersion`으로 구간을 갈라야 한다.
- **`batch-redis`는 `maxmemory 256mb` / `noeviction`이다.** 넘치면 쓰기가 에러로 터진다
  (조용한 유실보다는 낫다). Set에 **게시글 키만** 담는 결정 덕에 하루 7,000키 규모는 여유롭다.
- **분석·집계는 배선에서 빠져 있다.** `data/processed_data.txt`의 공급원(예전엔
  `run_validation`)이 끊긴 상태다. 재연결은 미도입. 두 러너는 **재실행=덮어쓰기** 한계도
  그대로다 — 산출물에 버전·타임스탬프가 없다.
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
