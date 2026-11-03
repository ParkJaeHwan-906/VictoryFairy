import { useCallback, useEffect, useRef, useState } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom';
import {
  COMMUNITY_PAGE_SIZE,
  createCommunityComment,
  deleteCommunityComment,
  deleteCommunityPost,
  getCommunityComments,
  getCommunityPost,
  isCommunityBlinded,
  isCommunityNotFound,
  reportCommunityComment,
  reportCommunityPost,
  setCommunityCommentReaction,
  setCommunityPostReaction,
  toAssetUrl,
  updateCommunityComment,
} from '../api';
import type {
  CommunityComment,
  CommunityPostDetail,
  CommunityReaction,
  CommunityReactionResult,
  CommunityReply,
} from '../api';
import CommunityCommentComposer, {
  type CommunityComposerMode,
} from '../components/CommunityCommentComposer';
import CommunityCommentList from '../components/CommunityCommentList';
import CommunityMoreMenu, { type CommunityMenuItem } from '../components/CommunityMoreMenu';
import CommunityReactionButtons from '../components/CommunityReactionButtons';
import ConfirmSheet from '../components/ConfirmSheet';
import NoticeSheet from '../components/NoticeSheet';
import {
  communityBoardPath,
  communityEditPath,
  type CommunityEditState,
  type CommunityNoticeState,
} from '../routes';
import { fallbackToPlaceholder, profilePlaceholder } from '../utils/avatar';
import {
  COMMUNITY_BLINDED_MESSAGE,
  formatCommunityDateTime,
  nextCommunityReaction,
  toCommunityMessage,
} from '../utils/community';
import '../styles/CommunityPostDetailPage.css';

/** 글을 그릴 수 있는지. 410(신고로 숨김)과 404(삭제됨·없음)는 서로 다른 안내를 띄운다. */
type PostStatus = 'loading' | 'ready' | 'blinded' | 'notFound' | 'error';

/** 한 번 더 묻고 하는 조작. 넷 다 되돌릴 수 없다(신고는 즉시 블라인드, 삭제는 복구 경로 없음). */
type Confirming =
  | { kind: 'deletePost' }
  | { kind: 'reportPost' }
  | { kind: 'deleteComment'; target: CommunityReply }
  | { kind: 'reportComment'; target: CommunityReply };

const CONFIRM_COPY: Record<
  Confirming['kind'],
  { title: string; description: readonly string[]; confirmLabel: string; pendingLabel: string }
> = {
  deletePost: {
    title: '게시글을 삭제하시겠어요?',
    description: ['삭제한 게시글은 되돌릴 수 없어요', '달린 댓글도 더 이상 볼 수 없어요'],
    confirmLabel: '삭제',
    pendingLabel: '삭제 중',
  },
  reportPost: {
    title: '게시글을 신고하시겠어요?',
    description: ['신고하면 이 게시글은 바로 숨겨져요', '이 작업은 되돌릴 수 없어요'],
    confirmLabel: '신고',
    pendingLabel: '신고 중',
  },
  deleteComment: {
    title: '댓글을 삭제하시겠어요?',
    description: ['삭제한 댓글은 되돌릴 수 없어요', '달린 답글은 그대로 남아요'],
    confirmLabel: '삭제',
    pendingLabel: '삭제 중',
  },
  reportComment: {
    title: '댓글을 신고하시겠어요?',
    description: ['신고하면 이 댓글은 바로 숨겨져요', '이 작업은 되돌릴 수 없어요'],
    confirmLabel: '신고',
    pendingLabel: '신고 중',
  },
};

const NEW_COMMENT: CommunityComposerMode = { type: 'new' };

/** 반응 응답을 대상 댓글(또는 답글)에 덮어쓴다. */
function patchComment(
  comments: CommunityComment[],
  commentId: number,
  result: CommunityReactionResult,
): CommunityComment[] {
  return comments.map((comment) =>
    comment.commentId === commentId
      ? { ...comment, ...result }
      : {
          ...comment,
          replies: comment.replies.map((reply) =>
            reply.commentId === commentId ? { ...reply, ...result } : reply,
          ),
        },
  );
}

/**
 * CommunityPostDetailPage — 게시글 상세 + 댓글.
 *
 * NavBar 없는 전체 화면이다. 위는 글(머리말 · 본문 · 사진 · 반응), 아래는 댓글 목록이고,
 * 화면 맨 아래에 댓글 입력창이 붙어 있다.
 *
 * ── 상세 조회는 조회수를 올린다 ───────────────────────────────────────
 * `GET /community/posts/{id}` 는 **부수효과가 있는 GET** 이다(같은 계정 · 같은 글은 5분에 한 번,
 * 작성자 본인은 세지 않음). 그래서 이 화면은 글을 **주소의 postId 가 바뀔 때 한 번만** 받는다.
 * 반응 · 댓글 작성 · 삭제 · 신고 뒤에도 글을 다시 받지 않고, 응답(반응 카운트)이나 아는
 * 사실(댓글 수 ±1)로 들고 있는 값을 고친다. 댓글은 조회수와 무관한 별도 경로라 마음껏 다시 받는다.
 * (개발 모드의 StrictMode 는 effect 를 두 번 돌려 두 번 부르지만, 서버의 5분 창이 한 번으로 센다.)
 *
 * ── 410 과 404 ────────────────────────────────────────────────────────
 * 410 은 "신고로 숨김", 404 는 "삭제됐거나 없음"이다. 숨겨진 글은 작성자도 열 수 없고
 * (응답에 작성자 여부도 없다), 지우는 것만 된다 — 그 길(마이페이지 > 내 게시글)을 안내한다.
 * 글을 보던 중 댓글 조작이 410 을 받으면 댓글 목록을 다시 받아, 거기서도 410 이면 글이
 * 숨겨진 것으로 보고 화면을 바꾼다(410 만으로는 글인지 댓글인지 갈리지 않는다).
 * ──────────────────────────────────────────────────────────────────────
 */
export default function CommunityPostDetailPage() {
  const { postId: rawPostId } = useParams();
  const postId = Number(rawPostId);
  const isValidId = Number.isInteger(postId) && postId > 0;

  const navigate = useNavigate();
  const location = useLocation();

  const [post, setPost] = useState<CommunityPostDetail | null>(null);
  const [status, setStatus] = useState<PostStatus>(isValidId ? 'loading' : 'notFound');

  const [comments, setComments] = useState<CommunityComment[]>([]);
  const [commentPages, setCommentPages] = useState(0);
  const [commentsHasNext, setCommentsHasNext] = useState(false);
  const [commentsLoading, setCommentsLoading] = useState(true);
  const [commentsFailed, setCommentsFailed] = useState(false);

  const [composerMode, setComposerMode] = useState<CommunityComposerMode>(NEW_COMMENT);
  const [pendingReactionId, setPendingReactionId] = useState<number | null>(null);
  const [isPostReactionPending, setIsPostReactionPending] = useState(false);
  const [confirming, setConfirming] = useState<Confirming | null>(null);
  const [isConfirmPending, setIsConfirmPending] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  /** 화면을 떠난 뒤 도착한 응답이 state 를 건드리지 않게 막는다. */
  const aliveRef = useRef(true);
  useEffect(() => {
    aliveRef.current = true;
    return () => {
      aliveRef.current = false;
    };
  }, []);

  /** 글이 사라졌다는 응답(410·404)을 화면 상태로 옮긴다. 옮겼으면 `true`. */
  const applyGone = useCallback((error: unknown): boolean => {
    if (isCommunityBlinded(error)) {
      setStatus('blinded');
      return true;
    }
    if (isCommunityNotFound(error)) {
      setStatus('notFound');
      return true;
    }
    return false;
  }, []);

  /* 글은 postId 마다 한 번만 받는다(머리말 "상세 조회는 조회수를 올린다"). */
  useEffect(() => {
    if (!isValidId) return;

    let alive = true;
    setStatus('loading');

    getCommunityPost(postId)
      .then((detail) => {
        if (!alive) return;
        setPost(detail);
        setStatus('ready');
      })
      .catch((cause: unknown) => {
        if (!alive) return;
        if (!applyGone(cause)) setStatus('error');
      });

    return () => {
      alive = false;
    };
  }, [applyGone, isValidId, postId]);

  /**
   * 댓글을 앞에서부터 `pageCount` 쪽까지 다시 받는다.
   *
   * 작성 · 수정 · 삭제 · 신고 뒤에 부른다 — 자리 표식(`DELETED`·`BLINDED`)과 답글 위치는
   * 서버만 정확히 알아서, 손으로 고치기보다 다시 받는 편이 맞다. 보던 쪽 수만큼 다시 받아
   * "더 보기"로 펼쳐 둔 댓글이 접히지 않게 한다. 410 이면 글이 숨겨진 것이다.
   */
  const reloadComments = useCallback(
    async (pageCount: number) => {
      const pages = Math.max(1, pageCount);
      setCommentsFailed(false);

      try {
        const results = await Promise.all(
          Array.from({ length: pages }, (_, page) =>
            getCommunityComments(postId, { page, size: COMMUNITY_PAGE_SIZE }),
          ),
        );
        if (!aliveRef.current) return;

        const seen = new Set<number>();
        const merged = results
          .flatMap((result) => result.content)
          .filter((comment) => {
            if (seen.has(comment.commentId)) return false;
            seen.add(comment.commentId);
            return true;
          });

        setComments(merged);
        setCommentPages(pages);
        setCommentsHasNext(results[results.length - 1].hasNext);
      } catch (cause: unknown) {
        if (!aliveRef.current) return;
        if (!applyGone(cause)) setCommentsFailed(true);
      } finally {
        if (aliveRef.current) setCommentsLoading(false);
      }
    },
    [applyGone, postId],
  );

  /* 댓글은 조회수를 세지 않는 경로라 글과 함께 바로 받는다. */
  useEffect(() => {
    if (!isValidId) return;
    setCommentsLoading(true);
    void reloadComments(1);
  }, [isValidId, reloadComments]);

  const [isLoadingMoreComments, setIsLoadingMoreComments] = useState(false);
  const loadMoreComments = () => {
    if (isLoadingMoreComments || !commentsHasNext) return;
    setIsLoadingMoreComments(true);

    getCommunityComments(postId, { page: commentPages, size: COMMUNITY_PAGE_SIZE })
      .then((next) => {
        if (!aliveRef.current) return;
        setComments((prev) => {
          const seen = new Set(prev.map((comment) => comment.commentId));
          return [...prev, ...next.content.filter((comment) => !seen.has(comment.commentId))];
        });
        setCommentPages((pages) => pages + 1);
        setCommentsHasNext(next.hasNext);
      })
      .catch((cause: unknown) => {
        if (aliveRef.current && !applyGone(cause)) {
          setNotice('댓글을 더 불러오지 못했어요. 잠시 후 다시 시도해 주세요.');
        }
      })
      .finally(() => {
        if (aliveRef.current) setIsLoadingMoreComments(false);
      });
  };

  /** 글의 댓글 수를 아는 만큼 고친다 — 글을 다시 받으면 조회수 GET 이 한 번 더 나간다. */
  const shiftCommentCount = (delta: number) => {
    setPost((prev) => (prev ? { ...prev, commentCount: Math.max(0, prev.commentCount + delta) } : prev));
  };

  /**
   * 화면을 떠난다(글 삭제 뒤). 앞 화면이 있으면 그리로, 주소로 바로 들어왔으면 게시판으로.
   * 게시판·내 게시글은 돌아가며 다시 받으므로 지운 글이 남아 보이지 않는다.
   */
  const leave = (message: string) => {
    if (location.key !== 'default') {
      navigate(-1);
      return;
    }
    const state: CommunityNoticeState = { notice: message };
    navigate(communityBoardPath, { replace: true, state });
  };

  /* ------------------------------------------------------------------ *
   * 글 조작
   * ------------------------------------------------------------------ */

  const handlePostReaction = async (pressed: CommunityReaction) => {
    if (post === null || isPostReactionPending) return;
    setIsPostReactionPending(true);

    try {
      // 같은 버튼을 다시 누르면 취소(NONE) — 반응 API 는 토글이 아니라 상태 지정이다.
      const result = await setCommunityPostReaction(
        post.postId,
        nextCommunityReaction(post.myReaction, pressed),
      );
      if (!aliveRef.current) return;
      setPost((prev) => (prev ? { ...prev, ...result } : prev));
    } catch (cause: unknown) {
      if (!aliveRef.current) return;
      if (!applyGone(cause)) {
        setNotice(toCommunityMessage(cause, '반응을 남기지 못했어요. 잠시 후 다시 시도해 주세요.'));
      }
    } finally {
      if (aliveRef.current) setIsPostReactionPending(false);
    }
  };

  const openEdit = () => {
    if (post === null) return;
    // 상세를 다시 부르지 않도록 들고 있는 글을 그대로 넘긴다(`CommunityEditState` 주석).
    const state: CommunityEditState = { post };
    navigate(communityEditPath(post.postId), { state });
  };

  /* ------------------------------------------------------------------ *
   * 댓글 조작
   * ------------------------------------------------------------------ */

  const handleCommentReaction = async (target: CommunityReply, pressed: CommunityReaction) => {
    if (pendingReactionId !== null) return;
    setPendingReactionId(target.commentId);

    try {
      const result = await setCommunityCommentReaction(
        target.commentId,
        nextCommunityReaction(target.myReaction, pressed),
      );
      if (!aliveRef.current) return;
      setComments((prev) => patchComment(prev, target.commentId, result));
    } catch (cause: unknown) {
      if (!aliveRef.current) return;
      setNotice(
        toCommunityMessage(cause, '반응을 남기지 못했어요. 잠시 후 다시 시도해 주세요.', 'comment'),
      );
      // 댓글이 사라졌거나 숨겨졌을 수 있다 — 목록을 고쳐 그린다(글이 숨겨졌으면 화면이 바뀐다).
      if (isCommunityBlinded(cause) || isCommunityNotFound(cause)) void reloadComments(commentPages);
    } finally {
      if (aliveRef.current) setPendingReactionId(null);
    }
  };

  const handleComposerSubmit = async (content: string, imageUrls: string[]) => {
    try {
      if (composerMode.type === 'edit') {
        await updateCommunityComment(composerMode.target.commentId, { content, imageUrls });
      } else {
        await createCommunityComment(postId, {
          content,
          imageUrls,
          parentCommentId: composerMode.type === 'reply' ? composerMode.parent.commentId : null,
        });
        shiftCommentCount(1);
      }
    } catch (cause: unknown) {
      // 대상이 사라졌거나 숨겨졌으면 목록부터 고쳐 그리고, 문구는 입력창이 띄운다.
      if (isCommunityBlinded(cause) || isCommunityNotFound(cause)) {
        void reloadComments(commentPages);
        /*
         * 답글·수정 모드였다면 새 댓글로 되돌린다. 모드가 바뀌면 입력창은 자기 문구를 지우므로
         * 이때만 안내를 이 화면의 NoticeSheet 로 올린다(새 댓글 모드는 입력창이 그대로 띄운다).
         */
        if (composerMode.type !== 'new') {
          setComposerMode(NEW_COMMENT);
          setNotice(
            toCommunityMessage(cause, '댓글을 남기지 못했어요. 잠시 후 다시 시도해 주세요.', 'comment'),
          );
        }
      }
      throw cause;
    }

    setComposerMode(NEW_COMMENT);
    await reloadComments(commentPages);
  };

  const handleConfirm = async () => {
    if (confirming === null || isConfirmPending) return;
    setIsConfirmPending(true);

    try {
      switch (confirming.kind) {
        case 'deletePost':
          await deleteCommunityPost(postId);
          if (!aliveRef.current) return;
          leave('게시글을 삭제했어요.');
          return;

        case 'reportPost':
          await reportCommunityPost(postId);
          if (!aliveRef.current) return;
          // 신고 1건이 곧바로 블라인드다 — 이 화면도 숨김 안내로 바뀐다.
          setStatus('blinded');
          setNotice('신고가 접수되었어요. 이 게시글은 숨겨져요.');
          break;

        case 'deleteComment':
          await deleteCommunityComment(confirming.target.commentId);
          if (!aliveRef.current) return;
          // 보이던 댓글이 지워지면 글의 commentCount 가 하나 준다.
          shiftCommentCount(-1);
          setNotice('댓글을 삭제했어요.');
          break;

        case 'reportComment':
          await reportCommunityComment(confirming.target.commentId);
          if (!aliveRef.current) return;
          shiftCommentCount(-1);
          setNotice('신고가 접수되었어요. 이 댓글은 숨겨져요.');
          break;
      }

      if (confirming.kind === 'deleteComment' || confirming.kind === 'reportComment') {
        const targetId = confirming.target.commentId;
        setComposerMode((mode) =>
          (mode.type === 'edit' && mode.target.commentId === targetId) ||
          (mode.type === 'reply' && mode.parent.commentId === targetId)
            ? NEW_COMMENT
            : mode,
        );
        void reloadComments(commentPages);
      }
      setConfirming(null);
    } catch (cause: unknown) {
      if (!aliveRef.current) return;
      const isComment = confirming.kind === 'deleteComment' || confirming.kind === 'reportComment';
      setConfirming(null);
      setNotice(
        toCommunityMessage(
          cause,
          '요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요.',
          isComment ? 'comment' : 'post',
        ),
      );
      if (isComment) void reloadComments(commentPages);
      else applyGone(cause);
    } finally {
      if (aliveRef.current) setIsConfirmPending(false);
    }
  };

  /* ------------------------------------------------------------------ *
   * 그리기
   * ------------------------------------------------------------------ */

  const topbar = (menu: CommunityMenuItem[]) => (
    <header className="community-post-page__topbar">
      <button
        className="community-post-page__back"
        type="button"
        onClick={() => (location.key !== 'default' ? navigate(-1) : navigate(communityBoardPath))}
        aria-label="뒤로 가기"
      >
        <span className="community-post-page__back-icon" aria-hidden="true" />
      </button>
      <p className="community-post-page__topbar-title">게시글</p>
      <div className="community-post-page__topbar-side">
        <CommunityMoreMenu label="게시글 더보기" items={menu} />
      </div>
    </header>
  );

  const sheets = (
    <>
      {confirming !== null && (
        <ConfirmSheet
          {...CONFIRM_COPY[confirming.kind]}
          isPending={isConfirmPending}
          onConfirm={() => void handleConfirm()}
          onClose={() => {
            if (isConfirmPending) return;
            setConfirming(null);
          }}
        />
      )}
      {notice !== null && <NoticeSheet message={notice} onClose={() => setNotice(null)} />}
    </>
  );

  if (status !== 'ready' || post === null) {
    return (
      <div className="community-post-page">
        {topbar([])}
        <div className="community-post-page__state">
          {status === 'loading' && <p className="community-post-page__state-text">게시글을 불러오는 중입니다.</p>}

          {status === 'blinded' && (
            <>
              <span className="community-post-page__state-icon" aria-hidden="true" />
              <p className="community-post-page__state-title">{COMMUNITY_BLINDED_MESSAGE}</p>
              <p className="community-post-page__state-text">
                신고가 접수된 게시물은 누구에게도 보이지 않아요.
                <br />
                내가 쓴 글이라면 마이페이지 &gt; 내 게시글에서 지울 수 있어요.
              </p>
            </>
          )}

          {status === 'notFound' && (
            <>
              <span className="community-post-page__state-icon" aria-hidden="true" />
              <p className="community-post-page__state-title">삭제되었거나 없는 게시글이에요.</p>
            </>
          )}

          {status === 'error' && (
            <p className="community-post-page__state-text community-post-page__state-text--error">
              게시글을 불러오지 못했습니다. 잠시 후 다시 시도해주세요.
            </p>
          )}

          {status !== 'loading' && (
            <Link className="community-post-page__state-link" to={communityBoardPath} replace>
              게시판으로 가기
            </Link>
          )}
        </div>
        {sheets}
      </div>
    );
  }

  /* 작성자만 수정 · 삭제, 남의 글만 신고 — 자기 글 신고는 403 이라 아예 열지 않는다. */
  const postMenu: CommunityMenuItem[] = post.isAuthor
    ? [
        { label: '수정', onSelect: openEdit },
        { label: '삭제', onSelect: () => setConfirming({ kind: 'deletePost' }), danger: true },
      ]
    : [{ label: '신고', onSelect: () => setConfirming({ kind: 'reportPost' }), danger: true }];

  const focusedCommentId =
    composerMode.type === 'edit'
      ? composerMode.target.commentId
      : composerMode.type === 'reply'
        ? composerMode.parent.commentId
        : null;

  return (
    <div className="community-post-page">
      {topbar(postMenu)}

      <article className="community-post-page__article">
        <div className="community-post-page__head">
          <p className="community-post-page__category">{post.categoryName}</p>
          <h1 className="community-post-page__title">{post.title}</h1>

          <div className="community-post-page__author">
            <img
              className="community-post-page__avatar"
              src={toAssetUrl(post.author.profileImgUrl) ?? profilePlaceholder}
              onError={fallbackToPlaceholder}
              alt=""
            />
            <div className="community-post-page__author-text">
              <p className="community-post-page__nickname">{post.author.nickname}</p>
              <p className="community-post-page__meta">
                <time dateTime={post.createdAt}>{formatCommunityDateTime(post.createdAt)}</time>
                {/* `updatedAt` 은 작성자가 고친 적이 있을 때만 값이 있다. null 이면 "수정 안 됨". */}
                {post.updatedAt !== null && <span>(수정됨)</span>}
                <span aria-hidden="true">·</span>
                <span>조회 {post.viewCount}</span>
              </p>
            </div>
          </div>
        </div>

        <div className="community-post-page__body">
          <p className="community-post-page__content">{post.content}</p>

          {post.imageUrls.length > 0 && (
            <ul className="community-post-page__images">
              {post.imageUrls.map((endpoint, index) => (
                <li key={endpoint}>
                  <img
                    className="community-post-page__image"
                    src={toAssetUrl(endpoint) ?? ''}
                    alt={`게시글 사진 ${index + 1}`}
                    loading="lazy"
                  />
                </li>
              ))}
            </ul>
          )}

          <CommunityReactionButtons
            myReaction={post.myReaction}
            likeCount={post.likeCount}
            dislikeCount={post.dislikeCount}
            onPress={(pressed) => void handlePostReaction(pressed)}
            disabled={isPostReactionPending}
          />
        </div>
      </article>

      <section className="community-post-page__comments" aria-labelledby="community-post-comments-title">
        {/* 글의 commentCount(답글 포함)를 쓴다 — 목록 totalElements 는 최상위+자리 표식 수라 다르다. */}
        <h2 className="community-post-page__comments-title" id="community-post-comments-title">
          댓글 <span className="community-post-page__comments-count">{post.commentCount}</span>
        </h2>

        {commentsLoading && <p className="community-post-page__status">댓글을 불러오는 중입니다.</p>}

        {commentsFailed && (
          <p className="community-post-page__status community-post-page__status--error">
            댓글을 불러오지 못했습니다.{' '}
            <button
              className="community-post-page__retry"
              type="button"
              onClick={() => void reloadComments(commentPages)}
            >
              다시 시도
            </button>
          </p>
        )}

        {!commentsLoading && !commentsFailed && comments.length === 0 && (
          <p className="community-post-page__status">첫 댓글을 남겨 보세요.</p>
        )}

        {comments.length > 0 && (
          <CommunityCommentList
            comments={comments}
            pendingReactionId={pendingReactionId}
            focusedId={focusedCommentId}
            onReply={(comment) => setComposerMode({ type: 'reply', parent: comment })}
            onEdit={(target) => setComposerMode({ type: 'edit', target })}
            onDelete={(target) => setConfirming({ kind: 'deleteComment', target })}
            onReport={(target) => setConfirming({ kind: 'reportComment', target })}
            onReact={(target, pressed) => void handleCommentReaction(target, pressed)}
          />
        )}

        {commentsHasNext && (
          <button
            className="community-post-page__more"
            type="button"
            onClick={loadMoreComments}
            disabled={isLoadingMoreComments}
          >
            {isLoadingMoreComments ? '불러오는 중...' : '댓글 더 보기'}
          </button>
        )}
      </section>

      <CommunityCommentComposer
        mode={composerMode}
        onCancelMode={() => setComposerMode(NEW_COMMENT)}
        onSubmit={handleComposerSubmit}
        describeError={(cause) =>
          toCommunityMessage(cause, '댓글을 남기지 못했어요. 잠시 후 다시 시도해 주세요.', 'comment')
        }
      />

      {sheets}
    </div>
  );
}
