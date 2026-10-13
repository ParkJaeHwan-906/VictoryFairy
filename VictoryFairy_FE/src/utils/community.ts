/**
 * 커뮤니티 화면들이 함께 쓰는 표기·문구.
 *
 * API 계층(`api/community.ts`)은 상태 코드 판별까지만 하고, 그 결과를 사용자에게 어떤
 * 말로 보일지는 여기서 정한다 — 게시판 · 글 상세 · 글쓰기 · 내 게시글 네 화면이 같은
 * 문구를 써야 해서 한 곳에 모았다.
 */
import {
  ApiError,
  COMMUNITY_IMAGE_MAX_BYTES,
  COMMUNITY_IMAGE_MIME_TYPES,
  isCommunityBlinded,
  isCommunityForbidden,
  isCommunityImageTooLarge,
  isCommunityNotFound,
} from '../api';
import type { CommunityReaction, CommunityReactionInput } from '../api';

/* ------------------------------------------------------------------ *
 * 시각
 *
 * 서버는 `createdAt`·`updatedAt` 을 **존 없는 ISO 문자열(Asia/Seoul 기준)** 로 준다
 * (docs/community.md). `new Date('2026-10-08T14:03:21')` 은 기기 시간대로 읽혀 해외
 * 기기에서 시각이 밀리므로, 반드시 `+09:00` 을 붙여 서울 시각으로 못 박고 읽는다.
 * ------------------------------------------------------------------ */

/** 서버 시각 문자열 → `Date`. 형식이 이상하면 `null`. */
export function parseCommunityTime(value: string): Date | null {
  const hasZone = /(?:[zZ]|[+-]\d{2}:?\d{2})$/.test(value);
  const date = new Date(hasZone ? value : `${value}+09:00`);
  return Number.isNaN(date.getTime()) ? null : date;
}

const SEOUL_PARTS = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Seoul',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
});

function seoulParts(date: Date) {
  const parts = Object.fromEntries(
    SEOUL_PARTS.formatToParts(date).map((part) => [part.type, part.value]),
  );
  return {
    year: parts.year,
    month: parts.month,
    day: parts.day,
    hour: parts.hour,
    minute: parts.minute,
  };
}

/**
 * 목록·댓글에 쓰는 짧은 표기.
 * 1분 안 `방금 전` · 1시간 안 `n분 전` · 같은 날 `n시간 전` · 올해 `MM.dd` · 그 밖 `yy.MM.dd`.
 */
export function formatCommunityRelativeTime(value: string, now: Date = new Date()): string {
  const date = parseCommunityTime(value);
  if (date === null) return '';

  const diffMinutes = Math.floor((now.getTime() - date.getTime()) / 60000);
  // 기기 시계가 서버보다 늦으면 음수가 된다 — 미래 시각을 그대로 적지 않고 "방금 전"으로 본다.
  if (diffMinutes < 1) return '방금 전';
  if (diffMinutes < 60) return `${diffMinutes}분 전`;

  const target = seoulParts(date);
  const today = seoulParts(now);
  const isSameDay =
    target.year === today.year && target.month === today.month && target.day === today.day;

  if (isSameDay) return `${Math.floor(diffMinutes / 60)}시간 전`;
  if (target.year === today.year) return `${target.month}.${target.day}`;
  return `${target.year.slice(2)}.${target.month}.${target.day}`;
}

/** 글 상세에 쓰는 전체 표기. 예) `2026.10.08 14:03` */
export function formatCommunityDateTime(value: string): string {
  const date = parseCommunityTime(value);
  if (date === null) return '';

  const { year, month, day, hour, minute } = seoulParts(date);
  return `${year}.${month}.${day} ${hour}:${minute}`;
}

/* ------------------------------------------------------------------ *
 * 반응
 * ------------------------------------------------------------------ */

/**
 * 버튼을 눌렀을 때 보낼 값.
 *
 * 반응 API 는 토글이 아니라 **상태 지정(멱등)** 이다 — 같은 값을 다시 보내면 아무것도
 * 바뀌지 않는다. 그래서 "이미 누른 버튼을 또 누르면 취소"라는 화면의 토글은 여기서
 * `NONE` 으로 바꿔 보내야 한다.
 */
export function nextCommunityReaction(
  current: CommunityReaction | null,
  pressed: CommunityReaction,
): CommunityReactionInput {
  return current === pressed ? 'NONE' : pressed;
}

/* ------------------------------------------------------------------ *
 * 실패 문구
 * ------------------------------------------------------------------ */

/** 410 — 신고로 숨김. 작성자·신고자·제3자 모두 같은 문구를 본다. */
export const COMMUNITY_BLINDED_MESSAGE = '신고로 숨겨진 게시물이에요.';

/** 어떤 대상에 대한 실패인지 — 404·403 의 문구가 대상마다 다르다. */
type CommunityErrorTarget = 'post' | 'comment' | 'image';

const NOT_FOUND_MESSAGE: Record<CommunityErrorTarget, string> = {
  post: '삭제되었거나 없는 게시글이에요.',
  comment: '삭제되었거나 없는 댓글이에요.',
  image: '이미지를 찾을 수 없어요. 다시 올려 주세요.',
};

/**
 * 커뮤니티 실패를 화면 한 줄로 옮긴다.
 *
 * - 410 은 언제나 "신고로 숨김" 안내다(404 "삭제됨"과 구분해 보여 주라는 계약).
 * - 400 Bean Validation 은 사유가 `message` 가 아니라 필드 맵에 담긴다 — 첫 사유를 보인다.
 * - 5xx · 네트워크는 사용자가 손쓸 수 없어 서버 문구 대신 재시도 안내를 쓴다.
 * - 그 밖(400 이미지 · 403 등)은 서버 문구가 무엇이 잘못됐는지 이미 담고 있어 그대로 쓴다.
 */
export function toCommunityMessage(
  error: unknown,
  fallback: string,
  target: CommunityErrorTarget = 'post',
): string {
  if (!(error instanceof ApiError)) return fallback;
  if (error.isNetworkError || (error.status !== null && error.status >= 500)) return fallback;

  /*
   * 댓글 조작의 410 은 댓글이 숨겨졌을 수도, 그 댓글이 달린 글이 숨겨졌을 수도 있다 —
   * 상태 코드만으로는 갈리지 않아 둘을 함께 말한다.
   */
  if (isCommunityBlinded(error)) {
    return target === 'comment' ? '신고로 숨겨진 게시글 또는 댓글이에요.' : COMMUNITY_BLINDED_MESSAGE;
  }
  if (isCommunityNotFound(error)) return NOT_FOUND_MESSAGE[target];
  if (isCommunityImageTooLarge(error)) return '이미지 크기는 5MB를 넘을 수 없어요.';
  if (isCommunityForbidden(error)) return error.message || fallback;

  const firstFieldError = error.fieldErrors ? Object.values(error.fieldErrors)[0] : undefined;
  return firstFieldError ?? (error.message || fallback);
}

/**
 * 고르자마자 화면에서 먼저 거르는 이미지 검사. 통과하면 `null`.
 * 서버는 형식을 선두 바이트로 보므로 **확실히 아닌 것만** 막고, 형식을 알 수 없는 파일은 맡긴다.
 */
export function validateCommunityImageFile(file: File): string | null {
  if (file.size === 0) return '빈 파일은 올릴 수 없어요.';
  if (file.size > COMMUNITY_IMAGE_MAX_BYTES) return '이미지 크기는 5MB를 넘을 수 없어요.';

  const mime = file.type as (typeof COMMUNITY_IMAGE_MIME_TYPES)[number];
  if (file.type.length > 0 && !COMMUNITY_IMAGE_MIME_TYPES.includes(mime)) {
    return 'JPG, PNG, WEBP 이미지만 올릴 수 있어요.';
  }

  return null;
}
