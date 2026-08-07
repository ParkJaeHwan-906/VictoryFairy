/**
 * 응원(support) 도메인 타입.
 *
 * 계정·구단·선수를 잇는 **쓰기 전용** 도메인이다. 조회 엔드포인트가 따로 없고
 * 현재 응원 상태는 `GET /users/me`(`MyProfile.supportTeam`·`supportPlayers`)로 읽는다.
 *
 * 두 가지 계약이 이 도메인 전체를 지배한다.
 * - **구단이 선수보다 앞선다.** 응원 구단을 고르기 전에는 선수를 고를 수 없고,
 *   선수는 응원 구단 소속이어야 한다.
 * - **응답은 언제나 "반영 후 현재 상태 전체"** 다. 방금 바꾼 항목만 오는 게 아니라
 *   전체가 오므로 호출 뒤 재조회할 필요가 없다.
 */

import type { Player } from './player';

export interface SupportTeamRequest {
  teamId: number;
}

export interface SupportPlayersRequest {
  playerIds: number[];
}

export interface SupportTeamSelection {
  id: number;
  name: string;
}

export type SupportPlayer = Player;
