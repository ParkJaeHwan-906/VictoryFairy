/**
 * 선수(player) 도메인 타입.
 *
 * 구단·경기와 같은 **공개 참조 데이터**다. 다만 이 도메인만 검색(이름 부분 일치)을 갖는다.
 *
 * 식별자 규칙(명세 기준):
 * - 소속 구단은 중첩 객체(`team: {...}`)가 아니라 `teamId`·`teamName` 두 필드로 **평평하게** 온다.
 * - `teamId` 는 `GET /teams` 의 `id`, 경기·라인업의 `teamId` 와 같은 값 체계다.
 *   **1~10 이 아니다** — 현재 16~25 범위(KIA 21)이므로 화면에 하드코딩하지 말고 구단 목록에서 받아 쓴다.
 */

/**
 * 선수 포지션. 명세가 정의한 값은 아래 4종이다.
 *
 * `GameState` 와 같은 이유로 union 을 **닫지 않는다** — 서버가 문자열로 내려보내고 언제든
 * 값을 추가할 수 있으므로, 알려진 4종은 자동완성·오타 검출이 되면서 미지의 문자열이 와도
 * 타입이 깨지지 않게 둔다. 화면에서 분기할 때는 반드시 기본 분기를 둬야 한다.
 *
 * 경기 라인업의 `PositionName`(`P`·`1B` 같은 수비 위치 약어, 자유 문자열)과는 **다른 축이다.**
 * 이쪽은 선수의 대분류라 한쪽 계약이 바뀌어도 다른 쪽을 따라 바꾸지 않는다.
 */
export type PlayerPosition =
  | 'PITCHER'
  | 'CATCHER'
  | 'INFIELDER'
  | 'OUTFIELDER'
  | (string & {});

/**
 * 선수 1명. `GET /players` 의 `data` 는 이 객체의 **배열**(`playerName` 오름차순, 페이징 없음)이다.
 *
 * 항목은 언제나 여섯 키를 **모두** 갖는다. 값이 없을 때 서버가 키를 생략하거나
 * `''`·`'UNKNOWN'` 같은 대체값으로 채우지 않고 `null` 을 그대로 내려보낸다.
 */
export interface Player {
  teamId: number;
  teamName: string;
  playerId: number;
  playerName: string;
  playerNumber: string | null;
  playerPosition: PlayerPosition | null;
}

/**
 * `GET /players` 쿼리 파라미터. 둘 다 선택이고, 함께 주면 AND 로 결합된다.
 *
 * 빈 값·공백만 있는 값은 서버가 미전달과 동일하게 처리하므로 이 계층에서 미리 걸러 보내지 않는다
 * (`getPlayerList` 참고).
 */
export interface PlayerListParams {
  teamId?: number;
  name?: string;
}
