# 2단계 야간 정제 배치 (two-stage batch) 요구사항
> 상태: **승인됨 (2026-07-25)** · 모듈: pipeline · 최종 수정: 2026-07-25
> 함께 승인된 문서: `docs/requirements/pipeline/s3-io.md`(v2) · `docs/requirements/bedrock/llm-validation.md`
> — **세 문서는 한 묶음이다.** 하나만 보고 구현하면 계약이 갈린다.

## 배경 / 목적
현재 정제는 사전·정규식 검열(`run_validation`) 한 단계뿐이다. 그 뒤에 Bedrock(LLM) 2차 검열 단계를 붙여
**크롤 → 패턴 검열 → LLM 검열**이 야간에 **스트리밍으로 동시에 흐르는** 배치를 만든다.
이 문서는 **배선·키 규약·트리거·실행 계약**만 다룬다 — "무엇을 걸러야 하는가"는
`docs/requirements/bedrock/llm-validation.md` 소관이다(의도적 분리).

> 선행 계약: `docs/requirements/pipeline/s3-io.md`(PIPE-S3IO-*). 키 규약·멱등 마커·에러 동작은
> 그 문서를 **계승**하며, 아래에서 명시적으로 대체·확장한 항목만 달라진다.

> **파이프라인의 전부 (확장 논의의 기준선)**: `크롤 1000건 → 패턴 트리거 → 패턴 성공분 1000건 → Bedrock 트리거`.
> **Bedrock 이후 추가 단계는 없다.** `run_analysis`·`run_aggregate` 는 배선 밖이다(PIPE-S3IO-29).

> **설계 축 (이번 재설계)**: **S3 = 결과의 정본 / Redis = 작업 집합(최적화).**
> 단계별 대기 목록을 Redis **Set** 으로 들고, 처리가 끝나면 마커를 쓴 뒤 Set 에서 뺀다.
> 게시글은 **게시글 단위로 순차 처리**되어 중간 상태가 게시글 하나 분량을 넘지 않는다.

## 범위
- 포함:
  - `validation/{method}/...` 키 규약의 **`method` 축 파라미터화**(`pattern` | `bedrock`).
  - 신규 러너 `pipeline/run_bedrock.py` 의 입출력·멱등·에러 계약.
  - **게시글 단위 순차 처리**와 **게시글 경계에서의 예산 확인**.
  - **금액 기준 비용 상한**($30/밤), 공유 카운터 누적, **컨트롤러 게이트**, 프롬프트 캐싱 계약.
  - 신규 최상위 모듈 `bedrock/` 과 pipeline·validation 사이의 **경계 계약**(이종 혼합 배치 포함).
  - 배치 전용 Redis의 **작업 집합 2개 + 비용 카운터 1개** + 경량 컨트롤러의 **트리거 계약**
    (단계별 1000건 소프트 게이트, 잔여분 flush, 배치 종료 조건).
  - 실행 시각 02:00 KST 전환, 크롤 종료 시각 06:00 KST, 배치 날짜(`{date}`) 확정 방식.
  - ECR 이미지 2개 분리(`victoryfairy-refine-pattern` / `victoryfairy-refine-bedrock`).
  - Spot 회수·재시도·실패 시 동작.
- 제외 (의도적):
  - **LLM 판정 품질**(무엇을 잡고 무엇을 안 잡을지·목표치) → `docs/requirements/bedrock/llm-validation.md`.
  - **프롬프트 내용·모델 튜닝** → `accuracy-tuner`.
  - **`run_analysis`·`run_aggregate` 재배선** — 이번에도 배선 밖(파일·코드는 보존).
  - **검열 판정 알고리즘 변경** — `validation_service.validation()`(정규화 + 룰/정규식) 자체는 손대지 않는다.
    ⚠️ 단, **`run_validation` 의 산출물 구성(`process_post`)과 판정 대상은 바뀐다** — 본문 폐기 시 게시글 전체 fail,
    빈 본문일 때 `title` 판정 추가. 이는 `docs/requirements/pipeline/s3-io.md` **v2 개정** 소관이며
    이 문서는 그 파급(PIPE-2SB-8/8b/9/13)만 다룬다.
  - **k8s 매니페스트 작성**(CronJob·Deployment·RBAC) — 계약만 정하고 작성은 인프라(`VictoryFairy_Infra`) 소관.
  - **누적 크롤 데이터의 백필** — `docs/requirements/pipeline/backfill.md`(신규, 미작성) 소관. **분리 근거**:
    정규 배치는 **야간 스트리밍**(크롤과 정제가 동시에 흐르고, 카운터로 트리거되며, 매일 반복)인데
    백필은 **1회성 범위 처리**(크롤 없음, 여러 날짜 순회, 예산이 여러 밤에 걸침)라 **실행 모델이 다르다.**
    한 문서에 넣으면 `BATCH_DATE`·트리거·종료 조건이 전부 두 갈래로 갈린다.

## 후속 문서 — 백필 (이 문서 범위 밖 → **`docs/requirements/pipeline/backfill.md` 로 작성됨**, PIPE-BF-*)

> 사용자 확정: **백필은 따로 분리한다 / 백필 전용 상한을 별도로 둔다.** 아래는 그 문서가 받은 입력이며,
> 실제 계약은 `backfill.md` 에 있다(이 문서의 계약은 백필로 인해 한 글자도 바뀌지 않았다).

```
대상   community/ 2026-07-08 ~ 07-25 (17일치, 58,066 게시글)
       이미 정제된 것은 dcinside/2026-07-22 하루치(557건)뿐 → 백필 대상 약 57,500건
비용   패턴 통과율 60.5%(실측) → Bedrock 입력 약 48,700 단위 → 약 $73
상한   백필 전용 상한을 별도로 둔다(사용자 확정) — 정규 $30/밤과 분리
미결   BATCH_DATE 가 하루치만 보는 구조(PIPE-2SB-37)를 어떻게 확장할지
       정규 배치와의 동시 실행·Spot 노드 경합
       백필 전용 카운터를 따로 둘지 bedrock_spend_usd 를 공유할지
```

## 구성 요소 (용어 고정)
| 이름 | 정체 | 이 문서에서의 표기 |
|---|---|---|
| 크롤 워커 | 커뮤니티 게시글을 `community/{source}/{date}/{post_id}.json` 으로 적재 | THE 크롤 워커 |
| 작업 집합 | `batch-redis`(ns `victoryfairy-batch`)의 Set 2개 — `pending:pattern`(크롤됨·패턴 대기) · `pending:bedrock`(패턴 통과·Bedrock 대기). **게시글 키만 담는다** | THE 작업 집합 |
| 비용 카운터 | 같은 Redis 의 실수 키 `bedrock_spend_usd`(누적 LLM 비용) | THE 비용 카운터 |
| 컨트롤러 | 작업 집합 크기·비용·Job 상태를 폴링해 다음 단계 Job 을 생성하는 경량 프로세스 | THE 컨트롤러 |
| 패턴 러너 | `python -m pipeline.run_validation` | THE 패턴 러너 |
| Bedrock 러너 | `python -m pipeline.run_bedrock` | THE Bedrock 러너 |

---

## 결정적 계약 (EARS)

### A. S3 키 규약 (`method` 축 확장)

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| PIPE-2SB-1 | 유비쿼터스 | THE 시스템 SHALL 검열 산출물 키의 방식 세그먼트(`method`)를 **호출 인자**로 받는다 | `pipeline/s3_io.py` 의 `METHOD` 상수 고정이 아니라 `output_key(method=...)`/`manifest_key(method=...)` 로 지정 가능. 미지정 시 기본 `"pattern"` |
| PIPE-2SB-2 | 유비쿼터스 | THE 시스템 SHALL Bedrock 단계 산출물 키를 `validation/bedrock/{success\|failed}/{source}/{date}/{postExternalId}.json` 으로 구성한다 | `validation/bedrock/success/dcinside/2026-07-25/11229559.json` |
| PIPE-2SB-3 | 유비쿼터스 | THE 시스템 SHALL Bedrock 단계 완결 마커를 `validation/bedrock/_manifest/{source}/{date}/{postExternalId}.json` 에 쓴다 | 마커 키가 success/failed 리스팅과 섞이지 않음 |
| PIPE-2SB-3b | 유비쿼터스 | THE 시스템 SHALL 밑줄 접두 폴더(`_manifest`·`_shadow`)를 **산출물이 아닌 것**으로 취급하고 success/failed 리스팅과 섞이지 않게 한다 | `validation/bedrock/success/` 리스팅 결과에 마커·shadow 가 섞여 나오지 않는다. 후속 소비자(analysis 등)는 `success/` 만 읽으면 된다 |
| PIPE-2SB-4 | 유비쿼터스 | THE 시스템 SHALL Bedrock 단계의 입력 prefix 를 `validation/pattern/success/{source}/{date}/` 로 한다 | 사전 검열 **통과분만** LLM 이 본다. `community/` 를 직접 읽지 않는다 |
| PIPE-2SB-5 | 유비쿼터스 | THE 시스템 SHALL `community/**` 와 `validation/pattern/**` 을 Bedrock 단계에서 **읽기 전용**으로만 접근한다 | Bedrock 러너의 PutObject/DeleteObject 대상 키가 전부 `validation/bedrock/` 접두(`success`·`failed`·`_manifest`·`_shadow`) |
| PIPE-2SB-6 | 유비쿼터스 | THE 시스템 SHALL 기존 `pattern` 단계의 입력·출력 키 문자열을 변경하지 않는다 | 리팩터링 후에도 `validation/pattern/success/...` 산출 키가 이전과 바이트 동일 |

### B. Bedrock 러너 입출력

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| PIPE-2SB-7 | 이벤트 | WHEN `run_bedrock` 이 실행되면, THE 시스템 SHALL 입력 prefix 하위 모든 `.json` 객체를 페이지네이션으로 리스팅해 각각 처리한다 | 1000+ 객체도 전건 리스팅(PIPE-S3IO-27 계승) |
| PIPE-2SB-8 | 이벤트 | WHEN 각 pattern success 객체를 읽으면, THE 시스템 SHALL 그 게시글의 **본문 판정 단위(`body`, 비어 있으면 `title`)** 와 `topComments[].body` 를 **모두 검열 단위로 삼아 그 게시글 안에서 순차 처리**하되 각 단위는 **독립 판정**한다 | 본문 1 + 댓글 3 → 판정 4건, 서로 결과 영향 없음. **처리 순서 구분은 없다**(구 PIPE-2SB-51 폐기). **패턴 단계가 title 을 판정하므로(PIPE-S3IO-32) Bedrock 도 같은 텍스트를 봐야 커버리지가 대칭**이 된다 — 안 그러면 "제목은 패턴만 거치고 LLM 2차는 안 거친다"는 비대칭이 생긴다 |
| PIPE-2SB-8b | 유비쿼터스 | THE 시스템 SHALL title 을 본문 자리 판정 단위로 쓸 때 `unit_kind` 를 **`"body"`** 로 취급한다 | **별도 값을 만들지 않는다.** title 이 본문 자리를 대신하므로 `offtopic` 적용 대상(BRK-LLM-19)이 되는 것이 맞다 — 제목은 그 글의 주제를 가장 직접적으로 드러내는 텍스트다. 다만 **산출물의 `reasons[].unit` 에는 `"title"` 로 남긴다**(PIPE-2SB-13) — 판정 인자와 사후 추적 표기는 별개다 |
| PIPE-2SB-9 | (폐기됨) | ~~pattern success 의 `body` 가 빈 문자열이면 판정 대상에서 제외~~ — **패턴 단계 v2 개정으로 성립하지 않는다.** 빈 본문 글은 이제 title 로 판정돼 통과했거나(→ `body:""` 이지만 title 이 판정된 success) 폐기됐거나(→ success 미생성, PIPE-S3IO-19/33) 둘 중 하나라, **"판정된 적 없는 빈 본문"이 Bedrock 에 오지 않는다** | 번호 재사용 금지. 대체: PIPE-2SB-8. ⚠️ 이 ID 의 인수 기준에 달았던 **"dcinside 13.8%"는 부정확한 근거였다** — 그 값은 `community/`(크롤 원본)에서 잰 것이라 **패턴이 폐기해서 빈 게 아니라 원래 본문이 없는 글**이다. 두 케이스를 섞고 있었다 |
| PIPE-2SB-10 | 이벤트 | WHEN 통과한 단위가 하나라도 있고 **PIPE-2SB-11b 에 해당하지 않으면**, THE 시스템 SHALL 통과 단위만 남긴 정화 객체를 **원본 스키마 형태**로 `validation/bedrock/success/...` 에 기록한다 | 댓글 3 중 1 폐기 → success `topComments` 2개, 나머지 원본 필드 보존 |
| PIPE-2SB-10b | 유비쿼터스 | THE 시스템 SHALL **폐기된 텍스트를 success 객체의 어느 필드에도 남기지 않는다** — 본문 자리 판정 단위가 축 B 로 폐기되면 **그 자리에 해당하는 필드**를 빈 문자열로 비운다 | 규칙은 "**본문 자리 판정 단위를 비운다**"이고, **어느 필드를 비울지는 그 자리가 무엇이었느냐로 정해진다**: 판정 단위가 `body` 였으면 `body:""`, **`title` 이었으면 `title:""`**(이때 `body` 는 원래부터 `""`). 폐기 댓글은 `topComments` 에서 제외. **이 조항이 없으면 빈 본문 글에서 폐기된 제목이 그대로 success 에 실려 하류로 흘러간다** — 정화 객체의 존재 이유가 깨지는 유일한 경로였다(패턴 단계는 PIPE-S3IO-19 가 success 를 아예 안 만들어 이 문제가 없다). 규모: 빈 본문 하루 약 950건 × 축 B 폐기율 2%(잠정) ≈ **하루 20건 안팎** |
| PIPE-2SB-10c | 이벤트 | WHEN `title` 이 축 B 로 폐기되고 통과 댓글이 있으면, THE 시스템 SHALL `body:""` · `title:""` · 통과 댓글로 이루어진 success 객체를 기록한다 | **판단 근거**: 댓글은 **독립 판정 단위**(PIPE-2SB-8)이고 실제로 통과했으므로 버릴 근거가 없다. 여기서 버리면 **축 B 오탐 1건이 통과 댓글까지 날리는 것**이 되어, 바로 그 이유로 축 B 를 전체 fail 에서 제외한 PIPE-2SB-11b 의 결정과 정면으로 어긋난다. 통과 댓글이 0이면 **PIPE-2SB-11**(통과 단위 0 → success 미생성)이 받으므로 빈 껍데기 객체는 생기지 않는다 |
| PIPE-2SB-11 | 예외 | IF 통과한 단위가 하나도 없으면, THEN THE 시스템 SHALL success 객체를 생성하지 않고 failed 만 기록한다 | 전건 폐기 → bedrock success 키 미생성. **본문이 축 B 로 폐기되고 통과 댓글도 0이면 이 조항이 받는다**(빈 success 를 만들지 않는다) |
| PIPE-2SB-11b | 예외 | IF **본문 판정 단위가 `axis="abuse"` 로 폐기되면**, THEN THE 시스템 SHALL 그 게시글 전체를 fail 로 확정한다 — 통과 댓글이 있어도 success 를 만들지 않는다 | **사용자 확정: 축 A 만 전체 fail.** 패턴 단계(PIPE-S3IO-19)와 대칭. **축 B(`spam`·`offtopic`)는 본문 단위만 폐기**하고 통과 댓글로 success 를 만든다(PIPE-2SB-10). 근거: 패턴 검열은 사전·정규식이라 오탐이 거의 없어(validation.md — 자연스러운 한국어 30문장 기준 신규 오탐 미발생) "본문 걸리면 전체 fail"이 안전했지만, **LLM 은 다르다** — 특히 축 B 는 경계가 모호해 오탐 위험이 **구조적으로** 크고(BRK-LLM-40~45 를 두껍게 깐 이유), 같은 규칙을 적용하면 **오탐 1건이 게시글 전체와 통과 댓글 전부를 날린다.** 근거가 명확한 축만 대칭을 맞추고, 모호한 축은 피해를 본문 단위로 제한한다 |
| PIPE-2SB-11c | 유비쿼터스 | THE 시스템 SHALL 본문 판정 결과를 **세 갈래**로만 라우팅한다 — (1) 본문 통과 → 정상 success, (2) 본문이 `abuse` 로 폐기 → 게시글 전체 fail, (3) 본문이 `spam`·`offtopic` 으로 폐기 → **본문 자리 필드를 비운 success**(PIPE-2SB-10b/10c; 통과 댓글이 0이면 PIPE-2SB-11 로 success 미생성) | 세 갈래는 **서로 겹치지 않고 빈 곳도 없다**(`axis` 는 폐기 시 반드시 세 값 중 하나 — BRK-LLM-1b/11b). ⚠️ **욕설+광고가 겹친 본문은 `axis="abuse"` 로 기록되므로(BRK-LLM-1c, abuse 우선) 갈래 (2)** 가 된다 — **의도된 동작**이다. 갈래 (2)에서 댓글 판정을 생략할 수 있는지는 규정하지 않는다 — **배칭(PIPE-2SB-14/71)으로 한 게시글의 단위가 이미 같은 호출에 들어가므로 실익이 없다** |
| PIPE-2SB-12 | 이벤트 | WHEN 모든 단위가 통과하면, THE 시스템 SHALL failed 객체를 생성하지 않는다 | 전건 통과 → bedrock failed 키 미생성 |
| PIPE-2SB-13 | 유비쿼터스 | THE 시스템 SHALL failed 객체를 `{postExternalId, source, date, stage:"bedrock", modelId, promptVersion, reasons:[{unit, commentIndex, author, text, axis, message}]}` 형태로 기록한다 | `reasons` 항목은 PIPE-S3IO-13 스키마에 **`axis` 를 추가**한 형태. `unit ∈ {"body","title","comment"}` — **title 을 본문 자리로 판정했으면 `"title"`** 로 남긴다(PIPE-S3IO-34 와 같은 값 체계) |
| PIPE-2SB-13b | 유비쿼터스 | THE 시스템 SHALL `reasons[].axis` 값을 `bedrock` 모듈이 반환한 축 식별자(`"abuse"` \| `"spam"` \| `"offtopic"`)로 그대로 기록하고 러너가 재해석하지 않는다 | `accuracy-tuner` 가 축별로 분리 측정할 수 있다. 축 정의는 BRK-LLM-1b. **러너가 이 값을 보고 라우팅을 결정하는 것(PIPE-2SB-11b/11c)은 "재해석"이 아니다** — 값을 변형·재판정하지 않고 그대로 쓰는 것이다 |
| PIPE-2SB-14 | 유비쿼터스 | THE 시스템 SHALL LLM 판정 로직을 `bedrock` 모듈 서비스에 위임하고, **하나 이상의 게시글의 단위들을 묶은 배치**(본문·댓글 **혼합 가능**)를 전달하되 **항목마다 `unit_kind`(`"body"` \| `"comment"`)를 동반**한다 | 러너는 S3 I/O·단위 분해·배치 조립·결과 라우팅만. **"한 게시글"로 한정하면 PIPE-2SB-71(게시글 N개분)과 직접 충돌**하므로 열었다. 실측상 게시글당 단위가 **1.4개**라 게시글 1개씩 보내면 호출마다 시스템 프롬프트 **2,470토큰(실측)** 이 통째로 붙어 **배칭 이점이 사실상 사라진다**. 캐싱을 쓸 수 없게 된 뒤(PIPE-2SB-64) **배치 크기가 이 반복분을 흡수하는 유일한 수단**이 됐다. 배치가 **이종 혼합**이므로 `unit_kind` 는 배치 인자가 아니라 **항목별 인자**여야 한다(BRK-LLM-1) |
| PIPE-2SB-71 | 유비쿼터스 | THE 시스템 SHALL 한 호출에 묶는 크기를 **설정값**으로 읽되, 그 단위를 **"게시글 N개분"** 으로 센다 | 자연스러운 배치 경계가 **게시글 하나**다(그 게시글의 본문+댓글이 함께 간다). "단위 N개"로 세면 게시글이 배치 경계에 걸쳐 잘리고, 그러면 **게시글 단위 원자성(PIPE-2SB-18)과 예산 확인 시점(PIPE-2SB-74)이 둘 다 깨진다.** 기본값은 문서에 박지 않는다 — 최적값은 판정 품질을 보고 `accuracy-tuner` 가 정한다. 비용 근거: 시스템 프롬프트 **2,470토큰(실측)** 이 호출마다 통째로 붙으므로 묶을수록 단위당 부담이 준다. 캐싱을 쓸 수 없으므로(PIPE-2SB-64) **이 값이 비용을 좌우하는 가장 큰 레버**다 — 배치 5게시글이면 하루 $13.79, 20게시글이면 $7.07 |
| PIPE-2SB-15 | 유비쿼터스 | THE 시스템 SHALL `bedrock` 모듈이 S3·버킷·키 규약을 알지 못하게 한다 | `bedrock/` 어디에도 `boto3.client("s3")`·`S3_BUCKET` 참조 없음(텍스트 in / 판정 out) |
| PIPE-2SB-16 | 유비쿼터스 | THE 시스템 SHALL Bedrock 판정 결과를 `{is_valid, axis, message, fallback}` 형태로 러너에 돌려받는다 | 러너의 통과/폐기 라우팅 코드는 `is_valid` 만 본다. `fallback`(BRK-LLM-15b)은 PIPE-2SB-59 기록용 |
| PIPE-2SB-59 | 이벤트 **(개정 2026-07-26)** | WHEN 어떤 단위가 **실제 장애로 인한** 폴백 통과(`fallback=true` — BRK-LLM-15)로 처리되면, THE 시스템 SHALL 그 사실을 **사유 문구(`message`)와 함께** success 객체에 기록한다 | ⚠️ **DRY_RUN(BRK-LLM-16b)은 이 조항의 대상에서 빠졌다** — PIPE-2SB-78 로 DRY_RUN 은 S3 에 아무것도 쓰지 않게 됐으므로 기록할 success 객체 자체가 없다. 구 문언은 DRY_RUN 산출물이 남는 것을 전제했고, 그 전제가 사고의 원인이었다. `message` 로 두 경우를 가르는 BRK-LLM-16b 의 구분은 **런타임 판정 결과에는 그대로 남는다**(로그·반환값). | `fallback` 만 남기면 **Bedrock 장애와 DRY_RUN 이 산출물에서 구분되지 않는다**. 폴백 통과는 `is_valid=true` 라 기록이 없으면 **"이 데이터는 2차 검열을 안 거쳤다"를 사후에 알 수 없다** |
| PIPE-2SB-70 | 선택 | WHERE shadow 모드(BRK-LLM-48)가 켜진 경우, THE 시스템 SHALL 판정 결과를 `validation/bedrock/_shadow/{source}/{date}/` 에만 기록하고 success/failed·`_manifest` 를 쓰지 않으며 작업 집합도 갱신하지 않는다 | 폐기율 실측 전용 경로. **마커를 쓰지 않으므로** shadow 실행이 실제 판정을 대신하지 않는다 |

### B-2. 게시글 단위 순차 처리 · 예산 확인 시점

> **배경 (실측 근거 — 버킷 `victoryfairy-crawl-dev`, `community/` 58,066 게시글)**
>
> | 소스 | 게시글 수 | 비중 | 게시글당 댓글 | 본문 길이(중앙/평균) |
> |---|---:|---:|---|---|
> | dcinside | 56,387 | **97%** | **0개** | 16자 / 29자 |
> | fmkorea | 1,682 | 3% | 평균 16, 최대 20 | 28자 / 85자 |
>
> 일별 물량은 약 **7,000 게시글**, 게시글당 검열 단위는 **1.4개**, 전수 처리해도 하루 **약 $13**.
> 여기에 **빈 본문 글의 `title` 판정(PIPE-S3IO-32)이 더해져 하루 약 950단위가 순증**하고 비용은 **약 $14.5** 가 된다
> (빈 본문 글은 `body` 0단위 → `title` 1단위). 여전히 상한($30)의 절반 수준이다.
> **본문/댓글을 나눠 처리할 비용 근거가 없다** — 그래서 게시글 단위 순차 처리로 간다.
>
> 다만 **dcinside 댓글이 0인 것은 결함이 아니라 크롤러 설계다**(`py-collector/kbo_collector/community.py:263`
> — AJAX 로드라 정적 HTML 에 없어 항상 `[]`). **AJAX 수집이 구현되면 검열 단위가 하루 11만 건 / 비용 $158 로
> 뛴다.** 그때 실제로 작동하는 방어선은 금액 상한(PIPE-2SB-60)과 게시글 경계 확인(PIPE-2SB-74)이다.

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| PIPE-2SB-74 | 이벤트 | WHEN 어떤 **배치**의 호출을 **시작하기 전에**, THE 시스템 SHALL `bedrock_spend_usd` 를 확인해 상한 이상이면 그 배치를 시작하지 않고 남은 게시글도 시작하지 않는다 | 확인 시점이 **배치 경계**다(구 "게시글 경계"에서 조정 — PIPE-2SB-71 이 배치를 게시글 N개분으로 정의하므로 게시글 경계와 배치 경계가 어긋났다). 배치는 게시글을 자르지 않으므로(PIPE-2SB-71) **게시글 단위 원자성(PIPE-2SB-18)은 그대로 지켜진다.** 호출 도중에 끊으면 부분 상태가 생긴다 |
| PIPE-2SB-75 | 유비쿼터스 | THE 시스템 SHALL 상한 초과 폭이 **최대 "마지막 배치 1회분"** 임을 전제로 운영한다 | PIPE-2SB-61 이 "각 호출 후" 누적인데 **배치 1회 = 호출 1회**라 호출 도중에는 끊을 수 없다. 즉 상한은 정확한 벽이 아니라 **소프트 상한**이다(PIPE-2SB-31 과 같은 종류). 배치 크기(PIPE-2SB-71)를 키울수록 초과 폭도 커진다 — **PIPE-2SB-74 와 같은 경계를 말한다** |
| PIPE-2SB-51 | (폐기됨) | ~~date prefix 의 모든 게시글 본문을 먼저 처리한 뒤 댓글 판정 시작~~ — **사용자 결정으로 폐기.** 본문 우선은 Job 내부에서만 성립하고 **Job 경계에서 깨졌다**(다음 Job 은 새 게시글 집합을 본다). 게시글 단위 순차 처리로 개념째 사라진다 | 번호 재사용 금지. 대체: PIPE-2SB-8 |
| PIPE-2SB-52 | (폐기됨) | ~~예산 소진 시 `judgedScope:"body_only"` 로 완결 처리~~ — 본문/댓글 구분이 사라져 성립하지 않는다 | 번호 재사용 금지. 대체: PIPE-2SB-74(게시글을 아예 시작하지 않음) |
| PIPE-2SB-53 | (폐기됨) | ~~산출물에 `judgedScope` 기록~~ — 게시글은 **전부 판정되거나 아예 시작되지 않으므로** 구분할 모집단이 없다 | 번호 재사용 금지 |
| PIPE-2SB-53b | (폐기됨) | ~~빈 본문 게시글의 `judgedScope` 규정~~ — 위와 같은 이유로 소멸. **빈 본문 처리 자체(PIPE-2SB-9)는 유효** | 번호 재사용 금지 |
| PIPE-2SB-58 | (폐기됨) | ~~`_staging` 재사용으로 본문 재판정 회피~~ — 중간물이 사라져 불필요 | 번호 재사용 금지 |
| PIPE-2SB-66 | (폐기됨) | ~~본문 판정 결과를 `_staging` 에 기록~~ — **게시글 단위 순차 처리로 중간 상태가 게시글 하나 분량뿐이라 중간물 자체가 불필요해졌다** | 번호 재사용 금지 |
| PIPE-2SB-67 | (폐기됨) | ~~완결 시 `_staging` 병합·삭제 및 그 순서~~ — 위와 같음. 산출물은 그 게시글을 다 판정한 뒤 1회 기록하면 되므로 **PIPE-2SB-18(원자성)이 자연히 성립**한다. 다만 "마커 뒤에 정리 작업" 이라는 **순서 논증은 PIPE-2SB-72 로 계승**된다 | 번호 재사용 금지. 대체: PIPE-2SB-10/18/72 |

### C. 멱등 · 실패 · Spot 회수

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| PIPE-2SB-17 | 예외 | IF 어떤 게시글의 **Bedrock 완결 마커**가 이미 존재하면, THEN THE 시스템 SHALL 그 게시글을 재처리하지 않고 건너뛴다 | 재실행 → 완결분 skip. LLM 호출 0회(=비용 0) |
| PIPE-2SB-18 | 유비쿼터스 | THE 시스템 SHALL 한 게시글의 Bedrock 산출물(success·failed)을 **원자적으로** 확정한다(마커는 마지막에 기록) | 게시글 단위 순차 처리라 **부분 산출물이 발생하는 정상 경로가 없다.** success 쓰고 마커 전 중단 → 미완결로 판정되어 재실행 시 재처리 |
| PIPE-2SB-18b | (폐기됨) | ~~예산 소진 시 부분 산출물을 마커 없이 보존하는 예외~~ | 번호 재사용 금지 |
| PIPE-2SB-19 | 유비쿼터스 | THE 시스템 SHALL 패턴 마커와 Bedrock 마커를 서로 독립된 키로 관리한다 | `validation/pattern/_manifest/...` 존재가 `validation/bedrock/_manifest/...` 존재를 함의하지 않음 |
| PIPE-2SB-20 | 예외 | IF 러너 프로세스가 SIGTERM(Spot 회수)을 받으면, THEN THE 시스템 SHALL 진행 중인 게시글의 마커를 쓰지 않고 종료한다 | 회수 시점의 게시글은 작업 집합에 남아 다음 실행에서 재처리 |
| PIPE-2SB-21 | 예외 | IF 러너가 0이 아닌 종료 코드로 끝나면, THEN THE 시스템 SHALL 그 Job 을 최대 **3회(가정)** 재시도한다 | k8s Job `backoffLimit: 3`. 멱등 skip 덕에 재시도가 중복 처리를 만들지 않는다 |
| PIPE-2SB-22 | 예외 | IF S3 접근이 실패하거나 자격증명이 만료되면, THEN THE 시스템 SHALL 명확한 에러로 중단하고 0이 아닌 종료 코드를 반환한다 | PIPE-S3IO-26 계승. 조용한 실패 금지 |
| PIPE-2SB-23 | 예외 | IF 입력 객체가 JSON 파싱 불가하거나 필수 필드가 없으면, THEN THE 시스템 SHALL 그 객체를 건너뛰고 나머지 처리를 계속한다 | PIPE-S3IO-23 계승 |
| PIPE-2SB-24 | 예외 | IF 입력 prefix 에 객체가 하나도 없으면, THEN THE 시스템 SHALL 크래시 없이 로그만 남기고 정상 종료한다 | 컨트롤러가 빈 상태에서 Job 을 띄워도 실패로 집계되지 않음 |

### D. 작업 집합 (Redis Set)

> **구조**
> ```
> batch-redis (ns victoryfairy-batch, emptyDir — 배치와 함께 뜨고 사라진다)
>   pending:pattern    Set    크롤됨, 패턴 검열 대기     ← 크롤 워커가 SADD
>   pending:bedrock    Set    패턴 통과, Bedrock 대기    ← 패턴 러너가 SADD
>   bedrock_spend_usd  실수   누적 LLM 비용
>
> 트리거  SCARD pending:X >= 1000
> 완료    S3 마커 기록 → SREM
> 종료    두 Set 이 모두 비고 두 단계 Job 이 Complete
> ```

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| PIPE-2SB-26 | 유비쿼터스 | THE 시스템 SHALL 배치 상태를 `batch-redis`(ns `victoryfairy-batch`)의 **Set 2개**(`pending:pattern`·`pending:bedrock`)와 **실수 키 1개**(`bedrock_spend_usd`)**(가정 키명)** 로 관리한다 | 운영 Redis 와 분리. **`emptyDir` 이라 배치와 함께 뜨고 사라진다** — 초기화 계약이 따로 필요 없다(구 PIPE-2SB-36b 폐기 근거). **Set 에는 게시글 키만 담는다** — 데이터 본체는 S3 에 있고 러너가 거기서 읽으므로 중복 보관할 이유가 없으며, 트리거는 개수만 필요하다 |
| PIPE-2SB-72 | 유비쿼터스 | THE 시스템 SHALL 게시글 처리 완료를 **`S3 마커 기록 → Redis SREM`** 순서로 반영한다 | **순서가 계약이다.** 반대로 하면 그 사이 크래시 시 **마커 없음 + 목록에서 사라짐 → 영구 미처리**가 된다. 이 순서면 마커 뒤 크래시 시 항목이 Set 에 남았다가 다음 Job 이 집어 **마커를 보고 skip(PIPE-2SB-17)하고 제거**하므로 무해하다 |
| PIPE-2SB-72b | 유비쿼터스 | THE 패턴 러너 SHALL 한 게시글에 대해 **`마커 기록 → (success 면) SADD pending:bedrock → SREM pending:pattern`** 순서를 지키되, **`SREM` 은 성공·실패와 무관하게 완결된 모든 게시글에 대해 수행**한다 | 다음 단계에 넣기 전에 현재 단계에서 빼면, 그 사이 크래시 시 **양쪽 Set 어디에도 없는 게시글**이 생긴다. 이 순서면 크래시 시 두 Set 에 동시에 존재할 뿐이고, 재처리 → 마커 skip → `SADD`(멱등) → `SREM` 으로 수렴한다. ⚠️ **`SADD` 는 조건부지만 `SREM` 은 무조건이다** — 전건 폐기된 게시글(PIPE-S3IO-19)은 `pending:bedrock` 에 넣지 않지만(PIPE-2SB-47), `pending:pattern` 에서는 반드시 빼야 한다. 빼지 않으면 실패분이 영원히 남아 **`SCARD pending:pattern` 이 0에 수렴하지 못하고 종료 조건(PIPE-2SB-35c)이 성립하지 않는다** — 실측 폐기율이 39.5%라 컨트롤러가 죽지 않는다 |
| PIPE-2SB-73 | 유비쿼터스 | THE 시스템 SHALL **S3 마커를 진실의 원천으로, Redis 작업 집합을 최적화 수단으로** 취급한다 | Redis 가 통째로 죽어도 **데이터 유실이 아니다** — 대상은 S3 prefix 리스팅 + 마커 확인으로 복구할 수 있다(기존 멱등 skip 과 같은 방식, `O(N)` 이라 느릴 뿐). 러너의 `SREM`/`SADD` 실패는 **에러로 중단하지 않고** 로그만 남긴다(다음 실행이 마커를 보고 수렴한다) |
| PIPE-2SB-25 | 이벤트 | WHEN 크롤 워커가 게시글 1건을 S3 에 기록하면, THE 크롤 워커 SHALL 그 게시글 키를 `pending:pattern` 에 **`SADD`** 한다 | 기록 **성공 후** 추가(유령 항목 금지). 같은 키를 두 번 넣어도 Set 이라 중복되지 않는다 |
| PIPE-2SB-76 | 예외 | IF 크롤 워커의 `SADD` 가 실패하면, THEN THE 크롤 워커 SHALL 최대 **3회(가정)** 재시도하고, 끝내 실패하면 **에러 로그를 남기되 크롤은 계속한다** | S3 기록은 이미 끝났으므로 **데이터 유실은 아니다.** 다만 그 게시글은 `pending:pattern` 에 없어 **트리거에 반영되지 않고**(PIPE-2SB-28·32 의 조건에도 안 걸려) 그 배치에서 정제되지 않는다 — PIPE-2SB-73 이 다루는 것은 **러너의** Set 실패뿐이라 이 경로가 비어 있었다. 크롤을 중단시키지 않는 이유: 입력 수집이 정제 트리거보다 우선한다 |
| PIPE-2SB-27 | 상태 | WHILE 배치가 진행되는 동안, THE 컨트롤러 SHALL `SCARD pending:pattern`·`SCARD pending:bedrock`·`bedrock_spend_usd` 를 **60초(가정)** 주기로 폴링한다 | 폴링 주기가 곧 트리거 지연 상한. 비용 키를 안 보면 PIPE-2SB-68 게이트가 작동하지 않는다 |
| PIPE-2SB-28 | 이벤트 | WHEN `SCARD pending:pattern` 이 1000 이상으로 관측되면, THE 컨트롤러 SHALL 패턴 단계 Job 을 1개 생성한다 | `victoryfairy-refine-pattern` Job 생성. **차감 규칙은 없다** — 처리하면 Set 에서 빠지므로(PIPE-2SB-72) 개수가 저절로 줄어든다 |
| PIPE-2SB-29 | (폐기됨) | ~~패턴 Job 생성 시 카운터 `DECRBY 1000`~~ — **Set 구조에서 차감 규칙 자체가 불필요**하다. 처리 완료가 곧 제거이고, 미처리분은 그대로 남는다 | 번호 재사용 금지. 대체: PIPE-2SB-72 |
| PIPE-2SB-30 | 상태 | WHILE 같은 단계의 Job 이 실행 중인 동안, THE 컨트롤러 SHALL 그 단계의 새 Job 을 생성하지 않는다 | 단계별 동시 실행 최대 1. **작업 집합은 락이 아니다**(항목을 원자적으로 점유하는 계약이 없다) — 두 러너가 뜨면 같은 게시글을 중복 처리해 Bedrock 단계에서 중복 과금이 난다 |
| PIPE-2SB-31 | 유비쿼터스 | THE 시스템 SHALL 1000 게이트를 **소프트 게이트**로 취급한다 — 한 Job 은 실행 시점에 존재하는 **미완결 게시글 전부**를 처리하며 1000 초과를 허용한다 | `SCARD` 2400 상태에서 뜬 Job 이 2400건을 처리해도 정상. **Set 구조에서는 개수와 실제 잔여가 항상 일치**하므로(구 카운터 방식의 괴리가 소멸) "미완결 전부"의 의미가 명확해졌다 |
| PIPE-2SB-32 | 이벤트 | WHEN 크롤 Job 이 종료되고 `pending:pattern` 이 비어 있지 않으면, THE 컨트롤러 SHALL 1000 미만이어도 마지막 패턴 Job 을 생성한다(잔여분 flush) | 크롤 종료 시 잔여 340건 → 패턴 Job 1개 생성, 340건 처리. **카운터 조작은 하지 않는다**(구 `SET 0` 폐기) — 처리되면 Set 에서 빠진다 |
| PIPE-2SB-33 | (폐기됨) | ~~패턴 Job 완료마다 Bedrock Job 생성~~ | 번호 재사용 금지. 대체: PIPE-2SB-49 |
| PIPE-2SB-34 | 예외 | IF 컨트롤러가 Redis 또는 k8s API 에 접근하지 못하면, THEN THE 컨트롤러 SHALL 해당 폴링 주기를 건너뛰고 에러 로그를 남긴 뒤 다음 주기에 재시도한다 | 일시 장애로 컨트롤러가 죽지 않는다 |
| PIPE-2SB-47 | 이벤트 | WHEN 패턴 러너가 어떤 게시글의 마커를 기록했고 그 게시글의 success 객체가 생성됐으면, THE 패턴 러너 SHALL 그 키를 `pending:bedrock` 에 **`SADD`** 한다 | 마커 기록 **성공 후** 추가. 전건 폐기되어 success 미생성(PIPE-S3IO-19)인 게시글은 넣지 않는다 — Bedrock 이 볼 입력이 없다 |
| PIPE-2SB-48 | 이벤트 | WHEN 패턴 러너가 여러 게시글을 처리하면, THE 패턴 러너 SHALL Set 갱신을 파이프라이닝으로 **묶어 반영해도 무방하다** — 단 게시글별 순서(PIPE-2SB-72b)는 지킨다 | 호출 수 절감 여지. **반영 전에 죽어도 유실이 아니다** — 그 게시글은 `pending:pattern` 에 남아 재처리되고, 마커 skip 후 `SADD`/`SREM` 이 수렴한다(구 카운터 방식의 "영영 안 세짐" 문제가 소멸) |
| PIPE-2SB-49 | 이벤트 | WHEN `SCARD pending:bedrock` 이 1000 이상으로 관측되면, THE 컨트롤러 SHALL Bedrock 단계 Job 을 1개 생성한다 | Bedrock 은 패턴 Job 완료 여부가 아니라 **자기 작업 집합**으로 뜬다. **차감 없음**(구 `DECRBY 1000` 폐기) |
| PIPE-2SB-50 | 이벤트 | WHEN 크롤 Job 이 종료되고 `pending:pattern` 이 비었으며 패턴 Job 이 `Complete` 이고 `pending:bedrock` 이 비어 있지 않으면, THE 컨트롤러 SHALL 1000 미만이어도 마지막 Bedrock Job 을 생성한다(잔여분 flush) | 패턴 잔여 flush(PIPE-2SB-32) 다음에 Bedrock 잔여 flush 가 이어져야 배치가 소진된다. **카운터 조작 없음.** ⚠️ **예산이 소진된 상태에서는 PIPE-2SB-68 이 우선한다** — flush Job 도 만들지 않는다 |
| PIPE-2SB-68 | 상태 | WHILE `bedrock_spend_usd` 가 상한(PIPE-2SB-60) 이상인 동안, THE 컨트롤러 SHALL Bedrock Job 을 **생성하지 않는다** — 게이트 Job(PIPE-2SB-49)이든 잔여분 flush(PIPE-2SB-50)든 예외 없다 | **이 금지는 PIPE-2SB-49·50 의 생성 의무보다 우선한다.** 실질적인 제동이 여기다 — 러너만 멈추면(PIPE-2SB-63) 새 Job 이 계속 떠서 **게시글은 하나도 처리하지 못한 채 공회전**한다 |
| PIPE-2SB-35 | 이벤트 | WHEN **06:00 KST** 에 도달하고 크롤 Job 이 아직 실행 중이면, THE 컨트롤러 SHALL 크롤 Job 을 **강제 종료**한다 | 06:00 은 **크롤 종료 시각**이지 배치 종료 시각이 아니다(사용자 확정) |
| PIPE-2SB-35b | 유비쿼터스 | THE 시스템 SHALL 크롤 종료 이후에도 **이미 적재된 게시글이 두 정제 단계에서 모두 소진될 때까지** 배치를 계속한다 | **단, 예산이 소진되면 Bedrock 단계는 소진되지 않고 멈춘다**(PIPE-2SB-68) — 패턴 단계는 LLM 비용이 0이라 예산과 무관하게 끝까지 돈다 |
| PIPE-2SB-35c | 이벤트 | WHEN `pending:pattern` 이 비고 크롤·패턴 Job 이 모두 종료했으며, **`pending:bedrock` 이 비었거나 `bedrock_spend_usd` 가 상한 이상이면**, THE 컨트롤러 SHALL 실행 중인 Bedrock Job 의 종료를 기다린 뒤 배치 종료로 판정하고 미처리 건수를 로그로 남긴 뒤 자신을 종료한다 | **예산 소진은 Bedrock 단계만 멈추는 조건이지 배치 전체의 수명이 아니다** — 그래서 예산 항목은 `pending:bedrock` 자리에만 걸린다. 예산 항목이 없으면 PIPE-2SB-68 게이트 때문에 그 Set 이 영영 안 비어 컨트롤러가 죽지 않는다. **키 초기화는 하지 않는다** — Redis 가 배치와 함께 사라지므로(PIPE-2SB-26) 초기화할 대상이 없다 |
| PIPE-2SB-36b | (폐기됨) | ~~배치 시작 시 컨트롤러가 Redis 키를 초기화~~ — **`batch-redis` 가 `emptyDir` 로 배치마다 새로 뜨므로 초기화 대상이 없다.** "크롤 워커가 먼저 떠서 올린 값을 컨트롤러가 지운다"는 경합도 **구조적으로 소멸**한다 | 번호 재사용 금지. 대체: PIPE-2SB-26 |

### E. 스케줄 · 배치 날짜

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| PIPE-2SB-36 | 이벤트 | WHEN 매일 **02:00 KST** 가 되면, THE 시스템 SHALL 야간 배치(크롤 Job + 컨트롤러 + `batch-redis`)를 시작한다 | CronJob `timeZone: Asia/Seoul` + `schedule: "0 2 * * *"`. 기존 03:15 스케줄은 제거 |
| PIPE-2SB-37 | 유비쿼터스 | THE 시스템 SHALL 각 단계 러너가 처리할 `{date}` 를 **배치 시작일(KST)** 로 고정하고 환경변수 `BATCH_DATE`**(가정 키명)** 로 주입받는다 | `BATCH_DATE=2026-07-25` → 모든 단계가 같은 날짜 prefix 를 본다. **PIPE-S3IO-4 개정 = 사용자 승인 완료** |
| PIPE-2SB-38 | 예외 | IF `BATCH_DATE` 가 주입되지 않으면, THEN THE 시스템 SHALL 실행 당일 KST 날짜로 폴백한다 | 로컬 수동 실행 시 기존 `today_kst()` 동작 유지 |

> ⚠️ PIPE-2SB-37 은 **PIPE-S3IO-4("`{date}`=실행 당일 KST")를 조건부로 대체**한다(사용자 승인 완료). 재시도·지연으로
> 러너가 자정을 넘겨 뜨면 러너마다 다른 날짜를 보게 되고, 그 순간 앞 단계 산출물을 **영영 못 찾는다**(조용한 유실).
> **06:00 이후에도 정제가 계속되는 결정(PIPE-2SB-35b)이 이 위험을 키운다.**

### F. 컨테이너 분리

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| PIPE-2SB-39 | 유비쿼터스 | THE 시스템 SHALL 정제 컨테이너를 두 이미지로 분리한다 — `victoryfairy-refine-pattern`(CMD `python -m pipeline.run_validation`), `victoryfairy-refine-bedrock`(CMD `python -m pipeline.run_bedrock`) | ECR `555209622409.dkr.ecr.ap-northeast-2.amazonaws.com` 아래 리포지터리 2개 |
| PIPE-2SB-40 | 유비쿼터스 | THE 시스템 SHALL 두 이미지 어디에도 `analysis` 모듈 의존성(torch·Kiwi·transformers)을 포함하지 않는다 | 이미지 내 `pip list` 에 `torch` 없음. Spot 노드 pull 시간 직결 |
| PIPE-2SB-41 | 유비쿼터스 | THE 시스템 SHALL pattern 이미지에 Bedrock 설정·프롬프트를, bedrock 이미지에 검열 사전(`validation/core/data/*.json`)을 포함하지 않는다 | 각 이미지가 자기 단계에 필요한 것만 담는다 |
| PIPE-2SB-42 | 이벤트 | WHEN Spot 노드가 0에서 새로 기동해 Job 파드를 띄우면, THE 시스템 SHALL 이미지 pull 을 포함해 **120초(가정)** 이내에 첫 게시글 처리를 시작한다 | 파드 생성 시각 ~ 첫 처리 로그 사이 경과 시간 |

### G. 관측 · 비용

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| PIPE-2SB-43 | 이벤트 | WHEN 러너가 종료되면, THE 시스템 SHALL 처리·멱등 skip·불량 객체 건수를 요약 로그로 남긴다 | `완료: 처리 N / 이미완결(skip) M / 불량객체(skip) K` |
| PIPE-2SB-44 | 이벤트 | WHEN Bedrock 러너가 종료되면, THE 시스템 SHALL LLM 호출 횟수·본문/댓글 단위 수·폐기 단위 수·폴백 통과 건수·**누적 소비액과 캐시 적중 토큰 수**·**예산으로 시작하지 못한 게시글 수**를 요약 로그로 남긴다 | **캐싱을 켠 상태에서** 캐시 적중 토큰이 0이면 PIPE-2SB-64/65 가 깨진 것이다 — 캐싱이 꺼져 있으면(PIPE-2SB-77 기본값) 0이 정상이므로 이 값만으로 고장을 판정하지 마라. 미시작 건수는 PIPE-2SB-74 의 관측 지점 |
| PIPE-2SB-45 | 유비쿼터스 | THE 시스템 SHALL Bedrock 호출 페이로드에 **판정 대상 텍스트와 프롬프트(항목별 `unit_kind` 포함)만** 담고 게시글 메타(`engagement`·`sourceUrl`·`author` 등)를 포함하지 않는다 | batch 노드는 프라이빗 서브넷이라 Bedrock 호출이 **단일 AZ NAT(2a)** 를 탄다. **`unit_kind` 는 "메타"가 아니라 판정 인자**다(PIPE-2SB-14, BRK-LLM-19) |
| PIPE-2SB-46 | (개정됨) | ~~한 실행의 LLM 호출 5000회(가정) 상한~~ → 금액 기준·하루 누적으로 개정 | 대체: PIPE-2SB-60~63 |
| PIPE-2SB-60 | 유비쿼터스 | THE 시스템 SHALL 하루(1배치) 누적 LLM 비용 상한을 **$30 USD** 로 둔다 | 사용자 확정. 건수가 아니라 **금액** 기준 |
| PIPE-2SB-61 | 유비쿼터스 | THE 시스템 SHALL 누적 소비액을 `batch-redis` 의 `bedrock_spend_usd` 에 두고, 각 호출 후 응답 `usage` 토큰 수 × 단가로 계산한 금액을 `INCRBYFLOAT` 로 누적한다 | 누적 주체가 러너 메모리면 **Job 이 여러 개라 총량이 집계되지 않는다.** 공유 키여야 한다 |
| PIPE-2SB-62 | 유비쿼터스 | THE 시스템 SHALL 입력·출력·캐시읽기·캐시쓰기 토큰의 **단가를 설정값(env/ConfigMap)** 으로 읽는다 | **코드 상수 금지.** 단가는 미확인·변동 값이다(아래 한계) |
| PIPE-2SB-63 | 예외 | IF 실행 중 상한에 도달하면, THEN THE 시스템 SHALL 남은 게시글을 시작하지 않고(PIPE-2SB-74) 미처리 건수를 로그로 남긴 뒤 정상 종료(exit 0)한다 | exit 0 이라 무한 재시도 루프에 빠지지 않는다. **실질적 제동은 컨트롤러 게이트**(PIPE-2SB-68) |
| PIPE-2SB-64 | 선택 **(v2 개정됨)** | WHERE 사용 모델이 Bedrock 프롬프트 캐싱을 지원하는 경우, THE 시스템 SHALL 판정 기준·케이스가 담긴 **시스템 프롬프트 접두부에 캐시 breakpoint** 를 둔다 | 인수 기준: **캐싱을 켠 상태의** 연속 호출에서 `cache_read_input_tokens > 0`. ⚠️ **v1 은 유비쿼터스 계약이었고 현행 모델에서 달성 불가능하다** — `claude-3-5-sonnet-20240620` 은 캐싱 미지원(BRK-LLM-6). 배선을 지우지 않고 조건부로 바꾼 이유는 **서울에 캐싱 지원 `ON_DEMAND` 모델이 들어오면 설정 한 줄로 켜야 하기 때문**이다. 비용 영향은 감당 가능하다 — 캐싱 없이도 배치 5게시글 기준 하루 약 **$13.79**(상한 $30의 46%)이고, 시스템 프롬프트 반복분은 **배치 크기가 대신 흡수한다** |
| PIPE-2SB-65 | 선택 **(v2 개정됨)** | WHERE 캐시 breakpoint 를 두는 경우, THE 시스템 SHALL **판정 대상 텍스트를 breakpoint 뒤에** 배치한다 | 캐시는 **프리픽스 매칭**이다. 앞에 오면 프리픽스가 매 호출 달라져 **캐시가 전혀 동작하지 않는다**. breakpoint 를 두지 않으면 이 조항은 공회전한다 |
| PIPE-2SB-78 | 선택 **(신규 2026-07-26)** | WHERE `BEDROCK_DRY_RUN=true` 인 경우, THE Bedrock 러너 SHALL **S3 에 아무 객체도 기록하지 않는다**(success·failed·`_manifest`·`_shadow` 전부) | ⚠️ **실제로 사고를 낸 결함이다.** 이전에는 DRY_RUN 이 success + 완결 마커를 기록했다. 판정을 한 번도 하지 않은 게시글이 "2차 검열 완결" 로 남고, **마커가 붙는 순간 멱등 skip 대상이 되어 진짜 실행도 섀도 측정도 그 게시글을 영영 건너뛴다.** BRK-LLM-16 이 DRY_RUN 을 "비용 없이 배선·키 규약 검증, 테스트 기본값" 으로 정의하는데 **테스트용 모드가 운영 상태를 영구히 바꾸고 있었다** — 실버킷에 한 번만 돌려도 그 날짜가 통째로 미판정인 채 완결 처리된다. PIPE-2SB-59(폴백 사유를 success 에 기록)는 **실제 장애로 인한 폴백(BRK-LLM-15)에만** 적용된다 — DRY_RUN 은 산출물 자체를 남기지 않으므로 기록할 대상이 없다 |
| PIPE-2SB-77 | 유비쿼터스 **(v2 신규)** | THE 시스템 SHALL 캐시 breakpoint 첨부 여부를 설정으로 제어하고 **기본값을 끔으로 둔다** | **기본값이 켬이면 배포 즉시 전면 장애다.** 미지원 모델에 `cachePoint` 를 붙이면 `AccessDeniedException` → `BedrockFatalError`(BRK-LLM-17) 로 러너가 즉시 중단돼 **한 건도 처리하지 못한다**. 폴백 통과로 조용히 새지 않는 것은 설계대로지만(그래서 데이터는 오염되지 않는다), 기본값이 잘못되면 **아무것도 처리되지 않는 밤**이 된다. 지원 모델로 바꿀 때만 켠다 |

---

## 판정 요구사항 (케이스 기반)
> **이 문서에는 판정 요구사항(재현율/오탐)이 없다 — 의도된 것이다.** "무엇을 잡아야 / 무엇이 잡히면 안 되는가"는
> 전부 `docs/requirements/bedrock/llm-validation.md`(축 A: BRK-LLM-20~24 ↔ 30~35 / 축 B: BRK-LLM-25~27 ↔ 40~45,
> 목표치 확정 절차 BRK-LLM-46/47)에 있다. 두 문서를 **함께** 승인해야 이 기능이 성립한다.
>
> 다만 아래 셋은 판정이 아니라 계약이므로 여기에 못박았다: **본문 자리 판정 단위의 결정**(PIPE-2SB-8/8b —
> 어느 텍스트를 판정 대상으로 삼는가), **통과/폐기 라우팅**(PIPE-2SB-10/10b/10c/11/11b/11c),
> **축 식별자의 무손실 기록**(PIPE-2SB-13/13b — 러너가 축을 재해석하지 않는다).

---

## 이미 기각된 것 / 기존 계약과의 충돌 (모듈 문서 "한계" 대조)

1. **`PIPE-S3IO-28`("파이프라인 흐름은 `run_validation` 단독") 은 이 문서로 대체된다.**
   `run_analysis`·`run_aggregate` 는 **여전히 배선 밖**(PIPE-S3IO-29 그대로 유효).
   → 승인 반영 시 `context-keeper` 가 s3-io.md 에 "대체됨" 표기를 넣는다.
2. **`PIPE-S3IO-4`("`{date}` = 실행 당일 KST") 는 PIPE-2SB-37/38 로 개정된다 — 사용자 승인 완료.**
   → 승인 반영 시 `context-keeper` 가 s3-io.md PIPE-S3IO-4 에 "개정됨" 표기를 넣는다.
3. **"오케스트레이션만 담당 — 러너 안에서 로직 재구현 금지"**(pipeline.md 한계) → **충돌 없음**(PIPE-2SB-14/15).
4. **"S3 설정은 pipeline 전용, validation·analysis 는 S3 를 몰라야 한다"** → **충돌 없음**(PIPE-2SB-15).
5. **"재실행=덮어쓰기 한계는 `run_validation` 에 한해 개선"** → Bedrock 단계도 같은 마커 방식 계승
   (PIPE-2SB-17/18/19). `run_analysis`·`run_aggregate` 의 덮어쓰기 한계는 **이번에도 그대로**(범위 밖).
6. **"S3 통합 테스트는 비결정적"** → Bedrock 단계는 **실 LLM 호출 비용과 비결정성**이 더해진다. 그래서
   `BEDROCK_DRY_RUN`(BRK-LLM-16)과 `BEDROCK_SHADOW`(BRK-LLM-48)를 계약에 넣었다. 로컬 격리는 미도입.

## 알려진 한계 (이 기능 자체)
- **멱등 skip 이 O(N) HeadObject 다.** 러너는 매 실행마다 날짜 prefix 전체를 리스팅하고 게시글마다 마커를
  확인한다. 하룻밤에 1000건 단위로 여러 번 돌면 이미 처리된 건을 매번 다시 훑는다(누적 호출 제곱 증가).
  **작업 집합(Redis)이 이걸 줄일 수 있는 자리지만, 이번 계약은 리스팅을 대체하지 않는다** — Redis 는 트리거용이고
  대상 선정은 여전히 S3 기준이다(PIPE-2SB-73 의 "정본은 S3" 원칙과 맞바꾼 비용). 최적화는 `perf-optimizer` 후속.
- **크롤 워커의 `SADD` 실패는 "그날 정제되지 않음"으로 이어진다**(PIPE-2SB-76). S3 에는 남아 있으므로 유실은
  아니지만, Set 에 없으면 트리거가 열리지 않고 `BATCH_DATE` 가 바뀌면 다음 배치 대상 밖이다. 컨트롤러가 배치 종료
  전에 S3 리스팅으로 누락을 확인하는 안전망은 **미도입**(그 리스팅이 `O(N)` 이라 Set 을 둔 이유와 상충한다).
- **작업 집합은 락이 아니다.** 항목을 원자적으로 점유하는 계약이 없으므로 같은 단계 Job 이 둘 이상 뜨면 같은
  게시글을 중복 처리한다(Bedrock 에선 중복 과금). 방어선은 PIPE-2SB-30 하나뿐이다.
- **Redis 유실은 지연이지 데이터 유실이 아니다**(PIPE-2SB-73). 다만 작업 집합이 사라지면 트리거가 멈춰
  **그 배치는 사실상 진행되지 않는다**(복구 절차는 S3 리스팅 기반 수동 재실행). 자동 재구축은 미도입.
- **패턴 success 는 "정책이 바뀌지 않는 한" 불변이다.** 패턴 마커가 찍힌 게시글은 재처리되지 않으므로 Bedrock 이
  본 내용이 나중에 바뀌지 않는다. ⚠️ **정책 변경 시에는 이 불변성이 의도적으로 깨진다**(PIPE-S3IO-36~38):
  마커를 지우고 다시 도는데, 그때 **Bedrock 마커도 함께 지워야** 새 패턴 결과가 옛 Bedrock 판정과 짝지어지지
  않는다. 즉 **정책 변경은 두 단계를 함께 되감는 작업**이다.
- **`BATCH_DATE` 하루 고정(PIPE-2SB-37)이 백필의 제약이 된다.** 정규 배치에는 필수인 계약(단계 간 날짜 분열
  방지)이 **여러 날짜를 순회해야 하는 백필에는 걸림돌**이다. 백필 문서가 이 확장을 별도로 풀어야 하며,
  정규 배치의 `-37` 을 느슨하게 고치는 방식으로 풀면 안 된다 — 그 순간 정규 배치의 조용한 유실 위험이 돌아온다.
- **⚠️ `body:""` success 는 Bedrock 산출물에는 여전히 생긴다**(축 B 본문 폐기 경로, PIPE-2SB-11c 갈래 3).
  패턴 단계는 v2 로 이 형태를 없앴는데 Bedrock 단계는 만든다 — **두 단계의 success 스키마가 같은 형태에 다른
  의미를 갖는다.** 하류 소비자(`analysis`)가 `validation/bedrock/success/` 에서 `body:""` 를 만나면 그건
  **"원래 본문이 없던 글"이 아니라 "LLM 이 축 B 로 본문을 폐기한 글"** 이다. 구분하려면 같은 게시글의
  `validation/bedrock/failed/` 객체에서 `axis` 를 봐야 한다.
- **본문도 제목도 없는 success 객체가 생길 수 있다**(PIPE-2SB-10c). 빈 본문 글의 `title` 이 축 B 로 폐기되고
  통과 댓글이 있으면 `body:""` + `title:""` + 댓글만 남는다. 하류(`analysis`)는 **게시글 본문 없이 댓글만 있는
  객체**를 받게 되므로, 본문 텍스트가 있다고 가정하고 파싱하면 깨진다. 통과 댓글이 0이면 객체 자체가 생기지
  않는다(PIPE-2SB-11).
- **같은 게시글이 success 와 failed 양쪽에 존재한다**(축 B 본문 폐기 경로). v1 패턴 단계에서도 그랬으므로
  (구 PIPE-S3IO-17) 새로운 성질은 아니지만, **패턴 단계에서 그 형태를 없앤 지금은 Bedrock 단계에만 남은 성질**이다.
  두 키를 함께 읽지 않으면 게시글의 최종 상태를 알 수 없다.
- **Bedrock 단계의 입력 성격이 바뀐다(s3-io v2).** 이제 `validation/pattern/success/` 에는 **본문(또는 title)이
  통과한 게시글만** 들어온다. 그만큼 입력이 줄어 비용도 줄지만, **v1 규칙으로 쌓인 과거 산출물에는 `body:""` 인
  success 가 섞여 있다** — 그 날짜를 다시 도는 경우 PIPE-2SB-8 이 빈 본문을 만나게 되므로, 러너는 그때도
  크래시하지 않고 title 대체 규칙으로 흘러야 한다.
- **컨트롤러가 단일 장애점**이다. 회수되면 그 동안 트리거가 멈춘다(작업 집합은 Redis 에 남아 있으므로 재기동 후
  이어받는다 — **구조 변경으로 "비정상 종료가 다음 배치를 막는" 실패 모드는 사라졌다**. Redis 가 배치와 함께
  사라지기 때문이다). 컨트롤러 재기동 보장은 인프라 소관.
- **배치가 주간까지 늘어질 수 있다 — 사용자가 알고 선택한 트레이드오프.** 06:00 은 크롤만 끊고 정제는 소진까지
  계속된다(PIPE-2SB-35b). 그동안 Spot batch 노드가 살아 있어 노드 비용이 예측을 벗어날 수 있다. 방어선은
  금액 상한과 컨트롤러 게이트(PIPE-2SB-60~63·68)뿐이며 시간 상한은 두지 않았다.
  **`batch-redis` 가 `emptyDir` 이므로 다음 배치가 겹쳐 뜨면 서로 다른 Redis 를 보게 된다** — 겹침 자체를 막는 것은
  CronJob `concurrencyPolicy: Forbid` 다(인프라 소관).
- **비용 상한은 소프트 상한이다**(PIPE-2SB-74/75). 게시글 경계에서만 확인하고 배치 1회 = 호출 1회라 중간에 끊을
  수 없어, 상한을 **마지막 배치 1회분만큼 초과**할 수 있다. 배치 크기를 키울수록 초과 폭도 커진다.
- **예산으로 시작하지 못한 게시글은 영구 미판정이다.** 마커 없이 `pending:bedrock` 에 남지만, 배치가 끝나면
  Redis 가 사라지고 `BATCH_DATE` 도 바뀌므로 **다음 배치의 대상 prefix 밖**이다. 그 게시글은 패턴 검열만 거친
  상태로 남는다. 누적 재처리 잡은 미도입.
- **모델 단가는 확인됐다 (2026-07-26).** `list-foundation-model-agreement-offers` 가 약관과 함께 요율표를 반환해
  **입력 $3 / 출력 $15 / 배치입력 $1.5 (per 1M)** 로 확정됐다 — AWS Pricing API 가 SCP(`p-5soyo0ar`)로 막힌 것을
  이 경로로 우회했다. 우려했던 Extended Access($6/$30)가 **아니다.** 설정 기본값(PIPE-2SB-62)과 일치한다.
  다만 `bedrock_spend_usd` 는 여전히 **추정 소비액이지 청구액이 아니다**(토큰 집계 × 단가) — **첫 수일간 AWS Cost
  Explorer 실청구액과 대조**한다. 단가를 코드 상수로 두지 않은 이유는 단가가 바뀔 수 있기 때문이다.
- **⚠️ 현재의 비용 여유는 크롤러의 미구현에 기대고 있다 — 모듈 간 숨은 결합.** 하루 $13 은 dcinside(97%)의
  **댓글이 수집되지 않는다**는 사실에서 나온다(`community.py:263`). **AJAX 댓글 수집이 추가되면 정제 비용이
  $13 → $158 로 약 12배 뛴다** — 정제 코드는 한 줄도 안 바뀌었는데 상한($30)을 5배 초과한다. 크롤러 변경 시
  이 문서의 비용 전제를 **반드시 재계산**해야 한다.
- **모델·프롬프트 교체 후에도 기존 Bedrock 마커는 유지된다**(사용자 확정) → 같은 날짜 prefix 안에 서로 다른
  기준의 판정이 섞인다. 측정 시 `modelId`·`promptVersion`(PIPE-2SB-13)으로 구간을 갈라야 한다.
- **`batch-redis` 는 `maxmemory 256mb` / `noeviction`** 이다. 넘치면 쓰기가 에러로 터진다(조용한 유실보다는
  낫다). Set 에 **게시글 키만** 담기로 한 결정(PIPE-2SB-26) 덕에 하루 7,000키 규모에선 여유롭다.

## 확정된 결정 (구 미해결 질문 — 해소분)
1. **Bedrock 트리거** = 전용 작업 집합 `pending:bedrock` 1000건 게이트. → PIPE-2SB-47~50.
2. **06:00 KST 는 크롤 종료 시각**이지 배치 종료 시각이 아니다. → PIPE-2SB-35/35b/35c.
3. **`BATCH_DATE` 주입으로 PIPE-S3IO-4 개정** = 승인됨. → PIPE-2SB-37/38.
   ⚠️ 승인 반영 시 `context-keeper` 가 s3-io.md 의 **PIPE-S3IO-4**(개정됨)·**PIPE-S3IO-28**(대체됨)에 표기를 넣는다.
4. **검열 범위** = **게시글 단위 순차 처리**(본문/댓글 구분 없음). → PIPE-2SB-8. *(구 결정 "본문 전건 우선"은 폐기 —
   PIPE-2SB-51~53b·58 폐기.)*
5. **비용 상한** = **$30/밤, 금액 기준**. → PIPE-2SB-60~63·68.
6. **모델** = 본문·댓글 **단일 모델**(단위 종류로 분기하지 않는다). → BRK-LLM-5/6.
   ⚠️ **모델 식별자의 정본은 `llm-validation.md` 이고 v2 가 승인됐다(2026-07-26)**: v1 의 `apac.*` Sonnet 4 가
   **조직 SCP 로 호출 불가**로 드러나(실측 — 도쿄 리소스 ARN 으로 explicit deny) 베어 모델 ID
   `anthropic.claude-3-5-sonnet-20240620-v1:0`(`ap-northeast-2` 고정)으로 교체됐다. **이 문서의 계약은 모델 식별자에
   의존하지 않는다** — 단가는 설정값(PIPE-2SB-62), 상한은 금액 기준(PIPE-2SB-60)이라 모델이 바뀌어도 문언이 그대로다.
   다만 **캐싱은 모델에 의존했다** — 교체 모델이 캐싱 미지원이라 PIPE-2SB-64/65 가 조건부로 개정되고 PIPE-2SB-77 이 생겼다.
7. **데이터 범위** = **서울(`ap-northeast-2`) 이내**(추론 프로파일 미사용). → BRK-LLM-6b/6c/6d.
   *(v1 의 "APAC 이내 유지"는 `llm-validation.md` v2 에서 서울 고정으로 좁혀졌다 — 선택이 아니라 SCP 강제.)*
8. **예산 소진 시** = **게시글을 시작하지 않는다**(게시글 경계 확인). 다음 배치로 이월하지 않는다. → PIPE-2SB-74.
   *(구 결정 "`body_only` 완결 처리"는 폐기 — PIPE-2SB-52 폐기.)*
9. **역할 분리** = **S3 = 결과의 정본 / Redis = 작업 집합(최적화)**. 중간물(`_staging`) 방식은 폐기. →
   PIPE-2SB-26/72/72b/73. *(구 결정 "중간물을 산출물 키스페이스 밖에 둔다"는 폐기 — PIPE-2SB-66/67 폐기.)*
10. **배칭** = **한 게시글의 단위들을 묶은 이종 혼합 배치**, 항목마다 `unit_kind` 동반, 크기는 "게시글 N개분"
    설정값. → PIPE-2SB-14/71, BRK-LLM-1. *(구 결정 "동종 배칭"은 폐기.)*
11. **예산 상한의 사정거리** = **Bedrock 단계만** 멈춘다. 크롤·패턴은 LLM 비용이 0이라 끝까지 돈다. → PIPE-2SB-35b/35c/68.
12. **(폐기됨)** ~~카운터 초기화의 정본 = 배치 시작 시~~ — `batch-redis` 가 `emptyDir` 이라 초기화 대상이 없다.
    → PIPE-2SB-26, PIPE-2SB-36b(폐기됨).
13. **완결 기록 순서** = **`S3 마커 → SREM`**(패턴 러너는 `마커 → SADD(다음) → SREM(현재)`). → PIPE-2SB-72/72b.
    *(구 결정 "`success/failed → 마커 → _staging 삭제`"는 폐기 — 순서 논증만 계승.)*
14. **패턴 단계 규칙 v2**(`s3-io.md` 개정) = **본문(또는 title) 폐기 → 게시글 전체 fail** + **빈 본문이면 title 판정**.
    파급: PIPE-2SB-8(Bedrock 도 title 판정)·8b(`unit_kind="body"`)·9(폐기)·13(`unit` 에 `"title"`).
15. **배치 경계** = 배치는 **게시글 N개분**이며 게시글을 자르지 않는다. 예산 확인·상한 초과 폭이 **모두 배치 경계**
    기준으로 정렬됐다. → PIPE-2SB-14/71/74/75.
16. **Bedrock 단계의 본문 폐기 라우팅** = **축 A(`abuse`)만 게시글 전체 fail**, 축 B(`spam`·`offtopic`)는
    **본문 자리 단위만 폐기**하고(그 필드를 비운다 — `body` 또는 `title`) 통과 댓글로 success 를 만든다.
    → PIPE-2SB-10b/10c/11b/11c.
    근거: 패턴은 오탐이 거의 없어 전체 fail 이 안전했지만 **LLM 의 축 B 는 경계가 모호해 오탐 1건이
    게시글 전체를 날린다.** 근거가 명확한 축만 대칭을 맞춘다.

17. **정책 변경 시 기존 산출물** = **폐기 후 새 정책으로 재적용**(원칙). 절차는 `_manifest` 삭제 → 재실행,
    **패턴을 되감으면 Bedrock 마커도 함께**. → PIPE-S3IO-36/37/38.
18. **백필 분리** = 누적 크롤 데이터 백필은 이 문서 범위 밖(`backfill.md`), **전용 상한을 별도로 둔다.**

## 미해결 질문
- **없음.** 요구사항 승인에 필요한 결정은 전건 해소됐다.

## 승인 후 확정 항목 (구현 시 결정 — 계약이 아니라 값)
> 아래는 **요구사항의 공백이 아니라 `(가정)` 표기된 값의 확정**이다. 계약의 의미를 바꾸지 않으므로 승인을
> 막지 않으며, 구현 담당(`pipeline-dev`/인프라)이 정하고 `api-documenter`·`context-keeper` 가 사실로 기록한다.

1. **`BATCH_DATE` 주입 방식**(PIPE-2SB-37) — 컨트롤러가 Job 스펙에 넣을지, CronJob 이 한 번 계산해 ConfigMap 에 둘지.
2. **env·Redis 키 이름** — `BATCH_DATE`, `pending:pattern`·`pending:bedrock`·`bedrock_spend_usd`(PIPE-2SB-26),
   단가 설정 키(PIPE-2SB-62), 배치 크기 설정 키(PIPE-2SB-71).
3. **재시도 횟수**(PIPE-2SB-21·76, 현재 3회 가정)와 **Spot 회수 시 즉시 재스케줄 여부**.
4. **작업 집합에 담을 키의 형태**(PIPE-2SB-26) — `{source}/{postExternalId}` 조합 vs S3 키 전체.
   `{date}` 는 `BATCH_DATE` 로 고정이라 넣지 않아도 되지만, 넣어두면 디버깅이 쉽다.
5. **배치 크기 기본값**(PIPE-2SB-71) — `accuracy-tuner` 가 판정 품질을 보고 정한다(비용만 보고 키우면 안 된다).

## 승인 후 후속 작업 (문서 밖 — 기재만)
- **`context-keeper`**: `docs/modules/pipeline.md` 에 2단계 흐름 반영 · `docs/modules/bedrock.md` 신규 작성 ·
  `s3-io.md` 의 `PIPE-S3IO-4`(개정됨)·`-28`(대체됨) 표기.
- **코드**: `run_validation.py` v2 반영(s3-io 후속 절 참조) · `pipeline/run_bedrock.py` 신규 · `bedrock/` 모듈 신규.
- **검증**: `test-data`(측정 세트·대표 케이스 확정) → `test-writer` → `module-verifier`.
- **후속 요구사항**: `docs/requirements/pipeline/backfill.md`(위 "후속 문서 — 백필" 절이 입력).
</content>
