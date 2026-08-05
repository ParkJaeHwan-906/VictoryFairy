# 모듈: bedrock (LLM 2차 검열)

패턴 검열(validation)이 통과시킨 텍스트를 **LLM으로 한 번 더 판정**한다. 사전·정규식이
구조적으로 못 잡는 것 — 문맥 의존 욕설, 광고·스팸, 야구와 무관한 글 — 이 대상이다.

**텍스트 in / 판정 out 이 이 모듈의 전부다.** S3·버킷·키 규약을 모르고(BRK-LLM-2),
어떤 게시글의 어느 부분인지도 모른다. 그건 pipeline 러너 소관이다.
**작업 시 이 문서 범위 안에서 완결**한다.

요구사항 정본: `docs/requirements/bedrock/llm-validation.md` (v2 승인됨 2026-07-26)

## 기능 단위

### 1. 설정 (`core/config.py`)
- `BedrockSettings` — 모델·리전·결정성 파라미터·재시도·모드.
- `validate_startup(settings)` — 기동 시 검증. **실패를 첫 배치가 아니라 기동 시점으로 앞당긴다.**
  - 추론 프로파일(`global.` `apac.` `us.` `eu.` `apne1.`)은 **전부 거부**한다(BRK-LLM-6b/6d).
  - `BEDROCK_DRY_RUN` 과 `BEDROCK_SHADOW` 동시 활성화 거부(BRK-LLM-48b).

| 키 | 기본값 | 비고 |
|---|---|---|
| `BEDROCK_MODEL_ID` | (없음) | **베어 모델 ID**. `anthropic.claude-3-5-sonnet-20240620-v1:0` |
| `BEDROCK_REGION` | `ap-northeast-2` | S3 리전(`AWS_REGION`)과 **별도 키** |
| `BEDROCK_TEMPERATURE` / `TOP_P` / `MAX_TOKENS` | `0.0` / `1.0` / `2048` | 결정성(BRK-LLM-8) |
| `BEDROCK_PROMPT_CACHE` | **`False`** | 현행 모델 미지원(PIPE-2SB-77) — 아래 "한계" |
| `BEDROCK_DRY_RUN` / `BEDROCK_SHADOW` | `False` / `False` | 양립 불가 |

### 2. 프롬프트 (`core/prompt.py`)
- `SYSTEM_PROMPT` — 판정 기준·케이스. **실측 2,470토큰**.
- `build_system_blocks(use_cache)` — Converse `system` 블록. 캐시 breakpoint는 시스템
  프롬프트 **뒤**에 온다(PIPE-2SB-65 — 캐시는 프리픽스 매칭이라 앞에 오면 전혀 안 걸린다).
- `build_user_message(items)` — 판정 대상을 번호 매겨 나열.
- `PROMPT_VERSION` — 산출물에 기록돼 사후 측정 시 구간을 가른다(BRK-LLM-18).

⚠️ **BRK-LLM-1c(두 축이 겹치면 `abuse` 우선)는 코드로 강제할 수 없다.** 응답 스키마가 축을
하나만 담기 때문이다. `abuse → spam → offtopic` 순서로 먼저 걸리는 데서 멈추는 절차를
**프롬프트에 넣어** 보장한다 — 그래서 이 파일이 계약의 일부다.

### 3. 스키마 (`schemas/judgement.py`)
- `JudgeItem{text, unit_kind}` — `unit_kind ∈ {"body", "comment"}`. **배치 인자가 아니라
  항목별 인자**다(한 배치에 본문·댓글이 섞인다).
- `JudgeResult{is_valid, axis, message, fallback, model_id, prompt_version}` —
  `axis ∈ {"abuse", "spam", "offtopic"}`, 통과 시 `null`.
- `BatchUsage` — 토큰 집계. **재시도한 호출의 토큰도 모두 더한다**(실패한 호출도 과금된다).
- `BatchJudgement{results, usage, truncated_count}`.
- `BODY_ONLY_AXES = ("offtopic",)` — 댓글은 짧고 부모 글 맥락에 의존해 단독 주제 판정이 불가능하다.

### 4. 판정 서비스 (`services/judge.py`)
- `judge(items) -> list[JudgeResult]` — 계약(BRK-LLM-1). 결과만 반환.
- `judge_batch(items) -> BatchJudgement` — 러너용. 비용 누적에 `usage`가 필요해서 나눴다.
- 싱글턴 `judge_service`.
- 응답은 **입력과 같은 길이·같은 순서**로 정렬된다(BRK-LLM-12 — `index` 기준 재정렬).
- 댓글에 `offtopic`이 붙어 오면 **통과로 되돌린다**(BRK-LLM-19).

### 5. 예외 분류 (`core/errors.py` · `core/client.py`)

| 종류 | 예외 | 처리 |
|---|---|---|
| Throttling · ServiceUnavailable · 타임아웃 | `BedrockTransientError` | 백오프 1s·2s·4s 재시도 → 소진 시 **폴백 통과**(BRK-LLM-14/15) |
| 스키마 불일치 · 미정의 축 | `BedrockSchemaError` | 재시도 → 소진 시 폴백 통과(BRK-LLM-13/11b) |
| 자격증명 없음 · `AccessDenied` · `Validation` | `BedrockFatalError` | **즉시 중단**(BRK-LLM-17) |

⚠️ **`BedrockFatalError`를 서비스가 삼키면 안 된다.** 전건 폴백 통과로 조용히 끝나면
"2차 검열을 거친 것처럼 보이는 데이터"가 쌓인다 — fail-open(`-15`)과 fail-closed(`-17`)를
가른 이 결정이 실제로 값을 했다(SCP explicit deny도 `AccessDeniedException`으로 온다).

## 한계 · 향후 과제

- **프롬프트 캐싱을 쓸 수 없다.** 현행 모델 `claude-3-5-sonnet-20240620`은 Bedrock 프롬프트
  캐싱 미지원이다(실측 — `cachePoint`를 붙이면 `AccessDeniedException`, 떼면 성공).
  캐싱을 지원하는 Claude 4+ 는 전부 `INFERENCE_PROFILE` 전용이라 아래 SCP 제약에 막힌다.
  **모델 제약과 캐싱 제약이 같은 원인에 묶여 있다.** 배선은 살아 있으니(`BEDROCK_PROMPT_CACHE`)
  서울에 캐싱 지원 `ON_DEMAND` 모델이 들어오면 켜기만 하면 된다.
- **모델 선택지가 사실상 없다.** 조직 SCP `p-meobeew3`가 서울 외 리전의 `bedrock:InvokeModel`을
  명시적으로 거부한다. 서울에서 `ON_DEMAND`로 부를 수 있는 Anthropic 모델은
  `claude-3-5-sonnet-20240620`과 `claude-3-haiku-20240307` **둘뿐**이고, 나머지 16개는
  전부 `INFERENCE_PROFILE` 전용이다. 모델을 올리려면 조직 정책부터 바꿔야 한다.
- **판정 목표치가 미확정이다.** 폐기율·재현율(BRK-LLM-20~27·30~35·40~45)은 전부 **잠정치**다.
  `BEDROCK_SHADOW` 실측 후 확정한다(BRK-LLM-46/47). 지금 수치로 튜닝을 판단하지 말 것.
- **BRK-LLM-1c는 테스트로 검증할 수 없다.** 응답 스키마가 축을 하나만 담아 이 계층에서
  관측이 불가능하다 — 프롬프트 계약으로만 보장되므로 `accuracy-tuner` 영역이다.
- **`fallback=true`는 판정을 안 거친 것이다.** 통과(`is_valid=true`)로 보이지만 LLM이
  판정하지 못했다는 뜻이다. 러너가 산출물에 이 사실을 기록한다(PIPE-2SB-59) — 지표를
  낼 때 폴백 통과분을 "통과"로 세면 재현율이 부풀려진다.

## 교차 의존 (명시)

- **bedrock은 S3를 모른다.** `S3_BUCKET`·키 규약·`boto3` S3 클라이언트는 `pipeline`에만 있다.
  여기에 섞지 말 것 — 텍스트를 받아 판정을 돌려주는 것이 전부다(BRK-LLM-2).
- **어느 게시글의 무엇인지 모른다.** 본문/댓글 구분은 `unit_kind` 한 글자로만 오고, 그걸
  게시글로 되돌리는 라우팅은 `pipeline/run_bedrock.py` 소관이다.
- `pipeline/run_bedrock.py`가 `judge_batch()`를, `pipeline/run_backfill.py`가 같은 경로를 쓴다.
- 비용 누적(`bedrock_spend_usd`)·예산 상한은 **러너 소관**이다. 이 모듈은 자기가 얼마를
  썼는지 모르고 `usage`만 돌려준다.
