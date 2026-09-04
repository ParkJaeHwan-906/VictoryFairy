/**
 * 공지사항 글.
 *
 * **서버가 없다 — 프론트에 붙박이로 둔다**(문의하기 FAQ 와 같은 방식). 공지는 배포와 함께
 * 나가는 글이고 바뀌는 주기도 배포보다 느려서, 글을 위해 API 를 두지 않기로 했다.
 * 새 공지는 이 배열 **맨 앞에** 얹는다 — 화면은 배열 순서를 그대로 그린다.
 *
 * 공지를 주는 API 가 생기면 `NOTICES` 를 조회 결과로, `findNotice` 를 단건 조회로
 * 갈아 끼우면 된다. 화면 둘은 이 모듈만 보고 있으므로 그 밖은 손댈 것이 없다.
 */

/**
 * 본문 한 덩어리.
 *
 * 디자인의 본문은 문단 사이가 한 줄씩 비어 있다(node 1430:17961). 그 빈 줄을 빈 문단으로
 * 적으면 화면 낭독기가 아무것도 없는 줄을 읽으므로, **덩어리로 나누고 간격은 CSS 가 준다**.
 *
 * 목록은 앞에 붙는 제목("<주요 업데이트>")과 한 덩어리다 — 디자인에서 그 둘 사이만
 * 빈 줄이 없기 때문이다.
 */
export type NoticeBlock =
  | { type: 'paragraph'; text: string }
  | { type: 'list'; title?: string; items: readonly string[] };

export interface Notice {
  /**
   * 주소(`/notice/:noticeId`)에 실리는 식별자.
   * 순번이 아니라 뜻이 담긴 문자열로 둔다 — 글 순서가 바뀌어도 링크가 흔들리지 않는다.
   */
  id: string;
  title: string;
  /** 게시일. `YYYY-MM-DD` 로 적고 화면에서 `2026.09.04` 꼴로 옮긴다. */
  publishedAt: string;
  body: readonly NoticeBlock[];
}

export const NOTICES: readonly Notice[] = [
  {
    id: 'service-launch',
    title: '[공지] 승리요정 서비스 출시 안내',
    publishedAt: '2026-09-04',
    body: [
      { type: 'paragraph', text: '안녕하세요, 승리요정입니다.' },
      {
        type: 'paragraph',
        text: '승리요정이 정식으로 문을 열었어요. 이제 앱에서 야구 보는 시간을 더 재미있게 보내실 수 있어요.',
      },
      {
        type: 'list',
        title: '<이런 걸 할 수 있어요>',
        items: [
          '경기가 진행되는 동안 이닝별 퀴즈에 참여할 수 있어요.',
          '퀴즈로 모은 포인트로 내 캐릭터를 꾸밀 수 있어요.',
          '라운지에서 다른 야구팬들과 이야기를 나눌 수 있어요.',
        ],
      },
      { type: 'paragraph', text: '더 즐겁게 야구를 즐길 수 있도록 계속해서 개선해 나갈게요.' },
      { type: 'paragraph', text: '감사합니다.' },
    ],
  },
];

/** 주소에서 받은 식별자로 글 한 건을 찾는다. 없는 주소로 들어올 수 있어 `undefined` 를 낸다. */
export function findNotice(id: string): Notice | undefined {
  return NOTICES.find((notice) => notice.id === id);
}

/** `2026-09-04` → `2026.09.04`. 디자인의 날짜 표기다. */
export function formatNoticeDate(publishedAt: string): string {
  return publishedAt.replaceAll('-', '.');
}

/** 목록에서 `N` 배지를 달아 두는 기간. */
const NEW_NOTICE_DAYS = 7;

/**
 * 갓 올라온 글인가 — 목록의 `N` 배지(디자인 node 1430:17862).
 *
 * "읽지 않음"이 아니라 **"새 글"** 로 본다. 읽음 여부를 쓰려면 기기마다 상태를 남겨야 하는데,
 * 공지가 배포 주기로만 늘어나는 지금은 날짜만으로 충분하고 관리할 것도 없다.
 *
 * `new Date('2026-09-04')` 는 UTC 자정으로 읽혀 한국 시간과 아홉 시간 어긋난다 —
 * 날짜만 비교하는 자리라 그 차이가 하루를 통째로 옮길 수 있어 직접 지역 날짜로 만든다.
 */
export function isNewNotice(publishedAt: string, now: Date = new Date()): boolean {
  const [year, month, day] = publishedAt.split('-').map(Number);
  const published = new Date(year, month - 1, day);
  const elapsedDays = (now.getTime() - published.getTime()) / 86_400_000;

  return elapsedDays < NEW_NOTICE_DAYS;
}
