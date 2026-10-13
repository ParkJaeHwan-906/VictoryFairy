import { toAssetUrl } from '../api';
import type { CommunityComment, CommunityReaction, CommunityReply } from '../api';
import { fallbackToPlaceholder, profilePlaceholder } from '../utils/avatar';
import { formatCommunityRelativeTime } from '../utils/community';
import CommunityMoreMenu, { type CommunityMenuItem } from './CommunityMoreMenu';
import CommunityReactionButtons from './CommunityReactionButtons';
import '../styles/CommunityCommentList.css';

export type CommunityCommentHandlers = {
  /** 답글 달기 — **보이는 최상위 댓글에만** 열린다(깊이 1 고정). */
  onReply: (comment: CommunityComment) => void;
  onEdit: (target: CommunityReply) => void;
  onDelete: (target: CommunityReply) => void;
  onReport: (target: CommunityReply) => void;
  onReact: (target: CommunityReply, pressed: CommunityReaction) => void;
};

type CommunityCommentListProps = CommunityCommentHandlers & {
  comments: readonly CommunityComment[];
  /** 반응 요청이 나가 있는 댓글 — 응답 전 연타를 막는다. */
  pendingReactionId: number | null;
  /** 입력창이 지금 붙들고 있는 댓글(답글 대상·수정 대상). 줄 바탕으로 알린다. */
  focusedId: number | null;
};

/**
 * CommunityCommentList — 댓글 목록. 최상위 댓글 아래 답글이 한 단 들여 붙는다.
 *
 * ── 자리 표식 ─────────────────────────────────────────────────────────
 * 삭제(`DELETED`)·블라인드(`BLINDED`)된 **최상위** 댓글은 사라지지 않고 본문·작성자가
 * `null` 인 채로 온다 — 그 아래 살아 있는 답글이 어디에 달린 것인지 보여 주기 위해서다.
 * 그래서 내용 자리에 "삭제된 댓글"·"신고로 숨겨진 댓글" 한 줄만 그리고, 조작은 하나도 열지
 * 않는다(작성자 본인이 봐도 `isAuthor:false` 로 온다). 삭제·블라인드된 **답글**은 응답에서
 * 아예 빠져 오므로 따로 다룰 일이 없다.
 * ──────────────────────────────────────────────────────────────────────
 */
export default function CommunityCommentList({
  comments,
  pendingReactionId,
  focusedId,
  ...handlers
}: CommunityCommentListProps) {
  return (
    <ol className="community-comment-list">
      {comments.map((comment) => (
        <li key={comment.commentId} className="community-comment-list__thread">
          <CommentRow
            target={comment}
            isReply={false}
            isPending={pendingReactionId === comment.commentId}
            isFocused={focusedId === comment.commentId}
            {...handlers}
            onReply={() => handlers.onReply(comment)}
          />

          {comment.replies.length > 0 && (
            <ol className="community-comment-list__replies">
              {comment.replies.map((reply) => (
                <li key={reply.commentId}>
                  <CommentRow
                    target={reply}
                    isReply
                    isPending={pendingReactionId === reply.commentId}
                    isFocused={focusedId === reply.commentId}
                    {...handlers}
                    onReply={undefined}
                  />
                </li>
              ))}
            </ol>
          )}
        </li>
      ))}
    </ol>
  );
}

type CommentRowProps = Omit<CommunityCommentHandlers, 'onReply'> & {
  target: CommunityReply;
  isReply: boolean;
  isPending: boolean;
  isFocused: boolean;
  /** 답글에는 없다. */
  onReply: (() => void) | undefined;
};

function CommentRow({
  target,
  isReply,
  isPending,
  isFocused,
  onReply,
  onEdit,
  onDelete,
  onReport,
  onReact,
}: CommentRowProps) {
  const rowClass = `community-comment${isReply ? ' community-comment--reply' : ''}${
    isFocused ? ' community-comment--focused' : ''
  }`;

  if (target.status !== 'VISIBLE' || target.author === null) {
    return (
      <div className={`${rowClass} community-comment--placeholder`}>
        <p className="community-comment__placeholder">
          {target.status === 'BLINDED' ? '신고로 숨겨진 댓글이에요.' : '삭제된 댓글이에요.'}
        </p>
      </div>
    );
  }

  /*
   * 작성자만 수정 · 삭제, 남의 댓글만 신고 — 자기 댓글 신고는 403 이라 아예 열지 않는다.
   */
  const menuItems: CommunityMenuItem[] = target.isAuthor
    ? [
        { label: '수정', onSelect: () => onEdit(target) },
        { label: '삭제', onSelect: () => onDelete(target), danger: true },
      ]
    : [{ label: '신고', onSelect: () => onReport(target), danger: true }];

  return (
    <div className={rowClass}>
      <img
        className="community-comment__avatar"
        src={toAssetUrl(target.author.profileImgUrl) ?? profilePlaceholder}
        onError={fallbackToPlaceholder}
        alt=""
      />

      <div className="community-comment__main">
        <div className="community-comment__head">
          <p className="community-comment__author">
            <span className="community-comment__nickname">{target.author.nickname}</span>
            {target.isAuthor && <span className="community-comment__mine">내 댓글</span>}
          </p>
          <CommunityMoreMenu label={`${target.author.nickname}님 댓글 더보기`} items={menuItems} />
        </div>

        <p className="community-comment__content">{target.content}</p>

        {target.imageUrls.length > 0 && (
          <ul className="community-comment__images">
            {target.imageUrls.map((endpoint, index) => (
              <li key={endpoint}>
                <img
                  className="community-comment__image"
                  src={toAssetUrl(endpoint) ?? ''}
                  alt={`댓글 사진 ${index + 1}`}
                  loading="lazy"
                />
              </li>
            ))}
          </ul>
        )}

        <div className="community-comment__foot">
          <time className="community-comment__time" dateTime={target.createdAt}>
            {formatCommunityRelativeTime(target.createdAt)}
            {/* `updatedAt` 은 작성자가 고친 적이 있을 때만 값이 있다(반응·신고로는 바뀌지 않는다). */}
            {target.updatedAt !== null && ' (수정됨)'}
          </time>

          {onReply !== undefined && (
            <button className="community-comment__reply" type="button" onClick={onReply}>
              답글
            </button>
          )}

          <CommunityReactionButtons
            size="small"
            myReaction={target.myReaction}
            likeCount={target.likeCount}
            dislikeCount={target.dislikeCount}
            onPress={(pressed) => onReact(target, pressed)}
            disabled={isPending}
          />
        </div>
      </div>
    </div>
  );
}
