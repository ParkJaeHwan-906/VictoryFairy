/**
 * 경기(game) 도메인 타입.
 *
 * 식별자 규칙(명세 기준):
 * - 구단·선수는 수집기 자연키를 감추지만, 경기는 `gameId`(네이버 스포츠 gameId)를 **그대로 노출**한다.
 *   더블헤더 구분 등에 클라이언트가 식별자로 써야 하기 때문이다.
 *   선발 라인업 조회의 쿼리 파라미터 `gameId` 도 내부 PK 가 아니라 이 값이다.
 * - 구단은 이름 문자열(`homeTeam`·`awayTeam`)과 PK(`homeTeamId`·`awayTeamId`)가 따로 온다.
 *   PK 는 `GET /teams` 의 `id`, 라인업의 `teamId` 와 같은 값 체계다.
 */

/**
 * 경기 상태. 명세가 정의한 값은 아래 5종이다.
 *
 * 다만 서버는 이 값을 문자열로 내려보내며 언제든 새 상태를 추가할 수 있으므로
 * `(string & {})` 를 union 에 더해 **닫지 않는다** — 알려진 5종은 자동완성·오타 검출이 되면서
 * 미지의 문자열이 와도 타입이 깨지지 않는다(런타임에서 잘라내거나 던지지 않는다).
 * 화면에서 분기할 때는 반드시 기본 분기(알 수 없는 상태)를 둬야 한다.
 */
export type GameState =
  | 'SCHEDULED'
  | 'IN_PROGRESS'
  | 'FINISHED'
  | 'DRAW'
  | 'CANCELED'
  | (string & {});

/** 경기 1건. `GET /games` 의 `data` 는 이 객체의 **배열**(경기 시각 오름차순, 페이징 없음)이다. */
export interface Game {
  gameId: string;
  stadium: string | null;
  homeTeam: string;
  homeTeamId: number;
  awayTeam: string;
  awayTeamId: number;
  homeTeamScore: number | null;
  awayTeamScore: number | null;
  gameDate: string;
  gameState: GameState;
  /**
   * 취소 사유(`폭염취소` 등). `gameState` 가 `CANCELED` 일 때만 채워지고 그 외에는 `null` 이다.
   *
   * **역은 성립하지 않는다** — `CANCELED` 인데 `null` 일 수 있다(수집기가 아직 못 채운 구간 등).
   * 그래서 표시할 때 기본 문구 fallback 이 필요하고, 그 fallback 은
   * **`CANCELED` 일 때만** 적용해야 한다(다른 상태에 적용하면 정상 경기에 취소 문구가 붙는다).
   * 두 규칙 모두 `getGameStateDisplay` 안에 있으니 화면에서 직접 읽지 말고 그 함수를 쓴다.
   *
   * 값의 종류는 닫힌 집합이 아니다(현재 관측된 건 `폭염취소` 하나뿐이지만 계속 늘어난다) —
   * 특정 문자열로 분기하지 말고 사유를 그대로 실어 보여준다
   * (표시할 때 끝의 `취소` 앞에 공백만 넣는다 — `getGameStateDisplay` 참고).
   */
  cancelReason: string | null;
}

/**
 * 포지션 표기. **약어 12종의 union 으로 좁히면 안 된다.**
 *
 * 명세가 정의한 약어는 12종(`P` `C` `1B` `2B` `3B` `SS` `LF` `CF` `RF` `DH` `PH` `PR`)이지만,
 * `Position` 은 코드 상수가 아니라 DB 테이블이고 수집기가 매핑에 없는 표기를 만나면
 * **원문을 그대로 적재**한다. 실측(2026-08-04 dev DB `positions` 70행) 기준 약어는 12종뿐이고
 * 나머지 58종(83%)은 한글·한자 원문 2글자(`주좌`·`二一`·`타지` 등)로 내려온다 —
 * 한 경기 안에서 수비 위치를 바꾼 선수의 표기라 드문 예외가 아니라 흔한 경우다.
 *
 * 그래서 여기서는 `string` 으로 열어 두고, 표시용 변환은 화면 계층에서 한다.
 * 변환에는 매핑에 없는 값을 위한 fallback(원문 그대로 노출 등)이 반드시 있어야 한다.
 * 포지션 정보 자체가 없으면 `null` 이다.
 */
export type PositionName = string | null;

/** 선발 투수 1명. 타자와 달리 타순(`batOrder`)이 없다 — 이게 투수/타자 구분 기준이다. */
export interface LineUpPitcher {
  name: string;
  positionName: PositionName;
}

/** 선발 타자 1명. */
export interface LineUpBatter {
  name: string;
  positionName: PositionName;
  batOrder: number;
}

/**
 * 팀 1개의 선발 라인업. `GET /games/lineup` 의 `data` 는 이 객체의 **배열**(`teamId` 오름차순)이다.
 *
 * **서버는 홈/원정을 판정하지 않는다.** 어느 쪽이 홈인지는 `Game` 의
 * `homeTeamId`/`awayTeamId` 와 `teamId` 를 대조해 호출자가 정해야 한다 —
 * 정렬이 `teamId` 오름차순이라 배열 순서는 홈/원정과 무관하다.
 *
 * `playerId` 는 응답에 노출되지 않는다(선수 식별은 `name` 뿐이다).
 */
export interface TeamLineUp {
  teamId: number;
  pitchers: LineUpPitcher[];
  batters: LineUpBatter[];
}
