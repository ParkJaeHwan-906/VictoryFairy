import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { COMMUNITY_PAGE_SIZE, deleteCommunityPost, getMyCommunityPosts } from '../api';
import type { MyCommunityPostSummary } from '../api';
import CommunityPostCard from '../components/CommunityPostCard';
import ConfirmSheet from '../components/ConfirmSheet';
import NoticeSheet from '../components/NoticeSheet';
import { toCommunityMessage } from '../utils/community';
import '../styles/MyCommunityPostsPage.css';

/**
 * MyCommunityPostsPage — 내 게시글. 마이페이지 "활동 > 내 게시글"로 들어온다.
 *
 * 공지사항 목록과 같은 NavBar 없는 전체 화면이다.
 *
 * ── 숨겨진 글은 여기서만 지울 수 있다 ─────────────────────────────────
 * 이 목록은 **신고로 숨겨진(블라인드) 내 글도 함께 준다**(`blinded: true`, 삭제 글은 제외).
 * 숨겨진 글은 상세가 410 이라 작성자도 열 수 없지만, 삭제는 작성자에게 허용된다
 * (docs/community.md). 상세로 들어가 "⋯ > 삭제"를 누를 길이 없으므로, 숨겨진 줄에는
 * 이 화면에서 바로 지우는 버튼을 붙인다. 보이는 글은 상세에서 지우면 된다.
 * ──────────────────────────────────────────────────────────────────────
 */
export default function MyCommunityPostsPage() {
  const navigate = useNavigate();

  const [posts, setPosts] = useState<MyCommunityPostSummary[]>([]);
  const [page, setPage] = useState(0);
  const [hasNext, setHasNext] = useState(false);
  const [isLoading, setIsLoading] = useState(true);
  const [isLoadingMore, setIsLoadingMore] = useState(false);
  const [loadFailed, setLoadFailed] = useState(false);

  const [deleteTarget, setDeleteTarget] = useState<MyCommunityPostSummary | null>(null);
  const [isDeleting, setIsDeleting] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  const aliveRef = useRef(true);
  useEffect(() => {
    aliveRef.current = true;

    getMyCommunityPosts({ page: 0, size: COMMUNITY_PAGE_SIZE })
      .then((first) => {
        if (!aliveRef.current) return;
        setPosts(first.content);
        setPage(first.page);
        setHasNext(first.hasNext);
      })
      .catch(() => {
        if (aliveRef.current) setLoadFailed(true);
      })
      .finally(() => {
        if (aliveRef.current) setIsLoading(false);
      });

    return () => {
      aliveRef.current = false;
    };
  }, []);

  const loadMore = () => {
    if (isLoadingMore || !hasNext) return;
    setIsLoadingMore(true);

    getMyCommunityPosts({ page: page + 1, size: COMMUNITY_PAGE_SIZE })
      .then((next) => {
        if (!aliveRef.current) return;
        setPosts((prev) => {
          const seen = new Set(prev.map((post) => post.postId));
          return [...prev, ...next.content.filter((post) => !seen.has(post.postId))];
        });
        setPage(next.page);
        setHasNext(next.hasNext);
      })
      .catch(() => {
        if (aliveRef.current) setNotice('게시글을 더 불러오지 못했어요. 잠시 후 다시 시도해 주세요.');
      })
      .finally(() => {
        if (aliveRef.current) setIsLoadingMore(false);
      });
  };

  const handleDelete = async () => {
    if (deleteTarget === null || isDeleting) return;
    setIsDeleting(true);

    try {
      await deleteCommunityPost(deleteTarget.postId);
      if (!aliveRef.current) return;
      const deletedId = deleteTarget.postId;
      setPosts((prev) => prev.filter((post) => post.postId !== deletedId));
      setDeleteTarget(null);
      setNotice('게시글을 삭제했어요.');
    } catch (cause: unknown) {
      if (!aliveRef.current) return;
      setDeleteTarget(null);
      setNotice(toCommunityMessage(cause, '게시글을 삭제하지 못했어요. 잠시 후 다시 시도해 주세요.'));
    } finally {
      if (aliveRef.current) setIsDeleting(false);
    }
  };

  return (
    <div className="my-community-posts-page">
      <header className="my-community-posts-page__topbar">
        <button
          className="my-community-posts-page__back"
          type="button"
          onClick={() => navigate(-1)}
          aria-label="뒤로 가기"
        >
          <span className="my-community-posts-page__back-icon" aria-hidden="true" />
        </button>
        <h1 className="my-community-posts-page__topbar-title">내 게시글</h1>
        <span className="my-community-posts-page__topbar-spacer" aria-hidden="true" />
      </header>

      {isLoading && <p className="my-community-posts-page__status">게시글을 불러오는 중입니다.</p>}

      {loadFailed && (
        <p className="my-community-posts-page__status my-community-posts-page__status--error">
          게시글을 불러오지 못했습니다. 잠시 후 다시 시도해주세요.
        </p>
      )}

      {!isLoading && !loadFailed && posts.length === 0 && (
        <p className="my-community-posts-page__status">아직 쓴 게시글이 없어요.</p>
      )}

      {posts.length > 0 && (
        <ul className="my-community-posts-page__list">
          {posts.map((post) => (
            <CommunityPostCard
              key={post.postId}
              post={post}
              blinded={post.blinded}
              action={
                post.blinded ? (
                  <button
                    className="my-community-posts-page__delete"
                    type="button"
                    onClick={() => setDeleteTarget(post)}
                  >
                    삭제
                  </button>
                ) : undefined
              }
            />
          ))}
        </ul>
      )}

      {hasNext && (
        <button
          className="my-community-posts-page__more"
          type="button"
          onClick={loadMore}
          disabled={isLoadingMore}
        >
          {isLoadingMore ? '불러오는 중...' : '게시글 더 보기'}
        </button>
      )}

      {deleteTarget !== null && (
        <ConfirmSheet
          title="게시글을 삭제하시겠어요?"
          description={['신고로 숨겨진 게시글이에요', '삭제하면 되돌릴 수 없어요']}
          confirmLabel="삭제"
          pendingLabel="삭제 중"
          isPending={isDeleting}
          onConfirm={() => void handleDelete()}
          onClose={() => {
            if (isDeleting) return;
            setDeleteTarget(null);
          }}
        />
      )}

      {notice !== null && <NoticeSheet message={notice} onClose={() => setNotice(null)} />}
    </div>
  );
}
