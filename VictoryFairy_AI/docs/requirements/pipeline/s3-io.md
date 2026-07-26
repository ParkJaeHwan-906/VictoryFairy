# 검열 러너 S3 입출력 (S3 I/O) 요구사항
> 상태: **v1 승인됨(구현·실버킷 검증 완료) / v2 승인됨 (2026-07-25)** · 모듈: pipeline · 최종 수정: 2026-07-25

> ## ⚠️ v2 개정 (2026-07-25) — 승인됨, **코드 미반영**
> 사용자가 **패턴 단계의 산출물 규칙**을 바꿨다. 아래 두 가지이며, 해당 ID 는 `(개정됨)`/`(폐기됨)` 으로 표기했다.
> 1. **본문(또는 title)이 폐기되면 게시글 전체를 fail 로 보낸다** — 댓글 통과 여부와 무관. → PIPE-S3IO-10·17·19
> 2. **`body` 가 비면 `title` 을 본문 자리의 판정 단위로 쓴다** — 신규. → PIPE-S3IO-32~35
>
> ### ⚠️ 승인했다고 코드가 바뀐 것이 아니다 — **현재 동작 = v1 코드, 계약 = v2**
> `pipeline/run_validation.py` 의 `process_post` 는 **폐기된 v1 규칙을 그대로 구현하고 있다**
> (특히 `success_obj["body"] = post.get("body") if body_ok else ""`). v2 승인으로 이 코드 수정은
> **확정된 후속 작업**이 됐다. 코드가 바뀌기 전까지 실행 결과는 v2 계약과 다르며, 이 문서를 근거로
> 테스트를 쓰면 **현재 코드에서는 실패하는 것이 정상**이다.
>
> 필요한 코드 변경: (a) 본문(또는 title) 폐기 시 success 미생성, (b) 빈 `body` 일 때 `title` 판정,
> (c) failed `unit` 에 `"title"` 기록.
>
> 그 외 v1 항목(키 규약·멱등 마커·에러 동작·테스트)은 전부 그대로 유효하다.

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
| PIPE-S3IO-10 | 이벤트 **(v2 개정됨)** | WHEN **본문 판정 단위(`body`, 비어 있으면 `title`)가 통과했으면**, THE 시스템 SHALL **통과한 단위만 남긴 정화 객체**를 success에 기록한다 | 댓글 3 중 1 폐기 → success `topComments` 2개(통과분만), 나머지 원본 필드 보존. **v1 의 "통과한 단위가 하나라도 있으면"에서 조건이 좁아졌다** — 본문이 폐기된 게시글은 이제 success 를 만들지 않는다(PIPE-S3IO-19). success 객체 형태는 v1 그대로 = **본문 + 통과 댓글로 이루어진 하나의 원문**(사용자 확인) |
| PIPE-S3IO-11 | 유비쿼터스 | THE 시스템 SHALL 출력 객체 키를 `validation/pattern/{success\|failed}/{source}/{date}/{postExternalId}.json` 규칙으로 구성한다 | success → `validation/pattern/success/dcinside/2026-07-22/11229559.json` |
| PIPE-S3IO-12 | 유비쿼터스 | THE 시스템 SHALL 출력 경로에 검열 방식 세그먼트(`pattern`)를 두어 향후 `validation/bedrock/...` 확장이 경로 규칙만으로 가능하게 한다 | 경로 상수/템플릿이 방식(`pattern`)을 변수로 가짐 |
| PIPE-S3IO-13 | 유비쿼터스 **(v2 개정됨)** | THE 시스템 SHALL failed 레코드에 **어느 검열 단위가 폐기됐는지 식별 정보**, **걸린 원본 텍스트(`text`)**, 사유(`ValidationResponse.message`)를 포함한다 | failed 항목이 `{unit:"body"\|"title"\|"comment", commentIndex, author, text, message}` 형태. **v2 에서 `unit` 에 `"title"` 이 추가**됐다(PIPE-S3IO-34) — 기존 값·필드 구조는 그대로이므로 소비자는 새 값만 인지하면 된다. `text`는 판정에 넘긴 원본 텍스트 그대로 |
| PIPE-S3IO-14 | 유비쿼터스 | THE 시스템 SHALL AWS 자격증명을 boto3 **기본 자격증명 체인(환경변수)**에서 획득한다 | `AWS_ACCESS_KEY_ID`/`AWS_SECRET_ACCESS_KEY`/`AWS_SESSION_TOKEN` 사용, AWS profile 미사용 |
| PIPE-S3IO-15 | 유비쿼터스 | THE 시스템 SHALL 자격증명·버킷명을 코드·문서에 하드코딩하지 않는다 | `.env.example`엔 키 이름/플레이스홀더만, 실제 값 없음 |
| PIPE-S3IO-16 | 유비쿼터스 | THE 시스템 SHALL `boto3`를 `pipeline/requirements.txt`와 pipeline Dockerfile 의존성에 포함한다 | 두 곳에 `boto3` 명시 |

### 검열 단위 폐기 엣지 케이스 (사용자 확정 반영)

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| PIPE-S3IO-17 | **(v2 폐기됨)** | ~~본문이 폐기되면 success 정화 객체의 `body`를 빈 문자열로 두고 통과 댓글만 유지~~ — **사용자 결정으로 폐기.** 본문이 걸린 게시글은 통째로 fail 이므로 **`body:""` 인 success 객체는 더 이상 생기지 않는다** | 번호 재사용 금지. 대체: PIPE-S3IO-19. ⚠️ **현재 코드(`run_validation.py` `process_post`)가 구현하고 있는 것이 바로 이 폐기된 규칙**이다 |
| PIPE-S3IO-18 | 복합 | WHILE 본문은 통과했으나, WHEN 통과 댓글이 0개가 되면, THE 시스템 SHALL 본문만 있고 `topComments`가 빈 배열인 success 객체를 낸다 | 본문 통과+댓글 전건 폐기 → success `topComments:[]`, 폐기 댓글 전부 failed. **v2 에서도 그대로 유효** — 본문이 통과했으므로 전체 fail 조건(PIPE-S3IO-19)에 걸리지 않는다 |
| PIPE-S3IO-19 | 예외 **(v2 개정됨)** | IF **본문 판정 단위(`body`, 비어 있으면 `title`)가 폐기되면**, THEN THE 시스템 SHALL 그 게시글의 success 객체를 생성하지 않고 failed만 기록한다 — **통과 댓글이 있어도 마찬가지다** | 본문 폐기 + 댓글 2 통과 → **success 미생성**, failed 에 본문 사유 기록. 사용자 원문: "패턴 기반으로 본문이 걸린다면 해당 데이터를 모두 fail 로 보내고". **v1 은 "통과 단위 0일 때만" 이었다** |
| PIPE-S3IO-19b | 이벤트 **(v2 신규)** | WHEN 본문 판정 단위가 폐기되면, THE 시스템 SHALL **댓글 판정 결과를 failed 에 함께 기록할지 여부와 무관하게** 그 게시글을 fail 로 확정한다 | 본문 폐기가 확정되면 댓글을 더 판정하지 않아도 된다(판정 비용 절감). 다만 이미 판정한 댓글 사유를 failed 에 남기는 것은 허용한다 — **어느 쪽이든 게시글의 최종 상태는 fail 로 같다** |
| PIPE-S3IO-20 | 이벤트 | WHEN 본문·댓글이 모두 통과하면, THE 시스템 SHALL failed 객체를 생성하지 않는다 | 전건 통과 → failed 키 미생성(success만) |
| PIPE-S3IO-20b | **(v2 개정됨)** | ~~`body`가 비면 즉시 폐기로 간주~~ → **`title` 을 본문 자리의 판정 단위로 삼는다**(PIPE-S3IO-32). 폐기 여부는 그 title 의 판정 결과로 정해진다 | v1 은 빈 본문을 무조건 폐기했다. v2 는 **제목에 실질 내용이 있는 글을 살린다** |
| PIPE-S3IO-20c | 예외 | IF `topComments`가 빈 배열이면, THEN THE 시스템 SHALL 댓글 검열 단위 없이 본문 판정만으로 처리한다 | 댓글 없는 게시글 → 본문만 검열, 크래시 없음 |

### v2 신규 — 빈 본문의 `title` 대체 판정

> **실측 근거**: 표본 **6,075 게시글 중 빈 본문 831건(13.7%)**. 그 글들의 **제목에는 실질 내용이 있다**:
> `title: "소신)그래도 오승환처럼 도박은 안했잖아" / body: "" / 댓글 3`,
> `title: "단독기사))키움 아시아쿼터 교체중비중" / body: "" / 댓글 0`.
> 그리고 **`title` 은 지금 어느 검열 단계에서도 판정 대상이 아니다** — `run_validation` 은 `body` 와
> `topComments[].body` 만 본다. **제목에 담긴 인물 비하·욕설은 검열된 적이 없다.**

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| PIPE-S3IO-39 | 유비쿼터스 **(신규 2026-07-26)** | THE 시스템 SHALL 크롤러가 자동으로 붙인 서명(예: `- dc official App`)을 **판정 대상 텍스트에서 제거**하고, 서명만 남는 본문은 **비어 있는 것으로 취급**한다. 이 판단은 패턴 러너와 Bedrock 러너가 **같은 구현을 공유**해야 한다 | 서명은 글쓴이가 쓴 내용이 아니라 크롤링 잔여물인데 `str.strip()` 이 이를 비었다고 보지 않아, **PIPE-S3IO-32 의 title 대체가 발동하지 않았다.** 실측(dcinside 2026-07-22, 337건): 본문이 서명뿐인 글 **15건(4.5%)**, Bedrock 폐기 60건 중 **8건(13%)** 이 서명을 판정한 결과. 특히 **`spam` 폐기 3건이 전부 이것이라 실제 spam 표본이 0건**이 되어 축 B 폐기율 상한을 확정하지 못했다(BRK-LLM-44). 같은 서명이 `spam`·`offtopic` 양쪽으로 갈려 **축 판정도 흔들렸다**. ⚠️ **저장되는 본문은 바꾸지 않는다** — 판정 대상을 정하는 데만 쓰고 success 객체에는 원문을 남긴다(크롤 원본 충실성). ⚠️ **두 러너가 각자 구현하면 안 된다** — 어긋나면 1차가 통과시킨 것과 2차가 보는 것이 달라진다. 구현: `pipeline/text_normalize.py`. ⚠️ fmkorea 서명은 **실표본 0건이라 확인된 바 없다** — 데이터가 들어오면 마지막 줄 빈발 패턴으로 확인할 것(dcinside 서명을 그렇게 찾았다) |
| PIPE-S3IO-32 | 예외 **(PIPE-S3IO-39 로 보강됨)** | IF `body`가 빈 문자열/공백**이거나 크롤러 서명만 남으면**, THEN THE 시스템 SHALL `title`을 **본문 자리의 판정 단위**로 삼아 검열한다 | `body:""` + `title:"소신)그래도 오승환처럼 도박은 안했잖아"` → title 을 `validation_service` 에 그대로 전달(분할·변형 없음, PIPE-S3IO-8 계승) |
| PIPE-S3IO-33 | 예외 | IF `body`와 `title`이 **둘 다** 비어 있으면, THEN THE 시스템 SHALL 그 게시글을 fail 로 확정한다 | 판정할 본문 자리 텍스트가 없음 → success 미생성, failed 에 사유 `빈 본문·빈 제목` |
| PIPE-S3IO-34 | 유비쿼터스 | THE 시스템 SHALL title 을 판정했을 때 failed 레코드의 `unit` 을 **`"title"`** 로 기록한다 | `{unit:"title", commentIndex:null, author:null, text:<제목 원문>, message:...}`. **기존 `unit` 필드 규약을 깨지 않고 값만 추가**한다(PIPE-S3IO-13) — 사후에 "본문이 아니라 제목이 걸렸다"를 구분할 수 있어야 한다 |
| PIPE-S3IO-35 | 유비쿼터스 | THE 시스템 SHALL `body`가 비어 있지 **않은** 게시글의 `title` 은 판정하지 않는다 | 사용자가 "빈 본문일 때"로 한정했다. 본문이 있는 글의 제목은 **여전히 검열되지 않는다**(아래 한계) |

### 정책 변경 시 기존 산출물 처리 (v2 신규 — 일회성이 아니라 **원칙**)

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| PIPE-S3IO-36 | 유비쿼터스 | THE 시스템 SHALL 검열 정책(판정 대상·라우팅 규칙)이 바뀌면 **그 시점 이전의 산출물을 폐기하고 새 정책으로 재적용**한다 | **사용자 확정 원칙**: "정책이 바뀌면 기존 구조를 폐기하고 새 구조를 적용한다." 구 산출물은 **구 규칙의 결과물**이라 새 규칙의 결과와 섞이면 하류·지표가 어긋난다. **v3·v4 가 와도 같은 절차다** — 이번 v2 에 한정된 처리가 아니다 |
| PIPE-S3IO-37 | 유비쿼터스 | THE 시스템 SHALL 재적용 절차를 **`_manifest` 마커 삭제 → 러너 재실행** 으로 규정하고, 마커 삭제는 **러너가 아니라 운영자가 수행**한다 | 마커가 남아 있으면 멱등 skip(PIPE-S3IO-24 / PIPE-2SB-17)이 재처리를 막는다. **러너가 스스로 마커를 지우게 하면 멱등성이 무의미해지므로** 이는 러너의 책임이 아니라 **운영 절차**다. 삭제 범위(날짜·소스·단계)는 바뀐 정책이 미치는 범위와 같아야 한다 |
| PIPE-S3IO-38 | 이벤트 | WHEN 정책 변경으로 패턴 단계를 재실행하면, THE 시스템 SHALL **Bedrock 단계의 마커도 함께 폐기 대상으로 본다** | 패턴 산출물이 바뀌면 그것을 입력으로 삼은 Bedrock 판정도 낡은 것이 된다. 패턴만 지우고 Bedrock 마커를 남기면 **새 패턴 결과가 옛 Bedrock 판정과 짝지어져** 어느 쪽 규칙의 산출물인지 알 수 없게 된다. 두 단계 마커는 키가 독립이므로(PIPE-2SB-19) 삭제도 각각 해야 한다 |

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
| PIPE-S3IO-29 | 유비쿼터스 | THE 시스템 SHALL `run_analysis.py`·`run_aggregate.py` 파일과 analysis 모듈 코드를 보존한다 | 파일 삭제·수정 없음; 문서에서 "우선/임시" 표현만 제거. ⚠️ **"보존"은 저장소의 코드에 대한 것이지 배치 이미지 포함을 요구하지 않는다** — PIPE-S3IO-40 참조 |
| PIPE-S3IO-40 | 유비쿼터스 **(신규 2026-07-26)** | THE 시스템 SHALL 배치 이미지(`pipeline/Dockerfile`)에 **현행 흐름이 실제로 쓰는 모듈만** 담고, 배선에서 빠진 analysis 계열(`analysis/` · `kiwipiepy` · `transformers` · `torch` · NER 모델)은 **포함하지 않는다** | 배치 흐름은 `run_validation` → `run_bedrock` 2단계뿐인데(PIPE-S3IO-28) 이미지가 torch(CPU 빌드)와 KoELECTRA 모델까지 담아 **1.63GB** 였다 — **전부 쓰지 않는 무게다.** Spot 노드는 **뜰 때마다 이미지를 pull** 하므로 이 무게가 기동 시간에 그대로 붙고, 02:00 배치가 노드 회수·재기동을 반복하는 구조에서 누적된다. `analysis/Dockerfile` 이 이미 torch·모델·코드를 모두 갖고 있어 배치 이미지의 사본은 **순수 중복**이었다. ⚠️ **결과: `run_analysis`·`run_aggregate` 는 이 이미지로 돌지 않는다** — `analysis/Dockerfile` 이미지나 로컬 `.venv` 를 쓴다. PIPE-S3IO-29(코드 보존)와 충돌하지 않는다: 파일은 저장소에 그대로 있다. analysis 가 배선에 복귀하면 이 조항을 재검토한다 |

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

### v2 개정에 따른 한계
- **⚠️ 문서(v2 승인됨)와 코드(v1)가 일치하지 않는다.** `run_validation.py` 의 `process_post` 는 폐기된
  PIPE-S3IO-17(본문 폐기 시 `body:""` success 생성)을 그대로 구현하고 있다. **코드 변경은 확정된 후속 작업**이다.
- **정책 변경 시 재처리 비용은 시점에 비례해 커진다**(PIPE-S3IO-36). **지금은 무시할 수준**이다 — 실측상
  `_manifest` 557건, 그중 v2 로 판정이 달라질 것은 "빈 본문" 23건뿐이고 패턴 단계는 LLM 비용이 0이다.
  하지만 **정제가 본격화된 뒤에 정책을 바꾸면 이야기가 다르다**: 누적 5만 건 규모에서 규칙을 바꾸면
  **Bedrock 재판정만 수십 달러**(패턴 통과율 60.5% 실측 → 약 3만 단위)이고, 정규 배치의 밤당 상한($30,
  PIPE-2SB-60)을 넘겨 **여러 밤에 걸쳐 재처리**해야 한다. 정책 변경은 **비용 결정**이기도 하다.
- **"이미지 글이면 폐기"는 계약으로 쓸 수 없다.** 크롤 데이터에 이미지 여부를 나타내는 필드가 없다
  (필드는 `title`·`body`·`topComments`·`engagement`·`sourceUrl`·`team`·`postExternalId`·`crawledAt`·
  `crawlerVersion`·`schemaVersion` 뿐). **이미지 글과 제목만 있는 글이 `body:""` 로 똑같이 보인다.**
  구현 가능한 조건은 **"`body` 가 비어 있으면"** 하나뿐이다. (본문이 URL·이미지 링크만인 경우는 표본에서
  8건, 0.13% 라 별도 규칙이 불필요하다.)
- **제목은 상시 검열되지 않는다**(PIPE-S3IO-35). `body` 가 있는 글의 제목에 담긴 욕설·비하는 **v2 에서도
  여전히 통과**한다. 빈 본문일 때만 title 이 판정 대상이 된다 — 사용자 한정에 따른 의도적 공백이다.
- **본문 3자 이하(표본의 10.8%, `ㅋㅋ`·`ㅇ`·`어떰`)는 이번에 손대지 않는다**(사용자 확정). 판정 대상이
  존재하므로 기존 규칙대로 흐른다. 짧다는 이유로 버리는 것은 `BRK-LLM-42`("짧다는 이유로 `offtopic` 판정하지
  않는다")와도 어긋나므로, **길이 기준 폐기는 두 문서 모두에서 일관되게 두지 않는다.**

## 확정된 결정 (구 미해결 질문 — 전건 해소)
1. **failed 사유 구조** = `{unit, commentIndex, author, text, message}` (unit ∈ {"body","comment"}). `text`는 걸린 원본 본문/댓글 텍스트 — postExternalId만으론 무엇이 필터링됐는지 알 수 없다는 사용자 피드백으로 추가. → PIPE-S3IO-13.
2. **본문 폐기 시** = 통과 단위가 하나라도 있으면 success 정화 객체를 낸다(`body:""` + 통과 댓글만 유지, 원본 필드 보존). 통과 단위 0이면 success 미생성. → PIPE-S3IO-10/17/19. (초안 "본문 폐기 시 success 미생성"은 교체됨.)
3. **빈 본문(`body:""`)** = 폐기로 간주해 failed 보존(통과 아님). → PIPE-S3IO-20b. (초안 "빈 본문=통과"는 뒤집힘.)
4. **재실행/원자성** = 게시글 단위 원자적 write, 미완결은 재처리, 부분 산출물 금지. → PIPE-S3IO-24/25.
5. **테스트** = dev 버킷 실입출력 통합 테스트, 전용 테스트 네임스페이스에만 쓰고 종료 시 정리. → PIPE-S3IO-30/31.
6. **버킷 env 키** = `S3_BUCKET`(입력·출력 동일 버킷). → PIPE-S3IO-1.

## v2 확정된 결정
1. **본문(또는 title) 폐기 = 게시글 전체 fail.** 댓글 통과 여부 무관. → PIPE-S3IO-10/17(폐기)/19/19b.
2. **빈 본문이면 `title` 을 본문 자리 판정 단위로.** 둘 다 비면 fail. → PIPE-S3IO-20b(개정)/32/33.
3. **`unit` 값에 `"title"` 추가.** 필드 구조는 불변. → PIPE-S3IO-34.
4. **`body` 가 있는 글의 title 은 판정하지 않는다.** → PIPE-S3IO-35.
5. **success 객체 형태는 불변** = 본문 + 통과 댓글로 이루어진 하나의 원문(PIPE-S3IO-10, v1 그대로).
6. **정책이 바뀌면 기존 산출물을 폐기하고 새 정책으로 재적용한다**(사용자 확정, **일회성이 아니라 원칙**).
   절차 = `_manifest` 마커 삭제 → 러너 재실행, 삭제는 **운영자 수행**(러너 자동 아님). 패턴을 재실행하면
   **Bedrock 마커도 함께 폐기 대상**이다. → PIPE-S3IO-36/37/38.

   **실측 근거 (버킷 `victoryfairy-crawl-dev`, 2026-07-25 기준)**
   ```
   validation/pattern/success/    337건   ← 그중 body:"" 인 것 0건
   validation/pattern/failed/     220건
   validation/pattern/_manifest/  557건   (크롤 원본 58,066건 — 정제는 테스트 수준만 진행됨)

   failed 220건 폐기 사유 상위:  36 욕설 '새끼' / 26 욕설 '존나' / 23 빈 본문 / 23 욕설 '씨발'
   ```
   **v2 가 없애려는 `body:""` success 는 실제 0건이라 소급 충돌이 없다.** v1 도 이미 빈 본문을 폐기하므로
   (사유 "빈 본문" 23건) v2 로 판정이 달라지는 것은 **그 23건 중 제목이 통과하는 것들뿐**이다.
   즉 **이번 재적용의 실질 규모는 23건 이하**이고, "하류가 어긋난다"는 위험도 현 규모에선 발생하지 않는다.
   비용이 문제가 되는 것은 정제가 본격화된 뒤의 정책 변경이다(위 한계 참조).

## 미해결 질문
- 없음. (v2 개정분 포함 전건 해소 — 위 "v2 확정된 결정" 6번으로 마지막 항목이 해소됐다.)

## 승인 후 후속 작업 (문서 밖 — 기재만)
- **코드**: `pipeline/run_validation.py` 의 `process_post` 를 v2 로 수정 — (a) 본문/title 폐기 시 success 미생성,
  (b) 빈 `body` 일 때 `title` 판정, (c) failed `unit` 에 `"title"`.
- **운영**: 기존 `validation/pattern/_manifest/`(557건) + 해당 `validation/bedrock/_manifest/` 삭제 후 재실행(PIPE-S3IO-36~38).
- **`context-keeper`**: 이 문서의 `PIPE-S3IO-4`(개정됨 — `BATCH_DATE`)·`PIPE-S3IO-28`(대체됨 — 2단계 흐름) 표기 반영.
