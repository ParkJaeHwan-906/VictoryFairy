/**
 * 라운지 채팅 더미 데이터.
 * Figma: SWM / [Lounge] 라운지-챗 (시안) (node 744:21903)
 *
 * API 연결 전까지만 쓰는 자리표시 데이터다. 서버가 붙으면 `chatApi` 의
 * 히스토리 조회 + SSE 구독 결과를 `LoungeChatMessage[]` 로 옮겨 넣으면 된다.
 */
import type { LoungeChatMessage } from '../types/community';

/** 시간 부분만 필요하므로 날짜는 아무 날이나 하나로 맞춰 둔다. */
const DUMMY_DATE = '2026-08-03';

export const LOUNGE_CHAT_MESSAGES: LoungeChatMessage[] = [
  {
    id: 1,
    senderNickname: '쓱처돌이',
    content: '최정 오늘 미쳤다 💥💥',
    createdAt: `${DUMMY_DATE}T19:32:00`,
    isMine: false,
  },
  {
    id: 2,
    senderNickname: '제발',
    content: '오늘 경기 좋은데요?',
    createdAt: `${DUMMY_DATE}T19:34:00`,
    isMine: false,
  },
  {
    id: 3,
    senderNickname: '쥐잡는막대기',
    content: '볼넷 13개!! 니들이 사람이냐??',
    createdAt: `${DUMMY_DATE}T19:35:00`,
    isMine: false,
  },
  {
    id: 4,
    senderNickname: '공을쳐제발',
    content: '엘지는 안타 몰아친 날 다음날엔 꼭 빈타에 허덕이던데..',
    createdAt: `${DUMMY_DATE}T19:37:00`,
    isMine: false,
  },
  {
    id: 5,
    senderNickname: '나',
    content: '오늘 경기 볼만하네요!',
    createdAt: `${DUMMY_DATE}T19:39:00`,
    isMine: true,
  },
  {
    id: 6,
    senderNickname: '가나다라마',
    content:
      '엘쥐펜들이여 걱정하지 말지어다 우짜피 한화패 왜냐 우리에게는 김깅문이 있어요 내부의 큰적 감독있으니까유',
    createdAt: `${DUMMY_DATE}T19:40:00`,
    isMine: false,
  },
];
