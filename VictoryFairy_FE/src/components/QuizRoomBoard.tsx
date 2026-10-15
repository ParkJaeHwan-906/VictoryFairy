import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { QUIZ_ROOM_PAGE_SIZE, getPopularQuizRooms, getQuizRooms } from '../api';
import type { QuizRoomSummary } from '../api';
import { ROUTES } from '../routes';
import QuizRoomCard from './QuizRoomCard';
import '../styles/QuizRoomBoard.css';

/**
 * QuizRoomBoard — 라운지의 퀴즈방 탭.
 *
 * 주간 인기 TOP 5 · 전체 목록(최신순, 페이징) · 만들기 버튼으로 이뤄진다. 페이징과 늦은 응답
 * 거르기는 게시판 탭(`CommunityBoard`)과 같은 방식이다.
 *
 * ── 계약에서 온 규칙 ──────────────────────────────────────────────────
 * - 인기 목록은 **배열 순서가 곧 순위**다(순위 필드 없음). 주간 확정 때 순서가 고정되고
 *   `likeCount`·`solvingCount` 만 지금 값이라 숫자와 순서가 어긋나 보일 수 있다 —
 *   **다시 정렬하지 않는다.** 확정된 주가 없으면 `[]` 이고, 그때는 묶음째 숨긴다.
 * - 인기 방도 전체 목록에서 빠지지 않고 겹쳐 온다 — **중복 제거는 화면 몫**이다.
 * - 페이지 크기는 1~50(기본 20) — 넘기면 400 이라 상수(`QUIZ_ROOM_PAGE_SIZE`)로 고정한다.
 * ──────────────────────────────────────────────────────────────────────
 */
export default function QuizRoomBoard() {
  const [popular, setPopular] = useState<QuizRoomSummary[]>([]);
  const [rooms, setRooms] = useState<QuizRoomSummary[]>([]);
  const [page, setPage] = useState(0);
  const [hasNext, setHasNext] = useState(false);
  const [isLoading, setIsLoading] = useState(true);
  const [isLoadingMore, setIsLoadingMore] = useState(false);
  const [loadFailed, setLoadFailed] = useState(false);

  /**
   * 화면을 떠난 뒤 도착한 응답을 버린다. 필터가 없어 첫 요청은 한 번뿐이지만 "더 보기"는
   * 탭을 옮긴 뒤에도 도착할 수 있어 게시판과 같은 요청 번호 방식을 쓴다.
   */
  const requestIdRef = useRef(0);
  useEffect(() => {
    return () => {
      requestIdRef.current += 1;
    };
  }, []);

  /** 첫 페이지와 인기 묶음을 함께 받는다. */
  useEffect(() => {
    const requestId = ++requestIdRef.current;

    Promise.all([
      getQuizRooms({ page: 0, size: QUIZ_ROOM_PAGE_SIZE }),
      // 인기 묶음은 덤이다 — 실패해도 목록은 보여 준다.
      getPopularQuizRooms().catch(() => []),
    ])
      .then(([firstPage, popularList]) => {
        if (requestId !== requestIdRef.current) return;
        setRooms(firstPage.content);
        setPage(firstPage.page);
        setHasNext(firstPage.hasNext);
        setPopular(popularList);
      })
      .catch(() => {
        if (requestId !== requestIdRef.current) return;
        setLoadFailed(true);
      })
      .finally(() => {
        if (requestId === requestIdRef.current) setIsLoading(false);
      });
  }, []);

  const loadMore = useCallback(() => {
    if (isLoadingMore || !hasNext) return;

    const requestId = requestIdRef.current;
    setIsLoadingMore(true);

    getQuizRooms({ page: page + 1, size: QUIZ_ROOM_PAGE_SIZE })
      .then((next) => {
        if (requestId !== requestIdRef.current) return;
        /*
         * 페이지 사이에 새 방이 생기면 앞 페이지의 마지막 방이 다음 페이지 첫머리로
         * 밀려 내려와 두 번 받게 된다 — id 로 한 번 더 거른다.
         */
        setRooms((prev) => {
          const seen = new Set(prev.map((room) => room.roomId));
          return [...prev, ...next.content.filter((room) => !seen.has(room.roomId))];
        });
        setPage(next.page);
        setHasNext(next.hasNext);
      })
      .catch(() => undefined)
      .finally(() => {
        if (requestId === requestIdRef.current) setIsLoadingMore(false);
      });
  }, [hasNext, isLoadingMore, page]);

  /** 인기 묶음에 이미 나온 방은 목록에서 뺀다(머리말 참고). */
  const visibleRooms = useMemo(() => {
    if (popular.length === 0) return rooms;
    const popularIds = new Set(popular.map((room) => room.roomId));
    return rooms.filter((room) => !popularIds.has(room.roomId));
  }, [popular, rooms]);

  /*
   * 빈 판정은 인기 묶음까지 함께 본다 — 방이 몇 개뿐이면 전부 인기 묶음으로 올라가
   * 목록이 비는데, 그때 "아직 퀴즈방이 없어요"라고 하면 틀린 말이 된다.
   */
  const isEmpty = !isLoading && !loadFailed && rooms.length === 0 && popular.length === 0;

  return (
    <section className="quiz-room-board" aria-label="퀴즈방">
      {!isLoading && popular.length > 0 && (
        <section className="quiz-room-board__popular" aria-labelledby="quiz-room-board-popular-title">
          <h2 className="quiz-room-board__section-title" id="quiz-room-board-popular-title">
            이번 주 인기 퀴즈방
          </h2>
          {/* 순위는 응답 배열 순서 그대로다 — 정렬하지 않는다(머리말 참고). */}
          <ol className="quiz-room-board__list quiz-room-board__list--popular">
            {popular.map((room, index) => (
              <QuizRoomCard key={room.roomId} room={room} rank={index + 1} />
            ))}
          </ol>
        </section>
      )}

      {isLoading && <p className="quiz-room-board__status">퀴즈방을 불러오는 중입니다.</p>}

      {loadFailed && (
        <p className="quiz-room-board__status quiz-room-board__status--error">
          퀴즈방을 불러오지 못했습니다. 잠시 후 다시 시도해주세요.
        </p>
      )}

      {isEmpty && (
        <p className="quiz-room-board__status">
          아직 퀴즈방이 없어요.
          <br />첫 퀴즈방을 만들어 보세요!
        </p>
      )}

      {!isLoading && visibleRooms.length > 0 && (
        <section className="quiz-room-board__all" aria-labelledby="quiz-room-board-all-title">
          {/* 인기 묶음이 없으면 제목 없이도 무엇의 목록인지 분명하다 — 그때는 화면에서만 감춘다. */}
          <h2
            className={`quiz-room-board__section-title${
              popular.length === 0 ? ' quiz-room-board__section-title--hidden' : ''
            }`}
            id="quiz-room-board-all-title"
          >
            전체 퀴즈방
          </h2>
          <ul className="quiz-room-board__list">
            {visibleRooms.map((room) => (
              <QuizRoomCard key={room.roomId} room={room} />
            ))}
          </ul>
        </section>
      )}

      {!isLoading && hasNext && (
        <button
          className="quiz-room-board__more"
          type="button"
          onClick={loadMore}
          disabled={isLoadingMore}
        >
          {isLoadingMore ? '불러오는 중...' : '퀴즈방 더 보기'}
        </button>
      )}

      <Link className="quiz-room-board__write-fab" to={ROUTES.quizRoomWrite} aria-label="퀴즈방 만들기">
        <span className="quiz-room-board__write-fab-icon" aria-hidden="true" />
      </Link>
    </section>
  );
}
