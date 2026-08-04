/**
 * 라운지 랭킹 더미 데이터.
 * Figma: SWM / [Lounge] 라운지 메인 (node 724:20029)
 *
 * API 연결 전까지만 쓰는 자리표시 데이터다. 서버가 붙으면 이 파일 대신
 * 랭킹 조회 응답을 CommunityPage 에 넘기면 된다(모양은 `RankingEntry` 그대로).
 */
import type { RankingEntry } from '../types/community';

/**
 * 1~10위. 앞 3명은 시상대에, 나머지는 아래 목록에 그린다.
 * 디자인에 4위 이하 닉네임·포인트가 모두 같은 값으로 잡혀 있어 그대로 옮겼다.
 */
export const COMMUNITY_RANKING: RankingEntry[] = [
  { id: 1, rank: 1, nickname: '김야구', point: 2000 },
  { id: 2, rank: 2, nickname: '김승리', point: 1500 },
  { id: 3, rank: 3, nickname: '김요정', point: 1200 },
  { id: 4, rank: 4, nickname: '김승리', point: 1500 },
  { id: 5, rank: 5, nickname: '김승리', point: 1500 },
  { id: 6, rank: 6, nickname: '김승리', point: 1500 },
  { id: 7, rank: 7, nickname: '김승리', point: 1500 },
  { id: 8, rank: 8, nickname: '김승리', point: 1500 },
  { id: 9, rank: 9, nickname: '김승리', point: 1500 },
  { id: 10, rank: 10, nickname: '김승리', point: 1500 },
];

/** 시상대에 오르는 상위 3명(1·2·3위). */
export const PODIUM_RANKING = COMMUNITY_RANKING.slice(0, 3);

/** 시상대 아래 목록에 늘어놓는 4위 이하. */
export const LIST_RANKING = COMMUNITY_RANKING.slice(3);

/** 목록 맨 아래에 따로 강조해 붙는 내 순위. */
export const MY_RANKING: RankingEntry = {
  id: 345,
  rank: 345,
  nickname: '김승요',
  point: 1500,
};
