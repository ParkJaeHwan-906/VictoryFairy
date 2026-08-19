import { API_USER_BASE_URL } from '../config';

/**
 * WebView 안에서 알림 대상 경기를 긁어 네이티브로 넘기는 다리.
 *
 * ── 왜 네이티브가 직접 API를 부르지 않나 ──────────────────────────────
 * 응원 구단(`GET /users/me`)은 인증이 필요하고, 토큰은 웹의 localStorage에만 있다.
 * 토큰을 네이티브로 꺼내오면 (1) 만료 시 재발급 로직을 앱에도 한 벌 두어야 하고
 * (2) 웹이 로그아웃해도 앱이 든 사본은 남는다. 그래서 조회 자체를 WebView 안에서
 * 끝내고, 네이티브로는 **알림에 필요한 최소한의 경기 정보만** 건너오게 한다.
 * ──────────────────────────────────────────────────────────────────────
 *
 * 스크립트는 `injectJavaScript`로 필요할 때마다 실행한다(페이지 로드·앱 복귀 시점).
 * 여러 번 실행돼도 부작용이 없고, 실패하면 웹에 예외를 던지는 대신 조용히
 * `unavailable`을 보고한다 — 알림 때문에 웹 화면이 깨지면 안 된다.
 */

/** 알림 하나를 만들기 위해 필요한 경기 정보. */
export interface UpcomingGame {
  /** 네이버 스포츠 gameId. 더블헤더까지 구분되는 자연키라 알림 식별자로 그대로 쓴다. */
  gameId: string;
  homeTeam: string;
  awayTeam: string;
  /** 구장 미정이면 `null`이다. */
  stadium: string | null;
  /** `2026-08-19T19:00:00` — 오프셋 없는 KST LocalDateTime. */
  gameDate: string;
}

/**
 * 한 번의 조회 결과.
 *
 * `unavailable`을 따로 두는 게 핵심이다. 조회 실패를 "경기 없음"으로 뭉뚱그리면
 * 잠깐 네트워크가 끊긴 사이에 예약해 둔 알림이 전부 취소된다.
 */
export type ReminderSnapshot =
  /** 조회 성공. `games`가 비어 있으면 정말로 예정 경기가 없다는 뜻이다. */
  | { status: 'ok'; games: UpcomingGame[] }
  /** 로그인 상태가 아니다 — 알릴 대상이 없으므로 예약을 비운다. */
  | { status: 'signed-out' }
  /** 로그인했지만 응원 구단을 아직 고르지 않았다(온보딩 중). */
  | { status: 'no-team' }
  /** 조회 실패(오프라인 · 토큰 만료 · 서버 오류). 기존 예약을 그대로 둔다. */
  | { status: 'unavailable' };

/** 웹이 보낸 다른 메시지와 섞이지 않도록 붙이는 표식. */
const MESSAGE_SOURCE = 'victoryfairy-app/reminders';

/**
 * 며칠 앞까지 훑을지.
 *
 * 앱을 열 때마다 다시 훑으므로 길게 잡을 이유가 없고, 짧게 잡으면 한동안 앱을 열지
 * 않은 사용자가 알림을 못 받는다. 한 구단은 하루 한 경기라 7일이면 예약은 최대 7건이다.
 */
const HORIZON_DAYS = 7;

/**
 * WebView에 주입해 실행하는 스크립트.
 *
 * 웹 번들과 같은 문맥에서 돌지만 웹 코드를 건드리지는 않는다 — localStorage의 토큰을
 * 읽어 fetch만 한다. 앱 밖(브라우저)에서는 실행될 일이 없다.
 *
 * 앱 코드가 아니라 페이지에서 도는 코드라 ES5 문법으로 쓴다. Android WebView는
 * 기기에 따라 오래된 버전일 수 있고, 문법 오류는 조용히 스크립트 전체를 죽인다.
 * 마지막 `true;`는 iOS에서 반환값 경고가 뜨지 않게 하는 관용구다.
 */
export const COLLECT_REMINDERS_SCRIPT = `
(function () {
  var USER_API = ${JSON.stringify(API_USER_BASE_URL)};
  var SOURCE = ${JSON.stringify(MESSAGE_SOURCE)};
  var HORIZON_DAYS = ${HORIZON_DAYS};

  function post(payload) {
    if (!window.ReactNativeWebView) {
      return;
    }
    payload.source = SOURCE;
    window.ReactNativeWebView.postMessage(JSON.stringify(payload));
  }

  // zustand persist('victoryfairy.auth')가 { state: { accessToken, refreshToken } } 로 저장한다.
  function readAccessToken() {
    try {
      var raw = window.localStorage.getItem('victoryfairy.auth');
      if (!raw) {
        return null;
      }
      var parsed = JSON.parse(raw);
      return (parsed && parsed.state && parsed.state.accessToken) || null;
    } catch (error) {
      return null;
    }
  }

  // 서버의 "오늘"은 Asia/Seoul 기준이라 기기 시계로 날짜를 만들면 자정 근처에서 하루가 어긋난다.
  function seoulDates(days) {
    var seoul = new Intl.DateTimeFormat('en-CA', {
      timeZone: 'Asia/Seoul',
      year: 'numeric',
      month: '2-digit',
      day: '2-digit'
    });
    var today = seoul.format(new Date()).split('-');
    var year = Number(today[0]);
    var month = Number(today[1]);
    var day = Number(today[2]);
    var dates = [];
    for (var offset = 0; offset < days; offset++) {
      dates.push(new Date(Date.UTC(year, month - 1, day + offset)).toISOString().slice(0, 10));
    }
    return dates;
  }

  function getJson(path, headers) {
    return fetch(USER_API + path, { headers: headers || {} }).then(function (response) {
      if (!response.ok) {
        throw new Error('HTTP ' + response.status);
      }
      return response.json();
    });
  }

  var token = readAccessToken();
  if (!token) {
    post({ status: 'signed-out' });
    return;
  }

  getJson('/users/me', { Authorization: 'Bearer ' + token })
    .then(function (profile) {
      var team = profile && profile.data && profile.data.supportTeam;
      if (!team) {
        post({ status: 'no-team' });
        return;
      }

      // 날짜 하나가 실패하면 그 날 경기만 빠진다. 전체를 실패로 돌리면 나머지 날짜의
      // 예약까지 취소되므로 날짜 단위로 끊어 삼킨다.
      var requests = seoulDates(HORIZON_DAYS).map(function (date) {
        return getJson('/games?date=' + date).catch(function () {
          return null;
        });
      });

      return Promise.all(requests).then(function (responses) {
        var games = [];
        responses.forEach(function (response) {
          var list = (response && response.data) || [];
          list.forEach(function (game) {
            // 취소·종료·진행 중인 경기는 알릴 것이 없다.
            if (game.gameState !== 'SCHEDULED') {
              return;
            }
            if (game.homeTeamId !== team.id && game.awayTeamId !== team.id) {
              return;
            }
            games.push({
              gameId: game.gameId,
              homeTeam: game.homeTeam,
              awayTeam: game.awayTeam,
              stadium: game.stadium,
              gameDate: game.gameDate
            });
          });
        });
        post({ status: 'ok', games: games });
      });
    })
    .catch(function () {
      post({ status: 'unavailable' });
    });
})();
true;
`;

/**
 * `onMessage`로 올라온 문자열을 조회 결과로 해석한다.
 *
 * 웹이 나중에 다른 용도로 `postMessage`를 쓰더라도 여기서 걸러지도록,
 * 표식이 없거나 형태가 어긋나면 `null`을 돌려 무시하게 한다.
 */
export function parseReminderSnapshot(raw: string): ReminderSnapshot | null {
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return null;
  }

  if (typeof parsed !== 'object' || parsed === null) {
    return null;
  }

  const message = parsed as Record<string, unknown>;
  if (message.source !== MESSAGE_SOURCE) {
    return null;
  }

  switch (message.status) {
    case 'signed-out':
    case 'no-team':
    case 'unavailable':
      return { status: message.status };
    case 'ok':
      return { status: 'ok', games: Array.isArray(message.games) ? (message.games as UpcomingGame[]) : [] };
    default:
      return null;
  }
}
