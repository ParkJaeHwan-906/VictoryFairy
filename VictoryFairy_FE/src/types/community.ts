/**
 * 커뮤니티(community) 도메인 타입 (user 모듈, `/api/community`, docs/community.md).
 *
 * - 카테고리 id 는 환경마다 다르다 — 코드에 박지 말고 `GET /categories` 가 준 값만 쓴다.
 * - 이미지는 BaseURL 없는 EP(오브젝트 키)로만 오간다. 업로드 직후는 `temp/…`, 글·댓글에
 *   붙으면 `community/…` 로 확정된다. 화면에서는 `toAssetUrl()` 로 주소를 만든다.
 * - `createdAt`·`updatedAt` 은 존 없는 ISO 문자열(Asia/Seoul 기준)이다.
 * - `updatedAt` 은 작성자가 수정한 적 없으면 `null` 이다(반응·조회수·블라인드로는 바뀌지 않음).
 */

/* ------------------------------------------------------------------ *
 * 공통
 * ------------------------------------------------------------------ */

/** 작성자. 계정 id·uid 는 싣지 않는다. 탈퇴 30일 뒤 정리되면 `(알수없음)` 으로 이관된다. */
export interface CommunityAuthor {
  nickname: string;
  /** 프로필 이미지 EP. 없으면 `null` */
  profileImgUrl: string | null;
}

/** 목록 3종(게시글·내 게시글·댓글)이 쓰는 페이지 응답. `page` 는 0부터. */
export interface CommunityPage<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
  hasNext: boolean;
}

/** 페이징 파라미터. `page` 기본 0(0 이상), `size` 기본 20(**1~50**, 벗어나면 400). */
export interface CommunityPageParams {
  page?: number;
  size?: number;
}

/** 응답에 실리는 내 반응. 반응이 없으면 `null` */
export type CommunityReaction = 'LIKE' | 'DISLIKE';

/** 반응 지정 요청 값. 토글이 아니라 **결과 상태**이며 `NONE` 은 취소다. */
export type CommunityReactionInput = CommunityReaction | 'NONE';

/** PUT …/reaction 요청 본문 */
export interface CommunityReactionRequest {
  reaction: CommunityReactionInput;
}

/** PUT …/reaction 응답. 취소 뒤에도 `myReaction` 키는 항상 실린다(`null`). */
export interface CommunityReactionResult {
  myReaction: CommunityReaction | null;
  likeCount: number;
  dislikeCount: number;
}

/* ------------------------------------------------------------------ *
 * 카테고리 · 이미지
 * ------------------------------------------------------------------ */

/** GET /community/categories 항목 */
export interface CommunityCategory {
  /** 카테고리 PK — 환경마다 다를 수 있다 */
  id: number;
  name: string;
  /** 구단 카테고리면 구단 id, 자유게시판은 `null` */
  teamId: number | null;
}

/** POST /community/images 응답 */
export interface CommunityImageUploadResponse {
  /** `temp/<uuid>.<ext>` — 글·댓글의 `imageUrls[]` 에 실어야 확정된다 */
  imageUrl: string;
}

/* ------------------------------------------------------------------ *
 * 게시글
 * ------------------------------------------------------------------ */

/** 목록 정렬. **소문자만** 받는다(대문자 포함 그 외 값은 400). 동률은 최신순. */
export type CommunityPostSort = 'latest' | 'likes' | 'views';

/** GET /community/posts 쿼리 */
export interface CommunityPostListParams extends CommunityPageParams {
  /** 없는 id 는 404 가 아니라 빈 페이지 */
  categoryId?: number;
  /** 기본 `latest` */
  sort?: CommunityPostSort;
}

/** GET /community/posts/popular 쿼리 */
export interface CommunityPopularPostParams {
  categoryId?: number;
}

/**
 * POST /community/posts · PUT /community/posts/{postId} 요청 본문.
 *
 * 수정은 **전체 교체**다 — `imageUrls` 가 수정 후 최종 목록이다(기존 `community/…` 는 그대로
 * 넣으면 유지, `temp/…` 는 새로 확정, 뺀 기존 이미지는 삭제). 작성 시 `community/…` 는 400.
 */
export interface CommunityPostRequest {
  categoryId: number;
  /** 공백 불가, 최대 100자 */
  title: string;
  /** 공백 불가, 최대 5000자 */
  content: string;
  /** 생략 = 빈 배열, 최대 5 */
  imageUrls?: string[];
}

/** POST /community/posts 응답(201) */
export interface CommunityPostCreateResult {
  postId: number;
}

/** 게시글 목록·인기 게시글 항목(11키). 본문은 싣지 않는다. */
export interface CommunityPostSummary {
  postId: number;
  categoryId: number;
  categoryName: string;
  title: string;
  /** 첫 이미지 EP. 없으면 `null` */
  thumbnailUrl: string | null;
  author: CommunityAuthor;
  viewCount: number;
  likeCount: number;
  dislikeCount: number;
  /** 보이는 댓글 + 답글 수 */
  commentCount: number;
  createdAt: string;
}

/** 내 게시글 항목(12키). 블라인드된 글도 포함된다. */
export interface MyCommunityPostSummary extends CommunityPostSummary {
  blinded: boolean;
}

/** 게시글 상세 · 수정 응답(15키). 댓글은 싣지 않는다(`commentCount` 만). */
export interface CommunityPostDetail {
  postId: number;
  categoryId: number;
  categoryName: string;
  title: string;
  content: string;
  /** 확정 EP 배열. 없으면 `[]` */
  imageUrls: string[];
  author: CommunityAuthor;
  viewCount: number;
  likeCount: number;
  dislikeCount: number;
  commentCount: number;
  myReaction: CommunityReaction | null;
  /** 요청자가 작성자인지 */
  isAuthor: boolean;
  createdAt: string;
  /** 수정한 적 없으면 `null` */
  updatedAt: string | null;
}

/* ------------------------------------------------------------------ *
 * 댓글 · 답글
 * ------------------------------------------------------------------ */

/** POST /community/posts/{postId}/comments 요청 본문 */
export interface CommunityCommentCreateRequest {
  /** 공백 불가, 최대 1000자 */
  content: string;
  /** 생략 = 빈 배열, 최대 3 */
  imageUrls?: string[];
  /** 있으면 답글. 같은 글의 **최상위** 댓글 id 만 가능(깊이 1 고정) */
  parentCommentId?: number | null;
}

/** PUT /community/comments/{commentId} 요청 본문. 부모는 바꿀 수 없다. */
export interface CommunityCommentUpdateRequest {
  content: string;
  /** 전체 교체, 최대 3 */
  imageUrls?: string[];
}

/** POST /community/posts/{postId}/comments 응답(201) */
export interface CommunityCommentCreateResult {
  commentId: number;
}

/** 댓글 상태. 답글은 항상 `VISIBLE`(삭제·블라인드된 답글은 목록에서 빠진다). */
export type CommunityCommentStatus = 'VISIBLE' | 'DELETED' | 'BLINDED';

/**
 * 답글(12키, `replies` 키 자체가 없다).
 *
 * 최상위 댓글이 삭제·블라인드된 **자리 표식**이면 같은 모양에서 `content`·`author` 가 `null`,
 * 카운트 0, `isAuthor: false`, `updatedAt: null` 이 된다(`createdAt` 은 유지).
 */
export interface CommunityReply {
  commentId: number;
  /** 최상위는 항상 `null`, 답글은 부모 id */
  parentCommentId: number | null;
  status: CommunityCommentStatus;
  content: string | null;
  imageUrls: string[];
  author: CommunityAuthor | null;
  likeCount: number;
  dislikeCount: number;
  myReaction: CommunityReaction | null;
  isAuthor: boolean;
  createdAt: string;
  updatedAt: string | null;
}

/** 최상위 댓글(13키). `replies` 에는 보이는 답글만 작성순으로 중첩된다(없으면 `[]`). */
export interface CommunityComment extends CommunityReply {
  replies: CommunityReply[];
}

/**
 * PUT /community/comments/{commentId} 응답.
 * 대상이 최상위 댓글이면 `replies` 가 있는 13키, 답글이면 12키다 — `'replies' in result` 로 가른다.
 */
export type CommunityCommentUpdateResult = CommunityComment | CommunityReply;
