# 커뮤니티(게시글·댓글·답글·좋아요/싫어요·이미지·조회수·신고·카테고리) 요구사항
> 상태: **승인됨 (2026-10-08, 개정 1차)** · 모듈: user(컨트롤러·서비스) + domain(엔티티·리포지토리) · 최종 수정: 2026-10-08
> ID 계열 `USER-CM-<n>`. 절마다 번호 블록을 띄워 뒀다(1~, 10~, 20~ …) — 빈 번호는 "삭제됨"이 아니라 예비 번호다. **번호는 재사용하지 않는다.**
> **2026-10-08 개정 1차 요약**: 초안의 미해결 질문 17건이 전부 결정됐다(하단 "결정 기록"). `(가정)` 표시는 이 문서에 없다. 바뀐 것은 하나 — **대댓글(1단 답글)이 범위에 들어왔다**(초안은 평면 댓글). 핵심 원칙은 사용자 원문 그대로 "**부모 삭제 시 CASCADE가 아닌, 삭제 처리는 개별로 처리**"이며, 블라인드도 같은 원리다. 신설 USER-CM-114~119·210~218, 수정 USER-CM-105·106·108·112·113·181·200. 댓글 조회수는 요청서에 있었으나 **없는 것으로 확정**(Q3).

## 배경 / 목적
지금 이 서비스에서 사용자가 글을 남기는 곳은 구단별 채팅(quiz 모듈, SSE)뿐이다 — 휘발성이고 구단 밖 사람은 볼 수 없다. 커뮤니티는 **비실시간·전 회원 공개**의 게시판이며, 채팅이 이미 확정한 정책(신고 즉시 blind·자기 신고 403·탈퇴자 콘텐츠 이관)과 프로필 이미지가 확정한 정책(S3 `victoryfairy-asset`, `temp/` 선업로드 → 확정 시 이동, EP는 BaseURL 없는 오브젝트 키)을 **그대로 물려받는 것**이 이 문서의 축이다. 새로 정하는 것은 넷이다 — 3상태 반응(좋아요/싫어요/없음), 5분 고정 창 조회수, 카테고리, 그리고 **부모와 자식의 운명을 묶지 않는 1단 답글**.

커뮤니티를 quiz(8081, `/rt`)가 아니라 **user 모듈(8080, `/api`)에 둔다**(결정 Q16 — 실시간 요소가 없고 S3·Redis·`Clock`·정리 배치가 전부 user에 있다).

## 선행 조건 / 인프라 의존 (이 문서의 범위 밖 — `VictoryFairy_Infra` 소관)
1. **CloudFront 경로 패턴 `/community/*` 추가** — 기존 배포(`https://victoryfairy.com`)에 `victoryfairy-asset` 오리진으로 라우팅되는 behavior가 지금은 `/temp/*`·`/user-profile-img/*`·캐릭터 3종뿐이다. 이게 없으면 확정된 게시글 이미지가 **저장은 되는데 읽히지 않는다**(캐릭터 에셋 때와 같은 구도 — `hwannee/infra/feat-character-asset-cdn` 선례).
2. **user 앱 IRSA 정책이 `community/` 접두 객체에 `PutObject`·`CopyObject`·`DeleteObject`·`HeadObject`를 허용하는지 확인** — 프로필 이미지용 정책이 버킷 전체인지 접두 한정인지는 이 저장소에서 확인되지 않는다. 접두 한정이면 추가가 필요하다.
3. **`temp/` 라이프사이클(1일 만료)과 04:00 정리 스케줄러는 그대로 재사용**한다 — 커뮤니티 임시 이미지도 같은 접두에 올리므로 새 규칙이 필요 없다(USER-CM-157).

## 범위
- 포함
  - 카테고리 코드 테이블 + 시드(구단 10 + 자유게시판) + 조회 API
  - 게시글 CRUD(작성·상세·전체 목록·인기 목록·내 글 목록·수정·삭제)
  - 댓글 CRUD(작성·목록·수정·삭제) + **1단 답글**(답글의 답글 없음) — 부모 삭제·블라인드가 자식에 연쇄하지 않는다
  - 게시글·댓글·답글 공통: 좋아요/싫어요(3상태, 계정당 1개, 취소 가능), 신고 → 즉시 블라인드, 이미지 복수 첨부
  - 게시글 조회수(계정 기준 5분 고정 창, Redis)
  - 탈퇴 계정 콘텐츠 처리(만료 데이터 정리와의 접점)
  - 신규 엔티티·테이블(domain) + 신규 `ErrorCode`(`:common`) + 배포 선행 조건
- 제외
  - **댓글·답글 조회수** — 요청서에는 있으나 단독으로 열리는 자원이 아니라 셀 사건이 없다. 응답에서 뺀다(결정 Q3)
  - **답글의 답글(깊이 2 이상)** — 깊이 1 고정(USER-CM-116)
  - **관리자 기능(블라인드 해제·강제 삭제·신고 열람 API)** — 채팅과 같은 범위 결정. 신고 행은 남기되(USER-CM-175) 읽는 API는 없다
  - **욕설 마스킹** — 채팅 필터(`chat-profanity-filter.md`)는 전송 경로 전용이며 커뮤니티에 적용하지 않는다. 필요하면 별도 요구사항
  - **게시글 검색(제목·본문 키워드)**, 해시태그, 북마크, 작성자별 글 보기(내 글 제외), 작성 횟수 제한
  - **이미지 리사이즈·썸네일 생성** — 원본 그대로 저장. 목록의 `thumbnailUrl`은 첫 이미지의 원본 EP다
  - **인기 게시글 캐시** — 요청마다 집계한다(알려진 한계 3)
  - **답글 페이징** — 한 댓글의 답글은 전부 한 번에 실린다(알려진 한계 8)
  - **알림(내 글에 댓글이 달림 등)**

## 용어
| 용어 | 뜻 |
|---|---|
| **요청자** | `@AuthenticationPrincipal Long userAccountId` — 토큰에서 해석된 계정. 본문·경로·쿼리로는 받지 않는다 |
| **작성자** | 게시글·댓글 행의 `user_account_id`가 가리키는 계정 |
| **댓글** | 게시글에 직접 달린 글(`parent_comment_id = NULL`, 최상위). 문맥상 답글까지 포함해 부를 때는 "댓글·답글"로 적는다 |
| **답글** | 댓글에 달린 글(`parent_comment_id`가 어떤 최상위 댓글을 가리킴). 답글에는 답글을 달 수 없다 |
| **부모** | 답글이 가리키는 최상위 댓글 |
| **자리 표식(placeholder)** | 삭제 또는 블라인드됐지만 보이는 답글이 남아 있어 목록에 `status: DELETED`·`BLINDED`로 남는 부모. 본문·이미지·작성자는 비어 있다 |
| **EP** | 버킷 안 오브젝트 키. BaseURL·스킴·버킷명 없음, 선행 슬래시 없음(`profile-image.md`와 동일). 예 `community/9f1c….webp` |
| **임시 EP** | `temp/{uuid}.{ext}` — 업로드 직후 상태. 게시글·댓글에 붙으며 `community/{uuid}.{ext}`(**확정 EP**)로 이동된다 |
| **반응(reaction)** | 한 계정이 한 게시글(또는 댓글·답글)에 남긴 `LIKE`·`DISLIKE` 중 하나. 없음(`NONE`)은 행 부재로 표현한다 |
| **블라인드** | 신고로 `blinded=true`가 된 상태. 행·본문·이미지는 보존되고 조회만 막힌다 |
| **삭제** | `deleted_at`이 채워진 소프트 삭제(결정 Q10). 어떤 응답에도 본문이 나오지 않는다 |
| **조회 창** | (게시글, 계정) 쌍에 대해 조회수 증가 직후 300초 동안 유지되는 Redis 키. 그 동안 같은 계정의 재조회는 세지 않는다 |
| **보이는 댓글·답글** | 삭제되지 않았고 블라인드되지 않은 댓글·답글. `commentCount`가 세는 대상이고 자리 표식은 아니다 |

## 엔드포인트 (전부 인증 필수 — `SecurityConfig` 무수정이 정답, `anyRequest().authenticated()`에 자연히 걸린다)

컨트롤러 `@RequestMapping`은 `/community/...`이고 `server.servlet.context-path`(`/api`)가 앞에 붙는다.

| 메서드 | 경로 | 요청 | 성공 | 절 |
|---|---|---|---|---|
| GET | `/api/community/categories` | 없음 | 200 `ApiResponse<List<CategoryResponse>>` | B |
| POST | `/api/community/posts` | `{categoryId, title, content, imageUrls[]}` | 201 `ApiResponse<{postId}>` | C |
| GET | `/api/community/posts` | `?categoryId=&sort=latest\|likes\|views&page=&size=` | 200 `ApiResponse<PageResponse<PostSummary>>` | D |
| GET | `/api/community/posts/popular` | `?categoryId=` | 200 `ApiResponse<List<PostSummary>>` (최대 5) | D |
| GET | `/api/community/posts/me` | `?page=&size=` | 200 `ApiResponse<PageResponse<MyPostSummary>>` | D |
| GET | `/api/community/posts/{postId}` | 없음 | 200 `ApiResponse<PostDetail>` (**조회수 증가 부수효과**) | D·E |
| PUT | `/api/community/posts/{postId}` | `{categoryId, title, content, imageUrls[]}` (전체 교체) | 200 `ApiResponse<PostDetail>` | F |
| DELETE | `/api/community/posts/{postId}` | 없음 | 204 | F |
| PUT | `/api/community/posts/{postId}/reaction` | `{"reaction":"LIKE"\|"DISLIKE"\|"NONE"}` | 200 `ApiResponse<{myReaction, likeCount, dislikeCount}>` | H |
| POST | `/api/community/posts/{postId}/report` | 없음 | 200 `ApiResponse<Void>` | J |
| POST | `/api/community/posts/{postId}/comments` | `{content, imageUrls[], parentCommentId?}` — `parentCommentId`가 있으면 답글 | 201 `ApiResponse<{commentId}>` | G |
| GET | `/api/community/posts/{postId}/comments` | `?page=&size=` | 200 `ApiResponse<PageResponse<CommentResponse>>` (최상위 댓글 페이징, 답글은 항목 안 `replies[]`) | G |
| PUT | `/api/community/comments/{commentId}` | `{content, imageUrls[]}` (전체 교체, 댓글·답글 공용) | 200 `ApiResponse<CommentResponse\|ReplyResponse>` | G |
| DELETE | `/api/community/comments/{commentId}` | 없음 (댓글·답글 공용) | 204 | G |
| PUT | `/api/community/comments/{commentId}/reaction` | `{"reaction":…}` (댓글·답글 공용) | 200 `ApiResponse<{myReaction, likeCount, dislikeCount}>` | H |
| POST | `/api/community/comments/{commentId}/report` | 없음 (댓글·답글 공용) | 200 `ApiResponse<Void>` | J |
| POST | `/api/community/images` | multipart `image` 1개 | 200 `ApiResponse<{imageUrl}>` (`temp/…`) | I |

`/posts/popular`·`/posts/me`는 `/posts/{postId}`보다 구체적인 리터럴 매핑이라 `postId` 파싱에 걸리지 않는다. 답글 전용 경로는 없다 — 답글은 댓글과 같은 `/comments/{commentId}` 자원이고, 생성 시 `parentCommentId` 유무로만 갈린다.

### 응답 모양 (키 집합이 계약이다)
- `author`: `{nickname, profileImgUrl}` — 계정 `id`·`uid`는 싣지 않는다. `profileImgUrl`은 EP 또는 `null`(채팅 `MessageResponse`와 같은 규칙)
- `PostSummary`: `{postId, categoryId, categoryName, title, thumbnailUrl, author, viewCount, likeCount, dislikeCount, commentCount, createdAt}` — 본문 미포함(결정 Q13), `thumbnailUrl`은 첫 이미지 EP 또는 `null`
- `MyPostSummary`: `PostSummary` + `blinded`(boolean)
- `PostDetail`: `{postId, categoryId, categoryName, title, content, imageUrls[], author, viewCount, likeCount, dislikeCount, commentCount, myReaction, isAuthor, createdAt, updatedAt}` — `myReaction`은 `"LIKE"`·`"DISLIKE"`·`null`
- `ReplyResponse`: `{commentId, parentCommentId, status, content, imageUrls[], author, likeCount, dislikeCount, myReaction, isAuthor, createdAt, updatedAt}` — **`viewCount` 없음**(결정 Q3). `status`는 항상 `"VISIBLE"`(보이지 않는 답글은 목록에 싣지 않는다)
- `CommentResponse`: `ReplyResponse`의 키 + `replies: ReplyResponse[]`, `parentCommentId`는 항상 `null`. `status`는 `"VISIBLE"`·`"DELETED"`·`"BLINDED"` — 뒤 둘은 자리 표식이며 그때 `content: null`·`imageUrls: []`·`author: null`·`likeCount: 0`·`dislikeCount: 0`·`myReaction: null`·`isAuthor: false`·`updatedAt: null`이고 `createdAt`·`replies`만 산다(USER-CM-212·213)
- `CategoryResponse`: `{id, name, teamId}` — 자유게시판은 `teamId: null`
- `PageResponse`: `{content, page, size, totalElements, totalPages, hasNext}` — quiz 모듈 채팅 히스토리와 같은 키. (그 record는 quiz 모듈 소속이라 user에서 그대로 import할 수 없다 — 같은 키로 user에 두거나 `:common`으로 올리는 것은 구현 판단)

## 데이터 모델 — 계약으로 포함하는 제약 (이름은 제안, 제약은 요구사항)
테이블명·컬럼명은 `spring-dev` 판단이되 domain 컨벤션(복수형, `@OnDelete` 기준, 제약 이름 명시)을 따른다. 아래는 **관측 가능한 동작을 보장하기 위해 반드시 있어야 하는 제약**만 적는다.

| 대상 | 제약 | 지키는 요구사항 |
|---|---|---|
| 카테고리 | 코드 테이블(제안 `community_categories`): `name`, `team_id`(nullable, FK `teams`, `@OnDelete` 없음 — 마스터), `sort_order` | USER-CM-10~13 |
| 게시글 | `category_id`(FK, `@OnDelete` 없음), `user_account_id`(FK, **`@OnDelete` 없음** — 삭제 전 이관 규약, 채팅 `Chatroom.owner`와 같은 판단), `view_count`·`like_count`·`dislike_count`·`comment_count`(카운터, `@ColumnDefault("0")`), `blinded`(TINYINT), `deleted_at`(nullable), `created_at`/`updated_at` | USER-CM-21·130·190~191 |
| 댓글·답글 | 한 테이블. `post_id`(FK, CASCADE), **`parent_comment_id`(nullable, 자기 참조 FK, `@OnDelete(CASCADE)`)**, `user_account_id`(FK, `@OnDelete` 없음 — 게시글과 동일), `like_count`·`dislike_count`, `blinded`, `deleted_at`, 타임스탬프 2종. ⚠ 자기 참조 FK의 CASCADE는 **DB 단 하드 삭제(게시글 행 CASCADE 등) 때 자식이 부모 삭제를 막지 않게 하는 것**이지 앱 삭제의 연쇄가 아니다 — 앱 삭제는 `deleted_at`만 채우며 자식에 전파되지 않는다(USER-CM-210) | USER-CM-100~119·210~218 |
| 반응 | 게시글용·댓글용 각 1테이블(댓글용이 답글까지 담는다). `(user_account_id, post_id)` / `(user_account_id, comment_id)` **UNIQUE(이름 명시)**, `type`(ORDINAL `LIKE=0`/`DISLIKE=1`, 선언 순서 고정), `user_account_id`는 **nullable + `@OnDelete(SET_NULL)`**(`QuizLike` 선례) | USER-CM-123~125·131·192 |
| 이미지 | 게시글용·댓글용 각 1테이블. `endpoint VARCHAR(255)`, `sort_order`, 부모 FK CASCADE | USER-CM-61·147~153 |
| 신고 | 1테이블(제안 `community_reports`): `reporter_account_id`(nullable, SET_NULL), `target_type`(ORDINAL `POST=0`/`COMMENT=1`), `target_id`, `created_at`. `(reporter_account_id, target_type, target_id)` UNIQUE | USER-CM-175·193 |

카운터 컬럼을 두는 이유는 "좋아요 순·조회수 순 정렬"과 "인기 게시글 점수"가 전체 게시글을 대상으로 한 정렬이라서다 — 요청마다 반응 테이블을 GROUP BY해 정렬하면 비용이 게시글 수×반응 수로 자란다. `quiz-like.md`가 카운터 컬럼을 거부한 것과 다른 선택이며 그 이유는 "기존 정책과의 충돌" 1에 적었다. 카운터와 반응 행의 **일치는 요구사항**(USER-CM-130)이다.

## 요구사항 (EARS)

> 인수 기준 앞의 `AC-CM-<요구사항번호>-<n>`은 `test-writer`가 1:1로 대응시킬 식별자다. 응답 예시의 `message`는 `ErrorCode`의 문구이며 신규 코드 문구는 L절에 모아 뒀다. `(결정 Qn)`은 하단 "결정 기록"의 근거를 가리킨다.

### A. 공통 — 인증·식별·응답 형태 (USER-CM-1 ~ 8)

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-CM-1 | 유비쿼터스 | THE 시스템 SHALL `/api/community/**` 모든 경로에 유효한 access 토큰을 요구한다 (결정 Q15 — 카테고리·목록 조회도 회원 전용) | `AC-CM-1-1` 헤더 없이 `GET /api/community/categories` → 401 `{"success":false,"data":null,"message":"인증이 필요합니다."}`. `AC-CM-1-2` 17개 경로 전부 동일. `AC-CM-1-3` `SecurityConfig`에 `/community` 관련 `permitAll` 줄이 **없다**(있으면 버그 — `/games/support` 선례) |
| USER-CM-2 | 유비쿼터스 | THE 시스템 SHALL 요청자를 토큰에서만 식별한다 | `AC-CM-2-1` 어떤 요청 본문·경로·쿼리에도 `userAccountId`·`uid`를 받는 자리가 없다. 타인 명의로 글·반응·신고를 남길 입력 경로가 없다 |
| USER-CM-3 | 유비쿼터스 | THE 시스템 SHALL 204를 제외한 모든 성공 응답을 `ApiResponse<T>`로 감싼다 | `AC-CM-3-1` 201·200 응답 본문이 `{"success":true,"data":…,"message":null}`. `AC-CM-3-2` `DELETE` 2개는 본문 없는 204 |
| USER-CM-4 | 유비쿼터스 | THE 시스템 SHALL 작성자 정보를 `author{nickname, profileImgUrl}`로만 노출한다 | `AC-CM-4-1` 모든 응답 JSON을 `userAccountId`·`uid`·`accountId`로 grep → 0건. `AC-CM-4-2` `profileImgUrl`이 `https://`·`victoryfairy.com`·버킷명을 포함하지 않고 `/`로 시작하지 않으며, 이미지 없는 계정은 `null` |
| USER-CM-5 | 유비쿼터스 | THE 시스템 SHALL 게시글·댓글의 외부 식별자로 내부 PK(`id`) 숫자를 사용한다 (결정 Q14 — 채팅 메시지 `id` 노출 결정과 같은 판단) | `AC-CM-5-1` 생성 응답 `data.postId`·`data.commentId`가 JSON 정수. `AC-CM-5-2` 그 값이 `/posts/{postId}`·`/comments/{commentId}` 경로에 그대로 쓰인다. `AC-CM-5-3` 게시글·댓글 테이블에 `uid` 컬럼이 없다 |
| USER-CM-6 | 유비쿼터스 | THE 시스템 SHALL 페이징 응답을 `{content, page, size, totalElements, totalPages, hasNext}` 키로 반환한다 | `AC-CM-6-1` 전체 목록·내 글·댓글 목록 세 응답의 `data` 키 집합이 정확히 이 6개 |
| USER-CM-7 | 유비쿼터스 | THE 시스템 SHALL `page` 기본 0·`size` 기본 20으로 처리한다 | `AC-CM-7-1` 파라미터 없이 요청 → `data.page=0`, `data.size=20` |
| USER-CM-8 | 예외 | IF `page`가 0 미만이거나 `size`가 1 미만·50 초과면, THEN THE 시스템 SHALL 400을 반환한다 (결정 Q13 — 조용히 접지 않는다) | `AC-CM-8-1` `?size=51` → 400 `ApiResponse` 래퍼. `AC-CM-8-2` `?size=50` → 200. `AC-CM-8-3` `?page=-1` → 400. `AC-CM-8-4` `?page=abc` → 400 `"요청 파라미터 형식이 올바르지 않습니다: page"`(기존 `handleTypeMismatch`) |

### B. 카테고리 (USER-CM-10 ~ 13)

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-CM-10 | 유비쿼터스 | THE 시스템 SHALL 카테고리를 별도 코드 테이블로 관리한다 (결정 Q8 — `teams` FK nullable 방식 아님) | `AC-CM-10-1` 카테고리 테이블이 존재하고 구단 카테고리 행은 `team_id`가 `teams.id`를, 자유게시판 행은 `team_id = NULL`을 갖는다 |
| USER-CM-11 | 이벤트 | WHEN 카테고리 목록을 요청하면, THE 시스템 SHALL 전체 카테고리를 `sort_order` 오름차순 배열로 반환한다 | `AC-CM-11-1` `GET /api/community/categories` → 200, `data`가 11개 원소(구단 10 + 자유게시판). `AC-CM-11-2` 각 원소 키 `{id, name, teamId}`. `AC-CM-11-3` 자유게시판 원소 `teamId: null`. `AC-CM-11-4` 구단 원소 `name`이 `GET /api/teams`의 같은 `teamId` 구단 `name`과 동일 |
| USER-CM-12 | 유비쿼터스 | THE 시스템 SHALL 카테고리 시드를 앱 기동마다 재실행 안전하게 적용한다 | `AC-CM-12-1` `infra/sql/community-categories-init.sql`이 존재하고 user `spring.sql.init.data-locations`에 등록돼 있다. `AC-CM-12-2` 앱을 2회 기동해도 11행 그대로(중복 INSERT 없음 — `teams-init.sql`·`quiz-type-init.sql`과 같은 방식) |
| USER-CM-13 | 유비쿼터스 | THE 시스템 SHALL 카테고리 시드를 `teams` 시드보다 뒤에 적용한다 | `AC-CM-13-1` `data-locations` 순서에서 `community-categories-init.sql`이 `teams-init.sql` 뒤. 빈 DB 첫 기동에서 FK 위반 없이 11행 생성(구단 카테고리의 `team_id`는 `teams.code`로 해석해 채운다 — 환경마다 다른 AUTO_INCREMENT id를 박아 넣지 않는다, `DefaultCharacterPolicy`가 id 대신 이름을 쓰는 것과 같은 이유) |

### C. 게시글 작성 (USER-CM-20 ~ 30)

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-CM-20 | 이벤트 | WHEN 인증 사용자가 유효한 `categoryId`·`title`·`content`로 작성을 요청하면, THE 시스템 SHALL 게시글을 저장하고 201과 `postId`를 반환한다 | `AC-CM-20-1` `POST /api/community/posts` `{"categoryId":11,"title":"오늘 직관 후기","content":"9회말…","imageUrls":[]}` → 201 `{"success":true,"data":{"postId":1},"message":null}`. `AC-CM-20-2` 게시글 테이블 1행 증가, `user_account_id`가 요청자 |
| USER-CM-21 | 유비쿼터스 | THE 시스템 SHALL 새 게시글의 `viewCount`·`likeCount`·`dislikeCount`·`commentCount`를 0, `blinded`를 false, `deletedAt`을 null로 시작한다 | `AC-CM-21-1` 작성 직후 상세 조회 응답(작성자 본인이라 조회수 미증가, USER-CM-75)에서 네 카운트가 전부 0 |
| USER-CM-22 | 예외 | IF `title`이 null·빈 문자열·공백만이면, THEN THE 시스템 SHALL 400과 `data.title` 필드 메시지를 반환한다 | `AC-CM-22-1` `{"title":"   ",…}` → 400 `{"success":false,"data":{"title":"…"},"message":…}`(`GlobalExceptionHandler`의 `MethodArgumentNotValidException` 형식). `AC-CM-22-2` 행 미생성 |
| USER-CM-23 | 예외 | IF `content`가 null·빈 문자열·공백만이면, THEN THE 시스템 SHALL 400과 `data.content` 필드 메시지를 반환한다 | `AC-CM-23-1` `{"content":""}` → 400 `data.content` 존재. `AC-CM-23-2` 행 미생성 |
| USER-CM-24 | 예외 | IF `title` 길이가 100자를 넘으면, THEN THE 시스템 SHALL 400을 반환한다 (결정 Q13) | `AC-CM-24-1` 101자 → 400 `data.title`. `AC-CM-24-2` 100자 → 201. 길이는 `String.length()`(UTF-16 code unit, 채팅 `@Size`와 같은 기준) |
| USER-CM-25 | 예외 | IF `content` 길이가 5000자를 넘으면, THEN THE 시스템 SHALL 400을 반환한다 (결정 Q13) | `AC-CM-25-1` 5001자 → 400 `data.content`. `AC-CM-25-2` 5000자 → 201 |
| USER-CM-26 | 예외 | IF `categoryId`가 누락되면, THEN THE 시스템 SHALL 400과 `data.categoryId` 필드 메시지를 반환한다 | `AC-CM-26-1` 본문에 `categoryId` 키 없음 → 400 `data.categoryId` 존재. **카테고리는 필수 선택**이다 |
| USER-CM-27 | 예외 | IF `categoryId`가 존재하지 않는 카테고리면, THEN THE 시스템 SHALL 404 `COMMUNITY_CATEGORY_NOT_FOUND`를 반환한다 | `AC-CM-27-1` `{"categoryId":999}` → 404 `{"success":false,"data":null,"message":"존재하지 않는 카테고리입니다."}`. 조회 필터(USER-CM-47)와 달리 **대상 자원 지정**이라 404다(`POST /support/team`의 `TEAM_NOT_FOUND`와 같은 판단) |
| USER-CM-28 | 유비쿼터스 | THE 시스템 SHALL `title`·`content`를 받은 그대로 저장한다 | `AC-CM-28-1` 앞뒤 공백·줄바꿈이 포함된 본문이 상세 응답에서 바이트 단위로 동일(트리밍·HTML 이스케이프·마스킹 없음 — 표시 가공은 프론트 몫) |
| USER-CM-29 | 유비쿼터스 | THE 시스템 SHALL `imageUrls`가 생략되거나 빈 배열이면 이미지 없는 게시글로 정상 처리한다 | `AC-CM-29-1` `imageUrls` 키 없음 → 201. `AC-CM-29-2` `"imageUrls":[]` → 201, 상세의 `imageUrls`가 `[]`(`null` 아님) |
| USER-CM-30 | 유비쿼터스 | THE 시스템 SHALL 작성 검증을 **형식(400) → 카테고리 존재(404) → 이미지 EP(400, I절) → 저장** 순으로 한다 | `AC-CM-30-1` 빈 제목 + 없는 카테고리 → 400(`data.title`). `AC-CM-30-2` 유효 제목 + 없는 카테고리 + 깨진 EP → 404. `AC-CM-30-3` 어느 단계에서 거절되든 행·S3 객체 변화 0 |

### D. 게시글 조회 — 전체 목록·인기·상세·내 글 (USER-CM-40 ~ 67)

#### D-1. 전체 목록 `GET /api/community/posts`

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-CM-40 | 이벤트 | WHEN 전체 목록을 요청하면, THE 시스템 SHALL 삭제되지 않고 블라인드되지 않은 게시글을 페이징해 반환한다 | `AC-CM-40-1` 게시글 25건(그중 삭제 2·블라인드 1) → `totalElements=22`, `page=0`에 20건, `hasNext=true` |
| USER-CM-41 | 유비쿼터스 | THE 시스템 SHALL 목록 항목을 `PostSummary` 키 집합으로 반환한다 | `AC-CM-41-1` 항목 키가 정확히 `{postId, categoryId, categoryName, title, thumbnailUrl, author, viewCount, likeCount, dislikeCount, commentCount, createdAt}`. `AC-CM-41-2` `content` 키 없음(결정 Q13 — 본문 미포함). `AC-CM-41-3` 이미지 없는 글은 `thumbnailUrl: null`, 있는 글은 첫 번째 EP |
| USER-CM-42 | 유비쿼터스 | THE 시스템 SHALL `sort` 생략 시 `latest`(작성 최신순)로 정렬한다 | `AC-CM-42-1` 파라미터 없음 → `postId` 내림차순(= `createdAt` 내림차순, 동률 `id` 내림차순) |
| USER-CM-43 | 이벤트 | WHEN `sort=likes`를 요청하면, THE 시스템 SHALL `likeCount` 내림차순·동률 `id` 내림차순으로 정렬한다 | `AC-CM-43-1` 좋아요 3·3·1인 글 → 좋아요 3 중 최신 글이 첫 번째. 싫어요 수는 정렬에 영향 없음 |
| USER-CM-44 | 이벤트 | WHEN `sort=views`를 요청하면, THE 시스템 SHALL `viewCount` 내림차순·동률 `id` 내림차순으로 정렬한다 | `AC-CM-44-1` 조회수 10·10·2 → 조회수 10 중 최신 글이 첫 번째 |
| USER-CM-45 | 예외 | IF `sort`가 `latest`·`likes`·`views` 밖의 값이면, THEN THE 시스템 SHALL 400을 반환한다 | `AC-CM-45-1` `?sort=hot` → 400 `"요청 파라미터 형식이 올바르지 않습니다: sort"`(enum 바인딩 실패 → 기존 `handleTypeMismatch`, 신규 코드 없음) |
| USER-CM-46 | 이벤트 | WHEN `categoryId`를 지정하면, THE 시스템 SHALL 그 카테고리 게시글만 반환한다 | `AC-CM-46-1` `?categoryId=3` → 모든 항목 `categoryId=3`. `AC-CM-46-2` 생략 → 전 카테고리 |
| USER-CM-47 | 유비쿼터스 | THE 시스템 SHALL 존재하지 않는 `categoryId` 필터를 404가 아니라 빈 페이지로 처리한다 | `AC-CM-47-1` `?categoryId=999` → 200 `content:[]`, `totalElements:0`(이 모듈 "조회 필터에 존재 검증을 붙이지 않는다" 컨벤션 — `/players?teamId=` 선례) |
| USER-CM-48 | 유비쿼터스 | THE 시스템 SHALL 목록 조회로 어떤 게시글의 `viewCount`도 증가시키지 않는다 | `AC-CM-48-1` 목록 10회 조회 전후 모든 게시글 `view_count` 불변 |
| USER-CM-49 | 유비쿼터스 | THE 시스템 SHALL 전체 목록에서 인기 게시글(D-2)을 제외하지 않는다 (결정 Q2 — 두 API가 독립) | `AC-CM-49-1` 인기 5건에 든 글이 `sort=latest` 목록에도 그대로 나온다(중복 제거는 프론트 몫) |

#### D-2. 인기 목록 `GET /api/community/posts/popular`

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-CM-50 | 이벤트 | WHEN 인기 목록을 요청하면, THE 시스템 SHALL 최근 7일 안에 작성된 비삭제·비블라인드 게시글 중 점수 상위 최대 5건을 반환한다 (결정 Q1 — 집계 기간 7일) | `AC-CM-50-1` `GET /api/community/posts/popular` → 200, `data` 길이 ≤ 5. `AC-CM-50-2` 8일 전 작성 글은 점수가 1위여도 제외(기준 시각은 `Clock` 빈, `created_at >= now - 7d`). `AC-CM-50-3` 블라인드·삭제 글 제외 |
| USER-CM-51 | 유비쿼터스 | THE 시스템 SHALL 인기 점수를 `likeCount × 10 + viewCount`로 계산한다 (결정 Q1) | `AC-CM-51-1` (좋아요 2, 조회 15)=35 vs (좋아요 0, 조회 30)=30 → 전자가 앞. `AC-CM-51-2` 싫어요 수는 점수에 영향 없음(싫어요 100이어도 순위 불변) |
| USER-CM-52 | 유비쿼터스 | THE 시스템 SHALL 동점이면 `id` 내림차순(최신)으로 정렬한다 | `AC-CM-52-1` 점수 같은 두 글 → 나중 글이 앞 |
| USER-CM-53 | 유비쿼터스 | THE 시스템 SHALL 후보가 5건 미만이면 있는 만큼, 0건이면 빈 배열을 200으로 반환한다 | `AC-CM-53-1` 게시글 2건 → `data` 길이 2. `AC-CM-53-2` 0건 → `data: []`(`null` 아님). 최소 점수 문턱 없음 — 조회수 0·좋아요 0인 글도 후보다 |
| USER-CM-54 | 이벤트 | WHEN `categoryId`를 지정하면, THE 시스템 SHALL 그 카테고리 안에서만 상위 5건을 고른다 | `AC-CM-54-1` `?categoryId=3` → 전부 `categoryId=3`. `AC-CM-54-2` 생략 → 전 카테고리 통합 상위 5. `AC-CM-54-3` 없는 카테고리 → 200 `[]` |
| USER-CM-55 | 유비쿼터스 | THE 시스템 SHALL 인기 항목을 전체 목록 항목과 같은 `PostSummary`로 반환한다 | `AC-CM-55-1` 키 집합이 USER-CM-41과 동일. 점수 값 자체는 응답에 싣지 않는다 |
| USER-CM-56 | 유비쿼터스 | THE 시스템 SHALL 인기 목록을 페이징하지 않는다 | `AC-CM-56-1` `data`가 `PageResponse`가 아니라 배열. `?page=`는 무시 |
| USER-CM-57 | 유비쿼터스 | THE 시스템 SHALL 인기 목록 조회로 `viewCount`를 증가시키지 않는다 | `AC-CM-57-1` USER-CM-48과 동일 방식 확인 |

#### D-3. 상세 `GET /api/community/posts/{postId}`

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-CM-60 | 이벤트 | WHEN 상세를 요청하면, THE 시스템 SHALL `PostDetail` 키 집합으로 게시글을 반환한다 | `AC-CM-60-1` 키가 정확히 `{postId, categoryId, categoryName, title, content, imageUrls, author, viewCount, likeCount, dislikeCount, commentCount, myReaction, isAuthor, createdAt, updatedAt}`. `AC-CM-60-2` 요청자가 반응을 남기지 않았으면 `myReaction: null`. `AC-CM-60-3` 작성자 본인이면 `isAuthor: true`, 아니면 `false` |
| USER-CM-61 | 유비쿼터스 | THE 시스템 SHALL `imageUrls`를 작성·수정 시 전달된 순서 그대로 반환한다 | `AC-CM-61-1` `[a,b,c]` 순으로 작성 → 상세 `[a',b',c']`(확정 EP, 같은 순서). `AC-CM-61-2` 수정으로 `[c,a]`로 바꾸면 `[c',a']` |
| USER-CM-62 | 예외 | IF `postId`가 존재하지 않거나 삭제된 게시글이면, THEN THE 시스템 SHALL 404 `COMMUNITY_POST_NOT_FOUND`를 반환한다 | `AC-CM-62-1` `GET /posts/999999` → 404 `"존재하지 않는 게시글입니다."`. `AC-CM-62-2` 삭제된 글 → **같은 404, 같은 문구**(삭제됐음을 구분해 주지 않는다 — 채팅 "삭제 메시지 404"와 같은 처리). `AC-CM-62-3` `/posts/abc` → 400(타입 불일치) |
| USER-CM-63 | 예외 | IF 게시글이 블라인드 상태면, THEN THE 시스템 SHALL 410 `COMMUNITY_POST_BLINDED`를 반환한다 (결정 Q6 — 404·403 아님; 결정 Q7 — 작성자 본인도 동일) | `AC-CM-63-1` 신고된 글 상세 → 410 `{"success":false,"data":null,"message":"신고로 숨김 처리된 게시글입니다."}`. `AC-CM-63-2` 작성자 본인 토큰으로도 410. `AC-CM-63-3` 신고자 본인도 410. **프론트는 이 코드로 예외 페이지를 띄운다** — 404(없음)와 구분되는 것이 410을 고른 이유 |
| USER-CM-64 | 유비쿼터스 | THE 시스템 SHALL 상세 응답에 댓글 목록을 포함하지 않는다 | `AC-CM-64-1` `PostDetail`에 `comments` 키 없음(댓글은 `GET …/comments`로 별도 페이징). `commentCount`만 싣는다 |

#### D-4. 내 글 `GET /api/community/posts/me`

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-CM-65 | 이벤트 | WHEN 내 글 목록을 요청하면, THE 시스템 SHALL 요청자가 작성한 비삭제 게시글을 **블라인드 포함**해 최신순으로 페이징 반환한다 (결정 Q7 — 마이페이지에서 블라인드 사실을 본인에게 알린다) | `AC-CM-65-1` 내 글 3건(블라인드 1) → `totalElements=3`. `AC-CM-65-2` 삭제한 글은 제외. `AC-CM-65-3` 타인 글 0건 |
| USER-CM-66 | 유비쿼터스 | THE 시스템 SHALL 내 글 항목을 `PostSummary` + `blinded`로 반환한다 | `AC-CM-66-1` 키 = USER-CM-41 11개 + `blinded`. 블라인드 글 `blinded: true`, 나머지 `false` |
| USER-CM-67 | 유비쿼터스 | THE 시스템 SHALL 내 글 목록을 최신순으로만 정렬한다 | `AC-CM-67-1` `?sort=likes`를 붙여도 무시되고 `id` 내림차순(`sort` 파라미터가 이 경로에 없다) |

### E. 조회수 — 계정 기준 5분 고정 창 (USER-CM-70 ~ 79)

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-CM-70 | 이벤트 | WHEN 상세 요청이 200으로 성공하고 그 (게시글, 요청자) 조회 창이 없으면, THE 시스템 SHALL `viewCount`를 1 증가시킨다 | `AC-CM-70-1` 계정 A가 처음 `GET /posts/1` → `view_count` 0→1 |
| USER-CM-71 | 이벤트 | WHEN 증가가 일어나면, THE 시스템 SHALL 그 (게시글, 요청자)의 조회 창을 300초 TTL로 생성한다 | `AC-CM-71-1` 증가 직후 Redis 키 `community:post:view:{postId}:{accountId}`(기존 `profile:image:temp:count:{appId}` 규약 미러링)가 존재하고 `TTL`이 300 이하 양수 |
| USER-CM-72 | 상태 | WHILE 조회 창이 살아 있는 동안, THE 시스템 SHALL 같은 요청자의 상세 요청에 `viewCount`를 증가시키지 않는다 | `AC-CM-72-1` A가 t=0·t=10·t=299에 조회 → `view_count` 총 +1 |
| USER-CM-73 | 유비쿼터스 | THE 시스템 SHALL 조회 창의 만료 시각을 재요청으로 연장하지 않는다 | `AC-CM-73-1` A가 t=0(+1)·t=240(+0)·t=301(+1) → 총 2. **슬라이딩 창이면 t=301이 +0이 된다 — 그걸 금지하는 줄이다.** 구현은 `SET NX EX 300`(존재하면 TTL을 건드리지 않음), 임시 프로필 이미지 한도 카운터와 같은 고정 창 방식 |
| USER-CM-74 | 유비쿼터스 | THE 시스템 SHALL 상세 응답의 `viewCount`에 그 요청의 증가분을 반영한 값을 싣는다 | `AC-CM-74-1` 첫 조회 응답 `viewCount: 1`(0이 아님). 창 안 재조회 응답은 그대로 1 |
| USER-CM-75 | 유비쿼터스 | THE 시스템 SHALL 작성자 본인의 상세 조회를 세지 않는다 | `AC-CM-75-1` 작성자가 자기 글을 10회 조회 → `view_count` 0. 조회 창도 만들지 않는다 |
| USER-CM-76 | 유비쿼터스 | THE 시스템 SHALL 404·410으로 끝나는 요청에 `viewCount`를 증가시키지 않는다 | `AC-CM-76-1` 블라인드 글 조회(410) 전후 `view_count` 불변, 조회 창 미생성 |
| USER-CM-77 | 유비쿼터스 | THE 시스템 SHALL 서로 다른 계정의 동시 조회 N건을 정확히 N만큼 반영한다 | `AC-CM-77-1` 계정 50개가 동시에 첫 조회 → `view_count` 정확히 50(read-modify-write가 아니라 원자 증가 `UPDATE … SET view_count = view_count + 1`) |
| USER-CM-78 | 예외 | IF 조회 창 저장소(Redis)에 접근할 수 없으면, THEN THE 시스템 SHALL `viewCount`를 증가시키지 않고 상세를 200으로 반환한다 (읽기는 fail-open, 집계는 fail-closed) | `AC-CM-78-1` Redis 중단 상태에서 상세 조회 → 200, `view_count` 불변, ERROR 로그 1건. **어뷰징 판정을 못 하는 상태에서 세면 그 동안 무제한 집계가 된다** |
| USER-CM-79 | 유비쿼터스 | THE 시스템 SHALL 조회 창을 TTL 만료 외의 경로로 지우지 않는다 | `AC-CM-79-1` 게시글 삭제·블라인드·앱 재기동 후에도 남은 키는 TTL까지 잔존(삭제 경로·정리 배치 없음 — 키는 300초 뒤 스스로 사라진다) |

### F. 게시글 수정·삭제 (USER-CM-80 ~ 97)

#### F-1. 수정 `PUT /api/community/posts/{postId}`

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-CM-80 | 이벤트 | WHEN 작성자가 유효한 본문으로 수정을 요청하면, THE 시스템 SHALL `title`·`content`·`categoryId`·`imageUrls`를 요청값으로 **전부 교체**하고 200과 `PostDetail`을 반환한다 | `AC-CM-80-1` `PUT /posts/1` `{"categoryId":2,"title":"수정","content":"본문","imageUrls":[]}` → 200, 상세가 요청값과 일치. `AC-CM-80-2` 부분 생략 불가 — `title` 키 없음 → 400(`PATCH`가 아니라 `PUT`인 이유) |
| USER-CM-81 | 유비쿼터스 | THE 시스템 SHALL 수정 본문에 작성과 같은 검증(USER-CM-22~27·30)을 적용한다 | `AC-CM-81-1` 빈 제목 → 400 `data.title`. `AC-CM-81-2` 없는 카테고리 → 404. `AC-CM-81-3` 101자 제목 → 400 |
| USER-CM-82 | 예외 | IF 요청자가 작성자가 아니면, THEN THE 시스템 SHALL 403 `COMMUNITY_NOT_AUTHOR`를 반환하고 아무것도 바꾸지 않는다 | `AC-CM-82-1` 타인 글 `PUT` → 403 `"작성자만 수정·삭제할 수 있습니다."`, 행·이미지 불변. `AC-CM-82-2` 403 판정은 본문 검증(400) **뒤**, 카테고리 존재(404) **앞** — 순서: 400 → 글 존재 404 → 작성자 403 → 카테고리 404 → EP 400 |
| USER-CM-83 | 예외 | IF 게시글이 없거나 삭제됐으면, THEN THE 시스템 SHALL 404 `COMMUNITY_POST_NOT_FOUND`를 반환한다 | `AC-CM-83-1` 삭제한 자기 글 `PUT` → 404 |
| USER-CM-84 | 예외 | IF 게시글이 블라인드 상태면, THEN THE 시스템 SHALL 410 `COMMUNITY_POST_BLINDED`를 반환하고 수정하지 않는다 (결정 Q7 — 수정으로 블라인드를 우회할 수 없다) | `AC-CM-84-1` 작성자가 블라인드 글 `PUT` → 410, 행 불변 |
| USER-CM-85 | 유비쿼터스 | THE 시스템 SHALL 수정으로 `viewCount`·`likeCount`·`dislikeCount`·`commentCount`·`createdAt`·작성자를 바꾸지 않는다 | `AC-CM-85-1` 조회 10·좋아요 2인 글 수정 후 상세 → 같은 값, `createdAt` 동일, `author` 동일 |
| USER-CM-86 | 이벤트 | WHEN 수정이 성공하면, THE 시스템 SHALL `updatedAt`을 갱신한다 | `AC-CM-86-1` 수정 후 `updatedAt > createdAt`. 목록 정렬(`latest`)은 `createdAt` 기준이라 **수정해도 목록 순서가 안 바뀐다** |
| USER-CM-87 | 이벤트 | WHEN 수정 요청의 `imageUrls`가 기존과 다르면, THE 시스템 SHALL 요청 배열을 최종 상태로 삼아 — 기존 확정 EP는 유지, 새 임시 EP는 이동, 빠진 기존 EP는 제거 — 처리한다 (결정 Q12 — 선언적 전체 교체) | `AC-CM-87-1` 기존 `[a',b']` + 요청 `[b', temp/c]` → 결과 `[b', c']`, `a'` 객체는 커밋 후 삭제(USER-CM-155), `c'`는 `community/`에 생성. `AC-CM-87-2` 요청 `[]` → 이미지 전부 제거 |

#### F-2. 삭제 `DELETE /api/community/posts/{postId}`

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-CM-90 | 이벤트 | WHEN 작성자가 삭제를 요청하면, THE 시스템 SHALL 게시글을 소프트 삭제(`deletedAt` 기록)하고 204를 반환한다 (결정 Q10 — hard delete 아님) | `AC-CM-90-1` `DELETE /posts/1` → 204 본문 없음. `AC-CM-90-2` 행은 남고 `deleted_at`이 `Clock` 빈 시각으로 채워짐 |
| USER-CM-91 | 상태 | WHILE 게시글이 삭제 상태인 동안, THE 시스템 SHALL 그 게시글을 상세·전체 목록·인기 목록·내 글 목록·댓글 경로·반응·신고 전부에서 404로 처리하거나 제외한다 | `AC-CM-91-1` 삭제 후 상세 404, 목록 3종 미포함. `AC-CM-91-2` `POST /posts/1/comments`·`PUT /posts/1/reaction`·`POST /posts/1/report`·`GET /posts/1/comments` → 404 |
| USER-CM-92 | 예외 | IF 요청자가 작성자가 아니면, THEN THE 시스템 SHALL 403 `COMMUNITY_NOT_AUTHOR`를 반환하고 삭제하지 않는다 | `AC-CM-92-1` 타인 글 `DELETE` → 403, `deleted_at` null 유지 |
| USER-CM-93 | 예외 | IF 이미 삭제된 게시글이면, THEN THE 시스템 SHALL 404를 반환한다 | `AC-CM-93-1` 두 번째 `DELETE` → 404(멱등 204가 아니다 — 응원 취소와 달리 "이미 없는 자원"으로 본다). `deleted_at` 최초 값 보존 |
| USER-CM-94 | 유비쿼터스 | THE 시스템 SHALL 블라인드 게시글도 작성자의 삭제를 허용한다 (결정 Q7 — 숨겨진 글을 치울 권리는 남긴다) | `AC-CM-94-1` 블라인드 글 작성자 `DELETE` → 204 |
| USER-CM-95 | 유비쿼터스 | THE 시스템 SHALL 게시글 삭제 시 그 댓글·답글 행을 개별 삭제 표시하지 않는다 | `AC-CM-95-1` 댓글 3건 달린 글 삭제 → 댓글 행의 `deleted_at`은 그대로 null(접근 경로가 전부 글 404로 막혀 보이지 않는다). 댓글 작성자의 `DELETE /comments/{id}`는 소속 글이 삭제라 404 |
| USER-CM-96 | 이벤트 | WHEN 삭제 트랜잭션이 커밋되면, THE 시스템 SHALL 그 게시글과 댓글·답글에 붙은 이미지 객체를 best-effort로 삭제한다 | `AC-CM-96-1` 삭제 후 각 확정 EP를 `HeadObject` → 404. `AC-CM-96-2` S3 삭제가 실패해도 204는 그대로이고 ERROR 로그만 남는다(프로필 이미지 USER-PI-72·73과 같은 처리 — 재시도·보류 큐 없음). `AC-CM-96-3` 이미지 테이블 행은 남는다(소프트 삭제라 행 보존, 객체만 없음) |
| USER-CM-97 | 유비쿼터스 | THE 시스템 SHALL 게시글·댓글·반응·신고 행을 하드 삭제하는 API 경로를 두지 않는다 (결정 Q10) | `AC-CM-97-1` 어떤 엔드포인트 호출로도 게시글 테이블 행 수가 줄지 않는다(계정 하드 삭제의 FK CASCADE는 별개 — K절) |

### G. 댓글·답글 (USER-CM-100 ~ 119, 210 ~ 218)

> 번호 블록 100~113은 초안의 댓글 요구사항이고 114~119가 개정 1차에서 이어 붙은 답글 요구사항이다. 120부터는 반응(H절)이 이미 차지하고 있어 **나머지 답글 요구사항은 210~218로 이어진다** — 같은 절의 두 블록이며 번호가 떨어진 것은 재배치가 아니라 예비 번호 소진 때문이다.

#### G-1. 댓글 작성·목록·수정·삭제

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-CM-100 | 이벤트 | WHEN 인증 사용자가 유효한 `content`로 댓글 작성을 요청하면, THE 시스템 SHALL 댓글을 저장하고 201과 `commentId`를 반환한다 | `AC-CM-100-1` `POST /posts/1/comments` `{"content":"ㅊㅋ","imageUrls":[]}` → 201 `data.commentId` 정수. `AC-CM-100-2` 그 글의 `commentCount` +1. `AC-CM-100-3` `parentCommentId` 생략 또는 `null` → 최상위 댓글(`parent_comment_id = NULL`) |
| USER-CM-101 | 예외 | IF `content`가 null·빈 문자열·공백만이면, THEN THE 시스템 SHALL 400과 `data.content` 필드 메시지를 반환한다 | `AC-CM-101-1` `{"content":" "}` → 400, `commentCount` 불변 |
| USER-CM-102 | 예외 | IF `content` 길이가 1000자를 넘으면, THEN THE 시스템 SHALL 400을 반환한다 (결정 Q13) | `AC-CM-102-1` 1001자 → 400. `AC-CM-102-2` 1000자 → 201 |
| USER-CM-103 | 예외 | IF 소속 게시글이 없거나 삭제됐으면, THEN THE 시스템 SHALL 404 `COMMUNITY_POST_NOT_FOUND`를 반환한다 | `AC-CM-103-1` `POST /posts/999999/comments` → 404 |
| USER-CM-104 | 예외 | IF 소속 게시글이 블라인드 상태면, THEN THE 시스템 SHALL 410 `COMMUNITY_POST_BLINDED`를 반환한다 | `AC-CM-104-1` 블라인드 글에 댓글 → 410, 행 미생성 |
| USER-CM-105 | 이벤트 | **(개정 1차 수정)** WHEN 댓글 목록을 요청하면, THE 시스템 SHALL **최상위 댓글**(보이는 댓글 + 자리 표식)을 작성순(`id` 오름차순)으로 페이징 반환한다 | `AC-CM-105-1` `GET /posts/1/comments` → 200 `PageResponse`, 가장 오래된 최상위 댓글이 `page=0` 첫 항목. `AC-CM-105-2` 삭제·블라인드 댓글 중 **보이는 답글이 없는 것**은 미포함(있는 것은 자리 표식으로 포함 — USER-CM-212~214). `AC-CM-105-3` 답글은 `content[]`에 최상위 항목으로 나오지 않는다(항목 안 `replies[]`에만). `AC-CM-105-4` 소속 글 404/410 규칙은 USER-CM-103·104와 동일 |
| USER-CM-106 | 유비쿼터스 | **(개정 1차 수정)** THE 시스템 SHALL 댓글 항목을 `CommentResponse` 키 집합으로 반환한다 | `AC-CM-106-1` 키가 정확히 `{commentId, parentCommentId, status, content, imageUrls, author, likeCount, dislikeCount, myReaction, isAuthor, createdAt, updatedAt, replies}`. `AC-CM-106-2` **`viewCount` 키 없음**(결정 Q3). `AC-CM-106-3` 최상위 항목은 `parentCommentId: null`. `AC-CM-106-4` 보이는 댓글은 `status: "VISIBLE"` |
| USER-CM-107 | 유비쿼터스 | THE 시스템 SHALL 댓글 목록 조회로 게시글 `viewCount`를 증가시키지 않는다 | `AC-CM-107-1` 댓글 목록 10회 조회 전후 글 `view_count` 불변(조회수는 상세 GET 하나만 센다) |
| USER-CM-108 | 유비쿼터스 | **(개정 1차 수정 — 초안 "평면 구조"를 뒤집음, 결정 Q5)** THE 시스템 SHALL 댓글을 **깊이 1의 2단 구조**(최상위 댓글 → 답글)로만 저장한다 | `AC-CM-108-1` 댓글 테이블에 자기 참조 `parent_comment_id`(nullable)가 있다. `AC-CM-108-2` 답글 행의 부모는 항상 `parent_comment_id = NULL`인 행이다 — 답글을 부모로 가리키는 행이 존재할 수 없다(USER-CM-116이 막는다) |
| USER-CM-109 | 이벤트 | WHEN 작성자가 유효한 본문으로 댓글 수정을 요청하면, THE 시스템 SHALL `content`·`imageUrls`를 전부 교체하고 200과 `CommentResponse`를 반환한다 | `AC-CM-109-1` `PUT /comments/5` `{"content":"수정","imageUrls":[]}` → 200. `AC-CM-109-2` `updatedAt` 갱신, `createdAt`·카운트 불변. 이미지 교체 규칙은 USER-CM-87과 동일. `AC-CM-109-3` 최상위 댓글 수정 응답의 `replies[]`는 현재 보이는 답글(수정이 답글을 건드리지 않는다) |
| USER-CM-110 | 예외 | IF 댓글 수정·삭제 요청자가 작성자가 아니면, THEN THE 시스템 SHALL 403 `COMMUNITY_NOT_AUTHOR`를 반환한다 | `AC-CM-110-1` 타인 댓글 `PUT`·`DELETE` → 403, 불변. 게시글 작성자라도 남의 댓글은 못 지운다. 부모 댓글 작성자라도 남의 답글은 못 지운다 |
| USER-CM-111 | 예외 | IF 댓글이 없거나 삭제됐거나 소속 게시글이 삭제됐으면, THEN THE 시스템 SHALL 404 `COMMUNITY_COMMENT_NOT_FOUND`를 반환한다 | `AC-CM-111-1` `PUT /comments/999999` → 404 `"존재하지 않는 댓글입니다."`. `AC-CM-111-2` 삭제된 글의 댓글 `PUT` → 404(어느 쪽이 삭제됐는지 구분하지 않는다). `AC-CM-111-3` 자리 표식(삭제된 부모) `PUT` → 404 |
| USER-CM-112 | 이벤트 | **(개정 1차 수정)** WHEN 작성자가 댓글 삭제를 요청하면, THE 시스템 SHALL **그 행만** 소프트 삭제하고 204를 반환한다 | `AC-CM-112-1` `DELETE /comments/5` → 204, 그 행 `deleted_at` 기록. `AC-CM-112-2` 글 `commentCount` −1(답글 수는 무관 — 부모 하나만 줄어든다). `AC-CM-112-3` 재삭제 → 404. `AC-CM-112-4` 이미지 객체는 커밋 후 best-effort 삭제(USER-CM-96과 동일). `AC-CM-112-5` 답글이 3개 달린 댓글 삭제 → 답글 3개의 `deleted_at`은 null 그대로(USER-CM-210) |
| USER-CM-113 | 유비쿼터스 | **(개정 1차 수정)** THE 시스템 SHALL `commentCount`를 보이는 댓글 수와 보이는 답글 수의 합과 일치시킨다 | `AC-CM-113-1` 댓글 5건(1 삭제·1 블라인드) + 답글 4건(1 삭제) → `commentCount: 6`(3 + 3). `AC-CM-113-2` 자리 표식은 세지 않는다 — 삭제된 부모에 보이는 답글 2개면 그 가지의 기여는 2. ⚠ 초안의 "댓글 목록 `totalElements`와 같다"는 **더 이상 참이 아니다**(`totalElements`는 최상위 항목 수 + 자리 표식 수, `commentCount`는 답글까지 센 수) |

#### G-2. 답글 작성 (USER-CM-114 ~ 119, 개정 1차 신설)

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-CM-114 | 이벤트 | WHEN 인증 사용자가 유효한 `content`와 **같은 게시글의 보이는 최상위 댓글** `parentCommentId`로 작성을 요청하면, THE 시스템 SHALL 답글을 저장하고 201과 `commentId`를 반환한다 | `AC-CM-114-1` `POST /posts/1/comments` `{"content":"ㄹㅇ","imageUrls":[],"parentCommentId":5}` → 201 `data.commentId` 정수, 그 행의 `parent_comment_id = 5`. `AC-CM-114-2` 글 `commentCount` +1(USER-CM-113). `AC-CM-114-3` 본문·이미지 검증은 댓글과 동일(USER-CM-101·102·152 — 답글도 이미지 3장) |
| USER-CM-115 | 예외 | IF `parentCommentId`가 존재하지 않거나 **다른 게시글**의 댓글이면, THEN THE 시스템 SHALL 404 `COMMUNITY_COMMENT_NOT_FOUND`를 반환한다 | `AC-CM-115-1` `parentCommentId: 999999` → 404 `"존재하지 않는 댓글입니다."`, 행 미생성. `AC-CM-115-2` 글 2의 댓글 id를 `POST /posts/1/comments`에 넣음 → 404(글 스코프 밖은 "그 글에 없는 댓글"이다 — 채팅의 room-스코프 조회 `findByIdAndChatroom`과 같은 판단) |
| USER-CM-116 | 예외 | IF `parentCommentId`가 가리키는 댓글이 **답글**이면, THEN THE 시스템 SHALL 400 `COMMUNITY_REPLY_DEPTH_EXCEEDED`를 반환한다 | `AC-CM-116-1` 답글 id를 부모로 지정 → 400 `"답글에는 답글을 달 수 없습니다."`, 행 미생성. **깊이 1 고정** — 프론트가 "답글의 답글" 버튼을 부모 댓글로 향하게 만들면 된다(요청의 `parentCommentId`를 그 답글의 `parentCommentId`로 바꿔 보내는 것은 클라이언트 선택이고 서버는 바꿔 주지 않는다) |
| USER-CM-117 | 예외 | IF 부모 댓글이 삭제됐으면 404, 블라인드 상태면 410 `COMMUNITY_COMMENT_BLINDED`를 THE 시스템 SHALL 반환하고 답글을 만들지 않는다 (결정 Q5 — 자리 표식에는 새 답글 불가) | `AC-CM-117-1` 삭제된 부모(자리 표식 상태 포함) → 404. `AC-CM-117-2` 블라인드 부모 → 410 `"신고로 숨김 처리된 댓글입니다."`. **이미 달린 답글은 그대로 보인다**(USER-CM-210·211)와 **새로 못 단다**는 서로 다른 규칙이다 |
| USER-CM-118 | 유비쿼터스 | THE 시스템 SHALL 댓글 목록의 각 최상위 항목에 그 댓글의 보이는 답글 **전부**를 `replies[]`로 작성순(`id` 오름차순) 포함한다 | `AC-CM-118-1` 답글 40개 달린 댓글 → `replies` 길이 40(페이징·상한 없음 — 알려진 한계 8). `AC-CM-118-2` 답글 없는 댓글 → `replies: []`(`null` 아님). `AC-CM-118-3` 각 원소는 `ReplyResponse` 키 집합(`replies` 키 없음, `parentCommentId`가 부모 id, `status: "VISIBLE"`) |
| USER-CM-119 | 유비쿼터스 | THE 시스템 SHALL `replies[]`에서 삭제·블라인드된 답글을 제외한다 | `AC-CM-119-1` 답글 3개 중 1 삭제·1 블라인드 → `replies` 길이 1. 답글에는 자리 표식이 없다(답글은 자식이 없어 자리를 지킬 이유가 없다) |

#### G-3. 부모와 자식의 운명을 묶지 않는다 (USER-CM-210 ~ 218, 개정 1차 신설)

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-CM-210 | 유비쿼터스 | THE 시스템 SHALL 부모 댓글 삭제를 답글에 전파하지 않는다 (사용자 원문: "부모 삭제 시 CASCADE가 아닌, 삭제 처리는 개별로 처리") | `AC-CM-210-1` 답글 3개 달린 댓글을 작성자가 삭제 → 답글 3개 전부 `deleted_at = NULL`, 목록에서 계속 보임. `AC-CM-210-2` 각 답글은 **자기 작성자만** `DELETE /comments/{id}`로 개별 삭제(USER-CM-110·112). `AC-CM-210-3` 부모 작성자가 자기 댓글을 지워도 남의 답글을 함께 지울 수단이 없다 |
| USER-CM-211 | 유비쿼터스 | THE 시스템 SHALL 부모 댓글 블라인드를 답글에 전파하지 않는다 | `AC-CM-211-1` 답글 2개 달린 댓글이 신고로 블라인드 → 답글 2개 `blinded = 0` 그대로, 목록에 계속 보임. `AC-CM-211-2` 답글을 숨기려면 그 답글을 따로 신고해야 한다(USER-CM-180·217) |
| USER-CM-212 | 상태 | WHILE 삭제된 부모 댓글에 보이는 답글이 하나 이상 있는 동안, THE 시스템 SHALL 그 댓글을 목록에 `status: "DELETED"` 자리 표식으로 유지한다 | `AC-CM-212-1` 항목 `{commentId: 5, parentCommentId: null, status: "DELETED", content: null, imageUrls: [], author: null, likeCount: 0, dislikeCount: 0, myReaction: null, isAuthor: false, createdAt: "<원래 작성 시각>", updatedAt: null, replies: [...]}`. `AC-CM-212-2` 작성자 본인이 봐도 `isAuthor: false`·`content: null`(본문은 누구에게도 안 나간다). `AC-CM-212-3` 자리 표식은 `totalElements`에 포함된다(한 항목이다). `AC-CM-212-4` 그 댓글이 실제로 가졌던 좋아요 수는 응답에 나오지 않는다(0으로 고정 — 행의 `like_count`는 보존) |
| USER-CM-213 | 상태 | WHILE 블라인드된 부모 댓글에 보이는 답글이 하나 이상 있는 동안, THE 시스템 SHALL 그 댓글을 목록에 `status: "BLINDED"` 자리 표식으로 유지한다 | `AC-CM-213-1` USER-CM-212와 같은 형태, `status`만 `"BLINDED"`. `AC-CM-213-2` 신고자·작성자·제3자 전원 동일(게시글 블라인드 USER-CM-183과 같은 원칙) |
| USER-CM-214 | 유비쿼터스 | THE 시스템 SHALL 보이는 답글이 하나도 없는 삭제·블라인드 댓글을 목록에서 제외한다 | `AC-CM-214-1` 답글 없는 댓글 삭제 → 목록 미포함, `totalElements` −1. `AC-CM-214-2` 자리 표식 상태였던 댓글의 마지막 답글이 삭제(또는 블라인드)되면 다음 조회부터 자리 표식도 사라진다 — **자리 표식은 저장 상태가 아니라 조회 시점에 "보이는 답글이 있는가"로 결정되는 표현**이다 |
| USER-CM-215 | 유비쿼터스 | THE 시스템 SHALL 삭제와 블라인드가 겹친 부모의 자리 표식을 `"DELETED"`로 표시한다 | `AC-CM-215-1` 블라인드 후 작성자가 삭제(USER-CM-181-3 허용) → 보이는 답글이 있으면 `status: "DELETED"`. 작성자의 "치우기"가 신고보다 우선한다(그 댓글에 대한 새 신고는 404 — USER-CM-182) |
| USER-CM-216 | 유비쿼터스 | THE 시스템 SHALL 자리 표식 부모에 대한 수정·반응·신고 요청을 그 부모의 실제 상태대로 거절한다 | `AC-CM-216-1` 삭제된 부모: `PUT`·`PUT …/reaction`·`POST …/report` → 404(USER-CM-111·127·182). `AC-CM-216-2` 블라인드 부모: `PUT`·`PUT …/reaction` → 410, `POST …/report` → 200 no-op(USER-CM-181·172 준용). 자리 표식이라고 특별한 상태코드는 없다 |
| USER-CM-217 | 유비쿼터스 | THE 시스템 SHALL 답글의 수정·삭제·반응·신고에 댓글과 같은 규칙(USER-CM-109~112·120~132·180~182)을 적용한다 | `AC-CM-217-1` 답글 `PUT /comments/{replyId}` → 200 `ReplyResponse`(`replies` 키 없음). `AC-CM-217-2` 답글 LIKE→DISLIKE→NONE 왕복이 댓글과 같은 응답. `AC-CM-217-3` 답글 신고 → 즉시 블라인드, `replies[]`에서 사라짐, `commentCount` −1. `AC-CM-217-4` 답글 삭제 → 204, `commentCount` −1, 부모는 영향 없음 |
| USER-CM-218 | 유비쿼터스 | THE 시스템 SHALL 답글 응답에 `replies` 키를 싣지 않는다 | `AC-CM-218-1` `ReplyResponse` 키가 정확히 `{commentId, parentCommentId, status, content, imageUrls, author, likeCount, dislikeCount, myReaction, isAuthor, createdAt, updatedAt}`(12개). `AC-CM-218-2` 깊이 2가 응답 구조상으로도 표현되지 않는다 |

### H. 좋아요/싫어요 — 3상태 반응 (USER-CM-120 ~ 132)

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-CM-120 | 이벤트 | WHEN 요청자가 `reaction: "LIKE"`를 보내면, THE 시스템 SHALL 그 (게시글, 요청자)의 반응을 LIKE로 설정하고 200과 `{myReaction, likeCount, dislikeCount}`를 반환한다 (결정 Q17 — 토글 POST가 아니라 상태 지정 PUT) | `AC-CM-120-1` `PUT /posts/1/reaction` `{"reaction":"LIKE"}` → 200 `{"myReaction":"LIKE","likeCount":1,"dislikeCount":0}`. `AC-CM-120-2` 반응 행 1건 생성 |
| USER-CM-121 | 이벤트 | WHEN 요청자가 `reaction: "DISLIKE"`를 보내면, THE 시스템 SHALL 반응을 DISLIKE로 설정한다 | `AC-CM-121-1` → `{"myReaction":"DISLIKE","likeCount":0,"dislikeCount":1}` |
| USER-CM-122 | 이벤트 | WHEN 요청자가 `reaction: "NONE"`을 보내면, THE 시스템 SHALL 기존 반응을 제거한다(취소) | `AC-CM-122-1` LIKE 상태에서 NONE → `{"myReaction":null,"likeCount":0,…}`, 반응 행 삭제(플래그 보존이 아니라 행 삭제 — `quiz-like.md` QUIZ-LIKE-8과 다른 선택, 충돌 2 참고). `AC-CM-122-2` 반응이 없던 상태에서 NONE → 200, 변화 없음 |
| USER-CM-123 | 이벤트 | WHEN LIKE 상태에서 DISLIKE를 보내면, THE 시스템 SHALL `likeCount`를 1 줄이고 `dislikeCount`를 1 늘린다 | `AC-CM-123-1` → `{"myReaction":"DISLIKE","likeCount":0,"dislikeCount":1}`. **둘이 동시에 켜진 순간이 없다** — 반응 행은 (계정, 글)당 하나이고 `type`만 바뀐다 |
| USER-CM-124 | 이벤트 | WHEN 현재 상태와 같은 값을 다시 보내면, THE 시스템 SHALL 카운트를 바꾸지 않고 200을 반환한다 | `AC-CM-124-1` LIKE 2회 → `likeCount` 1(2가 아니다). `AC-CM-124-2` 10회 반복해도 1. **"두 번 이상 누를 수 없다"는 요구의 구현이 멱등 응답이다** — 거절(4xx)이 아니다 |
| USER-CM-125 | 유비쿼터스 | THE 시스템 SHALL 반응을 (계정, 대상)당 한 행으로 저장하고 UNIQUE 제약으로 중복 행을 막는다 | `AC-CM-125-1` 반응 테이블에 `(user_account_id, post_id)` UNIQUE(이름 명시)가 걸려 있고 같은 쌍 2행 INSERT가 DB 단에서 실패한다 |
| USER-CM-126 | 예외 | IF `reaction`이 누락되거나 `LIKE`·`DISLIKE`·`NONE` 밖의 값이면, THEN THE 시스템 SHALL 400을 반환한다 | `AC-CM-126-1` `{"reaction":"LOVE"}` → 400(enum 역직렬화 실패 → `handleNotReadable` 래퍼). `AC-CM-126-2` `{}` → 400 `data.reaction` |
| USER-CM-127 | 예외 | IF 대상 게시글이 없거나 삭제됐으면 404, 블라인드면 410을 THE 시스템 SHALL 반환하고 반응을 바꾸지 않는다 | `AC-CM-127-1` 삭제 글 반응 → 404. `AC-CM-127-2` 블라인드 글 반응 → 410, 기존 반응 행 불변 |
| USER-CM-128 | 유비쿼터스 | THE 시스템 SHALL 자기 글·자기 댓글·자기 답글에 대한 반응을 허용한다 | `AC-CM-128-1` 작성자가 자기 글 LIKE → 200, `likeCount` 1(막지 않는다 — 인기 점수 +10 한 번뿐이라 어뷰징 가치가 없고, 막으면 403 코드가 하나 더 필요하다) |
| USER-CM-129 | 유비쿼터스 | THE 시스템 SHALL 댓글·답글 반응(`PUT /comments/{commentId}/reaction`)에 USER-CM-120~128과 같은 규칙을 적용한다 | `AC-CM-129-1` 댓글 LIKE→DISLIKE→NONE 왕복이 게시글과 같은 응답 형태. `AC-CM-129-2` 삭제 댓글 404·블라인드 댓글 410 `COMMUNITY_COMMENT_BLINDED` |
| USER-CM-130 | 유비쿼터스 | THE 시스템 SHALL 응답·목록의 `likeCount`·`dislikeCount`를 반응 테이블의 LIKE·DISLIKE 행 수와 항상 일치시킨다 | `AC-CM-130-1` 임의 시점에 `SELECT count(*) … WHERE post_id=? AND type=0`과 응답 `likeCount`가 같다(카운터 컬럼을 쓰면 **같은 트랜잭션**에서 갱신해야 한다는 뜻). 자리 표식의 0 고정(USER-CM-212-4)은 **표현**이지 저장값이 아니다 — 행의 카운터는 반응 행 수와 계속 일치한다 |
| USER-CM-131 | 유비쿼터스 | THE 시스템 SHALL 같은 계정의 동시 반응 요청 2건이 들어와도 반응 행을 1개만 남기고 카운트를 1 이하로만 반영한다 | `AC-CM-131-1` LIKE 2건 동시 → 최종 행 1개, `likeCount` 1(UNIQUE가 심판 — 위반을 잡아 재조회하든 선점 락을 걸든 결과만 계약) |
| USER-CM-132 | 유비쿼터스 | THE 시스템 SHALL 한 반응 요청의 행 변경과 카운트 변경을 단일 트랜잭션으로 처리한다 | `AC-CM-132-1` 카운트 갱신이 실패하면 반응 행도 남지 않는다(부분 반영 없음) |

### I. 이미지 — 선업로드 → 확정 시 이동 (USER-CM-140 ~ 159)

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-CM-140 | 이벤트 | WHEN 인증 사용자가 `POST /api/community/images`로 이미지 1개를 올리면, THE 시스템 SHALL 그 객체를 `temp/` 경로에 저장하고 200과 EP를 반환한다 (결정 Q11 — 선업로드 방식) | `AC-CM-140-1` multipart `image` → 200 `{"success":true,"data":{"imageUrl":"temp/9f1c….jpg"},"message":null}`. `AC-CM-140-2` 응답 직후 그 EP `HeadObject` → 200. `AC-CM-140-3` 어떤 DB 행도 생기지 않는다(글에 붙기 전까지 이미지는 S3에만 있다) |
| USER-CM-141 | 유비쿼터스 | THE 시스템 SHALL 한 업로드 요청에 파일 1개만 받는다 (결정 Q11) | `AC-CM-141-1` `image` 파트 2개 → 첫 번째만 처리하거나 400(구현 선택, 단 2개가 저장되지는 않는다). 5장이면 5회 호출 |
| USER-CM-142 | 예외 | IF 파일 선두 바이트가 JPEG·PNG·WEBP가 아니면, THEN THE 시스템 SHALL 400 `INVALID_COMMUNITY_IMAGE_FORMAT`을 반환한다 | `AC-CM-142-1` GIF·HEIC·`.png`로 이름 붙인 텍스트 → 400 `"JPG, PNG, WEBP 이미지만 업로드할 수 있습니다."`. 판정 근거는 확장자·`Content-Type`이 아니라 매직 넘버(`ProfileImageFormat` 재사용) |
| USER-CM-143 | 예외 | IF 파일이 5MiB를 넘으면, THEN THE 시스템 SHALL 413 `COMMUNITY_IMAGE_TOO_LARGE`를 반환한다 (결정 Q13 — 프로필과 같은 5MiB) | `AC-CM-143-1` 5MiB+1B → 413 `"이미지 크기는 5MB를 넘을 수 없습니다."`, 객체 미생성. `AC-CM-143-2` 5MiB 정확히 → 200 |
| USER-CM-144 | 예외 | IF `image` 파트가 없거나 빈 파일이면, THEN THE 시스템 SHALL 400 `COMMUNITY_IMAGE_REQUIRED`를 반환한다 | `AC-CM-144-1` 파트 없음·0바이트 → 400 `"이미지를 첨부해 주세요."` |
| USER-CM-145 | 유비쿼터스 | THE 시스템 SHALL 모든 이미지 EP를 BaseURL 없는 오브젝트 키로 반환·저장한다 | `AC-CM-145-1` 업로드 응답·상세 `imageUrls`·목록 `thumbnailUrl` 전부 `https://`·버킷명·선행 `/` 없음, `https://victoryfairy.com/` + 값으로 바로 읽힌다(USER-PI-4·9와 동일 규칙) |
| USER-CM-146 | 유비쿼터스 | THE 시스템 SHALL 이 업로드 경로에 횟수 한도를 두지 않는다 (`POST /users/me/profile-image`와 동일) | `AC-CM-146-1` 같은 계정 100회 연속 업로드 → 전부 200(알려진 한계 2). `appId`도 받지 않는다 |
| USER-CM-147 | 이벤트 | WHEN 게시글·댓글·답글이 임시 EP를 싣고 생성·수정되면, THE 시스템 SHALL 그 객체를 `community/{uuid}.{ext}`로 이동하고 이동 후 EP를 저장한다 | `AC-CM-147-1` `imageUrls:["temp/x.jpg"]`로 작성 → 상세 `imageUrls:["community/x.jpg"]`(파일명 동일, 접두만 교체). `AC-CM-147-2` 이동 직후 `temp/x.jpg` `HeadObject` → 404(원본 즉시 삭제, USER-PI-54와 동일). `AC-CM-147-3` 이미지 테이블에 `temp/`로 시작하는 값이 저장되는 일이 없다 |
| USER-CM-148 | 유비쿼터스 | THE 시스템 SHALL 확정 EP를 `community/{uuid}.{ext}` 세그먼트 2개 형태로 만든다 | `AC-CM-148-1` 하위 디렉터리 없음, `/community/*` CloudFront 패턴과 1:1(선행 조건 1). 게시글·댓글·답글 이미지가 같은 접두를 쓴다(셋을 가를 이유가 없다) |
| USER-CM-149 | 예외 | IF `imageUrls` 원소가 `temp/{uuid}.{ext}` 모양도 아니고 **그 글의** 기존 확정 EP도 아니면, THEN THE 시스템 SHALL 400 `INVALID_COMMUNITY_IMAGE_ENDPOINT`를 반환한다 | `AC-CM-149-1` `user-profile-img/a.jpg`·`../x`·`https://victoryfairy.com/temp/x.jpg`·`""`·`null` 원소 → 각각 400 `"유효하지 않은 이미지입니다."`, 행·객체 변화 0. `AC-CM-149-2` 작성 시엔 `community/…` 값 자체가 400(새 글에는 기존 EP가 없다) |
| USER-CM-150 | 예외 | IF 임시 EP가 가리키는 객체가 버킷에 없으면, THEN THE 시스템 SHALL 같은 400 `INVALID_COMMUNITY_IMAGE_ENDPOINT`를 반환한다 | `AC-CM-150-1` 존재하지 않는 `temp/{uuid}.png` → 400, **모양 오류와 같은 문구**(사유를 나누면 남의 EP 존재 여부를 탐색할 수 있다 — `INVALID_PROFILE_IMAGE_ENDPOINT`와 같은 계열의 은닉). `AC-CM-150-2` 24시간 지나 정리된 임시 EP도 여기 걸린다(USER-CM-157) |
| USER-CM-151 | 예외 | IF 수정 요청이 **다른 글·댓글에 붙어 있는** 확정 EP를 싣고 오면, THEN THE 시스템 SHALL 같은 400을 반환한다 | `AC-CM-151-1` 글 B의 `community/y.jpg`를 글 A 수정에 넣음 → 400, 글 A·B 불변. **남의 이미지 가로채기 방지** — 통과시키면 B 삭제 시 A의 이미지도 함께 사라진다 |
| USER-CM-152 | 예외 | IF 게시글 `imageUrls`가 5개, 댓글·답글 `imageUrls`가 3개를 넘으면, THEN THE 시스템 SHALL 400 `COMMUNITY_IMAGE_LIMIT_EXCEEDED`를 반환한다 (결정 Q13 — 상한 5/3) | `AC-CM-152-1` 게시글 6개 → 400 `"이미지는 게시글 5장, 댓글 3장까지 첨부할 수 있습니다."`. `AC-CM-152-2` 5개 → 201. `AC-CM-152-3` 댓글 4개 → 400. 모양 검증(USER-CM-149)보다 **앞**이라 6개짜리 요청은 S3를 조회하지 않는다 |
| USER-CM-153 | 예외 | IF `imageUrls`에 같은 EP가 두 번 있으면, THEN THE 시스템 SHALL 400 `INVALID_COMMUNITY_IMAGE_ENDPOINT`를 반환한다 | `AC-CM-153-1` `["temp/x.jpg","temp/x.jpg"]` → 400(한 객체를 두 번 이동할 수 없다 — 첫 이동이 원본을 지운다) |
| USER-CM-154 | 예외 | IF 이미지 이동(S3 복사)이 실패하면, THEN THE 시스템 SHALL 게시글·댓글을 생성·수정하지 않고 500을 반환한다 (가입과 달리 "이미지만 포기"하지 않는다) | `AC-CM-154-1` S3 복사 실패 상태에서 작성 → 500 `ApiResponse` 래퍼 `"서버 오류가 발생했습니다…"`, 행 미생성. 가입(USER-PI-57)이 이미지를 포기하고 성공시킨 근거는 "이메일 인증 소비가 Redis라 롤백이 안 된다"였고, 여기엔 그런 비가역 선행 효과가 없다. **S3 복사가 DB 쓰기보다 먼저**이므로 DB 쓰기가 실패하면 `community/` 고아 객체가 남을 수 있다(알려진 한계 1) |
| USER-CM-155 | 이벤트 | WHEN 수정으로 확정 EP가 목록에서 빠지면, THE 시스템 SHALL 커밋 후 그 객체를 best-effort로 삭제한다 | `AC-CM-155-1` `[a',b']`→`[b']` 수정 후 `a'` `HeadObject` → 404. `AC-CM-155-2` 삭제 실패 시 200은 그대로, ERROR 로그. **커밋 전에 지우지 않는다** — 트랜잭션이 롤백되면 글은 아직 `a'`를 가리키는데 객체만 사라진다 |
| USER-CM-156 | 유비쿼터스 | THE 시스템 SHALL 확정 EP가 가리키는 객체를 그 글·댓글의 삭제 또는 수정 제외 외의 경로로 지우지 않는다 | `AC-CM-156-1` `community/` 접두에는 어떤 스케줄러·라이프사이클 규칙도 없다(`user-profile-img/`와 같은 처지, USER-PI-84·94). 04:00 정리 회차 100번 뒤에도 잔존 |
| USER-CM-157 | 유비쿼터스 | THE 시스템 SHALL 커뮤니티 임시 이미지를 기존 `temp/` 정리(스케줄러 24h + 라이프사이클 1일)에 그대로 맡긴다 | `AC-CM-157-1` 업로드 후 글에 붙이지 않은 임시 객체가 04:00 회차에 24시간 기준으로 지워진다(프로필 임시 객체와 구분하지 않는다 — 같은 접두, 같은 규칙). **업로드 뒤 24시간 넘겨 작성하면 USER-CM-150의 400이다**(프론트는 작성 화면을 장시간 열어 둔 사용자에게 재업로드를 안내해야 한다) |
| USER-CM-158 | 유비쿼터스 | THE 시스템 SHALL 저장 객체의 `Content-Type`을 판정된 형식으로 설정하고 이동 시 그대로 유지한다 | `AC-CM-158-1` 확정 EP `HeadObject`의 `ContentType`이 `image/jpeg`·`image/png`·`image/webp` 중 판정값(CopyObject 기본 지시자가 메타데이터를 복사한다 — USER-PI-46과 같은 보장) |
| USER-CM-159 | 유비쿼터스 | THE 시스템 SHALL 파일명을 서버가 UUID v4로 생성하고 원본 파일명을 어디에도 쓰지 않는다 | `AC-CM-159-1` `../../etc/passwd`·`내 사진 (1).png`로 올려도 EP가 `temp/{uuid}.{ext}`(USER-PI-41·42 재사용) |

### J. 신고 → 즉시 블라인드 (USER-CM-170 ~ 183)

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-CM-170 | 이벤트 | WHEN 인증 사용자가 타인의 게시글을 신고하면, THE 시스템 SHALL 그 게시글을 즉시 `blinded=true`로 전환하고 200을 반환한다 | `AC-CM-170-1` `POST /posts/1/report` → 200 `{"success":true,"data":null,"message":null}`, `blinded=1`. **신고 1건 = 즉시, 임계치·관리자 승인 없음**(채팅 QUIZ-CHAT-20과 동일) |
| USER-CM-171 | 예외 | IF 신고자가 작성자 본인이면, THEN THE 시스템 SHALL 403 `COMMUNITY_SELF_REPORT_NOT_ALLOWED`를 반환하고 블라인드하지 않는다 | `AC-CM-171-1` 자기 글 신고 → 403 `"자신의 글은 신고할 수 없습니다."`, `blinded=0` 유지, 신고 행 미생성. 기존 `SELF_REPORT_NOT_ALLOWED`(문구 "자신의 메시지는…")를 재사용하지 않는 이유는 문구가 채팅 전용이라서다 |
| USER-CM-172 | 예외 | IF 이미 블라인드된 게시글을 신고하면, THEN THE 시스템 SHALL 상태를 바꾸지 않고 200을 반환한다 | `AC-CM-172-1` 재신고 → 200, 여전히 `blinded=1`(멱등, QUIZ-CHAT-28과 동일) |
| USER-CM-173 | 예외 | IF 없거나 삭제된 게시글을 신고하면, THEN THE 시스템 SHALL 404를 반환한다 | `AC-CM-173-1` 삭제 글 신고 → 404 `COMMUNITY_POST_NOT_FOUND`(QUIZ-CHAT-29와 동일) |
| USER-CM-174 | 유비쿼터스 | THE 시스템 SHALL 신고 요청에 본문을 요구하지 않는다 (결정 Q9 — 신고 사유 없음) | `AC-CM-174-1` 본문 없이 `POST` → 200. 본문을 보내도 무시 |
| USER-CM-175 | 이벤트 | WHEN 신고가 접수되면(USER-CM-170·172 모두), THE 시스템 SHALL 신고 행을 (신고자, 대상 유형, 대상 id, 시각)으로 보존하되 같은 신고자의 같은 대상 재신고는 행을 추가하지 않는다 (결정 Q9 — 채팅과 달리 보존) | `AC-CM-175-1` A가 글 1 신고 → 신고 테이블 1행. `AC-CM-175-2` A 재신고 → 여전히 1행(UNIQUE `(reporter, target_type, target_id)`). `AC-CM-175-3` B 신고 → 2행. **읽는 API는 없다**(제외 범위) — 운영자가 DB에서 직접 본다 |
| USER-CM-176 | 유비쿼터스 | THE 시스템 SHALL 블라인드된 게시글을 전체 목록·인기 목록에서 제외한다 | `AC-CM-176-1` 신고 직후 두 목록에 미포함. `AC-CM-176-2` 내 글 목록에는 `blinded:true`로 포함(USER-CM-65) |
| USER-CM-177 | 상태 | WHILE 게시글이 블라인드 상태인 동안, THE 시스템 SHALL 상세·수정·반응·댓글 작성·댓글 목록·댓글 수정·댓글 반응·댓글 신고 요청에 410을 반환한다 | `AC-CM-177-1` 8개 경로 전부 410(게시글 경로는 `COMMUNITY_POST_BLINDED`, 그 글의 댓글·답글 경로도 소속 글 사유로 같은 코드). `AC-CM-177-2` 예외는 둘 — 작성자의 `DELETE`(USER-CM-94)와 재신고(USER-CM-172) |
| USER-CM-178 | 유비쿼터스 | THE 시스템 SHALL 블라인드 해제 경로를 두지 않는다 | `AC-CM-178-1` 어떤 엔드포인트도 `blinded`를 false로 되돌리지 않는다(관리자 범위 밖 — 채팅과 같은 결정. 복구는 DB 직접 UPDATE) |
| USER-CM-179 | 유비쿼터스 | THE 시스템 SHALL 블라인드 시 본문·이미지·댓글·반응·카운트를 보존한다 | `AC-CM-179-1` 블라인드 전후 행 값·S3 객체 불변(삭제가 아니다). 해제하면 그대로 다시 보인다 |
| USER-CM-180 | 이벤트 | WHEN 인증 사용자가 타인의 댓글·답글을 신고하면, THE 시스템 SHALL 그 행만 즉시 `blinded=true`로 전환하고 소속 글 `commentCount`를 1 줄인다 | `AC-CM-180-1` `POST /comments/5/report` → 200, `blinded=1`, `commentCount` −1. `AC-CM-180-2` 재신고 → 200, `commentCount` 더 안 줄어듦. `AC-CM-180-3` 답글 2개 달린 댓글 신고 → 답글은 `blinded=0` 그대로, `commentCount`는 1만 줄어듦(USER-CM-211) |
| USER-CM-181 | 상태 | **(개정 1차 수정)** WHILE 댓글·답글이 블라인드 상태인 동안, THE 시스템 SHALL 그 행을 목록에서 제외하거나(보이는 답글이 있는 댓글이면 `status: "BLINDED"` 자리 표식으로 유지, USER-CM-213) 수정·반응에 410 `COMMUNITY_COMMENT_BLINDED`를 반환한다 | `AC-CM-181-1` 답글 없는 블라인드 댓글 → 목록 미포함. 블라인드 답글 → `replies[]` 미포함. `AC-CM-181-2` 작성자 `PUT`·누구든 `PUT …/reaction` → 410 `"신고로 숨김 처리된 댓글입니다."`. `AC-CM-181-3` 작성자 `DELETE` → 204 |
| USER-CM-182 | 예외 | IF 댓글·답글 신고자가 그 작성자면 403, 대상이 없거나 삭제됐으면 404를 THE 시스템 SHALL 반환한다 | `AC-CM-182-1` 자기 댓글 → 403 `COMMUNITY_SELF_REPORT_NOT_ALLOWED`(문구는 글·댓글 공용 "자신의 글은…"). `AC-CM-182-2` 삭제 댓글(자리 표식 포함) → 404 `COMMUNITY_COMMENT_NOT_FOUND` |
| USER-CM-183 | 유비쿼터스 | THE 시스템 SHALL 신고자·작성자를 포함한 모든 계정에 블라인드를 동일하게 적용한다 | `AC-CM-183-1` 신고자·작성자·제3자 세 토큰으로 상세 → 전부 410, 본문 동일(결정 Q7) |

### K. 탈퇴 계정 콘텐츠 (USER-CM-190 ~ 195)

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-CM-190 | 상태 | WHILE 작성자 계정이 탈퇴(`exit_at` 기록)했으나 아직 하드 삭제되지 않은 동안, THE 시스템 SHALL 그 글·댓글·답글을 종전 닉네임·프로필 이미지로 그대로 노출한다 (결정 Q4 — 채팅과 동일) | `AC-CM-190-1` 탈퇴 직후 그 계정의 글 상세 → 200, `author.nickname` 종전 값. 행은 `users_account`에 남아 있어 역참조가 된다. (프로필 이미지 객체는 탈퇴 시 삭제되므로 `profileImgUrl` 값은 남되 CDN에서 404 — 프로필 이미지 USER-PI-75와 같은 잔존) |
| USER-CM-191 | 이벤트 | WHEN 만료 데이터 정리가 탈퇴 30일 경과 계정을 하드 삭제하면, THE 시스템 SHALL 그 계정의 게시글·댓글·답글 작성자를 `(알수없음)` 더미 계정으로 **삭제 전에** 이관한다 (결정 Q4 — `ChatRepository.reassignSender`와 같은 정책) | `AC-CM-191-1` 회차 후 글·댓글 행이 남고 `user_account_id`가 `UnknownAccountPolicy.UID` 계정의 id. `AC-CM-191-2` 상세 `author.nickname`이 `(알수없음)`, `profileImgUrl: null`. `AC-CM-191-3` 소프트 삭제·블라인드 행도 함께 이관(거르지 않는다 — 하나라도 남으면 FK가 계정 삭제를 막는다, `reassignSender`와 같은 이유). **`expired-data-cleanup.md`(승인됨) 개정과 `ExpiredAccountEraser.erase()` ② 단계 확장이 필요하다** — 충돌 4 |
| USER-CM-192 | 이벤트 | WHEN 계정이 하드 삭제되면, THE 시스템 SHALL 그 계정의 반응 행을 소유자만 NULL로 비운 채 보존한다 | `AC-CM-192-1` 반응 FK `@OnDelete(SET_NULL)` + nullable. 삭제 후 `likeCount` 불변(집계 보존 — `QuizLike` SET_NULL 선례. 더미 계정 이관은 UNIQUE 때문에 탈퇴자 여럿의 같은 글 반응이 충돌한다). `AC-CM-192-2` `migrate-quiz-like-account-set-null.sql` 때의 교훈 — **신규 테이블이라 엔티티 선언만으로 SET NULL이 걸리지만**, 이미 테이블이 생긴 환경이 있으면 1회성 DDL이 필요하고 `QuizLikeDeleteRuleInspector`류의 확인 대상에 들어간다(충돌 4) |
| USER-CM-193 | 이벤트 | WHEN 계정이 하드 삭제되면, THE 시스템 SHALL 그 계정의 신고 행을 신고자만 NULL로 비운 채 보존한다 | `AC-CM-193-1` 신고 테이블 `reporter_account_id` SET_NULL. 블라인드된 글의 근거가 신고자 탈퇴로 사라지지 않는다 |
| USER-CM-194 | 유비쿼터스 | THE 시스템 SHALL 게시글·댓글 작성자 FK에 `@OnDelete(CASCADE)`를 걸지 않는다 | `AC-CM-194-1` 이관 없이 `DELETE FROM users`를 시도하면 FK 위반으로 실패한다(`chatrooms.owner_account_id`와 같은 fail-closed — 이관을 빠뜨린 배포가 글을 조용히 지우는 대신 시끄럽게 실패한다) |
| USER-CM-195 | 유비쿼터스 | THE 시스템 SHALL 조회 창 Redis 키를 계정 삭제와 무관하게 TTL로만 소멸시킨다 | `AC-CM-195-1` 정리 회차가 Redis를 건드리지 않는다(USER-CM-79) — 300초짜리 키라 정리할 것이 없다 |

### L. 신규 `ErrorCode`·스키마·배포 선행 (USER-CM-200 ~ 206)

| ID | 유형 | 요구사항 | 인수 기준 |
|---|---|---|---|
| USER-CM-200 | 유비쿼터스 | **(개정 1차 수정 — 13건)** THE 시스템 SHALL `:common` `ErrorCode`에 아래 13건을 신설한다 | `AC-CM-200-1` 존재: `COMMUNITY_CATEGORY_NOT_FOUND(404, "존재하지 않는 카테고리입니다.")` · `COMMUNITY_POST_NOT_FOUND(404, "존재하지 않는 게시글입니다.")` · `COMMUNITY_COMMENT_NOT_FOUND(404, "존재하지 않는 댓글입니다.")` · `COMMUNITY_POST_BLINDED(410, "신고로 숨김 처리된 게시글입니다.")` · `COMMUNITY_COMMENT_BLINDED(410, "신고로 숨김 처리된 댓글입니다.")` · `COMMUNITY_NOT_AUTHOR(403, "작성자만 수정·삭제할 수 있습니다.")` · `COMMUNITY_SELF_REPORT_NOT_ALLOWED(403, "자신의 글은 신고할 수 없습니다.")` · **`COMMUNITY_REPLY_DEPTH_EXCEEDED(400, "답글에는 답글을 달 수 없습니다.")`**(개정 1차 신설) · `COMMUNITY_IMAGE_REQUIRED(400, "이미지를 첨부해 주세요.")` · `INVALID_COMMUNITY_IMAGE_FORMAT(400, "JPG, PNG, WEBP 이미지만 업로드할 수 있습니다.")` · `INVALID_COMMUNITY_IMAGE_ENDPOINT(400, "유효하지 않은 이미지입니다.")` · `COMMUNITY_IMAGE_LIMIT_EXCEEDED(400, "이미지는 게시글 5장, 댓글 3장까지 첨부할 수 있습니다.")` · `COMMUNITY_IMAGE_TOO_LARGE(413, "이미지 크기는 5MB를 넘을 수 없습니다.")`. `AC-CM-200-2` **410은 이 저장소 최초**다(`ErrorCode.status`는 int라 제약 없음). `AC-CM-200-3` 413은 멀티파트 해석 단계(`handleMaxUploadSizeExceeded`)에서 나가므로 **프로필 코드와 분기되는지** 확인 — 그 핸들러가 `PROFILE_IMAGE_TOO_LARGE`를 고정으로 내보내면 경로별 분기가 필요하다(알려진 한계 4) |
| USER-CM-201 | 유비쿼터스 | THE 시스템 SHALL 신규 테이블 전부를 domain 엔티티 선언으로 정의하고 user `ddl-auto=update`로 생성한다 | `AC-CM-201-1` 빈 DB에서 user 기동 → 카테고리·게시글·댓글(답글 포함)·반응 2·이미지 2·신고 = 8테이블 생성, UNIQUE·FK 정책(CASCADE/SET_NULL/없음)이 선언대로 걸림(신규 테이블이라 `quizzes_like` 선례대로 자동 반영된다). `AC-CM-201-2` 공유 엔티티(`UserAccount` 등)에는 **컬럼을 추가하지 않는다** — quiz(`ddl-auto=none`) 배포 선행 조건이 생기지 않게 하기 위해서다 |
| USER-CM-202 | 유비쿼터스 | THE 시스템 SHALL 1회성 DDL `infra/sql/migrate-community.sql`을 함께 둔다 | `AC-CM-202-1` 파일 존재, 어떤 이유로든 테이블이 제약 없이 먼저 생긴 환경을 위한 UNIQUE·FK 확인·추가 절차(적용 전 실제 상태 조회 포함 — `migrate-quiz-like.sql` 선례) |
| USER-CM-203 | 유비쿼터스 | THE 시스템 SHALL 카테고리 시드를 `infra/sql/community-categories-init.sql`로 두고 user `data-locations`에 등록한다 | `AC-CM-203-1` USER-CM-12·13. 구단 매핑은 `teams.code`(`LG`, `OB` …)로 해석 |
| USER-CM-204 | 유비쿼터스 | THE 시스템 SHALL 이미지 서빙을 CloudFront `/community/*` behavior에 의존한다 | `AC-CM-204-1` 선행 조건 1이 배포된 환경에서 `https://victoryfairy.com/community/{uuid}.jpg` → 200 + 이미지. **앱 배포만으로는 이미지가 안 보인다** |
| USER-CM-205 | 유비쿼터스 | THE 시스템 SHALL 만료 데이터 정리 계약(`expired-data-cleanup.md`)을 이관 단계 확장으로 개정한 뒤 배포한다 | `AC-CM-205-1` 그 문서에 게시글·댓글 이관 요구사항이 추가되고 `ExpiredAccountEraser` ② 단계가 `reassignOwner`·`reassignSender`에 이어 게시글·댓글 이관을 포함한다. **이 개정 없이 커뮤니티를 먼저 배포하면 첫 하드 삭제 회차가 글을 가진 계정에서 FK 위반으로 계정 단위 실패한다**(USER-CM-194가 의도한 fail-closed — 데이터는 안전하지만 회차 로그에 ERROR가 쌓인다) |
| USER-CM-206 | 유비쿼터스 | THE 시스템 SHALL 새 스케줄러·새 Redis 키 접두를 추가하지 않는다 | `AC-CM-206-1` `AnyCleanupJobEnabled`에 추가할 스위치 없음(새 `@Scheduled` 빈이 없다). Redis 키는 `community:post:view:*` 하나의 계열뿐이고 TTL로 소멸 |

## 기존 정책과의 충돌 · 재사용 (모듈 컨텍스트 대조)

1. **카운터 컬럼 vs `quiz-like.md`의 "같은 사실을 두 곳에 두지 않는다"** — 퀴즈 좋아요는 `quizzes.like_count`를 두지 않았다(제외 범위에 명시). 커뮤니티는 `like_count`·`dislike_count`·`view_count`·`comment_count` 카운터를 둔다. 다른 선택인 이유: 퀴즈 좋아요는 "한 문제의 수"만 필요해 `countBy` 한 번이면 됐지만, 커뮤니티는 **전체 게시글을 좋아요 순으로 정렬**하고 **상위 5건을 점수로 고른다** — 집계로 풀면 요청마다 반응 테이블 전체 GROUP BY다. `view_count`는 애초에 행이 없어 카운터 외 길이 없다. 대가로 USER-CM-130(일치)·132(단일 트랜잭션)가 계약에 들어왔다. 승인 시 이 차이를 `quiz-like.md`에 소급하지 않는다(그쪽은 그대로 맞다).
2. **반응 취소 = 행 삭제 vs `QUIZ-LIKE-8`(플래그 보존)** — 퀴즈는 `liked=false` 행을 남겨 `updated_at`을 보존했다. 커뮤니티는 3상태라 "없음"을 행 부재로 두는 쪽이 UNIQUE·SET NULL과 자연스럽게 맞고, 반응 이력을 쓰는 곳이 없다. 다만 만료 데이터 정리가 퀴즈 취소 행을 지우는 단계(`deleteCancelledByUserAccountId`)는 커뮤니티에 필요 없다(취소 행이 없다).
3. **`SELF_REPORT_NOT_ALLOWED` 미재사용** — 문구가 "자신의 메시지는…"이라 글·댓글에 맞지 않는다. 기존 문구를 바꾸면 `chat.md` API 문서·채팅 테스트가 함께 바뀌므로 신규 코드로 분리(USER-CM-171).
4. **만료 데이터 정리(`expired-data-cleanup.md`, 승인됨 USER-EDC-1~50)와의 접점** — 게시글·댓글 작성자 FK를 CASCADE 없이 두면(USER-CM-194) 그 배치의 ② 이관 단계가 확장돼야 한다(USER-CM-191·205). 순수 DB 작업이라는 그 배치의 성격은 유지된다(S3 호출 없음 — 이관된 글의 이미지는 그대로 남는다). 반응·신고는 SET NULL이라 배치 코드 변경이 없다. **이 개정은 별도 요구사항 개정으로 진행해야 한다**(그 문서는 승인 상태라 이 문서가 대신 고칠 수 없다).
5. **`temp/` 접두 공유** — 커뮤니티 임시 이미지를 프로필과 같은 `temp/`에 두므로 `TempProfileImageCleanupService`·라이프사이클이 **그대로 커뮤니티 임시 이미지도 지운다**(USER-CM-157). 그 서비스의 "DB를 아예 안 본다 — temp 객체는 어떤 계정에도 저장되지 않는다"는 전제는 커뮤니티가 **확정 전 EP를 DB에 절대 저장하지 않는 것**(USER-CM-147-3)으로 유지된다. `ProfileImagePolicy.validateTempEndpoint`·`ProfileImageFormat`·`ProfileImageUploader.validate`는 재사용 대상이다 — 다만 프로필 전용 `ErrorCode`를 던지므로 코드 분기가 필요하다(구현 판단, 단 응답 코드는 USER-CM-200대로).
6. **`SecurityConfig` 무수정** — `/api/community/**`는 `anyRequest().authenticated()`에 자연히 걸린다(결정 Q15).
7. **`Clock` 빈 사용** — 인기 목록 7일 기준(USER-CM-50)·`deleted_at`(USER-CM-90·112)은 `ClockConfig`의 `Clock`을 써야 한다. `LocalDateTime.now()` 직접 호출은 모듈 컨텍스트가 "남은 부채"로 적은 함정이다.
8. **조회 필터 존재 검증 안 함 컨벤션** 유지(USER-CM-47) + **대상 자원 지정은 404** 예외(USER-CM-27·115) — 둘 다 기존 기준(`/players?teamId=` vs `/games/lineup?gameId=`)을 그대로 따른다.
9. **`GlobalExceptionHandler`의 `Map<필드명,메시지>` 비결정성** — `title`에 `@NotBlank`+`@Size`를 겹치면 동시 위반 시 메시지가 비결정적이다(`SignupRequest` 함정). 두 제약은 겹치지 않는 조건(빈 값 vs 초과)이라 같은 값이 둘 다 위반할 수 없어 문제없지만, 테스트는 한 번에 한 위반만 넣을 것.
10. **채팅 `Chat`에는 부모 개념이 없다** — 답글의 "부모 삭제 비연쇄 + 자리 표식"은 이 저장소 첫 사례라 선례가 없다. 가장 가까운 것은 `Chatroom.delete()`(방 소프트 삭제 뒤 메시지는 그대로)이며, 그쪽도 자식(메시지)을 건드리지 않는다는 점에서 같은 방향이다.

## 결정 근거 (트레이드오프)

**조회수 5분 창: Redis `SET NX EX` vs DB 테이블.** Redis 안: 키 하나(`community:post:view:{postId}:{accountId}`, TTL 300)로 "최초 생성 시점부터 고정 창"이 정확히 `NX`+`EX` 의미와 일치하고, 조회 경로에 DB 쓰기가 조회수 UPDATE 1건으로 끝난다. user 모듈에는 이미 `StringRedisTemplate`(이메일 인증·프로필 한도·정리 락)이 있어 새 의존이 없다. 대가는 Redis 장애 시의 선택(USER-CM-78)과 키 수(활성 사용자 × 5분 내 본 글 수 — 수천 건 수준, 메모리 무시 가능). DB 안: `(post_id, account_id, viewed_at)` 테이블에 UNIQUE + 300초 비교. 장애 축이 하나 줄지만 **조회마다 INSERT/UPDATE가 붙고**(읽기 경로에 쓰기 부하), 만료 행을 지우는 배치가 또 필요하다(새 `@Scheduled` → `AnyCleanupJobEnabled` 수정 함정). Redis로 확정.

**카테고리: 코드 테이블 vs `team_id nullable`.** `team_id nullable` 안은 테이블이 하나 적고 "구단 = 카테고리"가 바로 보이지만, 자유게시판에 id가 없어 카테고리 조회 API가 `teams` + 가상 항목을 합성해야 하고, 카테고리가 하나 더 늘 때(공지·직관 후기 등) 코드가 바뀐다. 코드 테이블 안은 시드 한 행으로 늘고 정렬·표시명을 구단명과 분리할 수 있다. 코드 테이블로 확정(USER-CM-10).

**블라인드 응답 코드.** 404는 "없는 글"과 섞여 프론트가 "신고로 숨김" 안내를 못 한다. 403은 이 API에서 도메인 규칙 거절(`CHATROOM_TEAM_MISMATCH` 등)에 쓰이고 있고, 프론트 인터셉터가 권한 문제로 해석할 여지가 있다. 410 Gone은 "있었으나 지금은 제공되지 않음"이라 의미가 정확하고 저장소에서 처음 쓰는 코드라 다른 뜻과 충돌하지 않는다. 사용자 요구("HTTP 에러 코드 → 예외 페이지")에 가장 맞는 것이 410이다.

**이미지: 선업로드 vs multipart 동시 전송.** 동시 전송은 요청 하나로 끝나지만 JSON 본문과 파일 파트를 한 요청에 섞어야 하고(`@RequestPart` 조합), 수정 시 "이미지 3장 중 1장만 교체"를 표현할 방법이 없어 매번 전부 재전송하게 된다. 선업로드는 프로필 임시 업로드와 같은 흐름이라 검증·정리·EP 규칙을 그대로 재사용하고, 수정이 EP 배열 교체로 끝난다. 대가는 호출 횟수(N+1)와 24시간 유효기간(USER-CM-157). 선업로드로 확정.

**삭제: soft vs hard.** hard는 CASCADE로 댓글·반응·이미지 행이 함께 사라져 단순하고 이관 대상도 준다. soft는 신고 근거·분쟁 대응용 보존이 되고 채팅(`deleted_at`)과 일관되지만 모든 조회에 `deleted_at IS NULL`이 붙고 행이 계속 쌓인다. 복구 API는 어차피 범위 밖이라 soft의 실익은 "운영자가 DB에서 볼 수 있다"뿐이다. 그럼에도 soft로 확정 — 신고 행(USER-CM-175)이 가리키는 대상이 사라지면 신고 보존의 의미가 없고, 채팅 선례와 맞춘다. **답글 구조가 들어오면서 soft의 실익이 하나 더 생겼다** — 부모 삭제 뒤 자리 표식(USER-CM-212)은 부모 행이 남아 있어야 `createdAt`·`commentId`를 그대로 낼 수 있다.

**신고 테이블 보존.** 채팅은 "신고 1건 = 즉시 blind"만 하고 이력을 남기지 않았다. 커뮤니티는 글 하나가 채팅 메시지보다 무겁고(작성 시간·이미지), 신고 1건으로 숨기는 정책이 공격적이라 **누가 신고했는지** 없이는 악의적 신고를 가려낼 길이 없다. 행 하나(4컬럼)의 비용은 작다. 보존으로 확정.

**페이징: offset vs cursor.** 좋아요 순·조회수 순은 값이 계속 바뀌어 offset에서 중복·누락이 생기지만, 게시판 UI는 페이지 번호 이동이 보통이고 cursor는 정렬 축마다 복합 커서(`(like_count, id)`)를 따로 설계해야 한다. 채팅 히스토리가 이미 offset(`page`, 고정 30)이다. offset(`page`/`size`)으로 확정, 중복·누락은 알려진 한계로 둔다.

**인기 점수·기간.** 산식에 정답이 없어 셋만 고정한다 — (a) 기간 없이 누적하면 초기 글이 영구 상단 고정, (b) 좋아요와 조회수는 단위가 달라 가중치가 필요, (c) 싫어요를 빼면 "논쟁적인 글"이 인기에서 사라진다. `likeCount × 10 + viewCount`, 최근 7일, 싫어요 미반영으로 확정. 가중치·기간은 운영하며 바꿀 값이지만 설정값으로 빼는 것까지는 요구하지 않는다.

**조회수 증가를 GET 부수효과로 두는 것.** `POST /posts/{id}/view`를 따로 두면 프론트가 두 번 호출해야 하고 한 번만 부르는 클라이언트는 영영 안 센다. 상세 GET 하나가 "봤다"의 유일한 사실이라 거기에 둔다(채팅 히스토리처럼 읽기 전용이 아닌 GET이 이 모듈에 하나 생긴다).

**답글(개정 1차): 부모 삭제·블라인드를 자식에 연쇄하지 않는다.** 사용자 원문 "부모 삭제 시 CASCADE가 아닌, 삭제 처리는 개별로 처리"가 출발점이다. 연쇄하면 남의 답글이 부모 작성자의 결정 하나로 사라지고(답글 작성자 입장에선 자기 글이 증발), 신고 1건으로 블라인드되는 정책과 겹치면 **댓글 하나를 신고해 그 아래 답글 전부를 지우는 공격**이 된다. 비연쇄의 대가는 "부모 없는 답글"의 표현이다 — 선택지는 셋이었다. (A) **자리 표식**: 부모를 `status: DELETED|BLINDED`로 목록에 남기고 본문·작성자를 비운다. 답글의 문맥(어떤 댓글에 대한 답인지)이 자리로 유지되고 프론트가 "삭제된 댓글입니다" 한 줄을 그리면 된다. 비용은 `status` 필드와 조회 시 "보이는 답글이 있는가" 판정. (B) **답글을 최상위로 승격**: 부모가 사라지면 답글이 댓글 자리로 올라간다. 문맥이 끊기고 "답글이었다"는 사실이 지워진다. (C) **답글은 남기되 목록에서 숨김**: `commentCount`는 세는데 보이지 않는 글이 생겨 숫자가 거짓이 된다. A로 확정. 자리 표식은 **저장 상태가 아니라 조회 시점 판정**이다(USER-CM-214) — 플래그를 두면 마지막 답글이 사라질 때 부모를 다시 갱신해야 하는 쓰기 경로가 생긴다. 새 답글은 자리 표식에 달 수 없다(USER-CM-117) — 허용하면 삭제된 댓글이 영원히 자리를 지키고, 블라인드된 댓글이 토론의 거점으로 계속 산다. 깊이는 1로 고정한다 — 2단 이상은 응답이 재귀 구조가 되고 "부모의 부모가 삭제되면"의 조합이 깊이마다 늘어난다. `commentCount`에 답글을 **포함**한다 — 게시판의 "댓글 N"은 통상 대화 전체 크기이고, 빼면 목록의 숫자와 열어 본 글의 분량이 어긋난다.

## 알려진 한계
1. **`community/` 고아 객체** — 이동(S3 복사) 뒤 DB 쓰기가 실패하면 확정 객체가 참조 없이 남는다(USER-CM-154). 회수 배치 없음(프로필 `user-profile-img/` 고아와 같은 처지).
2. **인증 업로드 무제한** — 계정당 업로드 횟수 한도가 없어 S3 쓰기를 소모시킬 수 있다(USER-CM-146). 임시 객체는 24시간 뒤 사라져 저장 비용은 유한하다.
3. **인기 목록 매 요청 집계** — 7일 범위 전체 스캔 + 정렬. 게시글 수천 건 규모에서는 인덱스(`created_at`)로 충분하지만 캐시는 없다.
4. **413의 코드 분기** — 멀티파트 크기 초과는 컨트롤러 전 단계에서 나가므로 경로별로 다른 `ErrorCode`를 내려면 web-support 핸들러가 경로를 봐야 한다. 못 가르면 `PROFILE_IMAGE_TOO_LARGE` 문구("프로필 이미지")가 커뮤니티에서도 나간다 — 구현 시 확인.
5. **offset 페이징의 중복·누락** — `likes`·`views` 정렬에서 페이지 사이에 값이 바뀌면 같은 글이 두 페이지에 나오거나 빠질 수 있다.
6. **블라인드 글 작성자 알림 없음** — 작성자는 마이페이지의 `blinded:true`로만 알 수 있다. 댓글·답글이 블라인드된 작성자는 그것조차 알 수단이 없다(내 댓글 목록이 범위 밖).
7. **조회 창은 계정 기준** — 같은 사람이 계정 둘을 쓰면 두 번 센다. 비로그인 조회가 없어 IP 기준 창은 필요 없다.
8. **답글 비페이징** — 한 댓글의 답글은 전부 한 응답에 실린다(USER-CM-118). 답글이 수백 개 달린 댓글은 그 페이지 응답이 커진다. 상한을 두지 않은 이유는 "N개까지만"의 N이 또 가정이 되기 때문이며, 실측 후 필요하면 답글 페이징을 별도 요구사항으로 올린다.
9. **자리 표식 판정 비용** — 댓글 목록 한 페이지의 삭제·블라인드 최상위 댓글마다 "보이는 답글이 있는가"를 봐야 한다. 페이지의 답글을 어차피 `IN` 한 방으로 가져오므로(`QuizOptionRepository`의 2쿼리 방식) 추가 쿼리는 없지만, 페이지 안 최상위 댓글 수가 `size`보다 많아질 수 있다(자리 표식 후보를 걸러내기 전에 읽어야 함) — 구현이 2단계 조회를 쓰는 것은 허용된 비용이다.

## 결정 기록 (2026-10-08, 초안 미해결 질문 17건의 확정)
> 질문 번호는 초안의 번호 그대로다. "추천안 확정(사용자 미응답, 기본값)"은 사용자가 답하지 않아 오케스트레이터가 추천안으로 확정한 항목이다 — 이후 사용자가 뒤집으면 해당 요구사항만 개정한다.

1. **Q1 인기 점수·기간** → **A 확정**: `likeCount×10 + viewCount`, 최근 7일, 싫어요 미반영, 동점 최신순(USER-CM-50~52). B(좋아요 단독·기간 없음)는 오래된 글이 영구 상단, C(24시간)는 새벽에 5건이 안 찬다.
2. **Q2 인기 5건을 전체 목록에서도** → **A 확정**: 중복 허용, 제외는 프론트가 `postId`로 거른다(USER-CM-49). B(제외)는 전체 목록이 인기 집계에 의존해 페이지 경계가 흔들리고 `sort=likes`에서 1위 글이 빠지는 역설이 생긴다.
3. **Q3 댓글 조회수** → **A 확정 — 댓글·답글에 조회수는 없다**: 응답에서 `viewCount` 제거(USER-CM-106). 댓글은 단독으로 열리는 자원이 아니라 "본 횟수"를 셀 사건이 없다. B(댓글 목록 반환 횟수)는 글 조회수와 사실상 같은 값이고 목록마다 N행 UPDATE, C(댓글 상세 API)는 여는 화면이 없으면 영원히 0.
4. **Q4 탈퇴 계정 글·댓글** → **A 확정**: 채팅과 동일 — 30일간 종전 닉네임, 하드 삭제 시 `(알수없음)` 이관(USER-CM-190·191). B(탈퇴 즉시 soft delete)는 남의 답글까지 접근 불가가 되고, C(즉시 이관)는 탈퇴 트랜잭션이 커뮤니티 테이블을 알아야 해 `UserAccountService.withdraw` 협력자가 늘며 채팅과 어긋난다. 선행: `expired-data-cleanup.md` 개정(USER-CM-205).
5. **Q5 대댓글** → **사용자가 추천안(평면)을 뒤집음 — 1단 답글 확정**: 답글의 답글 없음(USER-CM-116), 부모 삭제·블라인드 비연쇄(USER-CM-210·211), 보이는 답글이 있는 부모는 자리 표식(USER-CM-212·213), 없으면 목록 제외(USER-CM-214), 자리 표식에 새 답글 불가(USER-CM-117), 부모는 같은 글의 최상위 댓글이어야 함(USER-CM-115·116), `commentCount`는 답글 포함(USER-CM-113). 트레이드오프는 "결정 근거 — 답글" 절.
6. **Q6 블라인드 HTTP 코드** → **A 확정**: 410 + 신규 코드 2건(USER-CM-63·200). 404는 "신고로 숨김" 페이지 불가, 403은 프론트 인터셉터가 권한 문제로 오해.
7. **Q7 블라인드 글과 작성자** → **A 확정**: 상세는 전원 410, 마이페이지 목록에만 `blinded:true`, 수정 불가·삭제 가능(USER-CM-63·65·84·94·183). B(본인 상세 200+수정)는 수정이 블라인드 우회 수단, C(완전 은닉)는 작성자가 이유를 모르고 삭제도 못 한다.
8. **Q8 카테고리 저장** → **A 확정**: 코드 테이블 + 시드 11행, `team_id nullable`(USER-CM-10~13). B(`team_id nullable` 직접)는 자유게시판에 안정적 id가 없고 카테고리 추가마다 코드 변경.
9. **Q9 신고 테이블·사유** → **A 확정**: 신고 행 보존(신고자·대상·시각), 사유 본문 없음(USER-CM-174·175). B(미보존)는 악의적 신고를 가릴 수 없고, C(사유 enum)는 프론트 UI·enum 검증이 더 붙는다 — 사유는 나중에 컬럼 추가로 된다.
10. **Q10 삭제** → **A 확정**: soft(`deleted_at`) + 객체 즉시 best-effort 삭제(USER-CM-90·96·97·112). B(hard)는 신고 행의 대상과 자리 표식의 부모 행이 사라진다, C(객체 보존)는 복구 API가 없어 실익이 없다.
11. **Q11 이미지 업로드 흐름** → **A 확정**: 선업로드 1파일씩 → EP 배열 전달(USER-CM-140·141). B(작성 요청 multipart)는 수정 시 부분 교체 불가 + `max-request-size` 10MB에 5장이 안 들어감, C(다중 파일)는 부분 성공 응답이 필요하고 프로필 업로더를 재사용 못 한다.
12. **Q12 수정 시 이미지 교체** → **A 확정**: 선언적 전체 교체(요청 배열 = 최종 상태, 순서 포함)(USER-CM-87). B·C(분리 엔드포인트)는 2~3요청에 중간 상태 노출.
13. **Q13 상한값 묶음** → **그대로 확정**: 제목 100 / 본문 5000 / 댓글·답글 1000 / 이미지 게시글 5장·댓글 3장 / 5MiB / `size` 1~50 / 목록 항목 본문 미포함(USER-CM-8·24·25·41·102·143·152). 각 숫자가 한 요구사항에만 있어 서로 연쇄 없음.
14. **Q14 식별자** → **추천안 확정(사용자 미응답, 기본값)**: 내부 PK 숫자 노출, `uid` 없음(USER-CM-5). 공개 게시판이라 열거로 새는 정보가 없고, 고write 테이블에 랜덤 UUID 유니크 인덱스를 얹을 이유가 없다(채팅 `Chat`이 uid를 안 둔 이유).
15. **Q15 비로그인 조회** → **추천안 확정(사용자 미응답, 기본값)**: 전 경로 인증 필수(USER-CM-1). B(GET permitAll)는 `SecurityConfig` 5줄 + 비로그인 조회수를 셀 수 없음. 요청서 "회원들은 커뮤니티에서…"와 일치.
16. **Q16 모듈 배치** → **추천안 확정(사용자 미응답, 기본값)**: user 모듈. 반대 근거(신고·이관 정책 코드가 quiz에 있음)보다 S3·Redis·`Clock`·배치가 전부 user에 있다는 쪽이 무겁고, quiz에 넣으면 "quiz 모듈이 채팅까지 담는 잡동사니" 경향이 굳는다.
17. **Q17 반응 API 형태** → **A 확정**: `PUT …/reaction {reaction: LIKE|DISLIKE|NONE}` 상태 지정·멱등(USER-CM-120~124). B(토글 2개)는 더블클릭·재시도에 취약하고 LIKE→dislike 전환 규칙이 따로 필요, C(4개 경로)도 같은 규칙이 필요.

## 미해결 질문
- 없음. (검증 깊이는 이 문서의 범위 밖이며 별도로 정해진다.)
