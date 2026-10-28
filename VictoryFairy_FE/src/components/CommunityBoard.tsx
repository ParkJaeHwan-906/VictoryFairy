import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  COMMUNITY_PAGE_SIZE,
  getCommunityCategories,
  getCommunityPosts,
  getPopularCommunityPosts,
} from '../api';
import type { CommunityCategory, CommunityPostSort, CommunityPostSummary } from '../api';
import { communityPostPath, ROUTES } from '../routes';
import CommunityPostCard from './CommunityPostCard';
import '../styles/CommunityBoard.css';

const SORT_OPTIONS: readonly { value: CommunityPostSort; label: string }[] = [
  { value: 'latest', label: '최신순' },
  { value: 'likes', label: '추천순' },
  { value: 'views', label: '조회순' },
];

/**
 * CommunityBoard — 라운지의 게시판 탭.
 *
 * 카테고리 칩 · 정렬 · 인기 게시글 · 전체 목록(페이징) · 글쓰기 버튼으로 이뤄진다.
 *
 * ── 계약에서 온 규칙 ──────────────────────────────────────────────────
 * - **카테고리 id 는 코드에 박지 않는다.** 환경마다 달라(로컬은 자유게시판이 16) 칩은
 *   언제나 `GET /community/categories` 응답으로만 만든다.
 * - `sort` 는 **소문자만** 받는다(`latest`·`likes`·`views`). 위 상수 그대로 보낸다.
 * - 인기 게시글은 전체 목록에서 빠지지 않고 겹쳐 온다 — **중복 제거는 화면 몫**이다.
 *   인기 묶음은 최신순일 때만 위에 띄우고, 그때 목록에서 같은 글을 뺀다. 추천순·조회순은
 *   목록 자체가 이미 "인기 순서"라 따로 띄우면 같은 글이 두 번 보이기만 한다.
 * - 페이지 크기는 1~50(기본 20) — 넘기면 400 이라 상수(`COMMUNITY_PAGE_SIZE`)로 고정한다.
 * ──────────────────────────────────────────────────────────────────────
 */
export default function CommunityBoard() {
  const [categories, setCategories] = useState<CommunityCategory[]>([]);
  /** `null` 이면 전체. 값은 반드시 카테고리 응답에서 온 id 다. */
  const [categoryId, setCategoryId] = useState<number | null>(null);
  const [sort, setSort] = useState<CommunityPostSort>('latest');

  const [popular, setPopular] = useState<CommunityPostSummary[]>([]);
  const [posts, setPosts] = useState<CommunityPostSummary[]>([]);
  const [page, setPage] = useState(0);
  const [hasNext, setHasNext] = useState(false);
  const [isLoading, setIsLoading] = useState(true);
  const [isLoadingMore, setIsLoadingMore] = useState(false);
  const [loadFailed, setLoadFailed] = useState(false);

  /**
   * 필터를 빠르게 바꾸면 앞선 요청이 뒤늦게 도착해 새 목록을 덮을 수 있다.
   * 요청마다 번호를 붙이고 가장 최근 번호의 응답만 받는다(화면을 떠난 뒤 도착한 응답도 같이 버린다).
   */
  const requestIdRef = useRef(0);
  useEffect(() => {
    return () => {
      requestIdRef.current += 1;
    };
  }, []);

  useEffect(() => {
    let alive = true;

    // 칩이 없어도 "전체"로 목록은 볼 수 있다 — 실패해도 화면 전체를 실패로 만들지 않는다.
    getCommunityCategories()
      .then((list) => {
        if (alive) setCategories(list);
      })
      .catch(() => undefined);

    return () => {
      alive = false;
    };
  }, []);

  const showPopular = sort === 'latest';

  /** 첫 페이지와 인기 묶음을 함께 받는다. 필터가 바뀔 때마다 처음부터 다시 받는다. */
  useEffect(() => {
    const requestId = ++requestIdRef.current;
    const filter = categoryId === null ? {} : { categoryId };

    setIsLoading(true);
    setLoadFailed(false);
    // 앞 필터의 "더 보기"가 아직 나가 있었다면 그 응답은 버려진다 — 잠금도 여기서 푼다.
    setIsLoadingMore(false);

    Promise.all([
      getCommunityPosts({ ...filter, sort, page: 0, size: COMMUNITY_PAGE_SIZE }),
      // 인기 묶음은 덤이다 — 실패해도 목록은 보여 준다.
      showPopular ? getPopularCommunityPosts(filter).catch(() => []) : Promise.resolve([]),
    ])
      .then(([firstPage, popularList]) => {
        if (requestId !== requestIdRef.current) return;
        setPosts(firstPage.content);
        setPage(firstPage.page);
        setHasNext(firstPage.hasNext);
        setPopular(popularList);
      })
      .catch(() => {
        if (requestId !== requestIdRef.current) return;
        setPosts([]);
        setPopular([]);
        setHasNext(false);
        setLoadFailed(true);
      })
      .finally(() => {
        if (requestId === requestIdRef.current) setIsLoading(false);
      });
  }, [categoryId, sort, showPopular]);

  const loadMore = useCallback(() => {
    if (isLoadingMore || !hasNext) return;

    const requestId = requestIdRef.current;
    setIsLoadingMore(true);

    getCommunityPosts({
      ...(categoryId === null ? {} : { categoryId }),
      sort,
      page: page + 1,
      size: COMMUNITY_PAGE_SIZE,
    })
      .then((next) => {
        if (requestId !== requestIdRef.current) return;
        /*
         * 페이지 사이에 새 글이 올라오면 앞 페이지의 마지막 글이 다음 페이지 첫머리로
         * 밀려 내려와 두 번 받게 된다 — id 로 한 번 더 거른다.
         */
        setPosts((prev) => {
          const seen = new Set(prev.map((post) => post.postId));
          return [...prev, ...next.content.filter((post) => !seen.has(post.postId))];
        });
        setPage(next.page);
        setHasNext(next.hasNext);
      })
      .catch(() => undefined)
      .finally(() => {
        if (requestId === requestIdRef.current) setIsLoadingMore(false);
      });
  }, [categoryId, hasNext, isLoadingMore, page, sort]);

  /** 인기 묶음에 이미 나온 글은 목록에서 뺀다(머리말 참고). */
  const visiblePosts = useMemo(() => {
    if (!showPopular || popular.length === 0) return posts;
    const popularIds = new Set(popular.map((post) => post.postId));
    return posts.filter((post) => !popularIds.has(post.postId));
  }, [popular, posts, showPopular]);

  const isEmpty = !isLoading && !loadFailed && posts.length === 0;

  return (
    <section className="community-board" aria-label="게시판">
      <div className="community-board__filters">
        <div className="community-board__chips" role="radiogroup" aria-label="게시판 카테고리">
          <button
            className={`community-board__chip${categoryId === null ? ' community-board__chip--active' : ''}`}
            type="button"
            role="radio"
            aria-checked={categoryId === null}
            onClick={() => setCategoryId(null)}
          >
            전체
          </button>
          {categories.map((category) => (
            <button
              key={category.id}
              className={`community-board__chip${
                categoryId === category.id ? ' community-board__chip--active' : ''
              }`}
              type="button"
              role="radio"
              aria-checked={categoryId === category.id}
              onClick={() => setCategoryId(category.id)}
            >
              {category.name}
            </button>
          ))}
        </div>

        <div className="community-board__sorts" role="radiogroup" aria-label="정렬">
          {SORT_OPTIONS.map((option) => (
            <button
              key={option.value}
              className={`community-board__sort${sort === option.value ? ' community-board__sort--active' : ''}`}
              type="button"
              role="radio"
              aria-checked={sort === option.value}
              onClick={() => setSort(option.value)}
            >
              {option.label}
            </button>
          ))}
        </div>
      </div>

      {showPopular && !isLoading && popular.length > 0 && (
        <section className="community-board__popular" aria-labelledby="community-board-popular-title">
          <h2 className="community-board__popular-title" id="community-board-popular-title">
            인기 게시글
          </h2>
          <ol className="community-board__popular-list">
            {popular.map((post, index) => (
              <li key={post.postId}>
                <Link className="community-board__popular-item" to={communityPostPath(post.postId)}>
                  <span className="community-board__popular-rank">{index + 1}</span>
                  <span className="community-board__popular-name">{post.title}</span>
                  {post.commentCount > 0 && (
                    <span className="community-board__popular-count">
                      <span className="community-board__sr-only">댓글</span>[{post.commentCount}]
                    </span>
                  )}
                </Link>
              </li>
            ))}
          </ol>
        </section>
      )}

      {isLoading && <p className="community-board__status">게시글을 불러오는 중입니다.</p>}

      {loadFailed && (
        <p className="community-board__status community-board__status--error">
          게시글을 불러오지 못했습니다. 잠시 후 다시 시도해주세요.
        </p>
      )}

      {isEmpty && (
        <p className="community-board__status">
          아직 게시글이 없어요.
          <br />첫 글을 남겨 보세요!
        </p>
      )}

      {!isLoading && visiblePosts.length > 0 && (
        <ul className="community-board__list">
          {visiblePosts.map((post) => (
            <CommunityPostCard key={post.postId} post={post} />
          ))}
        </ul>
      )}

      {!isLoading && hasNext && (
        <button
          className="community-board__more"
          type="button"
          onClick={loadMore}
          disabled={isLoadingMore}
        >
          {isLoadingMore ? '불러오는 중...' : '게시글 더 보기'}
        </button>
      )}

      <Link className="community-board__write-fab" to={ROUTES.communityWrite} aria-label="글쓰기">
        <span className="community-board__write-fab-icon" aria-hidden="true" />
      </Link>
    </section>
  );
}
