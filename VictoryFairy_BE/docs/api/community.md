# 커뮤니티(community) API 명세

> **도메인** `community` — 구단별/자유 게시판의 게시글·댓글·답글·반응·신고·이미지.
> **모듈** user · **경로 접두사** `/api/community` · **엔드포인트** 17개
> **컨트롤러** `user/src/main/java/com/skhynix/user/community/controller/` — `CommunityCategoryController`(`/community/categories`) · `CommunityPostController`(`/community/posts`) · `CommunityCommentController`(`/community/comments`) · `CommunityImageController`(`/community/images`)
> **최종 갱신** 2026-10-08 — 도메인 신설(17개 전부 신규)
> **요구사항** `docs/requirements/user/community.md` (USER-CM-1 ~ 218, 승인됨 2026-10-08)
> 공통 규약(응답 래퍼·JWT·401 정책)은 [README.md](README.md)를 먼저 볼 것.

## 엔드포인트 목록

| 메서드 | 경로 | 성공 | 용도 |
|---|---|---|---|
| GET | [/api/community/categories](#get-apicommunitycategories) | 200 | 카테고리 목록 |
| POST | [/api/community/images](#post-apicommunityimages) | 200 | 이미지 선업로드(`temp/`) |
| POST | [/api/community/posts](#post-apicommunityposts) | 201 | 게시글 작성 |
| GET | [/api/community/posts](#get-apicommunityposts) | 200 | 게시글 목록(페이징) |
| GET | [/api/community/posts/popular](#get-apicommunitypostspopular) | 200 | 인기 게시글(배열 ≤5) |
| GET | [/api/community/posts/me](#get-apicommunitypostsme) | 200 | 내 게시글(페이징, 블라인드 포함) |
| GET | [/api/community/posts/{postId}](#get-apicommunitypostspostid) | 200 | 게시글 상세(+조회수) |
| PUT | [/api/community/posts/{postId}](#put-apicommunitypostspostid) | 200 | 게시글 수정(전체 교체) |
| DELETE | [/api/community/posts/{postId}](#delete-apicommunitypostspostid) | 204 | 게시글 삭제 |
| PUT | [/api/community/posts/{postId}/reaction](#put-apicommunitypostspostidreaction) | 200 | 게시글 반응 지정 |
| POST | [/api/community/posts/{postId}/report](#post-apicommunitypostspostidreport) | 200 | 게시글 신고(즉시 블라인드) |
| POST | [/api/community/posts/{postId}/comments](#post-apicommunitypostspostidcomments) | 201 | 댓글·답글 작성 |
| GET | [/api/community/posts/{postId}/comments](#get-apicommunitypostspostidcomments) | 200 | 댓글 목록(중첩) |
| PUT | [/api/community/comments/{commentId}](#put-apicommunitycommentscommentid) | 200 | 댓글·답글 수정 |
| DELETE | [/api/community/comments/{commentId}](#delete-apicommunitycommentscommentid) | 204 | 댓글·답글 삭제 |
| PUT | [/api/community/comments/{commentId}/reaction](#put-apicommunitycommentscommentidreaction) | 200 | 댓글·답글 반응 지정 |
| POST | [/api/community/comments/{commentId}/report](#post-apicommunitycommentscommentidreport) | 200 | 댓글·답글 신고 |

## 이 도메인의 특이사항

**17개 전부 인증 필수.** `permitAll` 목록에 없어 `anyRequest().authenticated()`에 걸린다 — `SecurityConfig`에 `/community` 규칙을 추가하면 그것이 버그다. 요청자는 access 토큰에서만 식별하며 본문·경로·쿼리에 계정 식별자 자리가 없다. 토큰이 없거나 무효면 401(`UNAUTHENTICATED`).

**⚠ 카테고리 id는 환경마다 다를 수 있다.** 시드가 `INSERT … SELECT`로 들어가 AUTO_INCREMENT 갭이 생기므로(로컬에서는 자유게시판이 16) **id를 코드에 박지 말고 반드시 `GET /api/community/categories`가 준 값을 쓸 것.** 작성·수정의 `categoryId`와 목록의 `?categoryId=`가 모두 해당된다.

**⚠ 410 Gone은 이 저장소 최초다.** 블라인드(신고로 숨김)된 글·댓글에 접근하면 404(없음)도 403(권한)도 아닌 **410**이 나간다. 작성자·신고자·제3자 전원에게 같은 코드다(작성자만 200을 주면 수정이 블라인드 우회 수단이 된다). 프론트는 410을 "신고로 숨김" 안내로 처리하고, 인터셉터가 권한 문제로 오해하지 않게 할 것. 삭제된 글·댓글은 404이며 "삭제됨"을 구분해 주지 않는다.

**`updatedAt`은 작성자가 수정한 적이 없으면 `null`이다.** 반응·조회수·블라인드로는 바뀌지 않는다(`PUT` 수정 성공 시에만 서버 시각으로 채워진다). 프론트는 `null`을 "수정 안 됨"으로 읽는다. 이미지만 바뀐 수정도 갱신된다.

**날짜 형식** `createdAt`·`updatedAt`은 `LocalDateTime`(존 없는 ISO 문자열). 서버는 Asia/Seoul `Clock` 기준이다.

**이미지 EP 규칙** 모든 이미지 값은 BaseURL을 뺀 오브젝트 키(선행 슬래시·버킷명·`https://` 없음)이며 `profileImgUrl`과 같은 규칙이다. 클라이언트가 `https://victoryfairy.com/` + 값을 이어 붙인다. 업로드 직후는 `temp/<uuid>.<ext>`, 글·댓글에 붙으면 서버가 `community/<uuid>.<ext>`로 확정한다(파일명은 그대로, 접두만 바뀜). ⚠ **이미지가 실제로 서빙되려면 CloudFront `/community/*` behavior가 함께 배포돼야 한다**(VictoryFairy_Infra 쪽 작업). 없으면 API는 정상이어도 이미지가 열리지 않는다.

**공통 타입**

`author` (`AuthorResponse`) — 계정 `id`·`uid`는 싣지 않는다.

| 필드 | 타입 | 설명 |
|---|---|---|
| nickname | String | 작성자 닉네임. 탈퇴 30일 후 정리되면 `(알수없음)`으로 이관된다 |
| profileImgUrl | String? | 프로필 이미지 EP. 없으면 `null` |

`PageResponse<T>` — 페이징 응답(채팅 히스토리와 같은 6키).

| 필드 | 타입 | 설명 |
|---|---|---|
| content | List&lt;T&gt; | 항목 |
| page | int | 0부터 |
| size | int | 요청한 페이지 크기 |
| totalElements | long | 전체 건수 |
| totalPages | int | 전체 페이지 수 |
| hasNext | boolean | 다음 페이지 존재 |

`ReactionResponse` — 반응 변경 결과(글·댓글 공용).

| 필드 | 타입 | 설명 |
|---|---|---|
| myReaction | String? | `LIKE`·`DISLIKE`, 취소(`NONE`) 뒤에는 `null`. 키는 항상 실림 |
| likeCount | long | 변경 반영 후 값 |
| dislikeCount | long | 변경 반영 후 값 |

**페이징 파라미터(목록 3종 공통)** `page`(기본 0, 0 이상) · `size`(기본 20, **1~50**). 범위를 벗어나면 400이고 래퍼의 `data`에 파라미터명이 키로 실린다:
```json
{"success":false,"data":{"size":"size는 50 이하여야 합니다."},"message":"입력값이 올바르지 않습니다."}
```
(`page` 위반 시 키는 `page`, 메시지 `page는 0 이상이어야 합니다.` / `size는 1 이상이어야 합니다.`)

**공통 실패(전 엔드포인트)** 401 `UNAUTHENTICATED`(토큰 없음/무효). 본문 파싱 실패 400, Content-Type 오류 415, 경로 변수 타입 불일치 400 — [README 1-1](README.md) 참고.

**에러 코드 한눈에(community 신규 13건)**

| 상태 | ErrorCode | 의미 |
|---|---|---|
| 400 | COMMUNITY_REPLY_DEPTH_EXCEEDED | 답글에는 답글을 달 수 없다 |
| 400 | COMMUNITY_IMAGE_REQUIRED | 업로드 `image` 파트 없음/빈 파일 |
| 400 | INVALID_COMMUNITY_IMAGE_FORMAT | JPEG/PNG/WEBP 아님(선두 바이트로 판정) |
| 400 | INVALID_COMMUNITY_IMAGE_ENDPOINT | `imageUrls` 원소가 유효하지 않음(temp 모양 아님·객체 없음·남의 확정 EP·중복·null/빈 값) — 사유를 합쳐 탐색 불가 |
| 400 | COMMUNITY_IMAGE_LIMIT_EXCEEDED | 첨부 상한 초과(게시글 5·댓글 3) |
| 403 | COMMUNITY_NOT_AUTHOR | 작성자만 수정·삭제 |
| 403 | COMMUNITY_SELF_REPORT_NOT_ALLOWED | 자기 글/댓글 신고 |
| 404 | COMMUNITY_CATEGORY_NOT_FOUND | 본문 `categoryId`가 없음(목록 필터의 없는 id는 빈 페이지) |
| 404 | COMMUNITY_POST_NOT_FOUND | 없는/삭제된 글 |
| 404 | COMMUNITY_COMMENT_NOT_FOUND | 없는/삭제된 댓글, 소속 글이 삭제됨, 다른 글의 댓글을 `parentCommentId`로 지목 |
| 410 | COMMUNITY_POST_BLINDED | 블라인드된 글 |
| 410 | COMMUNITY_COMMENT_BLINDED | 블라인드된 댓글 |
| 413 | COMMUNITY_IMAGE_TOO_LARGE | 이미지 5MiB 초과 |

---

## GET /api/community/categories
> 최종 변경: 2026-10-08 — 신규 추가

카테고리 전체 목록. 작성·수정·필터에 쓸 `id`의 **유일한 출처**다.

**요청** 본문·파라미터 없음.

**응답 200 OK** `ApiResponse<List<CategoryResponse>>`
```json
{"success":true,"data":[{"id":16,"name":"자유게시판","teamId":null},{"id":17,"name":"두산 베어스","teamId":2}],"message":null}
```
| 필드 | 타입 | 설명 |
|---|---|---|
| id | Long | 카테고리 PK(환경마다 다를 수 있음) |
| name | String | 표시명 |
| teamId | Long? | 구단 카테고리면 구단 id, 자유게시판은 `null` |

**실패** 401만.

```bash
curl https://victoryfairy.com/api/community/categories -H 'Authorization: Bearer <accessToken>'
```

---

## POST /api/community/images
> 최종 변경: 2026-10-08 — 신규 추가

이미지 1개를 `temp/`에 선업로드하고 EP를 돌려준다. 5장을 붙이려면 5회 호출한다. 이 시점에는 DB 행이 없고 글/댓글 작성·수정 요청의 `imageUrls[]`에 실어야 확정된다. 확정되지 않은 임시 객체는 24시간 뒤 정리된다. 인증 경로라 횟수 한도는 없다.

**요청** `multipart/form-data`
| 파트 | 타입 | 제약 | 설명 |
|---|---|---|---|
| image | file | 필수, JPEG/PNG/WEBP, 5MiB 이하 | 파트 이름이 다르거나 없으면 400. 형식은 확장자·Content-Type이 아니라 **파일 선두 바이트**로 판정. HEIC 불가 |

**응답 200 OK** `ApiResponse<CommunityImageResponse>`
```json
{"success":true,"data":{"imageUrl":"temp/4f1c0e3a-8b0e-4c47-9a35-2d2f0f1d9c11.jpg"},"message":null}
```
| 필드 | 타입 | 설명 |
|---|---|---|
| imageUrl | String | `temp/<uuid>.<ext>` EP(BaseURL 없음) |

**실패**
| 상태 | ErrorCode | 조건 |
|---|---|---|
| 400 | COMMUNITY_IMAGE_REQUIRED | 파트 없음/빈 파일 |
| 400 | INVALID_COMMUNITY_IMAGE_FORMAT | 허용 형식 아님 |
| 413 | COMMUNITY_IMAGE_TOO_LARGE | 5MiB 초과(멀티파트 해석 단계에서 나가며 경로의 `/community/`로 코드를 가른다) |
| 401 | UNAUTHENTICATED | 미인증 |

저장소 장애는 래퍼 없는 5xx가 아니라 `INTERNAL_SERVER_ERROR` 500 래퍼다(README 1-1).

```bash
curl -X POST https://victoryfairy.com/api/community/images \
  -H 'Authorization: Bearer <accessToken>' -F 'image=@photo.jpg'
```

---

## POST /api/community/posts
> 최종 변경: 2026-10-08 — 신규 추가

게시글 작성.

**요청 본문** `PostRequest`
| 필드 | 타입 | 제약 | 설명 |
|---|---|---|---|
| categoryId | Long | @NotNull | **`GET /categories`의 id** |
| title | String | @NotBlank @Size(max=100) | 제목 |
| content | String | @NotBlank @Size(max=5000) | 본문 |
| imageUrls | List&lt;String&gt; | 생략·null = 빈 배열, 최대 5 | 업로드 응답의 `temp/…` EP. 작성에는 기존 EP가 없어 `community/…` 값은 400 |

**응답 201 Created** `ApiResponse<PostIdResponse>`
```json
{"success":true,"data":{"postId":42},"message":null}
```
**실패**
| 상태 | ErrorCode | 조건 |
|---|---|---|
| 400 | - | 검증 실패(`data`에 필드명→메시지) |
| 400 | COMMUNITY_IMAGE_LIMIT_EXCEEDED | 이미지 6장 이상 |
| 400 | INVALID_COMMUNITY_IMAGE_ENDPOINT | EP 모양/존재/중복 위반 |
| 404 | COMMUNITY_CATEGORY_NOT_FOUND | 없는 `categoryId` |

판정 순서: 본문 검증 → 카테고리 404 → 이미지 400 → 저장.

```bash
curl -X POST https://victoryfairy.com/api/community/posts \
  -H 'Authorization: Bearer <accessToken>' -H 'Content-Type: application/json' \
  -d '{"categoryId":16,"title":"오늘 경기","content":"역전승!","imageUrls":["temp/4f1c0e3a-8b0e-4c47-9a35-2d2f0f1d9c11.jpg"]}'
```

---

## GET /api/community/posts
> 최종 변경: 2026-10-08 — 신규 추가

게시글 목록. 삭제·블라인드 글은 제외된다. 본문은 싣지 않는다.

**쿼리 파라미터**
| 이름 | 타입 | 기본값 | 설명 |
|---|---|---|---|
| categoryId | Long | (없음) | 선택. 없는 id는 404가 아니라 **빈 페이지** |
| sort | String | `latest` | `latest`(id 내림차순)·`likes`·`views` — **소문자**, 대문자·그 외 값은 400. 동률은 최신순 |
| page | int | 0 | 0 이상 |
| size | int | 20 | 1~50 |

**응답 200 OK** `ApiResponse<PageResponse<PostSummaryResponse>>`
```json
{"success":true,"data":{"content":[{"postId":42,"categoryId":16,"categoryName":"자유게시판","title":"오늘 경기","thumbnailUrl":"community/4f1c….jpg","author":{"nickname":"승리요정","profileImgUrl":null},"viewCount":10,"likeCount":3,"dislikeCount":0,"commentCount":2,"createdAt":"2026-10-08T14:03:21"}],"page":0,"size":20,"totalElements":1,"totalPages":1,"hasNext":false},"message":null}
```
`PostSummaryResponse` (11키)
| 필드 | 타입 | 설명 |
|---|---|---|
| postId | Long | |
| categoryId | Long | |
| categoryName | String | |
| title | String | |
| thumbnailUrl | String? | 첫 이미지 EP, 이미지가 없으면 `null` |
| author | AuthorResponse | |
| viewCount | long | |
| likeCount | long | |
| dislikeCount | long | |
| commentCount | long | 보이는 댓글 + 보이는 답글 |
| createdAt | LocalDateTime | |

**실패** 400(`page`/`size` 범위, `sort` 값 오류), 401.

```bash
curl 'https://victoryfairy.com/api/community/posts?categoryId=16&sort=likes&page=0&size=20' -H 'Authorization: Bearer <accessToken>'
```

---

## GET /api/community/posts/popular
> 최종 변경: 2026-10-08 — 신규 추가

인기 게시글. **페이징하지 않는 배열**이며 최대 5건이다(`?page=`는 무시). 최근 7일 이내 글만, 점수 `likeCount × 10 + viewCount` 내림차순. 블라인드·삭제 제외. 전체 목록에서 제외하지 않으므로 중복 제거는 프론트 몫.

**쿼리 파라미터** `categoryId`(Long, 선택).

**응답 200 OK** `ApiResponse<List<PostSummaryResponse>>` — 항목은 위 목록과 동일한 11키. 조건에 맞는 글이 없으면 `data: []`.

**실패** 401만.

```bash
curl 'https://victoryfairy.com/api/community/posts/popular?categoryId=16' -H 'Authorization: Bearer <accessToken>'
```

---

## GET /api/community/posts/me
> 최종 변경: 2026-10-08 — 신규 추가

내가 쓴 글(마이페이지). **블라인드된 글도 포함**해 본인에게 알린다(삭제된 글은 제외). 최신순뿐이라 `sort` 파라미터가 없다(붙여도 무시).

**쿼리 파라미터** `page`(기본 0)·`size`(기본 20, 1~50).

**응답 200 OK** `ApiResponse<PageResponse<MyPostSummaryResponse>>` — 항목은 `PostSummaryResponse` 11키 + `blinded`(boolean, 신고로 숨겨진 글이면 `true`) = 12키.

**실패** 400(페이징 범위), 401.

```bash
curl 'https://victoryfairy.com/api/community/posts/me' -H 'Authorization: Bearer <accessToken>'
```

---

## GET /api/community/posts/{postId}
> 최종 변경: 2026-10-08 — 신규 추가

게시글 상세. **조회수 증가 부수효과가 있는 GET이다.**

**조회수 규칙** 이 상세 GET에서만 센다(댓글 목록 등 다른 경로는 안 센다). **작성자 본인은 세지도 않는다.** 같은 계정이 같은 글을 **5분 고정 창**(Redis, 창을 연 시점부터 300초) 안에서 다시 열어도 한 번만 센다. **Redis에 닿지 못하면 세지 않고 200으로 응답한다.** 404·410으로 끝난 요청은 세지 않는다. 응답의 `viewCount`는 이번 요청의 증가분이 반영된 값이다.

**경로 변수** `postId`(Long)

**응답 200 OK** `ApiResponse<PostDetailResponse>`
```json
{"success":true,"data":{"postId":42,"categoryId":16,"categoryName":"자유게시판","title":"오늘 경기","content":"역전승!","imageUrls":["community/4f1c0e3a-8b0e-4c47-9a35-2d2f0f1d9c11.jpg"],"author":{"nickname":"승리요정","profileImgUrl":null},"viewCount":11,"likeCount":3,"dislikeCount":0,"commentCount":2,"myReaction":"LIKE","isAuthor":false,"createdAt":"2026-10-08T14:03:21","updatedAt":null},"message":null}
```
| 필드 | 타입 | 설명 |
|---|---|---|
| postId, categoryId, categoryName, title | | 목록과 동일 |
| content | String | 본문 |
| imageUrls | List&lt;String&gt; | 확정 EP 배열(순서 유지), 없으면 `[]` |
| author | AuthorResponse | |
| viewCount, likeCount, dislikeCount | long | |
| commentCount | long | 댓글은 싣지 않는다 — 별도 목록 API |
| myReaction | String? | `LIKE`·`DISLIKE`·`null`(반응 없음) |
| isAuthor | boolean | 요청자가 작성자인가 |
| createdAt | LocalDateTime | |
| updatedAt | LocalDateTime? | **수정한 적 없으면 `null`**("수정 안 됨") |

**실패**
| 상태 | ErrorCode | 조건 |
|---|---|---|
| 404 | COMMUNITY_POST_NOT_FOUND | 없음/삭제 |
| 410 | COMMUNITY_POST_BLINDED | 블라인드(작성자 포함 전원) |

```bash
curl https://victoryfairy.com/api/community/posts/42 -H 'Authorization: Bearer <accessToken>'
```

---

## PUT /api/community/posts/{postId}
> 최종 변경: 2026-10-08 — 신규 추가

게시글 **전체 교체** 수정(작성과 같은 본문, 부분 생략 불가 — `title` 키가 없으면 400). 작성자만 가능.

**이미지는 전체 교체다.** `imageUrls[]`가 수정 후의 최종 목록이다. 기존 `community/…` EP를 그대로 넣으면 유지, `temp/…`는 새로 확정, 목록에서 뺀 기존 이미지는 커밋 후 삭제된다. 최대 5장.

**경로 변수** `postId` · **요청 본문** `PostRequest`(작성과 동일).

**응답 200 OK** `ApiResponse<PostDetailResponse>` — 상세와 같은 15키(`isAuthor: true`, `updatedAt`은 이번 수정 시각).

**실패**
| 상태 | ErrorCode | 조건 |
|---|---|---|
| 400 | - / COMMUNITY_IMAGE_LIMIT_EXCEEDED / INVALID_COMMUNITY_IMAGE_ENDPOINT | 검증 · 이미지 |
| 404 | COMMUNITY_POST_NOT_FOUND | 없음/삭제 |
| 403 | COMMUNITY_NOT_AUTHOR | 작성자 아님 |
| 410 | COMMUNITY_POST_BLINDED | 블라인드된 글은 작성자도 수정 불가 |
| 404 | COMMUNITY_CATEGORY_NOT_FOUND | 없는 `categoryId` |

판정 순서: 400(본문) → 글 404 → 작성자 403 → 블라인드 410 → 카테고리 404 → EP 400.

```bash
curl -X PUT https://victoryfairy.com/api/community/posts/42 \
  -H 'Authorization: Bearer <accessToken>' -H 'Content-Type: application/json' \
  -d '{"categoryId":16,"title":"수정","content":"내용","imageUrls":[]}'
```

---

## DELETE /api/community/posts/{postId}
> 최종 변경: 2026-10-08 — 신규 추가

게시글 소프트 삭제. 작성자만 가능하며 **블라인드된 글도 작성자는 지울 수 있다.** 댓글·답글 행은 손대지 않으나 글 404로 접근이 막힌다. 글과 그 댓글에 붙은 이미지 객체는 커밋 후 best-effort 삭제.

**응답 204 No Content** (본문 없음)

**실패** 404 `COMMUNITY_POST_NOT_FOUND`(재삭제 포함) · 403 `COMMUNITY_NOT_AUTHOR` · 401.

```bash
curl -X DELETE https://victoryfairy.com/api/community/posts/42 -H 'Authorization: Bearer <accessToken>'
```

---

## PUT /api/community/posts/{postId}/reaction
> 최종 변경: 2026-10-08 — 신규 추가

게시글 반응 **상태 지정**. 토글이 아니라 요청값이 곧 결과 상태라 **멱등**이다 — 같은 값을 다시 보내도 카운트가 변하지 않고, `NONE`은 취소다. 반대 반응으로 보내면 하나가 줄고 하나가 는다. 자기 글에도 반응할 수 있다.

**요청 본문** `ReactionRequest`
| 필드 | 타입 | 제약 | 설명 |
|---|---|---|---|
| reaction | String | @NotNull, `LIKE`\|`DISLIKE`\|`NONE` | 그 외 문자열은 400(본문 파싱 실패) |

**응답 200 OK** `ApiResponse<ReactionResponse>`
```json
{"success":true,"data":{"myReaction":"LIKE","likeCount":4,"dislikeCount":0},"message":null}
```
**실패** 400(검증/파싱) · 404 `COMMUNITY_POST_NOT_FOUND` · 410 `COMMUNITY_POST_BLINDED`.

```bash
curl -X PUT https://victoryfairy.com/api/community/posts/42/reaction \
  -H 'Authorization: Bearer <accessToken>' -H 'Content-Type: application/json' -d '{"reaction":"LIKE"}'
```

---

## POST /api/community/posts/{postId}/report
> 최종 변경: 2026-10-08 — 신규 추가

게시글 신고. **신고 1건이 즉시 블라인드**한다(임계치·승인 없음, 해제 경로 없음). 사유 본문은 없다(보내도 무시). 이미 블라인드된 글을 다시 신고하거나 같은 사람이 재신고해도 **멱등 200**이다.

**응답 200 OK** `ApiResponse<Void>` → `{"success":true,"data":null,"message":null}`

**실패** 404 `COMMUNITY_POST_NOT_FOUND` · 403 `COMMUNITY_SELF_REPORT_NOT_ALLOWED`(자기 글) · 401. 판정 순서: 404 → 403 → 블라인드. (블라인드 글 재신고는 410이 아니라 200이다.) 블라인드된 글은 이후 목록·인기에서 빠지고 상세·수정·반응·댓글 작성은 410, 내 글 목록에는 `blinded:true`로 남는다.

```bash
curl -X POST https://victoryfairy.com/api/community/posts/42/report -H 'Authorization: Bearer <accessToken>'
```

---

## POST /api/community/posts/{postId}/comments
> 최종 변경: 2026-10-08 — 신규 추가

댓글 또는 답글 작성. `parentCommentId`가 있으면 답글이다(**답글 전용 경로는 없다**). 깊이는 1단계 고정이며 서버는 부모를 바꿔 주지 않는다. 작성 성공 시 글의 `commentCount`가 1 늘어난다.

**경로 변수** `postId`(Long)

**요청 본문** `CommentRequest`
| 필드 | 타입 | 제약 | 설명 |
|---|---|---|---|
| content | String | @NotBlank @Size(max=1000) | 내용 |
| imageUrls | List&lt;String&gt; | 생략·null = 빈 배열, 최대 3 | `temp/…` EP |
| parentCommentId | Long | 선택 | 같은 글의 **최상위** 댓글 id. 있으면 답글 |

**응답 201 Created** `ApiResponse<CommentIdResponse>` → `{"success":true,"data":{"commentId":101},"message":null}`

**실패**
| 상태 | ErrorCode | 조건 |
|---|---|---|
| 400 | - | 검증 실패 |
| 400 | COMMUNITY_REPLY_DEPTH_EXCEEDED | 부모가 이미 답글 |
| 400 | COMMUNITY_IMAGE_LIMIT_EXCEEDED / INVALID_COMMUNITY_IMAGE_ENDPOINT | 이미지(상한 3) |
| 404 | COMMUNITY_POST_NOT_FOUND | 글 없음/삭제 |
| 404 | COMMUNITY_COMMENT_NOT_FOUND | 부모 없음/삭제/다른 글의 댓글 |
| 410 | COMMUNITY_POST_BLINDED | 블라인드 글 |
| 410 | COMMUNITY_COMMENT_BLINDED | 블라인드된 부모에는 새 답글 불가 |

판정 순서: 본문 400 → 글 404 → 글 410 → 부모 404 → 깊이 400 → 부모 410 → 이미지 400.

```bash
curl -X POST https://victoryfairy.com/api/community/posts/42/comments \
  -H 'Authorization: Bearer <accessToken>' -H 'Content-Type: application/json' \
  -d '{"content":"좋은 글","imageUrls":[],"parentCommentId":null}'
```

---

## GET /api/community/posts/{postId}/comments
> 최종 변경: 2026-10-08 — 신규 추가

댓글 목록. **최상위 댓글을 페이징**하고 각 항목의 `replies[]`에 보이는 답글이 중첩된다(답글은 작성순 오름차순, 별도 페이징 없음). 이 호출은 글 조회수를 세지 않는다.

**경로 변수** `postId` · **쿼리 파라미터** `page`(기본 0)·`size`(기본 20, 1~50)

**응답 200 OK** `ApiResponse<PageResponse<CommentResponse>>`
```json
{"success":true,"data":{"content":[
 {"commentId":101,"parentCommentId":null,"status":"VISIBLE","content":"좋은 글","imageUrls":[],"author":{"nickname":"승리요정","profileImgUrl":null},"likeCount":1,"dislikeCount":0,"myReaction":null,"isAuthor":false,"createdAt":"2026-10-08T14:10:00","updatedAt":null,
  "replies":[{"commentId":102,"parentCommentId":101,"status":"VISIBLE","content":"동의","imageUrls":[],"author":{"nickname":"곰","profileImgUrl":null},"likeCount":0,"dislikeCount":0,"myReaction":null,"isAuthor":true,"createdAt":"2026-10-08T14:11:00","updatedAt":null}]},
 {"commentId":103,"parentCommentId":null,"status":"DELETED","content":null,"imageUrls":[],"author":null,"likeCount":0,"dislikeCount":0,"myReaction":null,"isAuthor":false,"createdAt":"2026-10-08T14:12:00","updatedAt":null,"replies":[]}
],"page":0,"size":20,"totalElements":2,"totalPages":1,"hasNext":false},"message":null}
```
최상위 댓글 `CommentResponse` (13키)
| 필드 | 타입 | 설명 |
|---|---|---|
| commentId | Long | |
| parentCommentId | Long? | 최상위는 항상 `null` |
| status | String | `VISIBLE`·`DELETED`·`BLINDED` |
| content | String? | 자리 표식이면 `null` |
| imageUrls | List&lt;String&gt; | 확정 EP, 없으면 `[]` |
| author | AuthorResponse? | 자리 표식이면 `null` |
| likeCount, dislikeCount | long | 자리 표식이면 0 |
| myReaction | String? | `LIKE`·`DISLIKE`·`null` |
| isAuthor | boolean | 자리 표식이면 `false` |
| createdAt | LocalDateTime | 자리 표식에서도 유지 |
| updatedAt | LocalDateTime? | 수정 안 했으면 `null`, 자리 표식도 `null` |
| replies | List&lt;ReplyResponse&gt; | 보이는 답글만. 없으면 `[]` |

**답글 `ReplyResponse` (12키)** = 위에서 `replies`를 뺀 키 집합. **답글에는 `replies` 키 자체가 없다.** `status`는 항상 `VISIBLE`, `parentCommentId`는 부모 댓글 id. **삭제·블라인드된 답글은 배열에서 제외**된다(자리 표식이 없다).

**자리 표식(placeholder)** 삭제(`DELETED`) 또는 블라인드(`BLINDED`)된 **최상위** 댓글은 목록에서 사라지지 않고 `status`만 `DELETED`|`BLINDED`로 남으며 `content`·`author`가 `null`, `imageUrls`는 `[]`, 카운트 0, `myReaction` `null`, `isAuthor` `false`, `updatedAt` `null`이다(작성자 본인이 봐도 동일). 그 아래 보이는 답글은 `replies`에 그대로 산다. 삭제와 블라인드가 겹치면 `DELETED`.

`totalElements`는 최상위 항목 + 자리 표식 수라 글의 `commentCount`(답글 포함)와 다르다 — 맞추려 하지 말 것.

**실패** 400(페이징) · 404 `COMMUNITY_POST_NOT_FOUND` · 410 `COMMUNITY_POST_BLINDED` · 401.

```bash
curl 'https://victoryfairy.com/api/community/posts/42/comments?page=0&size=20' -H 'Authorization: Bearer <accessToken>'
```

---

## PUT /api/community/comments/{commentId}
> 최종 변경: 2026-10-08 — 신규 추가

댓글·답글 수정(전체 교체). 작성자만 가능, 부모는 바꿀 수 없다. 이미지는 `imageUrls[]` **전체 교체**(최대 3장, 규칙은 게시글 수정과 같다).

**경로 변수** `commentId` · **요청 본문** `CommentUpdateRequest`
| 필드 | 타입 | 제약 | 설명 |
|---|---|---|---|
| content | String | @NotBlank @Size(max=1000) | 내용 |
| imageUrls | List&lt;String&gt; | 생략·null = 빈 배열, 최대 3 | 최종 이미지 목록 |

**응답 200 OK** `ApiResponse<CommentItemResponse>` — 대상이 **최상위 댓글이면 `CommentResponse`(13키, `replies` 포함)**, **답글이면 `ReplyResponse`(12키, `replies` 없음)**. `updatedAt`은 이번 수정 시각.

**실패**
| 상태 | ErrorCode | 조건 |
|---|---|---|
| 400 | - / COMMUNITY_IMAGE_LIMIT_EXCEEDED / INVALID_COMMUNITY_IMAGE_ENDPOINT | 검증 · 이미지 |
| 404 | COMMUNITY_COMMENT_NOT_FOUND | 댓글 없음/삭제, 소속 글 삭제 |
| 403 | COMMUNITY_NOT_AUTHOR | 작성자 아님 |
| 410 | COMMUNITY_POST_BLINDED | 소속 글 블라인드 |
| 410 | COMMUNITY_COMMENT_BLINDED | 블라인드된 댓글은 작성자도 수정 불가 |

판정 순서: 400(본문) → 404 → 403 → 글 410 → 댓글 410 → 이미지 400.

```bash
curl -X PUT https://victoryfairy.com/api/community/comments/101 \
  -H 'Authorization: Bearer <accessToken>' -H 'Content-Type: application/json' \
  -d '{"content":"수정","imageUrls":[]}'
```

---

## DELETE /api/community/comments/{commentId}
> 최종 변경: 2026-10-08 — 신규 추가

댓글·답글 소프트 삭제. **그 행만** 지우며 답글로 전파하지 않는다(부모를 지워도 답글은 산다 — 목록에는 `DELETED` 자리 표식으로 남는다). 작성자만 가능, 블라인드된 댓글·소속 글이 블라인드인 경우에도 지울 수 있다. 보이던 댓글이면 글의 `commentCount`가 1 줄어든다. **재삭제는 204가 아니라 404**다.

**응답 204 No Content**

**실패** 404 `COMMUNITY_COMMENT_NOT_FOUND`(소속 글 삭제 포함) · 403 `COMMUNITY_NOT_AUTHOR` · 401.

```bash
curl -X DELETE https://victoryfairy.com/api/community/comments/101 -H 'Authorization: Bearer <accessToken>'
```

---

## PUT /api/community/comments/{commentId}/reaction
> 최종 변경: 2026-10-08 — 신규 추가

댓글·답글 공용 반응 지정. 의미는 게시글 반응과 같다(상태 지정·멱등, `LIKE|DISLIKE|NONE`).

**요청 본문** `ReactionRequest` · **응답 200 OK** `ApiResponse<ReactionResponse>`(위 공통 타입).

**실패** 400(검증/파싱) · 404 `COMMUNITY_COMMENT_NOT_FOUND` · 410 `COMMUNITY_POST_BLINDED` · 410 `COMMUNITY_COMMENT_BLINDED`. 판정 순서: 댓글 404 → 글 404 → 글 410 → 댓글 410.

```bash
curl -X PUT https://victoryfairy.com/api/community/comments/101/reaction \
  -H 'Authorization: Bearer <accessToken>' -H 'Content-Type: application/json' -d '{"reaction":"DISLIKE"}'
```

---

## POST /api/community/comments/{commentId}/report
> 최종 변경: 2026-10-08 — 신규 추가

댓글·답글 신고. 1건 즉시 블라인드, 멱등 200. **그 행만** 숨기며 답글에 전파하지 않는다(전파하면 댓글 하나 신고로 답글 전부를 지우는 공격이 된다). 보이던 댓글이 블라인드되면 글의 `commentCount`가 1 줄어든다. 본문 없음.

**응답 200 OK** `ApiResponse<Void>` → `{"success":true,"data":null,"message":null}`

**실패** 404 `COMMUNITY_COMMENT_NOT_FOUND`(소속 글 삭제 포함) · 410 `COMMUNITY_POST_BLINDED` · 403 `COMMUNITY_SELF_REPORT_NOT_ALLOWED`(자기 댓글) · 401. 판정 순서: 댓글 404 → 글 404 → 글 410 → 자기 신고 403 → 블라인드(이미면 no-op 200).

```bash
curl -X POST https://victoryfairy.com/api/community/comments/101/report -H 'Authorization: Bearer <accessToken>'
```
