# 프로필 이미지 업로드 요구사항
> 상태: **승인됨 (2026-08-20)** · 모듈: user · 최종 수정: 2026-08-20 (2차 개정 — 런타임 검증에서 드러난 `appId` 한도 카운터의 실패 처리를 확정: **저장 실패 시 환불**, USER-PI-116~121·결정 기록 9. 1차 개정에서 초안의 미해결 질문 8건이 전부 확정됐다)
> **2026-08-20 개정 요약**: 초안이 `(가정)`으로 표시했던 값이 전부 확정값이 됐다. 주요 변경은 셋이다. ①**BaseURL은 `https://victoryfairy.com`**이고 **기존 CloudFront 배포에 오리진을 추가**하는 방식이다(신규 배포 생성이 아니다) — `temp/`도 CDN으로 읽히므로 보안 한계가 하나 늘었다. ②**`appId` 카운터 TTL이 24시간에서 30분으로, 방식이 고정 창으로 확정**됐다(키가 새로 만들어질 때만 TTL을 건다). ③**`temp/` 정리는 스케줄러 1차 + S3 라이프사이클 2차의 이중화**가 됐다. 초안의 "기존 정책과의 충돌" 3건은 전부 **해소하는 것으로 확정**돼 "해소 방침" 절로 바뀌었다.

## 배경 / 목적
프로필 이미지 자체보다 **계정이 아직 없는 시점의 업로드**가 이 기능의 실제 쟁점이다. 회원가입 화면에서 사진을 고르는 순간에는 인증할 주체가 없으므로, 이 기능은 "인증된 사용자의 파일 업로드"가 아니라 **인증 없는 쓰기 창구를 하나 여는 일**이다. 그래서 계약의 무게가 정상 경로가 아니라 남용 한도·고아 객체·이동 실패 쪽에 실려 있다.

또한 이 저장소에는 **S3를 쓰는 코드도, 파일을 받는 엔드포인트도 아직 없다**(`.claude/modules/infra.md` 기준 S3는 tfstate·DB 백업 용도뿐). 즉 이 기능은 이 백엔드 최초의 외부 오브젝트 스토리지 의존이자 최초의 multipart 경로이며, **DB 트랜잭션 롤백으로 되돌아가지 않는 부수효과**가 처음 들어온다. "이동 실패 시 가입은 성공인가"가 요청서에 명시된 이유가 이것이다.

## 선행 조건 / 인프라 의존 (이 문서의 범위 밖 — infra/Terraform 소관)
아래가 준비되지 않으면 요구사항 전부가 미충족 상태가 된다. **`dev_infra` 작업이며 이 문서는 전제로만 다룬다.**

1. **버킷 `victoryfairy-asset` 생성** — 퍼블릭 액세스 차단(BPA) 4종 on, **프라이빗 유지**. 읽기는 CloudFront OAC만 허용한다(버킷 정책에 OAC 조건부 `s3:GetObject`).
2. **기존 CloudFront 배포에 오리진 추가** — `modules/cdn`의 **현행 배포**에 `victoryfairy-asset`을 두 번째 S3 오리진으로 붙이고, 경로 패턴 **`/user-profile-img/*`와 `/temp/*`만** 그 오리진으로 라우팅한다. **신규 배포를 만들지 않는다**(도메인이 갈리면 BaseURL이 둘이 된다).
3. **BaseURL = `https://victoryfairy.com`** — 서버가 돌려주는 EP를 그 뒤에 **그대로 이어 붙이면 되는 형태**여야 한다(USER-PI-9). 즉 EP는 선행 슬래시 없이 `user-profile-img/…`이고 클라이언트가 `https://victoryfairy.com/` + EP로 조립한다.
4. **user 앱(EKS 파드)의 버킷 쓰기 권한** — `PutObject` · `HeadObject`(=`GetObject`) · `CopyObject` · `DeleteObject` · `ListBucket`(prefix `temp/`). IRSA 권장(기존 quiz ingest IRSA 선례). **업로드는 파드가 AWS SDK로 S3에 직접 쓴다 — CloudFront·ALB를 타지 않는다**(CDN은 읽기 전용 경로다).
5. **S3 라이프사이클 규칙: `temp/` 접두사 1일 만료** — 앱 스케줄러(USER-PI-80~92)의 **2차 안전망**이다. 스케줄러가 며칠 못 돌아도 쓰레기가 무한 축적되지 않게 하는 이중화이며, **`user-profile-img/`에는 어떤 라이프사이클 규칙도 걸지 않는다**(USER-PI-94).
6. **로컬·dev 환경에서 실제 S3를 치는지, 대체 구현으로 우회하는지** — 기존 `EmailSender` 포트가 `prod`/`!prod`로 갈리는 선례가 있다. 구현자 재량이되 dev에서 운영 버킷을 쓰지 않는 것이 전제다.

## 범위
- 포함
  - 업로드 엔드포인트 2개(인증 필수 1 · 인증 불필요 1), 둘 다 `multipart/form-data`
  - 응답으로 **EP(오브젝트 키)** 반환 — BaseURL 접두사 제외
  - 비인증 경로의 `appId` 기반 10회 제한(Redis, 30분 고정 창) + 초과 시 응답
  - `POST /api/auth/signup`의 선택 입력 `profileImgUrl` 추가
  - 가입 성공 시 `temp/` → `user-profile-img/` 이동과 그 실패 처리
  - `GET /api/users/me` 응답의 `profileImgUrl` 추가
  - `users_account.profile_img_url` 컬럼 신설(nullable, default null) + `infra/sql` 선투입 마이그레이션
  - `temp/` 잔여 객체를 지우는 user 모듈 스케줄러(1차) — 라이프사이클(2차)은 infra 소관
  - 프로필 이미지 변경·회원 탈퇴 시 기존 객체 삭제
  - 스케줄링 활성화와 개별 잡 on/off 분리(해소 방침 1) · 업로드 크기 초과의 공유 핸들러 처리(해소 방침 3)
- 제외
  - **버킷·CloudFront 오리진·OAC·라이프사이클 생성**(위 "선행 조건") — 이 문서는 앱 동작만 규정한다
  - **이미지 리사이즈·썸네일 생성·재인코딩·EXIF 제거** — 서버는 받은 바이트를 그대로 저장한다
  - **HEIC 지원** — 허용 목록에 넣지 않는다. **iOS 기본 촬영 포맷을 앱(RN)이 JPEG로 변환해 보낸다는 전제**이며, 변환하지 않고 그대로 보내면 400이다(USER-PI-36)
  - **presigned URL로 클라이언트가 S3에 직접 올리는 방식** — 요청은 "API가 파일을 받는" 형태다
  - **프로필 이미지 삭제(기본 이미지로 되돌리기) 전용 API** — 지금은 이미지를 없앨 방법이 탈퇴뿐이다
  - **이미지를 서버가 대신 내려주는 조회/프록시 엔드포인트** — 읽기는 CloudFront가 담당한다
  - **`appId` 발급·서명·검증 체계** — RN 앱이 발급하고 서버는 받은 문자열을 그대로 키로 쓴다(그래서 위조로 한도를 우회할 수 있다, USER-PI-106)
  - **`user-profile-img/` 경로의 고아 객체 회수** — 스케줄러도 라이프사이클도 `temp/`만 본다(USER-PI-84·94)
  - **관리자의 타인 이미지 변경·조회** — 대상은 항상 토큰 주체 본인이다
  - **이미지 콘텐츠 검열(부적절 이미지 탐지)·신고**
  - **기존 계정 이미지 백필** — 신설 컬럼은 전부 NULL로 시작한다

## 용어
| 용어 | 뜻 |
|---|---|
| **EP** | 버킷 안 오브젝트 키. BaseURL·스킴·버킷명을 포함하지 않고 선행 슬래시도 없다. 예 `user-profile-img/9f1c….webp` |
| **BaseURL** | `https://victoryfairy.com` — 기존 CloudFront 배포. `EP` 앞에 `/`와 함께 이어 붙이면 실제 이미지 URL이 된다 |
| **temp 경로** | `temp/` 접두 — 계정이 아직 없는 상태에서 올린 이미지가 머무는 자리. **CDN으로 읽힌다**(가입 전 미리보기 때문) |
| **영구 경로** | `user-profile-img/` 접두 — 계정에 실제로 매달린 이미지 |

---

## 요구사항 (EARS)

### A. 두 경로 공통

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-PI-1 | 유비쿼터스 | THE 시스템 SHALL 두 업로드 경로의 요청 본문을 `multipart/form-data`로 받는다 | `Content-Type: application/json` + JSON 본문으로 호출 → 415 |
| USER-PI-2 | 유비쿼터스 | THE 시스템 SHALL 이미지 파트 이름을 `image`로 고정한다 | 파트 이름을 `file`로 보내면 400 `PROFILE_IMAGE_REQUIRED` |
| USER-PI-3 | 유비쿼터스 | THE 시스템 SHALL 업로드 성공 시 200과 `ApiResponse<ProfileImageResponse>`를 반환한다 | 본문 `{"success":true,"data":{"profileImgUrl":"temp/9f1c….webp"},"message":null}` |
| USER-PI-4 | 유비쿼터스 | THE 시스템 SHALL 응답의 `profileImgUrl`에 BaseURL 접두사를 포함하지 않는다 | 응답 값이 `https://`·`http://`·버킷명 `victoryfairy-asset`·`s3.`·`victoryfairy.com`을 포함하지 않고 `/`로 시작하지도 않는다 |
| USER-PI-5 | 예외 | IF 이미지 파트가 없거나 0바이트이면, THEN THE 시스템 SHALL 400 `PROFILE_IMAGE_REQUIRED`를 반환한다 | 파트 없이 호출 → 400, `message`가 이미지 필요 문구. 버킷에 객체 0개 |
| USER-PI-6 | 예외 | IF S3 저장이 실패하면, THEN THE 시스템 SHALL 5xx를 반환한다 | 잘못된 자격증명·버킷 부재 상태에서 업로드 → 5xx. ⚠ `GlobalExceptionHandler`가 잡지 않는 예외라 **`ApiResponse` 래퍼가 아니다**(알려진 한계 4) |
| USER-PI-7 | 예외 | IF S3 저장이 실패하면, THEN THE 시스템 SHALL `users_account.profile_img_url`을 바꾸지 않는다 | 실패 요청 이후 `GET /api/users/me`의 `profileImgUrl`이 요청 전 값과 동일 |
| USER-PI-8 | 유비쿼터스 | THE 시스템 SHALL 성공 응답을 반환하기 전에 객체가 버킷에 실제로 존재하게 한다 | 응답으로 받은 EP를 즉시 HeadObject → 200(응답과 저장 사이에 관측 가능한 공백이 없다) |
| USER-PI-9 | 유비쿼터스 | THE 시스템 SHALL 응답 EP가 `https://victoryfairy.com/` 뒤에 그대로 이어 붙여 읽히는 형태가 되게 한다 | 응답이 `user-profile-img/9f1c….webp`이면 `https://victoryfairy.com/user-profile-img/9f1c….webp`가 200으로 이미지를 내려준다. **클라이언트가 EP를 가공(슬래시 추가·경로 재조립)할 필요가 없다** |

### B. 인증 필요 업로드 — `POST /api/users/me/profile-image`

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-PI-10 | 이벤트 | WHEN 인증된 사용자가 이미지를 업로드하면, THE 시스템 SHALL 그 객체를 `user-profile-img/` 경로에 저장한다 | 응답 EP가 `user-profile-img/`로 시작. **`temp/`를 경유하지 않는다** — 회차 중 `temp/` 객체 수 증가 0 |
| USER-PI-11 | 이벤트 | WHEN 저장이 성공하면, THE 시스템 SHALL 그 계정의 `profile_img_url`을 새 EP로 갱신한다 | 업로드 직후 `GET /api/users/me`의 `profileImgUrl`이 방금 받은 EP와 문자 그대로 동일. **업로드가 곧 변경 확정이며 취소·확정 단계가 없다**(결정 기록 2) |
| USER-PI-12 | 이벤트 | WHEN 저장이 성공하면, THE 시스템 SHALL 응답으로 그 EP를 반환한다 | USER-PI-3 형태, `data.profileImgUrl`이 `user-profile-img/…` |
| USER-PI-13 | 예외 | IF `Authorization` 헤더가 없으면, THEN THE 시스템 SHALL 401 `UNAUTHENTICATED`를 반환한다 | 헤더 없이 호출 → 401 `{"success":false,"data":null,"message":"인증이 필요합니다."}` |
| USER-PI-14 | 예외 | IF 토큰이 위조·만료·refresh 종류이거나 비밀번호 변경으로 무효화된 것이면, THEN THE 시스템 SHALL 401 `UNAUTHENTICATED`를 반환한다 | 각 경우 401, 본문은 USER-PI-13과 문자 그대로 동일(기존 `access-token-invalidation.md` 계약 재사용) |
| USER-PI-15 | 유비쿼터스 | THE 시스템 SHALL 대상 계정을 access 토큰에서만 결정한다 | 경로·본문 어디에도 계정 식별자가 없다. 타인 계정 이미지를 바꿀 파라미터가 존재하지 않음 |
| USER-PI-16 | 유비쿼터스 | THE 시스템 SHALL 이 경로에 `appId` 한도(10회)를 적용하지 않는다 | 같은 계정으로 30회 연속 업로드 → 30회 모두 200. Redis 카운터 키가 생기지 않음 |
| USER-PI-17 | 유비쿼터스 | THE 시스템 SHALL 이 경로에서 `appId` 파라미터를 받지 않는다 | `appId` 파트를 함께 보내도 동작·응답이 달라지지 않는다(무시) |

### C. 인증 불필요 업로드 — `POST /api/auth/profile-image`

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-PI-20 | 이벤트 | WHEN 비인증 클라이언트가 `appId`와 이미지를 함께 업로드하면, THE 시스템 SHALL 그 객체를 `temp/` 경로에 저장한다 | 응답 EP가 `temp/`로 시작하고 `user-profile-img/` 객체 수는 증가하지 않음 |
| USER-PI-21 | 유비쿼터스 | THE 시스템 SHALL 이 경로에 인증을 요구하지 않는다 | `Authorization` 헤더 없이 호출 → 200 |
| USER-PI-22 | 유비쿼터스 | THE 시스템 SHALL 유효한 access 토큰이 함께 오더라도 이 경로의 동작을 바꾸지 않는다 | 유효 토큰을 실어 호출해도 저장 위치는 `temp/`이고 `profile_img_url`은 갱신되지 않으며 `appId` 한도도 그대로 적용. **`GET /api/players`(토큰이 있으면 결과가 달라지는 공개 경로)와 반대 성격임을 명시적으로 고정한다** |
| USER-PI-23 | 예외 | IF `appId` 파트가 없거나 공백이면, THEN THE 시스템 SHALL 400 `INVALID_APP_ID`를 반환한다 | `appId` 없이 호출 → 400, 버킷에 객체 0개. **비어 있지만 않으면 통과한다** — 형식(UUID 등)은 검증하지 않는다(결정 기록 5) |
| USER-PI-24 | 유비쿼터스 | THE 시스템 SHALL `appId`별 임시 업로드 성공 횟수를 Redis에 누적한다 | 키 `profile:image:temp:count:{appId}`(기존 `email:verify:` 접두 규약을 미러링), 값은 정수 문자열 |
| USER-PI-25 | 이벤트 | WHEN 임시 업로드가 성공하면, THE 시스템 SHALL 그 `appId`의 카운터를 1 증가시킨다 | 성공 3회 후 `GET profile:image:temp:count:{appId}` = `3` |
| USER-PI-26 | 유비쿼터스 | THE 시스템 SHALL 성공하지 못한 요청이 그 `appId`의 남은 횟수를 소모하지 않게 한다 | 소모하지 않는 실패에는 **①검증 실패(형식·크기·`appId` 누락)와 ②저장 실패(S3 오류)가 모두 포함된다** — ①은 카운터 증가 **이전**에 걸리고(USER-PI-116), ②는 증가 **이후**라 환불로 되돌린다(USER-PI-117). 실측 ①: 형식 위반으로 20회 거절당한 `appId`가 이후 정상 업로드 10회를 그대로 성공. 실측 ②: **S3를 끊은 상태에서 같은 `appId`로 10회 실패시킨 뒤 11번째 요청이 429가 아니라 정상 처리 시도로 진행된다**(2026-08-20 런타임 검증에서 이 케이스가 실제로는 429였다 — 그것이 이 요구사항을 명확히 한 계기다) |
| USER-PI-27 | 예외 | IF 그 `appId`의 카운터가 이미 10 이상이면, THEN THE 시스템 SHALL 429 `PROFILE_IMAGE_UPLOAD_LIMIT_EXCEEDED`를 반환한다 | 같은 `appId`로 11번째 호출 → 429. 10번째는 200(경계: **10회까지 허용**) |
| USER-PI-28 | 예외 | IF 한도 초과로 거절하면, THEN THE 시스템 SHALL 객체를 저장하지 않는다 | 11번째 호출 이후 `temp/` 객체 수가 10에서 늘지 않음 |
| USER-PI-29 | 유비쿼터스 | THE 시스템 SHALL 카운터 키가 **새로 만들어질 때만** TTL 30분을 설정한다 | 첫 업로드 직후 `TTL` ≈ 1800. 5번째 업로드 직후 TTL이 다시 1800이 되지 않고 계속 줄어든다(**고정 창** — 결정 기록 5) |
| USER-PI-30 | 이벤트 | WHEN 카운터 키의 TTL이 만료되면, THE 시스템 SHALL 그 `appId`에 10회를 다시 허용한다 | 10회를 채워 429를 받은 `appId`가 30분 뒤 호출 → 200. `EXISTS` 결과가 0 |
| USER-PI-31 | 유비쿼터스 | THE 시스템 SHALL 회원가입 성공으로 그 `appId`의 카운터를 초기화하지 않는다 | 3회 업로드 후 가입 성공 → 카운터 여전히 `3`, 그 창 안의 남은 허용은 7회 |
| USER-PI-32 | 유비쿼터스 | THE 시스템 SHALL `appId`를 EP·파일명·응답 본문 어디에도 싣지 않는다 | 응답 EP를 `appId`로 grep해 0건. **다른 사람의 `appId`를 알아도 그가 올린 EP를 유도할 수 없다** |
| USER-PI-33 | 예외 | IF Redis 조회·증가가 실패하면, THEN THE 시스템 SHALL 업로드를 거절한다 | Redis 중지 상태에서 호출 → 5xx, 버킷에 객체 미생성(**fail-closed** — 결정 기록 8) |
| USER-PI-34 | 유비쿼터스 | THE 시스템 SHALL `temp/` EP도 BaseURL로 읽히게 한다 | 임시 업로드 응답 EP를 `https://victoryfairy.com/temp/…`로 요청 → 200 + 이미지. **가입 전 미리보기가 이 경로에 의존한다**(그 대가는 알려진 한계 2) |
| USER-PI-116 | 유비쿼터스 | THE 시스템 SHALL 카운터 증가를 S3 저장보다 먼저 수행한다 | 한도 경계(9회 소모 상태)에서 같은 `appId`로 동시 요청 2건 → 통과는 정확히 1건. ⚠ **"저장에 성공한 뒤 증가"로 순서를 바꾸지 말 것** — 원자적 `INCR`이 게이트 역할을 못 하게 되어 동시 요청 둘이 같은 값을 읽고 함께 통과하면 한도가 뚫린다. 단순해 보여서 되돌리기 쉬운 자리라 순서 자체를 계약으로 고정한다(결정 기록 9) |
| USER-PI-117 | 예외 | IF 카운터 증가 이후 S3 저장이 실패하면, THEN THE 시스템 SHALL 그 카운터를 1 되돌린다 | S3를 끊고 1회 요청 → 5xx, `GET profile:image:temp:count:{appId}`가 요청 전 값과 동일(환불). 소모된 슬롯이 복구된다 |
| USER-PI-118 | 유비쿼터스 | THE 시스템 SHALL 환불이 카운터 키의 TTL을 바꾸지 않게 한다 | 환불 직전·직후의 `TTL` 값이 경과 시간만큼만 줄어 있고 재설정되지 않는다. **고정 창 계약(USER-PI-29·30)은 그대로다** — 환불은 남은 창의 길이에 영향을 주지 않는다 |
| USER-PI-119 | 예외 | IF 환불 시점에 카운터 키가 이미 없으면(TTL 만료), THEN THE 시스템 SHALL 아무 것도 하지 않는다 | 키를 지운 뒤 환불 시도 → `EXISTS` 결과가 계속 0. ⚠ **Redis `DECR`는 없는 키를 새로 만들면서 `-1`을 넣고 그 키에는 TTL이 붙지 않는다** — 그대로 두면 그 `appId`는 사실상 무제한이 되어, 원래 막으려던 것보다 나쁜 구멍이 생긴다 |
| USER-PI-120 | 예외 | IF 환불이 실패하면, THEN THE 시스템 SHALL 원래의 저장 실패 응답을 그대로 반환한다 | S3 실패 + Redis 실패가 겹친 요청 → 응답은 USER-PI-6의 5xx 그대로. **환불 실패가 원인 예외를 가리지 않는다**(사용자가 보는 것은 언제나 원래의 저장 실패다) |
| USER-PI-121 | 예외 | IF 환불이 실패하면, THEN THE 시스템 SHALL 그 사실을 로그로 남긴다 | 해당 `appId`와 환불 실패가 로그에 남는다. **재시도하지 않는다**(best-effort — 그 슬롯 1개는 창이 만료될 때까지 소모된 채 남는다, 알려진 한계 3) |

### D. 파일 검증 · 파일명 · EP 형태

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-PI-35 | 유비쿼터스 | THE 시스템 SHALL 허용 이미지 형식을 JPEG·PNG·WebP 셋으로 한정한다 | `image/jpeg`·`image/png`·`image/webp` 3종만 200 |
| USER-PI-36 | 예외 | IF 허용 목록 밖 형식이면, THEN THE 시스템 SHALL 400 `INVALID_PROFILE_IMAGE_FORMAT`을 반환한다 | GIF·SVG·**HEIC**·PDF 업로드 → 각각 400, 객체 미생성. **HEIC를 앱이 JPEG로 변환해 보내는 것이 전제다**(결정 기록 4) |
| USER-PI-37 | 유비쿼터스 | THE 시스템 SHALL 형식 판정을 파일 선두 바이트로 수행한다 | 실행 파일의 이름만 `a.png`로 바꾸고 `Content-Type: image/png`로 보내면 400 `INVALID_PROFILE_IMAGE_FORMAT`. **확장자·요청 Content-Type은 판정 근거가 아니다**(둘 다 클라이언트가 자유로이 정하는 값) |
| USER-PI-38 | 유비쿼터스 | THE 시스템 SHALL 이미지 최대 크기를 5MiB로 제한한다 | 5MiB 정확히 → 200, 5MiB + 1바이트 → 거절 |
| USER-PI-39 | 예외 | IF 이미지가 최대 크기를 넘으면, THEN THE 시스템 SHALL 413 `PROFILE_IMAGE_TOO_LARGE`를 반환한다 | 10MiB 파일 → 413, 객체 미생성 |
| USER-PI-40 | 유비쿼터스 | THE 시스템 SHALL 크기 초과 응답도 `ApiResponse` 래퍼로 반환한다 | 413 본문이 `{"success":false,"data":null,"message":…}`. ⚠ **아무것도 하지 않으면 이 요구사항은 자동으로 깨진다** — 업로드 크기 초과 예외를 공유 핸들러가 처리하도록 확장해야 한다(해소 방침 3) |
| USER-PI-41 | 유비쿼터스 | THE 시스템 SHALL 저장 파일명을 서버가 생성한다 | 원본 파일명이 `../../etc/passwd`·`내 사진 (1).png`·`a.png.exe`여도 EP는 `{경로}/{서버생성값}.{확장자}` 형태이고 원본 파일명 조각을 포함하지 않는다 |
| USER-PI-42 | 유비쿼터스 | THE 시스템 SHALL 생성 파일명에 UUID v4를 사용한다 | EP의 파일명 부분이 UUID v4 형식(하이픈 포함 36자) |
| USER-PI-43 | 유비쿼터스 | THE 시스템 SHALL EP를 `temp/{uuid}.{ext}` 또는 `user-profile-img/{uuid}.{ext}` 형태로 만든다 | 두 경로 모두 세그먼트 2개(하위 디렉터리 없음). `/temp/…`처럼 `/`로 시작하지 않는다 — CloudFront 경로 패턴 `/temp/*`·`/user-profile-img/*`와 1:1로 맞는다 |
| USER-PI-44 | 유비쿼터스 | THE 시스템 SHALL 확장자를 USER-PI-37로 판정한 실제 형식에 맞춘다 | JPEG 바이트를 `x.png`라는 이름으로 올려도 EP 확장자가 `.jpg`(허용 확장자 `jpg`/`png`/`webp`) |
| USER-PI-45 | 유비쿼터스 | THE 시스템 SHALL 같은 파일을 두 번 올려도 서로 다른 EP 두 개를 만든다 | 동일 바이트 2회 업로드 → EP 2개, 객체 2개(내용 기반 중복 제거 없음) |
| USER-PI-46 | 유비쿼터스 | THE 시스템 SHALL 저장 객체의 Content-Type을 판정된 형식으로 설정한다 | 저장 후 HeadObject의 `ContentType`이 `image/jpeg`·`image/png`·`image/webp` 중 하나이며, 요청이 보낸 값이 아니라 판정 결과와 일치. **CloudFront가 이 값을 그대로 응답 헤더로 내보내므로 브라우저 렌더링이 여기에 달려 있다** |
| USER-PI-47 | 유비쿼터스 | THE 시스템 SHALL 업로드된 바이트를 변형하지 않고 그대로 저장한다 | 업로드한 파일과 다운로드한 객체의 바이트가 동일(리사이즈·재인코딩·EXIF 제거 없음 — "제외" 절과 짝) |

### E. 회원가입 연계 — `POST /api/auth/signup`의 `profileImgUrl`

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-PI-50 | 유비쿼터스 | THE 시스템 SHALL 회원가입 요청 본문에 선택 필드 `profileImgUrl`을 받는다 | `SignupRequest`에 필드 추가. 기존 6개 필드의 이름·검증은 불변 |
| USER-PI-51 | 예외 | IF `profileImgUrl`이 생략되거나 null이면, THEN THE 시스템 SHALL 가입을 성공시키고 컬럼을 null로 저장한다 | 종전과 동일한 본문으로 가입 → 201 `true`, `profile_img_url IS NULL`. **기존 가입 클라이언트가 그대로 동작한다**(하위 호환) |
| USER-PI-52 | 복합 | WHILE `profileImgUrl`이 주어진 상태에서, WHEN 가입이 성공하면, THE 시스템 SHALL 그 객체를 `user-profile-img/` 경로로 이동시킨다 | 가입 후 `user-profile-img/` 아래에 같은 바이트의 객체가 존재 |
| USER-PI-53 | 이벤트 | WHEN 이동이 성공하면, THE 시스템 SHALL `profile_img_url`에 **이동 후** EP를 저장한다 | 저장 값이 `user-profile-img/…`. 요청에 실린 `temp/…`가 그대로 저장되지 않는다 |
| USER-PI-54 | 이벤트 | WHEN 이동이 성공하면, THE 시스템 SHALL `temp/` 원본 객체를 삭제한다 | 가입 직후 원본 EP를 HeadObject → 404(스케줄러·라이프사이클을 기다리지 않는다) |
| USER-PI-55 | 예외 | IF `profileImgUrl`이 `temp/` 접두로 시작하지 않으면, THEN THE 시스템 SHALL 400 `INVALID_PROFILE_IMAGE_ENDPOINT`를 반환한다 | `user-profile-img/x.png`·`../x`·`https://victoryfairy.com/temp/x.png`·빈 문자열 → 각각 400, 계정 미생성. **타인의 영구 경로 EP를 그대로 자기 계정에 붙이는 것을 막는 줄이다** |
| USER-PI-56 | 예외 | IF `profileImgUrl`이 가리키는 객체가 버킷에 없으면, THEN THE 시스템 SHALL 400 `INVALID_PROFILE_IMAGE_ENDPOINT`를 반환한다 | 존재하지 않는 `temp/{uuid}.png`로 가입 → 400, 계정 미생성 |
| USER-PI-57 | 예외 | IF USER-PI-55·56을 통과한 뒤 이동이 실패하면, THEN THE 시스템 SHALL 가입을 성공시키고 `profile_img_url`을 null로 저장한다 | S3를 끊은 상태에서 가입 → 201 `true`, `profile_img_url IS NULL`, `GET /api/users/me`의 `profileImgUrl`이 `null`. **근거: 이메일 인증 소비(`consumeVerified`)가 Redis라 DB 롤백으로 되돌아오지 않는다 — 가입을 실패시키면 사용자가 이메일 인증부터 다시 하도록 강요당한다**(결정 기록 3) |
| USER-PI-58 | 예외 | IF 이동이 실패하면, THEN THE 시스템 SHALL 기존 가입 부수효과를 그대로 수행한다 | 이동 실패 회차에도 `UserBq` 행 1건 생성 + 이메일 인증 상태 소비(`verified:{email}` 삭제) — 기존 signup 계약 불변 |
| USER-PI-59 | 유비쿼터스 | THE 시스템 SHALL 이미지 검증을 기존 가입 검사 순서의 **마지막**에 둔다 | 인증 안 된 이메일 + 잘못된 EP → `EMAIL_NOT_VERIFIED`(400). 중복 이메일 + 잘못된 EP → `DUPLICATE_EMAIL`(409). 순서: 형식 → 이메일 인증 → 중복 → 이미지 |
| USER-PI-60 | 예외 | IF 같은 `temp/` EP로 두 번째 가입을 시도하면, THEN THE 시스템 SHALL 400 `INVALID_PROFILE_IMAGE_ENDPOINT`를 반환한다 | 첫 가입 성공 후 같은 EP로 다른 계정 가입 → 400(원본이 이미 삭제돼 USER-PI-56에 자연히 걸린다). **EP 하나가 계정 둘에 공유되지 않는다** |
| USER-PI-61 | 예외 | IF `profileImgUrl` 길이가 255자를 넘으면, THEN THE 시스템 SHALL 400을 반환한다 | 300자 문자열 → 400. DB 저장 단계에서 잘리거나 500이 나지 않는다(컬럼 길이와 같은 값) |
| USER-PI-62 | 유비쿼터스 | THE 시스템 SHALL 이동 이후 가입 트랜잭션이 실패한 경우에 복사된 객체를 회수하지 않는다 | 그 객체는 `user-profile-img/` 아래에 참조 없이 남는다(스케줄러·라이프사이클 대상도 아님, USER-PI-84·94). **알려진 한계 1** — "안 한다"를 계약으로 못 박아 나중에 "왜 안 지워지지?"를 막는다 |
| USER-PI-63 | 유비쿼터스 | THE 시스템 SHALL 가입 응답 본문을 바꾸지 않는다 | 성공 응답은 종전대로 201 `true`(raw Boolean). 이미지 EP를 응답에 싣지 않는다 |

### F. 조회 — `GET /api/users/me`

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-PI-65 | 유비쿼터스 | THE 시스템 SHALL `/me` 응답에 `profileImgUrl` 필드를 포함한다 | `data`에 키 `profileImgUrl`이 항상 존재(값이 없어도 키는 있다) |
| USER-PI-66 | 예외 | IF 그 계정에 프로필 이미지가 없으면, THEN THE 시스템 SHALL `profileImgUrl`을 `null`로 반환한다 | 이미지 없는 계정 → 200 + `"profileImgUrl": null`. 빈 문자열도 기본 이미지 URL도 아니다(`supportTeam: null` 선례와 같은 방식) |
| USER-PI-67 | 유비쿼터스 | THE 시스템 SHALL `/me`의 `profileImgUrl`을 BaseURL 없는 EP로 반환한다 | 값이 `user-profile-img/…`로 시작(업로드 응답과 문자 그대로 같은 형태) |
| USER-PI-68 | 유비쿼터스 | THE 시스템 SHALL 이 필드 추가로 `/me`의 SELECT 횟수를 늘리지 않는다 | `show-sql` 실측 5회 유지(기존 계약 USER-ME-22) — 값이 이미 조회하는 `users_account` 행에 있으므로 추가 조회가 없다 |
| USER-PI-69 | 유비쿼터스 | THE 시스템 SHALL `/me`의 나머지 필드를 바꾸지 않는다 | `nickname`·`supportTeam`·`supportPlayers`·`point`·`bqScore`의 이름·형태·안전망 동작 불변(`me-profile.md` 계약 유지) |

### G. 기존 이미지 삭제 — 변경 시 · 탈퇴 시

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-PI-70 | 이벤트 | WHEN 인증 업로드로 `profile_img_url`이 갱신되면, THE 시스템 SHALL 직전 EP가 가리키던 객체를 삭제한다 | 2회 업로드 후 첫 EP를 HeadObject → 404, 두 번째 EP → 200 |
| USER-PI-71 | 예외 | IF 직전 값이 null이면, THEN THE 시스템 SHALL 삭제를 시도하지 않는다 | 첫 업로드 회차의 S3 DeleteObject 호출 0회(오류·경고 로그도 없다) |
| USER-PI-72 | 예외 | IF 직전 객체 삭제가 실패하면, THEN THE 시스템 SHALL 업로드 요청을 200으로 성공시킨다 | 삭제만 실패하게 만든 상태에서 업로드 → 200, `profile_img_url`은 새 EP, 옛 객체는 고아로 잔존 |
| USER-PI-73 | 예외 | IF 직전 객체 삭제가 실패하면, THEN THE 시스템 SHALL ERROR 로그를 남긴다 | 실패한 EP가 로그에 남는다. **재시도·보류 큐는 만들지 않는다**(회수는 수동) |
| USER-PI-74 | 이벤트 | WHEN 회원 탈퇴가 성공하면, THE 시스템 SHALL 그 계정의 프로필 이미지 객체를 삭제한다 | `DELETE /api/users/me` → 204, 그 계정의 EP를 HeadObject → **404**. **삭제는 탈퇴 요청 시점이다**(30일 뒤 하드 삭제 회차가 아니다 — 결정 기록 7) |
| USER-PI-75 | 유비쿼터스 | THE 시스템 SHALL 탈퇴 시 `profile_img_url` 컬럼 값을 비우지 않는다 | 탈퇴 후 그 행의 `profile_img_url`이 여전히 옛 EP. **탈퇴는 soft delete라 행이 남으므로 컬럼도 남는다** — 그 계정은 어떤 응답에도 노출되지 않아 값의 잔존이 관측되지 않고, 30일 뒤 하드 삭제로 행과 함께 사라진다(가리키는 객체는 이미 없다) |
| USER-PI-76 | 예외 | IF 탈퇴 시 객체 삭제가 실패하면, THEN THE 시스템 SHALL 탈퇴를 204로 성공시킨다 | S3를 끊은 상태에서 탈퇴 → 204, `exit_at` 기록·refresh 토큰 만료는 그대로 수행(**best-effort** — 이미지 하나 때문에 탈퇴가 막히면 안 된다) |
| USER-PI-77 | 유비쿼터스 | THE 시스템 SHALL 이미지 객체 삭제 대상을 `user-profile-img/` 접두 값으로 한정한다 | 컬럼에 `temp/…`나 다른 접두 값이 들어 있으면 DeleteObject를 호출하지 않는다(이미지 변경 경로·탈퇴 경로 공통. 잘못된 값 하나로 남의 객체를 지우지 못하게 하는 안전장치) |
| USER-PI-78 | 유비쿼터스 | THE 시스템 SHALL 만료 데이터 정리(탈퇴 30일 경과 하드 삭제)에서 프로필 이미지 객체를 삭제하지 않는다 | `expired-data-cleanup.md`(USER-EDC-*) 계약 **불변** — 그 배치는 종전대로 **S3를 호출하지 않는 순수 DB 작업**이고 `ExpiredAccountEraser`의 순서 고정 계약(①취소 좋아요 → ②소유권 이관 → ③`users` 삭제)에 네 번째 단계가 붙지 않는다. 이미지 삭제는 탈퇴 시점 1회뿐(USER-PI-74) |
| USER-PI-79 | 예외 | IF 탈퇴 시 객체 삭제가 실패하면, THEN THE 시스템 SHALL ERROR 로그를 남긴다 | 실패한 EP가 로그에 남는다. **재시도·보류 큐는 만들지 않으며** 남은 객체는 고아가 된다(알려진 한계 1). USER-PI-73(이미지 변경 경로)과 같은 처리다 |
| USER-PI-108 ~ USER-PI-114 | — | **(삭제됨 — 2026-08-20)** 만료 데이터 정리 배치가 계정 하드 삭제 시 S3 객체를 지우게 하던 요구사항 7건. 탈퇴 즉시 삭제(USER-PI-74)로 되돌리면서 존재 이유가 사라졌다 — **그 배치는 종전대로 S3를 호출하지 않는다**(USER-PI-78). 번호는 재사용하지 않는다 | — |

### H. `temp/` 정리 — 앱 스케줄러(1차) + S3 라이프사이클(2차)

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-PI-80 | 이벤트 | WHEN Asia/Seoul 기준 매일 04:00이 되면, THE 시스템 SHALL `temp/` 정리를 1회 실행한다 | 앱이 떠 있는 상태에서 KST 04:00 도달 → 시작 로그 1건, 하루 1회. **기존 만료 데이터 정리(03:00)와 한 시간 띄운다**(같은 시각에 두면 락·부하가 겹친다) |
| USER-PI-81 | 유비쿼터스 | THE 시스템 SHALL 정리 대상을 `temp/` 접두 객체로 한정한다 | 회차 중 ListObjectsV2의 prefix가 `temp/` |
| USER-PI-82 | 유비쿼터스 | THE 시스템 SHALL `마지막 수정 시각 + 24시간 <= 기준 시각`인 객체만 삭제한다 | 25시간 전 객체는 삭제, 23시간 전 객체는 잔존. **가입 화면을 열어 둔 사용자의 이미지가 회차 도중 사라지지 않게 하는 여유가 이 값의 목적이다** |
| USER-PI-83 | 유비쿼터스 | THE 시스템 SHALL 기준 시각을 `global.config.ClockConfig`의 `Clock` 빈에서 한 번만 읽어 회차 전체에 사용한다 | `Clock.fixed`로 UTC 19:00(=KST 익일 04:00)을 고정했을 때 판정이 KST 벽시계와 일치. `Instant.now()`·`LocalDateTime.now()` 직접 사용 금지(기존 `Clock` 단일 출처 계약) |
| USER-PI-84 | 유비쿼터스 | THE 시스템 SHALL `user-profile-img/` 접두 객체를 삭제하지 않는다 | 1년 전에 만들어진 영구 경로 객체가 회차 100번 뒤에도 잔존 |
| USER-PI-85 | 예외 | IF 어떤 객체의 삭제가 실패하면, THEN THE 시스템 SHALL 그 객체만 건너뛰고 회차를 계속한다 | 대상 3건 중 2번째 실패 → 1·3번째는 삭제되고 회차는 정상 종료(기존 `ExpiredDataCleanupService`의 계정 단위 격리와 같은 방식) |
| USER-PI-86 | 예외 | IF 예정 시각에 앱이 기동돼 있지 않았으면, THEN THE 시스템 SHALL 그 회차를 보정 실행하지 않는다 | 04:30에 기동해도 즉시 실행되지 않음. 대상 객체는 다음 날 회차에 그대로 잡힌다(유실 없음 — 못 돌아도 라이프사이클이 받아 준다, USER-PI-93) |
| USER-PI-87 | 선택 | WHERE 실행 스위치 `user.cleanup.temp-profile-image.enabled`가 켜진 경우에만, THE 시스템 SHALL 이 정리를 실행한다 | 기본 `false`, prod 프로파일만 `true`(기존 `expired-data`와 같은 구조·같은 이유 — 로컬 bootRun이 원격 dev 자원을 본다) |
| USER-PI-88 | 선택 | WHERE cron 설정이 주어지면, THE 시스템 SHALL 그 값으로 스케줄을 구성한다 | 미지정 시 기본 `0 0 4 * * *` + `zone = "Asia/Seoul"`(파드 TZ와 무관). **시각과 존이 코드에 하드코딩되지 않는 것이 계약이고 키 이름은 구현자 재량** |
| USER-PI-89 | 유비쿼터스 | THE 시스템 SHALL 이 스케줄러의 실행 여부가 `user.cleanup.expired-data.enabled`에 좌우되지 않게 한다 | `expired-data.enabled=false` + `temp-profile-image.enabled=true`로 기동 → 04:00에 정리 회차 1건 실행, 03:00 만료 데이터 정리는 0건(해소 방침 1) |
| USER-PI-90 | 유비쿼터스 | THE 시스템 SHALL 파드가 여러 개인 환경에서도 한 회차의 정리가 중복 실행되지 않게 한다 | 파드 2개로 KST 04:00 통과 → 회차 시작 로그 1건(기존 `CleanupExecutionLock`(Redis, TTL 30분) 재사용. 삭제는 멱등이라 겹쳐도 데이터 피해는 없지만 List/Delete 호출이 2배가 된다) |
| USER-PI-91 | 이벤트 | WHEN 회차가 끝나면, THE 시스템 SHALL 삭제 건수를 로그로 남긴다 | 결과 로그에 삭제 객체 수(정수)가 포함. 0건이어도 ERROR가 아니라 정상 종료 |
| USER-PI-92 | 유비쿼터스 | THE 시스템 SHALL 삭제 전에 DB의 `profile_img_url` 참조 여부를 확인하지 않는다 | `temp/` EP는 어떤 계정에도 저장되지 않으므로(USER-PI-53) 참조가 존재할 수 없다. 확인 조회를 넣으면 매 회차 전체 스캔이 붙는다 |
| USER-PI-93 | 유비쿼터스 | THE 시스템 SHALL 앱 스케줄러와 무관하게 `temp/` 객체가 1일 뒤 만료되는 안전망을 갖는다 | 버킷에 `temp/` 접두 1일 만료 라이프사이클 규칙이 존재. **스케줄러를 며칠 꺼 두거나 앱이 계속 죽어 있어도 `temp/`가 무한히 쌓이지 않는다**(1차=스케줄러, 2차=라이프사이클. 규칙 생성은 infra 소관 — 선행 조건 5) |
| USER-PI-94 | 유비쿼터스 | THE 시스템 SHALL `user-profile-img/`에 만료 라이프사이클 규칙을 두지 않는다 | 버킷 라이프사이클 규칙의 접두사 목록에 `user-profile-img/`가 없다. **여기에 규칙이 걸리면 살아 있는 계정의 프로필 사진이 조용히 사라진다** |

### I. 저장 스키마 — `users_account.profile_img_url`

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-PI-95 | 유비쿼터스 | THE 시스템 SHALL `users_account`에 `profile_img_url` 컬럼을 둔다 | `VARCHAR(255)`, `NULL` 허용, `DEFAULT NULL` |
| USER-PI-96 | 유비쿼터스 | THE 시스템 SHALL 기존 계정의 이 컬럼을 NULL로 둔다 | 배포 후 기존 계정 전부 `profile_img_url IS NULL`(백필 없음). 기존 사용자에게 관측되는 변화는 `/me`에 `profileImgUrl: null`이 늘어난 것뿐 |
| USER-PI-97 | 유비쿼터스 | THE 시스템 SHALL 이 컬럼에 EP만 저장한다 | 저장 값이 `user-profile-img/`로 시작하고 `https://`·`victoryfairy.com`·버킷명·선행 슬래시를 포함하지 않는다. **BaseURL(CloudFront 도메인)이 바뀌어도 DB를 마이그레이션할 필요가 없다는 것이 EP 저장의 목적이다** |
| USER-PI-98 | 유비쿼터스 | THE 시스템 SHALL 이 컬럼에 UNIQUE·인덱스·FK를 두지 않는다 | 조회 조건으로 쓰이지 않는 표시용 값이다 |
| USER-PI-99 | 유비쿼터스 | THE 시스템 SHALL 컬럼 추가 마이그레이션을 `infra/sql`에 파일로 두고 **앱 배포보다 먼저** 적용한다 | `infra/sql/`에 `ALTER TABLE users_account ADD COLUMN profile_img_url VARCHAR(255) NULL;`을 담은 스크립트가 존재하고, 배포 순서가 **①SQL 적용 → ②user·quiz 배포**로 문서화돼 있다. ⚠ **user는 `ddl-auto=update`라 빠뜨려도 스스로 컬럼을 만들어 멀쩡해 보이지만, `ddl-auto=none`인 quiz는 공유 엔티티(`UserAccount`)를 공유 필터(`JwtAuthenticationFilter`)로 매핑하는 순간 인증 요청이 전부 500이 된다** — user만 확인해서는 못 잡는다(해소 방침 2) |

### J. 보안 · SecurityConfig

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-PI-100 | 유비쿼터스 | THE 시스템 SHALL 비인증 업로드 경로가 인증 없이 통과하게 한다 | 경로 `/auth/profile-image`는 기존 `.requestMatchers("/auth/**").permitAll()`에 자연히 걸려 **`SecurityConfig` 수정이 불필요**하다. ⚠ 경로를 `/users/**` 아래로 옮기면 `anyRequest().authenticated()`에 걸려 401이고, 여는 줄을 추가하는 순간 **이 저장소 최초의 비-GET `permitAll`**이 된다(기존 공개 줄은 전부 `HttpMethod.GET` 한정) |
| USER-PI-101 | 유비쿼터스 | THE 시스템 SHALL 인증 업로드 경로에 인증을 요구한다 | `/users/me/profile-image`는 `anyRequest().authenticated()`에 자연히 걸린다 — **`SecurityConfig`에 `permitAll` 줄을 추가하면 그것이 버그다**(`/games/support` 선례) |
| USER-PI-102 | 유비쿼터스 | THE 시스템 SHALL 이 기능으로 인증 없이 쓰기가 가능한 경로를 1개만 늘린다 | 신설 경로 2개 중 permitAll은 정확히 1개 |
| USER-PI-103 | 유비쿼터스 | THE 시스템 SHALL 비인증 업로드로 어떤 DB 행도 만들지 않는다 | 임시 업로드 100회 후 `users`·`users_account`·`users_bq` 행 수 불변. 남는 것은 S3 객체와 Redis 카운터 키뿐 |
| USER-PI-104 | 유비쿼터스 | THE 시스템 SHALL EP에 추측 가능한 값을 넣지 않는다 | 파일명이 UUID v4(USER-PI-42)라 순번·이메일·계정 uid로 남의 EP를 유도할 수 없다. **버킷은 프라이빗이고 ListBucket 권한도 앱만 갖지만, CDN으로 열린 두 경로(`/temp/*`·`/user-profile-img/*`)에서 EP는 사실상의 접근 토큰이다**(알려진 한계 2) |
| USER-PI-105 | 유비쿼터스 | THE 시스템 SHALL 응답·로그에 버킷명·자격증명·계정 `uid`를 싣지 않는다 | 성공·실패 응답 본문과 로그를 `victoryfairy-asset`·`AKIA`·uid로 grep해 0건(기존 "uid를 응답에 노출하지 않는다" 정책의 연장) |
| USER-PI-106 | 유비쿼터스 | THE 시스템 SHALL `appId` 외의 식별자로 임시 업로드를 추가 제한하지 않는다 | IP·User-Agent·전역 한도 없음. **즉 클라이언트가 `appId`를 새로 만들면 10회가 다시 열리고, 기다리기만 해도 30분 뒤 열린다**(USER-PI-30). 이 한도는 남용을 막는 장치가 아니라 정상 사용자의 반복 시도를 막는 장치다(알려진 한계 3). 실제 방어는 버킷 용량 알람·WAF 등 infra 몫 |
| USER-PI-107 | 유비쿼터스 | THE 시스템 SHALL 업로드 크기 초과 처리를 `web-support`의 공유 `GlobalExceptionHandler`에서 수행한다 | user(8080)와 **quiz(8081) 양쪽**에서 크기 초과가 래퍼 있는 413으로 나간다. ⚠ 공유 컴포넌트라 **quiz에도 자동으로 적용된다** — quiz에는 현재 업로드 경로가 없어 관측되는 변화가 없지만, 이후 quiz에 업로드가 생기면 같은 계약을 물려받는다(해소 방침 3) |
| USER-PI-115 | — | **(삭제됨 — 2026-08-20)** 탈퇴 후 최대 30일간 이미지가 계속 읽힌다던 요구사항. 탈퇴 시점에 즉시 지우므로 더 이상 참이 아니다(USER-PI-74). 번호는 재사용하지 않는다 | — |

---

## 해소 방침 (초안이 "기존 정책과의 충돌"로 올렸던 3건 — 전부 해소하기로 확정)

1. **`@EnableScheduling`과 개별 잡 on/off를 분리한다.** 현행 `cleanup.config.CleanupSchedulingConfig`는 `@ConditionalOnProperty(user.cleanup.expired-data.enabled=true)`로 **스케줄링 활성화 자체**를 켠다. 그대로 두면 `temp` 정리만 켠 환경에서 스케줄러 빈이 만들어지지 않고 **에러도 없이 조용히 안 돈다**. 스케줄링 활성화 조건과 잡별 실행 조건을 갈라, `expired-data`·`temp-profile-image` 중 **어느 하나라도 켜져 있으면 스케줄링이 활성화**되고 각 잡은 자기 스위치로만 켜지게 한다(USER-PI-89). ⚠ **기존 만료 데이터 정리 배치의 동작은 한 글자도 바뀌면 안 된다** — `expired-data.enabled=true` 단독 환경에서 03:00 회차가 종전과 동일하게 도는 것이 회귀 기준이다(`ExpiredDataCleanupSchedulerTest` 7건 그린 유지).
2. **`profile_img_url` DDL을 배포 전에 선투입한다.** `infra/sql`에 마이그레이션 스크립트를 추가하고 배포 순서를 **①SQL → ②앱**으로 고정한다(USER-PI-99). 이유를 계약으로 박아 둔다: **user는 `ddl-auto=update`라 스스로 컬럼을 만들어 멀쩡해 보이지만, `ddl-auto=none`인 quiz는 공유 엔티티 `UserAccount`를 공유 필터로 매핑하는 순간 모든 인증 요청이 500**이 된다. `nickname_changed_at`·`password_changed_epoch_second`에서 이미 두 번 밟은 자리다.
3. **업로드 크기 초과 예외를 공유 `GlobalExceptionHandler`가 413으로 처리한다.** 현재 이 핸들러는 `BusinessException`·`MethodArgumentNotValidException`·`MissingServletRequestParameterException` 셋만 잡아, 크기 초과는 래퍼 없는 500이 된다(`ConstraintViolationException`이 400 대신 500이던 것과 같은 계열). `web-support`에 핸들러를 추가해 USER-PI-40·39를 만족시키며, **공유 컴포넌트라 quiz에도 함께 적용된다**(USER-PI-107).

### 그 밖에 구현이 지켜야 할 기존 컨벤션 (충돌은 아니지만 밟기 쉬운 자리)
- **`SignupRequest`에 검증 애노테이션을 겹쳐 걸지 말 것.** 위반이 2개 이상이면 `GlobalExceptionHandler`가 `Map#put` 순서 비보장으로 메시지가 비결정적이 된다. `profileImgUrl`도 판정 주체를 한 곳으로 둘 것(`PasswordPolicy`·`NicknamePolicy` 방식).
- **계정 행 락 트랜잭션 안에서 외부 I/O를 하지 말 것.** 모듈 컨텍스트에 "이 트랜잭션 안에 외부 호출 등 오래 걸리는 작업을 넣지 말 것"이 명시돼 있다(FK 검사가 부모 행에 공유 락을 잡아 그 계정의 자식 테이블 쓰기 전반이 대기한다). 프로필 이미지 갱신이 `findWithLockById`를 쓰면서 S3 호출을 트랜잭션 안에 넣으면 **로그인(`users_refreshtoken` INSERT)까지 대기**할 수 있다.
- **Redis는 기존 `StringRedisTemplate`을 재사용한다**(새 인스턴스·새 의존 없음). 키는 기존 접두사 규약(`기능:용도:{식별자}`)을 따른다.
- **`UserAccountResponse`는 `/me` 전용이라 필드 추가가 파괴적이지 않다.** 반면 `PlayerResponse`·`TeamResponse`처럼 여러 곳이 공유하는 DTO는 건드리지 말 것.
- **작업 트리에 이전 시도의 잔재가 있다.** `user/build/classes/.../account/{config,controller,dto,service}/ProfileImage*.class`가 남아 있으나 **대응하는 소스는 저장소에 없다**. 그 잔재의 설계(경로 `temp/profiles/`·`profiles/`, `PendingProfileImageDeletion` 테이블, ImageIO 재인코딩)는 **이 문서의 계약과 다르다** — 참고 자료로 삼되 계약으로 삼지 말 것. `./gradlew clean`으로 지워도 무방하다.

## 알려진 한계 (설계상 받아들이는 것 — 지금 고치지 않는다)
1. **고아 객체가 생길 수 있다.** ①가입 이동 성공 후 커밋 실패(USER-PI-62) ②이미지 변경 시 옛 객체 삭제 실패(USER-PI-72·73) ③탈퇴 시 객체 삭제 실패(USER-PI-76·79). 셋 다 `user-profile-img/` 경로라 스케줄러·라이프사이클 대상이 아니며 자동 회수 경로가 없다. S3 저장 비용이 작아 감수하는 선택이다.
2. **`temp/`가 CDN으로 열려 있어, 링크를 아는 사람은 가입 전 이미지를 읽을 수 있다.** 버킷 자체는 프라이빗(BPA 4종 on, OAC만 읽음)이고 키가 UUID v4라 **열거는 불가능**하지만, `https://victoryfairy.com/temp/{uuid}.jpg`를 아는 사람은 인증 없이 그 이미지를 본다. 이는 **가입 전 미리보기를 위해 의식적으로 연 것**이다(결정 기록 1). 같은 성질이 `user-profile-img/`에도 있으며, 유출된 EP를 취소할 방법은 이미지를 교체해 옛 객체를 지우는 것(USER-PI-70)뿐이다.
3. **비인증 업로드 한도는 우회 가능하다.** `appId`를 클라이언트가 발급하므로 새 값을 만들면 카운터가 초기화되고, 고정 창이라 30분만 기다려도 초기화된다(USER-PI-30·106). 서버 단독으로 막을 수 없는 성질이며, 실효 방어는 인프라 레벨(버킷 용량 알람·WAF rate limit)이다. 반대 방향의 작은 한계도 하나 있다 — **환불(USER-PI-117)이 실패하면 그 슬롯 1개는 창이 만료될 때까지 소모된 채 남는다**(재시도하지 않는다, USER-PI-121).
4. **S3 호출 실패 응답은 `ApiResponse` 래퍼가 아니다**(USER-PI-6). 크기 초과(USER-PI-40·107)만 명시적으로 래퍼를 요구하고, 나머지 인프라 실패는 기존 500 경로를 따른다.
5. **이동은 원자적이지 않다.** S3 copy+delete와 DB 커밋을 하나의 트랜잭션으로 묶을 수단이 없다. USER-PI-57·62가 그 경계에서 무엇이 참인지를 고정할 뿐, 중간 상태 자체를 없애지는 못한다.
6. **Redis가 죽으면 가입 전 이미지 등록이 막힌다**(USER-PI-33, fail-closed). 다만 그때는 이메일 인증 코드도 못 읽어 **어차피 가입 자체가 불가능**하므로 업로드만 열어 둘 실익이 없다는 판단이다(결정 기록 8).

## 신규 `ErrorCode` (`:common`)
| 코드 | 상태 | 메시지 | 쓰이는 곳 |
|---|---|---|---|
| `PROFILE_IMAGE_REQUIRED` | 400 | 프로필 이미지를 첨부해 주세요. | USER-PI-5 |
| `INVALID_PROFILE_IMAGE_FORMAT` | 400 | JPG, PNG, WEBP 이미지만 업로드할 수 있습니다. | USER-PI-36·37 |
| `PROFILE_IMAGE_TOO_LARGE` | **413** | 이미지 크기는 5MB를 넘을 수 없습니다. | USER-PI-39 (**이 저장소 최초의 413**) |
| `INVALID_PROFILE_IMAGE_ENDPOINT` | 400 | 유효하지 않은 프로필 이미지입니다. | USER-PI-55·56·60·61 (네 사유를 한 문구로 합친다 — 존재 여부를 알려 주면 EP 열거가 가능해진다) |
| `INVALID_APP_ID` | 400 | 앱 식별자가 필요합니다. | USER-PI-23 |
| `PROFILE_IMAGE_UPLOAD_LIMIT_EXCEEDED` | **429** | 이미지 등록 횟수를 초과했습니다. 잠시 후 다시 시도해 주세요. | USER-PI-27 (**이 모듈 세 번째 429** — 앞선 둘은 `EMAIL_SEND_COOLDOWN`·`NICKNAME_CHANGE_COOLDOWN`. 30분 뒤 풀리므로 "잠시 후"가 사실이다) |

## 배포 전제
1. `infra/sql`의 마이그레이션 `ALTER TABLE users_account ADD COLUMN profile_img_url VARCHAR(255) NULL;` — **앱 배포보다 먼저**(해소 방침 2). 순서를 뒤집으면 quiz 인증 요청이 전부 500
2. 버킷 `victoryfairy-asset` 생성(BPA on, OAC 읽기) + user 앱 쓰기 권한 — **앱 배포보다 먼저**(없으면 두 업로드 경로가 전부 5xx)
3. 기존 CloudFront 배포에 오리진 추가 + 경로 패턴 `/user-profile-img/*`·`/temp/*` — 앱 배포와 순서 무관하나, 없으면 업로드는 되고 **읽기만 404**가 된다
4. `temp/` 접두 1일 만료 라이프사이클 규칙(USER-PI-93)
5. 백필 없음(USER-PI-96)

## 결정 기록 (2026-08-20, 사용자 확정)
1. **BaseURL·공개 방식** — 신규 CloudFront 배포를 만들지 않고 **`modules/cdn`의 기존 배포에 `victoryfairy-asset`을 두 번째 오리진으로 추가**한다. 경로 패턴은 `/user-profile-img/*`·`/temp/*` 둘뿐이고 버킷은 프라이빗(BPA 4종 on, OAC만 읽음). **BaseURL은 `https://victoryfairy.com`**이며 EP를 그대로 이어 붙이면 된다. **`temp/`를 연 것은 가입 전 미리보기 때문**이고 그 대가(링크를 아는 사람은 읽을 수 있음)는 알려진 한계 2로 명시한다. 업로드는 파드가 SDK로 S3에 직접 쓰며 CDN·ALB를 타지 않는다.
2. **인증 업로드 = 변경 확정** — 엔드포인트 1개, 업로드가 곧 변경이며 취소 단계를 두지 않는다. 초안의 B안(별도 `PATCH`로 확정)은 채택하지 않았다.
3. **가입 중 이동 실패 → 가입 성공 + 컬럼 null** — 근거는 **이메일 인증 소비가 Redis라 DB 롤백으로 되돌아오지 않는다**는 것이다. 가입을 실패시키면 사용자가 이메일 인증부터 다시 하도록 강요당한다(USER-PI-57).
4. **형식·크기** — JPEG/PNG/WebP · 5MiB · 초과 시 413 · 매직 넘버 판정 · 파일명 UUID v4. **HEIC는 허용 목록에 넣지 않으며, 앱(RN)이 변환해 보내는 것이 전제**다.
5. **`appId` 한도** — 10회는 유지하되 **TTL은 30분이고 고정 창**이다: `INCR` 후 **키가 새로 만들어질 때만** TTL을 걸고 업로드마다 갱신하지 않는다. 갱신형(sliding)으로 구현하면 **30분 안에 계속 올리는 사용자가 영구히 갇히거나**, 반대 방향으로 잘못 구현하면 창이 계속 밀려 **사실상 무한 업로드**가 된다. 30분이 지나면 카운터가 사라져 다시 10회가 열린다. `appId` 형식 검증은 하지 않는다. 키 이름은 기존 접두사 규약을 따른다.
6. **temp 보존·정리** — 24시간 경과분을 매일 **04:00 KST** 삭제(기존 만료 데이터 정리 03:00과 한 시간 띄움). **추가로 S3 라이프사이클(`temp/` 1일 만료)을 2차 안전망으로 건다** — 스케줄러가 며칠 못 돌아도 쓰레기가 무한 축적되지 않게 하는 이중화이며, 둘 다 `user-profile-img/`는 건드리지 않는다.
7. **탈퇴 시 삭제 시점 — 탈퇴 즉시 삭제(최종)**: `DELETE /api/users/me` 처리 중에 S3 객체를 지운다. 한때 "30일 뒤 계정 하드 삭제 시점"으로 옮기는 안을 검토했으나 **되돌렸다.** 이유는 셋이다. ①**만료 데이터 정리 배치를 순수 DB 작업으로 유지**하기 위해서다 — 그 배치에 S3 호출이 붙으면 트랜잭션 경계·실패 모드·개인정보 범위 서술이 전부 흔들려 **`expired-data-cleanup.md`(승인됨, USER-EDC-1~50)를 함께 개정해야 한다.** 즉시 삭제를 고르면 **그 문서는 개정 대상이 아니고 한 글자도 바뀌지 않는다**(최소 수정). ②**삭제된 계정을 30일 안에 되살리는 복구 기능이 없다** — 미루어서 지키는 것이 없다. ③탈퇴 후 이미지가 남아 URL을 아는 사람에게 계속 읽히는 창(최대 30일)이 아예 생기지 않는다. 삭제는 **best-effort**이며 실패해도 탈퇴는 204로 성공하고 ERROR 로그만 남긴다(USER-PI-76·79, 재시도 큐 없음). **`profile_img_url` 컬럼은 비우지 않는다** — 탈퇴가 soft delete라 행이 남고, 그 계정은 어떤 응답에도 노출되지 않으며 30일 뒤 행과 함께 사라진다(USER-PI-75).
8. **Redis 장애 시** — fail-closed(거절). 근거: Redis가 죽으면 이메일 인증 코드도 못 읽어 **어차피 가입이 불가능**하므로 업로드만 열어 둘 실익이 없고, 열면 그동안 한도 없이 S3에 쌓인다.
9. **한도 카운터의 실패 처리 — 환불(2026-08-20 런타임 검증 후 추가)**: 검증에서 **`카운터 증가 → S3 저장` 순서 때문에 저장이 실패해도 슬롯이 소모되는** 동작이 발견됐다(S3를 끊고 10회 실패시켰더니 객체는 0개인데 11번째가 429). 선택지는 셋이었다. **A: 그대로 둔다** — 실패도 시도로 치지만, 서버 장애로 사용자가 가입 화면에서 막히는 결과라 기각. **B(채택): 저장이 실패하면 카운터를 되돌린다(환불)** — 증가 순서는 그대로여서 동시성 보호를 잃지 않고, 소모된 슬롯만 복구된다. **C: 저장에 성공한 뒤 증가한다** — 가장 단순해 보이지만 **원자적 `INCR`이 게이트 역할을 못 하게 되어 동시 요청 둘이 함께 통과해 한도가 뚫린다**(그래서 버렸다, USER-PI-116). 환불의 경계 둘을 함께 고정했다: **TTL은 건드리지 않고**(고정 창 유지, USER-PI-118), **키가 이미 만료됐으면 아무 것도 하지 않는다**(USER-PI-119 — `DECR`가 없는 키를 TTL 없는 `-1`로 만들어 그 `appId`를 무제한으로 열어 버리기 때문이다). 환불 자체는 best-effort이며 실패해도 사용자에게는 원래의 저장 실패가 그대로 나간다(USER-PI-120·121).

## 미해결 질문
없음 — 초안의 8건은 2026-08-20 사용자 답변으로 전부 해소됐다(위 "결정 기록" 참조).
