import { useEffect, useState } from 'react';
import {
  applyGameUpdate,
  getGameList,
  getSupportGameList,
  subscribeGameList,
  subscribeSupportGameList,
} from '../api';
import type { Game, GameSubscription, GameSubscriptionHandlers } from '../api';

/** `all` = 날짜별 전체 경기(`/games`), `support` = 내 응원 구단 경기(`/games/support`). */
export type LiveGamesSource = 'all' | 'support';

interface LiveGamesState {
  games: Game[];
  isLoading: boolean;
  loadFailed: boolean;
}

/**
 * 경기 목록을 받아 두고, `live` 면 SSE 로 실시간 갱신까지 붙인다.
 *
 * ── GET 과 SSE 를 함께 쓴다 ──────────────────────────────────────────
 * 첫 화면은 GET 1회로 그린다(docs/game.md 가 정한 "최초 진입 1회 조회"이자 SSE 를 못 쓸 때의
 * 폴백). 스트림이 열리면 `snapshot` 이 목록을 통째로 덮고, 이후 `game-update` 가 경기 1건씩
 * 갈아 끼운다. GET 이 snapshot 보다 늦게 도착하면 더 오래된 값이므로 버린다.
 *
 * 재접속마다 서버가 snapshot 을 다시 보내므로, 끊긴 사이의 갱신을 따로 메울 필요가 없다.
 *
 * ── 실시간은 오늘만 ─────────────────────────────────────────────────
 * 갱신은 그 날짜 경기의 이닝·점수·상태가 바뀔 때만 온다. 지난 날짜·다가올 날짜는 바뀔 일이
 * 없어 연결만 잡아먹으므로 `live: false` 로 GET 만 한다(호출부가 정한다).
 *
 * 구독은 연결 시점의 날짜·응원 구단에 고정된다. 날짜가 바뀌면 이 훅이 끊고 새로 연다.
 */
export function useLiveGames(
  source: LiveGamesSource,
  date: string,
  live: boolean,
): LiveGamesState {
  const [games, setGames] = useState<Game[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [loadFailed, setLoadFailed] = useState(false);

  useEffect(() => {
    // 날짜를 연달아 넘길 때 이전 날짜의 응답·프레임이 화면을 덮지 않도록 막는다.
    let alive = true;
    /** snapshot 을 한 번이라도 받았는지 — 받았다면 GET 결과는 더 오래된 값이다. */
    let gotSnapshot = false;
    /** 화면에 그릴 목록이 있는지 — 스트림 오류를 실패 문구로 보일지 가른다. */
    let hasData = false;

    setGames([]);
    setIsLoading(true);
    setLoadFailed(false);

    // 날짜는 항상 명시해 보낸다 — 생략하면 자정 경계에서 서버의 "오늘"과 어긋난다.
    const fetchList = source === 'support' ? getSupportGameList : getGameList;
    fetchList(date)
      .then((list) => {
        if (!alive || gotSnapshot) return;
        hasData = true;
        setGames(list);
        setIsLoading(false);
        // 스트림 오류가 GET 보다 먼저 와 실패 문구가 떠 있었을 수 있다 — 목록이 왔으니 지운다.
        setLoadFailed(false);
      })
      .catch(() => {
        if (!alive || gotSnapshot) return;
        setLoadFailed(true);
        setIsLoading(false);
      });

    let subscription: GameSubscription | null = null;

    if (live) {
      const handlers: GameSubscriptionHandlers = {
        onSnapshot: (list) => {
          if (!alive) return;
          gotSnapshot = true;
          hasData = true;
          setGames(list);
          setIsLoading(false);
          setLoadFailed(false);
        },
        onUpdate: (event) => {
          if (alive) setGames((prev) => applyGameUpdate(prev, event));
        },
        onError: () => {
          // 이미 그린 목록이 있으면 실시간만 멈추고 화면은 그대로 둔다.
          if (!alive || hasData) return;
          setLoadFailed(true);
          setIsLoading(false);
        },
      };

      // 응원 구단이 없으면 빈 snapshot 뒤 서버가 닫는다 — 빈 목록이 이미 들어가 있어
      // 화면은 "응원 구단을 골라주세요" 안내로 자연히 떨어진다(구독은 스스로 멈춘다).
      subscription =
        source === 'support'
          ? subscribeSupportGameList(handlers, date)
          : subscribeGameList(handlers, date);
    }

    return () => {
      alive = false;
      subscription?.close();
    };
  }, [source, date, live]);

  return { games, isLoading, loadFailed };
}
