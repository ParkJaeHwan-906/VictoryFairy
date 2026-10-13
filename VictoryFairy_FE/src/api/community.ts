import type { AxiosResponse } from 'axios';
import { userClient } from './httpClient';
import { ApiError } from './errors';
import { multipartConfig, PROFILE_IMAGE_MIME_TYPES } from './profileImage';
import type { ApiResponse } from '../types/api';
import type {
  CommunityCategory,
  CommunityComment,
  CommunityCommentCreateRequest,
  CommunityCommentCreateResult,
  CommunityCommentUpdateRequest,
  CommunityCommentUpdateResult,
  CommunityImageUploadResponse,
  CommunityPage,
  CommunityPageParams,
  CommunityPopularPostParams,
  CommunityPostCreateResult,
  CommunityPostDetail,
  CommunityPostListParams,
  CommunityPostRequest,
  CommunityPostSummary,
  CommunityReactionInput,
  CommunityReactionRequest,
  CommunityReactionResult,
  MyCommunityPostSummary,
} from '../types/community';

/**
 * 커뮤니티 API (user 모듈, `/community`, docs/community.md).
 *
 * 17개 엔드포인트 **전부 인증이 필수**라 모두 `requiresAuth: true` 로 보낸다.
 *
 * ── 이 도메인만의 규칙 ────────────────────────────────────────────────
 * - **410 Gone = 신고로 숨김(블라인드)**. 이 저장소 최초다. 작성자·신고자·제3자 모두 같다.
 *   삭제된 글·댓글은 404 이고 "삭제됨"을 따로 알려 주지 않는다.
 * - 카테고리 id 는 환경마다 다르다 — `getCommunityCategories()` 가 준 값만 쓴다.
 * - 이미지는 `uploadCommunityImage()` 로 한 장씩 `temp/` 에 먼저 올리고, 받은 EP 를
 *   글·댓글의 `imageUrls[]` 에 실어야 `community/…` 로 확정된다.
 * - 반응은 토글이 아니라 **상태 지정(멱등)** 이다. 같은 값을 다시 보내도 변하지 않고 `NONE` 이 취소.
 * ──────────────────────────────────────────────────────────────────────
 */

/** ApiResponse 로 감싸인 성공 응답의 `data` 를 벗겨낸다(block·account 와 같은 방식). */
function unwrap<T>(res: AxiosResponse<ApiResponse<T>>): T {
  return res.data.data as T;
}

/* ------------------------------------------------------------------ *
 * 상수 · 도메인 에러 판별
 * ------------------------------------------------------------------ */

/** 이미지 1장 최대 크기(5MiB). 넘기면 413. */
export const COMMUNITY_IMAGE_MAX_BYTES = 5 * 1024 * 1024;

/** 서버가 받는 형식(JPEG·PNG·WEBP, 선두 바이트로 판정 — HEIC 불가). 프로필 이미지와 같다. */
export const COMMUNITY_IMAGE_MIME_TYPES = PROFILE_IMAGE_MIME_TYPES;

/** `<input type="file">` 의 accept 값 */
export const COMMUNITY_IMAGE_ACCEPT = COMMUNITY_IMAGE_MIME_TYPES.join(',');

/** 첨부 상한 — 게시글 5장, 댓글·답글 3장. 넘기면 400 COMMUNITY_IMAGE_LIMIT_EXCEEDED. */
export const COMMUNITY_POST_IMAGE_MAX = 5;
export const COMMUNITY_COMMENT_IMAGE_MAX = 3;

/** 글자 수 상한(Bean Validation). */
export const COMMUNITY_POST_TITLE_MAX_LENGTH = 100;
export const COMMUNITY_POST_CONTENT_MAX_LENGTH = 5000;
export const COMMUNITY_COMMENT_CONTENT_MAX_LENGTH = 1000;

/** 목록 페이지 크기 기본값·상한(1~50, 벗어나면 400). */
export const COMMUNITY_PAGE_SIZE = 20;
export const COMMUNITY_PAGE_SIZE_MAX = 50;

/*
 * ⚠️ 커뮤니티 에러의 message 문구는 계약 문서에 없다(ErrorCode 이름만 있다).
 * 그래서 다른 도메인처럼 `상태 + message` 로 비교하지 않고 **상태 코드로만** 가른다.
 * 엔드포인트별로 같은 상태가 한 가지 뜻만 갖도록 판정 순서가 짜여 있어, 호출한 함수와
 * 묶어 읽으면 충분하다(각 함수 JSDoc 의 "에러" 참고). 문구가 확정되면 message 비교로 좁힌다.
 */

function hasStatus(error: unknown, status: number): boolean {
  return error instanceof ApiError && error.status === status;
}

/**
 * 410 — 블라인드(신고로 숨김)된 글 또는 댓글. "신고로 숨겨진 게시물입니다" 류 안내로 처리한다.
 * 글(COMMUNITY_POST_BLINDED)인지 댓글(COMMUNITY_COMMENT_BLINDED)인지는 상태만으로 갈리지 않는다.
 */
export function isCommunityBlinded(error: unknown): boolean {
  return hasStatus(error, 410);
}

/**
 * 404 — 없거나 삭제된 글·댓글(COMMUNITY_POST_NOT_FOUND / COMMUNITY_COMMENT_NOT_FOUND),
 * 또는 작성·수정 본문의 `categoryId` 가 없음(COMMUNITY_CATEGORY_NOT_FOUND).
 */
export function isCommunityNotFound(error: unknown): boolean {
  return hasStatus(error, 404);
}

/**
 * 403 — 수정·삭제에서는 작성자가 아님(COMMUNITY_NOT_AUTHOR),
 * 신고에서는 자기 글·댓글 신고(COMMUNITY_SELF_REPORT_NOT_ALLOWED).
 */
export function isCommunityForbidden(error: unknown): boolean {
  return hasStatus(error, 403);
}

/** 413 — 이미지 5MiB 초과(COMMUNITY_IMAGE_TOO_LARGE). */
export function isCommunityImageTooLarge(error: unknown): boolean {
  return hasStatus(error, 413);
}

/* ------------------------------------------------------------------ *
 * 카테고리 · 이미지
 * ------------------------------------------------------------------ */

/**
 * GET /community/categories — 카테고리 목록. 작성·수정·필터에 쓸 `id` 의 유일한 출처다.
 * 에러: 401.
 */
export function getCommunityCategories(): Promise<CommunityCategory[]> {
  return userClient
    .get<ApiResponse<CommunityCategory[]>>('/community/categories', { requiresAuth: true })
    .then(unwrap);
}

/**
 * POST /community/images — 이미지 1장을 `temp/` 에 선업로드하고 EP 를 돌려준다.
 *
 * 5장이면 5번 부른다. 받은 EP 를 글·댓글의 `imageUrls[]` 에 실어야 확정되고, 확정되지 않은
 * 임시 객체는 서버가 정리한다. 인증 경로라 횟수 한도(429)는 없다.
 *
 * 에러: 400 COMMUNITY_IMAGE_REQUIRED(빈 파일) · 400 INVALID_COMMUNITY_IMAGE_FORMAT ·
 * 413 `isCommunityImageTooLarge` · 401.
 *
 * @returns `temp/<uuid>.<ext>` EP
 */
export async function uploadCommunityImage(image: File): Promise<string> {
  const form = new FormData();
  form.append('image', image);

  const res = await userClient.post<ApiResponse<CommunityImageUploadResponse>>(
    '/community/images',
    form,
    multipartConfig(true),
  );

  return unwrap(res).imageUrl;
}

/* ------------------------------------------------------------------ *
 * 게시글
 * ------------------------------------------------------------------ */

/**
 * POST /community/posts — 게시글 작성(201).
 *
 * 판정 순서: 본문 검증 400 → 카테고리 404 → 이미지 400 → 저장.
 * 에러: 400(`fieldErrors` 에 필드명→메시지) · 400 COMMUNITY_IMAGE_LIMIT_EXCEEDED ·
 * 400 INVALID_COMMUNITY_IMAGE_ENDPOINT(`temp/…` 가 아니거나 객체 없음·중복 등) ·
 * 404 COMMUNITY_CATEGORY_NOT_FOUND · 401.
 */
export function createCommunityPost(body: CommunityPostRequest): Promise<CommunityPostCreateResult> {
  return userClient
    .post<ApiResponse<CommunityPostCreateResult>>('/community/posts', body, { requiresAuth: true })
    .then(unwrap);
}

/**
 * GET /community/posts — 게시글 목록(페이징). 삭제·블라인드 글은 빠지고 본문은 싣지 않는다.
 * 에러: 400(페이징 범위·`sort` 값 — `fieldErrors` 에 파라미터명) · 401.
 */
export function getCommunityPosts(
  params: CommunityPostListParams = {},
): Promise<CommunityPage<CommunityPostSummary>> {
  return userClient
    .get<ApiResponse<CommunityPage<CommunityPostSummary>>>('/community/posts', {
      params,
      requiresAuth: true,
    })
    .then(unwrap);
}

/**
 * GET /community/posts/popular — 인기 게시글. **페이징하지 않는 배열**, 최대 5건.
 *
 * 최근 7일 이내 글을 `likeCount × 10 + viewCount` 내림차순으로 준다. 전체 목록에서 빼 주지
 * 않으므로 두 목록을 함께 그릴 때 중복 제거는 화면 몫이다. 없으면 `[]`.
 * 에러: 401.
 */
export function getPopularCommunityPosts(
  params: CommunityPopularPostParams = {},
): Promise<CommunityPostSummary[]> {
  return userClient
    .get<ApiResponse<CommunityPostSummary[]>>('/community/posts/popular', {
      params,
      requiresAuth: true,
    })
    .then(unwrap);
}

/**
 * GET /community/posts/me — 내 게시글(마이페이지). 최신순 고정이라 `sort` 가 없다.
 * **블라인드된 글도 포함**하며 항목의 `blinded` 로 구분한다(삭제 글은 제외).
 * 에러: 400(페이징) · 401.
 */
export function getMyCommunityPosts(
  params: CommunityPageParams = {},
): Promise<CommunityPage<MyCommunityPostSummary>> {
  return userClient
    .get<ApiResponse<CommunityPage<MyCommunityPostSummary>>>('/community/posts/me', {
      params,
      requiresAuth: true,
    })
    .then(unwrap);
}

/**
 * GET /community/posts/{postId} — 게시글 상세.
 *
 * ⚠️ **조회수가 오르는 GET** 이다. 작성자 본인은 세지 않고, 같은 계정이 5분 안에 다시 열어도
 * 한 번만 센다. 응답 `viewCount` 는 이번 증가분이 반영된 값이라, 리렌더·포커스 복귀마다
 * 다시 부르지 말고 필요할 때만 부른다. 댓글은 `getCommunityComments()` 로 따로 받는다.
 *
 * 에러: 404 COMMUNITY_POST_NOT_FOUND · 410 COMMUNITY_POST_BLINDED · 401.
 */
export function getCommunityPost(postId: number): Promise<CommunityPostDetail> {
  return userClient
    .get<ApiResponse<CommunityPostDetail>>(`/community/posts/${postId}`, { requiresAuth: true })
    .then(unwrap);
}

/**
 * PUT /community/posts/{postId} — 게시글 수정(**전체 교체**). 작성자만.
 *
 * `imageUrls` 가 수정 후 최종 목록이다 — 남길 기존 `community/…` EP 도 빠짐없이 넣어야 한다.
 * 응답은 상세와 같은 15키(`isAuthor: true`, `updatedAt` 은 이번 수정 시각).
 *
 * 판정 순서: 400 → 글 404 → 403 → 410 → 카테고리 404 → 이미지 400.
 * 에러: 400 검증·이미지 · 404(글 또는 카테고리) · 403 COMMUNITY_NOT_AUTHOR ·
 * 410 COMMUNITY_POST_BLINDED(블라인드 글은 작성자도 수정 불가) · 401.
 */
export function updateCommunityPost(
  postId: number,
  body: CommunityPostRequest,
): Promise<CommunityPostDetail> {
  return userClient
    .put<ApiResponse<CommunityPostDetail>>(`/community/posts/${postId}`, body, {
      requiresAuth: true,
    })
    .then(unwrap);
}

/**
 * DELETE /community/posts/{postId} — 게시글 삭제(소프트 삭제, 204 무본문). 작성자만.
 * 블라인드된 글도 작성자는 지울 수 있다.
 * 에러: 404(재삭제 포함) · 403 COMMUNITY_NOT_AUTHOR · 401.
 */
export async function deleteCommunityPost(postId: number): Promise<void> {
  await userClient.delete<void>(`/community/posts/${postId}`, { requiresAuth: true });
}

/**
 * PUT /community/posts/{postId}/reaction — 게시글 반응 지정(상태 지정·멱등).
 * 자기 글에도 반응할 수 있다. `NONE` 이면 취소.
 * 에러: 400 · 404 COMMUNITY_POST_NOT_FOUND · 410 COMMUNITY_POST_BLINDED · 401.
 */
export function setCommunityPostReaction(
  postId: number,
  reaction: CommunityReactionInput,
): Promise<CommunityReactionResult> {
  const body: CommunityReactionRequest = { reaction };

  return userClient
    .put<ApiResponse<CommunityReactionResult>>(`/community/posts/${postId}/reaction`, body, {
      requiresAuth: true,
    })
    .then(unwrap);
}

/**
 * POST /community/posts/{postId}/report — 게시글 신고. **1건이 즉시 블라인드**한다.
 *
 * 이미 블라인드된 글·같은 사람의 재신고는 멱등 200. 성공 뒤 그 글은 목록·인기에서 빠지고
 * 상세·수정·반응·댓글 작성은 410 이 된다.
 * 에러: 404 COMMUNITY_POST_NOT_FOUND · 403 COMMUNITY_SELF_REPORT_NOT_ALLOWED · 401.
 */
export async function reportCommunityPost(postId: number): Promise<void> {
  await userClient.post<ApiResponse<null>>(`/community/posts/${postId}/report`, undefined, {
    requiresAuth: true,
  });
}

/* ------------------------------------------------------------------ *
 * 댓글 · 답글
 * ------------------------------------------------------------------ */

/**
 * POST /community/posts/{postId}/comments — 댓글·답글 작성(201).
 *
 * `parentCommentId` 가 있으면 답글이다(답글 전용 경로는 없고 깊이 1 고정).
 *
 * 판정 순서: 본문 400 → 글 404 → 글 410 → 부모 404 → 깊이 400 → 부모 410 → 이미지 400.
 * 에러: 400 검증·이미지 · 400 COMMUNITY_REPLY_DEPTH_EXCEEDED(답글에 답글) ·
 * 404(글, 또는 부모 없음·삭제·다른 글) · 410(글 또는 블라인드 부모) · 401.
 */
export function createCommunityComment(
  postId: number,
  body: CommunityCommentCreateRequest,
): Promise<CommunityCommentCreateResult> {
  return userClient
    .post<ApiResponse<CommunityCommentCreateResult>>(`/community/posts/${postId}/comments`, body, {
      requiresAuth: true,
    })
    .then(unwrap);
}

/**
 * GET /community/posts/{postId}/comments — 댓글 목록.
 *
 * **최상위 댓글만 페이징**하고 보이는 답글은 각 항목의 `replies[]` 에 작성순으로 중첩된다.
 * 삭제·블라인드된 최상위 댓글은 `status` 가 바뀐 **자리 표식**으로 남는다(본문·작성자 `null`).
 * 그래서 `totalElements` 는 글의 `commentCount`(답글 포함, 자리 표식 제외)와 다르다.
 * 글 조회수는 세지 않는다.
 *
 * 에러: 400(페이징) · 404 COMMUNITY_POST_NOT_FOUND · 410 COMMUNITY_POST_BLINDED · 401.
 */
export function getCommunityComments(
  postId: number,
  params: CommunityPageParams = {},
): Promise<CommunityPage<CommunityComment>> {
  return userClient
    .get<ApiResponse<CommunityPage<CommunityComment>>>(`/community/posts/${postId}/comments`, {
      params,
      requiresAuth: true,
    })
    .then(unwrap);
}

/**
 * PUT /community/comments/{commentId} — 댓글·답글 수정(전체 교체). 작성자만, 부모는 못 바꾼다.
 *
 * 응답은 최상위 댓글이면 `replies` 가 있는 13키, 답글이면 12키다.
 *
 * 판정 순서: 400 → 404 → 403 → 글 410 → 댓글 410 → 이미지 400.
 * 에러: 400 검증·이미지 · 404 COMMUNITY_COMMENT_NOT_FOUND · 403 COMMUNITY_NOT_AUTHOR ·
 * 410(글 또는 댓글 블라인드 — 작성자도 수정 불가) · 401.
 */
export function updateCommunityComment(
  commentId: number,
  body: CommunityCommentUpdateRequest,
): Promise<CommunityCommentUpdateResult> {
  return userClient
    .put<ApiResponse<CommunityCommentUpdateResult>>(`/community/comments/${commentId}`, body, {
      requiresAuth: true,
    })
    .then(unwrap);
}

/**
 * DELETE /community/comments/{commentId} — 댓글·답글 삭제(소프트 삭제, 204 무본문). 작성자만.
 *
 * **그 행만** 지운다 — 부모를 지워도 답글은 살고, 목록에는 `DELETED` 자리 표식이 남는다.
 * 블라인드된 댓글·블라인드 글 소속이어도 지울 수 있다. **재삭제는 404** 다.
 * 에러: 404 COMMUNITY_COMMENT_NOT_FOUND · 403 COMMUNITY_NOT_AUTHOR · 401.
 */
export async function deleteCommunityComment(commentId: number): Promise<void> {
  await userClient.delete<void>(`/community/comments/${commentId}`, { requiresAuth: true });
}

/**
 * PUT /community/comments/{commentId}/reaction — 댓글·답글 반응 지정(상태 지정·멱등).
 * 판정 순서: 댓글 404 → 글 404 → 글 410 → 댓글 410.
 * 에러: 400 · 404 · 410 · 401.
 */
export function setCommunityCommentReaction(
  commentId: number,
  reaction: CommunityReactionInput,
): Promise<CommunityReactionResult> {
  const body: CommunityReactionRequest = { reaction };

  return userClient
    .put<ApiResponse<CommunityReactionResult>>(`/community/comments/${commentId}/reaction`, body, {
      requiresAuth: true,
    })
    .then(unwrap);
}

/**
 * POST /community/comments/{commentId}/report — 댓글·답글 신고. 1건 즉시 블라인드, 멱등 200.
 * **그 행만** 숨기며 답글에 전파하지 않는다.
 * 판정 순서: 댓글 404 → 글 404 → 글 410 → 자기 신고 403 → 블라인드(이미면 no-op 200).
 * 에러: 404 COMMUNITY_COMMENT_NOT_FOUND · 410 COMMUNITY_POST_BLINDED ·
 * 403 COMMUNITY_SELF_REPORT_NOT_ALLOWED · 401.
 */
export async function reportCommunityComment(commentId: number): Promise<void> {
  await userClient.post<ApiResponse<null>>(`/community/comments/${commentId}/report`, undefined, {
    requiresAuth: true,
  });
}
