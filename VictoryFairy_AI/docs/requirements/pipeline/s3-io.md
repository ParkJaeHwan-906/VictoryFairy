# 검열 러너 S3 입출력 (S3 I/O) 요구사항
> 상태: 승인됨(구현·실버킷 검증 완료) · 모듈: pipeline · 최종 수정: 2026-07-22

## 배경 / 목적
크롤러가 커뮤니티 게시글을 S3에 **게시글별 개별 `.json` 객체**로 적재한다. 검열 러너(`run_validation`)를 로컬 txt 기반에서 **S3 읽기 → 검열 → S3 쓰기**로 바꿔, 크롤러-검열-후속단계를 클라우드에서 이어붙이기 위한 계약이다. 이번 이터레이션은 **패턴(룰/정규식) 검열만** 클라우드로 옮기며, 검열 판정 로직 자체는 기존 `validation_service`를 그대로 재사용한다(정확도는 이 기능의 범위가 아니다).

> 실측 반영: 입력은 단일 JSONL이 아니라 **날짜 prefix 하위 게시글별 `.json` 객체 다수**다. 검열 단위는 게시글 본문과 각 댓글이 **독립**이며, 출력은 게시글별로 **정화된 성공 객체 / 폐기 사유 객체**를 미러링한다.

## 범위
- 포함:
  - `run_validation` 러너의 데이터 소스/싱크를 로컬 txt → **S3 게시글별 `.json`**으로 전환.
  - 입력: prefix `community/{source}/{date}/` 하위 모든 `.json` 객체를 리스팅해 각각 처리(source ∈ {`dcinside`, `fmkorea`}).
  - 검열 단위: 게시글 `body` + `topComments[].body` 각각 **독립 판정**(title 제외).
  - 출력: 게시글별 `.json` 미러링 — 정화된 성공 객체 `validation/pattern/success/...`, 폐기 사유 객체 `validation/pattern/failed/...`.
  - boto3 기본 자격증명 체인(환경변수) 인증, 버킷명 환경변수화, 리전 ap-northeast-2.
  - 경계/에러 동작(입력 부재·부분 소스·불량 객체·재실행 멱등·본문 폐기·빈 본문·S3 접근 실패)의 계약.
  - `pipeline` 흐름·문서에서 `run_analysis`·`run_aggregate` **배선 제거**(파일·코드 유지).
- 제외 (의도적):
  - **SQS 트리거 / 버퍼 컨슈머** — 다음 단계.
  - **로컬 파일 폴백** — 이번 러너는 S3 전용(로컬 txt 경로 미지원).
  - **analysis / aggregate 로직** — 코드·판정 모두 손대지 않음(배선만 제거).
  - **Bedrock(LLM) 검열** — 이번 범위 아님. 단, 출력 경로는 `validation/bedrock/...` 확장을 전제로 설계(경로 확장성만, 구현 안 함).
  - **검열 판정 정확도(오탐/재현율)** — 기존 `validation_service` 재사용, 이 문서에서 목표치를 새로 정하지 않는다(아래 "판정 요구사항" 참조).

## 입력 객체 스키마 (실측 — 필드명 그대로 계약에 사용)
```json
{"schemaVersion":2,"source":"DCINSIDE","postExternalId":"11229559","sourceUrl":"...",
 "title":"...","body":"게시글 본문","engagement":{...},
 "topComments":[{"author":"...","body":"댓글 본문","likeCount":19}, ...],
 "team":"DOOSAN"|null,"crawledAt":"2026-07-21T15:09:21+00:00","crawlerVersion":"community-v3"}
```
- 객체 하나 = 게시글 하나. 확장자 `.json`. 파일명 = `{postExternalId}.json`(자연 ID).
- 검열 대상: `body`, `topComments[].body`. (`title`·기타 메타는 대상 아님.)

## 결정적 계약 (EARS)

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| PIPE-S3IO-1 | 유비쿼터스 | THE 시스템 SHALL 입력·출력 버킷명을 환경변수에서 읽는다 | `S3_BUCKET`(가정) 미설정 시 실행 중단 + 명확한 에러; 입력·출력 **동일 버킷** |
| PIPE-S3IO-2 | 유비쿼터스 | THE 시스템 SHALL 입력 prefix를 `community/{source}/{date}/` 규칙으로 구성한다 | source=`dcinside`, date=`2026-07-22` → prefix `community/dcinside/2026-07-22/` |
| PIPE-S3IO-3 | 이벤트 | WHEN 러너가 실행되면, THE 시스템 SHALL 해당 prefix 하위의 모든 `.json` 객체를 리스팅해 각각 처리한다 | prefix 아래 `11229559.json` 등 N개 → N개 게시글 처리 |
| PIPE-S3IO-4 | 유비쿼터스 | THE 시스템 SHALL `{date}`를 **실행 당일 날짜**(`YYYY-MM-DD`, Asia/Seoul/KST)로 결정한다 | `crawledAt` 15:09Z(=00:09 KST)가 `2026-07-22/`에 들어가는 실측과 일치 |
| PIPE-S3IO-5 | 유비쿼터스 | THE 시스템 SHALL S3 리전을 `ap-northeast-2`로 사용한다(환경변수/기본 체인 경유) | `AWS_REGION`/`AWS_DEFAULT_REGION`=`ap-northeast-2` |
| PIPE-S3IO-5b | 선택 | WHERE `S3_ENDPOINT_URL`이 설정된 경우, THE 시스템 SHALL 그 엔드포인트로 S3에 접근한다(미설정/빈 값이면 기본 AWS 리전 엔드포인트) | `S3_ENDPOINT_URL=http://minio:9000` → 해당 엔드포인트 사용; 미설정 → `s3.ap-northeast-2.amazonaws.com`. VPC 엔드포인트·S3 호환 스토리지 대응 |
| PIPE-S3IO-6 | 이벤트 | WHEN 러너가 실행되면, THE 시스템 SHALL `dcinside`·`fmkorea` 두 소스를 한 번의 실행에서 처리한다 | 한 번 실행 → 두 소스 각각 리스팅/검열/write (가정: 단일 실행 2소스) |
| PIPE-S3IO-7 | 이벤트 | WHEN 각 게시글 객체를 읽으면, THE 시스템 SHALL JSON으로 파싱해 `body`와 `topComments[].body`를 각각 **독립된 검열 단위**로 삼는다 | 본문 1 + 댓글 3 → 검열 4회, 서로 결과 영향 없음 |
| PIPE-S3IO-8 | 이벤트 | WHEN 각 검열 단위를 검열하면, THE 시스템 SHALL 그 텍스트를 `validation_service`에 **분할·변형 없이** 전달한다 | body 1개 → `validation()` 호출 1회, 러너 내 판정 재구현 없음 |
| PIPE-S3IO-9 | 이벤트 | WHEN 게시글 본문과 모든 댓글이 통과하면, THE 시스템 SHALL 원본과 동일한 게시글 객체를 success에 기록한다 | 전건 통과 → success 객체 == 입력 객체(필드 무변형) |
| PIPE-S3IO-10 | 이벤트 | WHEN 통과한 단위(본문 또는 댓글)가 하나라도 있으면, THE 시스템 SHALL **통과한 단위만 남긴 정화 객체**를 success에 기록한다 | 댓글 3 중 1 폐기 → success `topComments` 2개(통과분만), 나머지 원본 필드 보존 |
| PIPE-S3IO-11 | 유비쿼터스 | THE 시스템 SHALL 출력 객체 키를 `validation/pattern/{success\|failed}/{source}/{date}/{postExternalId}.json` 규칙으로 구성한다 | success → `validation/pattern/success/dcinside/2026-07-22/11229559.json` |
| PIPE-S3IO-12 | 유비쿼터스 | THE 시스템 SHALL 출력 경로에 검열 방식 세그먼트(`pattern`)를 두어 향후 `validation/bedrock/...` 확장이 경로 규칙만으로 가능하게 한다 | 경로 상수/템플릿이 방식(`pattern`)을 변수로 가짐 |
| PIPE-S3IO-13 | 유비쿼터스 | THE 시스템 SHALL failed 레코드에 **어느 검열 단위가 폐기됐는지 식별 정보**(본문 여부 / 댓글 index·author), **걸린 원본 텍스트(`text`)**, 사유(`ValidationResponse.message`)를 포함한다 | failed 항목이 `{unit:"body"|"comment", commentIndex, author, text, message}` 형태로 출처 식별 + 실제 걸린 내용 확인 가능. `text`는 판정에 넘긴 원본 본문/댓글 텍스트 그대로 |
| PIPE-S3IO-14 | 유비쿼터스 | THE 시스템 SHALL AWS 자격증명을 boto3 **기본 자격증명 체인(환경변수)**에서 획득한다 | `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`/`AWS_SESSION_TOKEN` 사용, AWS profile 미사용 |
| PIPE-S3IO-15 | 유비쿼터스 | THE 시스템 SHALL 자격증명·버킷명을 코드·문서에 하드코딩하지 않는다 | `.env.example`엔 키 이름/플레이스홀더만, 실제 값 없음 |
| PIPE-S3IO-16 | 유비쿼터스 | THE 시스템 SHALL `boto3`를 `pipeline/requirements.txt`와 pipeline Dockerfile 의존성에 포함한다 | 두 곳에 `boto3` 명시 |

### 검열 단위 폐기 엣지 케이스 (사용자 확정 반영)

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| PIPE-S3IO-17 | 예외 | IF 게시글 **본문(`body`) 자체가 폐기**되면, THEN THE 시스템 SHALL success 정화 객체의 `body`를 빈 문자열(`""`)로 두고 통과 댓글만 `topComments`에 유지하며, **본문 폐기 사유를 failed**에 기록한다 | 본문 폐기 + 댓글 2 통과 → success `{body:"", topComments:[통과 2], ...원본 필드 보존}`, failed에 `unit:"body"` 사유 |
| PIPE-S3IO-18 | 복합 | WHILE 본문은 통과했으나, WHEN 통과 댓글이 0개가 되면, THE 시스템 SHALL 본문만 있고 `topComments`가 빈 배열인 success 객체를 낸다 | 본문 통과+댓글 전건 폐기 → success `topComments:[]`, 폐기 댓글 전부 failed |
| PIPE-S3IO-19 | 예외 | IF **통과한 단위가 하나도 없으면**(본문 폐기 + 통과 댓글 0), THEN THE 시스템 SHALL success 객체를 생성하지 않고 failed만 기록한다 | 전건 폐기 → success 미생성, failed에 본문·댓글 사유 전부 |
| PIPE-S3IO-20 | 이벤트 | WHEN 본문·댓글이 모두 통과하면, THE 시스템 SHALL failed 객체를 생성하지 않는다 | 전건 통과 → failed 키 미생성(success만) |
| PIPE-S3IO-20b | 예외 | IF `body`가 빈 문자열/공백이면, THEN THE 시스템 SHALL 이를 **폐기로 간주**해 사유와 함께 failed에 기록한다 | 빈 본문 → failed `unit:"body"` 사유 `빈 본문`; 통과 댓글이 있으면 PIPE-S3IO-17 규칙대로 success `body:""` 별도 생성 |
| PIPE-S3IO-20c | 예외 | IF `topComments`가 빈 배열이면, THEN THE 시스템 SHALL 댓글 검열 단위 없이 본문 판정만으로 처리한다 | 댓글 없는 게시글 → 본문만 검열, 크래시 없음 |

### 경계 / 에러 동작 (EARS unwanted behaviour)

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| PIPE-S3IO-21 | 예외 | IF 해당 소스의 당일 입력 prefix에 객체가 하나도 없으면, THEN THE 시스템 SHALL 그 소스를 건너뛰고(로그 남김) 실행을 크래시 없이 계속한다 | fmkorea에 07-22 없음(dcinside만 존재) → fmkorea skip, 러너 정상 종료 |
| PIPE-S3IO-22 | 예외 | IF 두 소스 중 하나만 존재하면, THEN THE 시스템 SHALL 존재하는 소스만 정상 처리한다 | dcinside만 존재 → dcinside 산출물만 생성 |
| PIPE-S3IO-23 | 예외 | IF 입력 객체가 JSON 파싱 불가하거나 필수 필드(`postExternalId` 등)가 없으면, THEN THE 시스템 SHALL 그 객체를 건너뛰고(집계 로그) 나머지 객체 처리를 계속한다 | 불량 1객체 + 정상 9객체 → 9건 처리, 불량 1건 로그 |
| PIPE-S3IO-24 | 예외 | IF 어떤 게시글이 **이미 완결 처리됨**(그 게시글의 success/failed 산출이 모두 확정됨)으로 판정되면, THEN THE 시스템 SHALL 그 게시글을 재처리하지 않고 건너뛴다(멱등 skip) | 재실행 → 완결 게시글 skip, 미완결/신규 게시글만 처리 |
| PIPE-S3IO-25 | 유비쿼터스 | THE 시스템 SHALL 한 게시글의 success·failed 산출을 **원자적으로** 확정한다 — 부분 산출물이 남아서는 안 되며, 미완결 게시글은 재실행 시 재처리된다 | success만 쓰고 failed 쓰기 전 중단 → 그 게시글은 "미완결"로 판정되어 재실행 시 다시 처리(부분 상태 고착 금지). 원자성 구현(임시 키 후 copy / 마지막 일괄 put)은 pipeline-dev 소관 |
| PIPE-S3IO-26 | 예외 | IF S3 접근이 실패하거나(권한·네트워크) 자격증명이 만료되면, THEN THE 시스템 SHALL 명확한 에러로 중단하고 0이 아닌 종료 코드를 반환한다 | ExpiredToken/AccessDenied → 조용한 실패 금지 |
| PIPE-S3IO-27 | 이벤트 | WHEN 입력 객체 수가 리스팅 1페이지를 초과하면, THE 시스템 SHALL 페이지네이션으로 전체 객체를 처리한다 | ListObjectsV2 continuation token으로 1000+ 객체 전건 리스팅 |

### 파이프라인 배선 정리

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| PIPE-S3IO-28 | 유비쿼터스 | THE 시스템 SHALL 이번 이터레이션 파이프라인 흐름을 `run_validation` 단독으로 구성한다 | pipeline Dockerfile CMD/문서 흐름에서 `run_analysis`·`run_aggregate` 배선 제거 |
| PIPE-S3IO-29 | 유비쿼터스 | THE 시스템 SHALL `run_analysis.py`·`run_aggregate.py` 파일과 analysis 모듈 코드를 보존한다 | 파일 삭제·수정 없음; 문서에서 "우선/임시" 표현만 제거 |

### 테스트 (통합 — dev 버킷 실입출력)

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| PIPE-S3IO-30 | 선택 | WHERE 통합 테스트가 실행되는 경우, THE 시스템(테스트) SHALL `victoryfairy-crawl-dev` 버킷의 **전용 테스트 키 네임스페이스**에만 쓰고 읽는다 | 테스트용 접두(예: `_test_`/테스트 전용 날짜) 키만 사용, 실크롤 입력(`community/`)·실운영 출력 키 미접촉 |
| PIPE-S3IO-31 | 이벤트 | WHEN 통합 테스트가 종료되면, THE 시스템(테스트) SHALL 자신이 쓴 테스트 객체를 삭제해 버킷을 정리한다 | 테스트 종료 후 테스트 네임스페이스에 잔여 객체 0 |

## 판정 요구사항 (케이스 기반)
> **이 기능에는 새로운 판정 요구사항(재현율/오탐)이 없다 — 의도된 것이다.** 검열 판정은 기존 `validation_service`를 **그대로 재사용**하며, 이 기능은 데이터 소스/싱크 배선과 검열 단위 분해만 바꾼다. 따라서 "무엇을 잡고 무엇을 안 잡을지"의 목표치는 이 문서의 대상이 아니다(오탐 요구사항 0건인 이유). 판정 정확도 자체는 별도 `docs/requirements/validation/*`와 `accuracy-tuner`의 소관이다.
>
> 단, **통과/폐기 라우팅과 정화**는 판정이 아니라 결정적 계약이므로 PIPE-S3IO-9/10/13/17/18/19로 못박았다: 통과한 단위는 정화 success에 유지, 폐기된 단위는 사유와 함께 failed로. 검열 결과를 이 기능이 재해석하지 않는다.

## 이미 기각된 것 (모듈 문서 "한계" 대조)
- **단계 간 결합이 파일** (pipeline.md 한계): 결합 매체를 파일→S3로 바꾸는 것이지 러너가 판정 로직을 갖는 게 아니다. PIPE-S3IO-8은 여전히 `validation_service`에 위임 — "러너 안에서 로직 재구현 금지" 제약을 지킨다. **충돌 없음.**
- **재실행이 곧 덮어쓰기 / 버전 없음** (pipeline.md 한계): PIPE-S3IO-24·25는 이 한계를 **원자적 멱등 skip으로 개선**한다. 완결 게시글은 재처리하지 않고, 미완결(부분 산출)은 재처리하므로 재실행이 이전 결과를 파괴하거나 부분 상태를 고착시키지 않는다. 초안의 "덮어쓰기" 가정은 폐기됨. **개선(충돌 없음).**
- **입력 원본 복구 불가** (pipeline.md 한계): 입력 prefix(`community/...`)는 **읽기 전용**으로만 접근하고, 러너는 입력 키에 절대 쓰지 않는다(출력은 `validation/` prefix 전용, 새로 생성). **충돌 없음.**

## 알려진 한계 (이 기능 자체)
- **통합 테스트가 비결정적**: 테스트가 dev 버킷 실입출력이므로(PIPE-S3IO-30/31) 자격증명·네트워크에 의존하고, **임시 STS 만료 시 실패**할 수 있다. 로컬 격리(moto/페이크)는 이번 결정에서 제외됨.

## 확정된 결정 (구 미해결 질문 — 전건 해소)
1. **failed 사유 구조** = `{unit, commentIndex, author, text, message}` (unit ∈ {"body","comment"}). `text`는 걸린 원본 본문/댓글 텍스트 — postExternalId만으론 무엇이 필터링됐는지 알 수 없다는 사용자 피드백으로 추가. → PIPE-S3IO-13.
2. **본문 폐기 시** = 통과 단위가 하나라도 있으면 success 정화 객체를 낸다(`body:""` + 통과 댓글만 유지, 원본 필드 보존). 통과 단위 0이면 success 미생성. → PIPE-S3IO-10/17/19. (초안 "본문 폐기 시 success 미생성"은 교체됨.)
3. **빈 본문(`body:""`)** = 폐기로 간주해 failed 보존(통과 아님). → PIPE-S3IO-20b. (초안 "빈 본문=통과"는 뒤집힘.)
4. **재실행/원자성** = 게시글 단위 원자적 write, 미완결은 재처리, 부분 산출물 금지. → PIPE-S3IO-24/25.
5. **테스트** = dev 버킷 실입출력 통합 테스트, 전용 테스트 네임스페이스에만 쓰고 종료 시 정리. → PIPE-S3IO-30/31.
6. **버킷 env 키** = `S3_BUCKET`(입력·출력 동일 버킷). → PIPE-S3IO-1.

## 미해결 질문
- 없음.
</content>
