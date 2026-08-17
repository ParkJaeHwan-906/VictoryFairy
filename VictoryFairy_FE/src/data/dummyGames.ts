import type { Game, PositionName, TeamLineUp } from '../api';
import { getTodayInSeoul } from '../utils/date';

/**
 * ⚠️ UI 확인용 임시 더미 데이터. 백엔드가 뜨면 이 파일째 지운다.
 *
 * `GamePage` · `GameDetailSheet` 에서 `getGameList`/`getLineUp` 대신 부르고 있으며,
 * 되돌릴 지점은 두 파일의 `[DUMMY]` 주석에 표시해 뒀다.
 *
 * 디자인의 모든 상태를 볼 수 있게 값을 골랐다 — 날짜(과거·오늘·미래)로 인사말과
 * 경기 상태가 갈리고, 구장 미정(`stadium: null`)·라인업 미공시·2글자 포지션 원문·
 * 포지션 없음(`null`)이 각각 하나씩 들어 있다.
 */

interface DummyTeam {
  id: number;
  name: string;
}

const TEAMS = {
  LG: { id: 1, name: 'LG' },
  NC: { id: 2, name: 'NC' },
  SSG: { id: 3, name: 'SSG' },
  롯데: { id: 4, name: '롯데' },
  삼성: { id: 5, name: '삼성' },
  두산: { id: 6, name: '두산' },
  KIA: { id: 7, name: 'KIA' },
  한화: { id: 8, name: '한화' },
  KT: { id: 9, name: 'KT' },
  키움: { id: 10, name: '키움' },
} satisfies Record<string, DummyTeam>;

/** `gameId` 는 `${yyyyMMdd}DUMMY${순번}` 형식이라 여기서 날짜·순번을 되짚을 수 있다. */
function makeGame(
  index: number,
  date: string,
  stadium: string | null,
  away: DummyTeam,
  home: DummyTeam,
  gameState: string,
  scores: [number, number] | null = null,
  /** `CANCELED` 경기에만 넣는다 — 다른 상태는 실제 API 에서도 항상 `null` 이다. */
  cancelReason: string | null = null,
): Game {
  return {
    gameId: `${date.replaceAll('-', '')}DUMMY${index}`,
    stadium,
    awayTeam: away.name,
    awayTeamId: away.id,
    homeTeam: home.name,
    homeTeamId: home.id,
    awayTeamScore: scores?.[0] ?? null,
    homeTeamScore: scores?.[1] ?? null,
    gameDate: `${date}T18:30:00`,
    gameState,
    cancelReason,
    /*
     * 진행 중 경기여도 `null` 이다 — 실제 API 가 지금 그렇다(컬럼만 생겼고 값을 채우는
     * 수집기 구현이 아직 없다, docs/game.md). 더미가 값을 지어내면 "이닝을 못 받는" 지금의
     * 정상 경로를 화면에서 확인할 수 없게 되므로 실물과 같게 둔다.
     */
    inning: null,
    inningHalf: null,
  };
}

/** 날짜별 경기 구성. 라인업 대역도 이 목록을 되짚어 쓰므로 동기 함수로 둔다. */
function buildGameList(date: string): Game[] {
  const today = getTodayInSeoul();

  // 과거 — 끝났거나 취소된 경기만. 1번은 취소 사유가 칩에 실리는 경우다.
  if (date < today) {
    return [
      makeGame(1, date, '잠실', TEAMS.NC, TEAMS.LG, 'CANCELED', null, '폭염취소'),
      makeGame(2, date, '사직', TEAMS.SSG, TEAMS.롯데, 'FINISHED', [3, 7]),
      makeGame(3, date, '광주', TEAMS.한화, TEAMS.KIA, 'FINISHED', [5, 8]),
      makeGame(4, date, '수원', TEAMS.두산, TEAMS.KT, 'DRAW', [4, 4]),
    ];
  }

  // 미래 — 전부 예정
  if (date > today) {
    return [
      makeGame(1, date, '잠실', TEAMS.삼성, TEAMS.두산, 'SCHEDULED'),
      makeGame(2, date, '사직', TEAMS.KT, TEAMS.롯데, 'SCHEDULED'),
      makeGame(3, date, '인천', TEAMS.NC, TEAMS.SSG, 'SCHEDULED'),
      // 구장 미정 — stadium 이 null 로 오는 경우
      makeGame(4, date, null, TEAMS.키움, TEAMS.KIA, 'SCHEDULED'),
    ];
  }

  /*
   * 오늘 — 진행중·예정·종료·취소를 한 번에.
   * 4번은 사유 없는 취소(`cancelReason: null`)라 기본 문구 fallback 을 여기서 확인한다 —
   * 과거 날짜의 1번(사유 있음)과 같이 보면 두 갈래가 다 보인다.
   */
  return [
    makeGame(1, date, '잠실', TEAMS.NC, TEAMS.LG, 'IN_PROGRESS', [2, 1]),
    makeGame(2, date, '사직', TEAMS.SSG, TEAMS.롯데, 'SCHEDULED'),
    makeGame(3, date, '광주', TEAMS.한화, TEAMS.KIA, 'FINISHED', [8, 6]),
    makeGame(4, date, '수원', TEAMS.두산, TEAMS.KT, 'CANCELED'),
  ];
}

/** 응답이 즉시 오면 로딩 상태를 볼 수 없어 살짝 늦춘다. */
function delay<T>(value: T): Promise<T> {
  return new Promise((resolve) => setTimeout(() => resolve(value), 150));
}

/** `getGameList` 대역. */
export function getDummyGameList(date: string): Promise<Game[]> {
  return delay(buildGameList(date));
}

/** 타순 한 줄. `positionName` 이 `null` 일 수 있어 타입을 직접 적는다. */
type DummyBatter = { name: string; positionName: PositionName };

/** 원정 팀 타순. 마지막 한 명은 2글자 원문(대주자 → 좌익수)이다. */
const AWAY_BATTERS: DummyBatter[] = [
  { name: '김주원', positionName: 'SS' },
  { name: '고준휘', positionName: 'LF' },
  { name: '박민우', positionName: '2B' },
  { name: '블레인', positionName: '1B' },
  { name: '박건우', positionName: 'RF' },
  { name: '이우성', positionName: 'DH' },
  { name: '김휘집', positionName: '3B' },
  { name: '김형준', positionName: 'C' },
  { name: '천재환', positionName: '주좌' },
];

/** 홈 팀 타순. 마지막 한 명은 포지션 정보가 없는 경우다. */
const HOME_BATTERS: DummyBatter[] = [
  { name: '홍창기', positionName: 'RF' },
  { name: '박해민', positionName: 'CF' },
  { name: '오스틴', positionName: 'DH' },
  { name: '문정빈', positionName: '1B' },
  { name: '오지환', positionName: 'SS' },
  { name: '문보경', positionName: '3B' },
  { name: '문성주', positionName: 'LF' },
  { name: '박동원', positionName: 'C' },
  { name: '신민재', positionName: null },
];

function toLineUp(teamId: number, batters: DummyBatter[], pitcher: string): TeamLineUp {
  return {
    teamId,
    pitchers: [{ name: pitcher, positionName: 'P' }],
    batters: batters.map((batter, index) => ({ ...batter, batOrder: index + 1 })),
  };
}

/**
 * `getLineUp` 대역.
 *
 * 실제 API 처럼 `teamId` 오름차순으로 돌려준다 — 시트가 배열 순서가 아니라
 * `homeTeamId`/`awayTeamId` 로 홈·원정을 가르는지 여기서 같이 확인된다.
 * 2번 경기(사직)만 빈 배열이라 "라인업 미공시" 상태를 볼 수 있다.
 */
export function getDummyLineUp(gameId: string): Promise<TeamLineUp[]> {
  const match = /^(\d{4})(\d{2})(\d{2})DUMMY(\d+)$/.exec(gameId);
  if (!match) return delay([]);

  const [, year, month, day, index] = match;
  if (index === '2') return delay([]);

  const game = buildGameList(`${year}-${month}-${day}`).find((item) => item.gameId === gameId);
  if (!game) return delay([]);

  return delay(
    [
      toLineUp(game.awayTeamId, AWAY_BATTERS, '카스타노'),
      toLineUp(game.homeTeamId, HOME_BATTERS, '임찬규'),
    ].sort((a, b) => a.teamId - b.teamId),
  );
}
