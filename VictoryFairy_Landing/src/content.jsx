/* ═══════════════════════════════════════════════════════════════════
   페이지의 모든 데이터 — 문구·수치·링크는 여기서만 고친다.
   컴포넌트(src/components/)는 이 값을 그리기만 한다.
   ═══════════════════════════════════════════════════════════════ */

/** 스토어 링크. TODO: 스토어 등록 후 실제 URL 로 교체 */
export const STORE_LINKS = {
  appStore: '#',
  googlePlay: '#',
};

/** 출시 문구 — 히어로와 최종 CTA 두 곳에 같이 쓰인다 */
export const RELEASE_NOTE = '2026년 9월 가을야구 시즌 출시 예정';

export const NAV_LINKS = [
  { href: '#quiz', label: '매일 퀴즈' },
  { href: '#bq', label: 'BQ 레이팅' },
  { href: '#lounge', label: '구단 라운지' },
  { href: '#character', label: '내 승요' },
];

/* ── 문제 제기 섹션 ─────────────────────────────────────────────── */
export const PROBLEM_BARS = [
  { name: '모바일로 리그 정보 탐색', value: 84.3 },
  { name: '뉴스 기사 열람', value: 72.0 },
  { name: '동영상 플랫폼 시청', value: 65.3 },
  { name: '하이라이트 다시 보기', value: 62.0 },
];

/* ── 퀴즈 카드 ───────────────────────────────────────────────────
   예측형 문제는 앱과 같은 규칙을 따른다 — 승패·득점·점수차처럼
   경기 기록으로 결과를 확정할 수 있는 항목만 낸다. */
export const QUIZZES = [
  {
    date: '2026년 7월 23일 (목)', match: 'NC 다이노스 VS LG 트윈스', inning: '1회 초',
    q: '김도영 선수가 첫 타석에서 안타를 기록할 수 있을까요?',
  },
  {
    date: '2026년 7월 23일 (목)', match: 'NC 다이노스 VS LG 트윈스', inning: '3회 말',
    q: '오늘 경기 최종 점수차가 3점 이상 벌어질까요?',
  },
  {
    date: '2026년 7월 23일 (목)', match: 'NC 다이노스 VS LG 트윈스', inning: '5회 초',
    q: 'NC 다이노스가 오늘 경기에서 승리할까요?',
  },
];

/* ── 라운지 채팅(연출용 예시) ──────────────────────────────────────
   화면에 들어오면 위에서부터 한 줄씩 올라온다. 길어지면 오래된 말풍선이
   위로 밀려 나가므로, 실제 라운지처럼 흐르는 대화로 읽힌다. */
export const CHAT = [
  { text: '오늘 선발 누구예요?', name: '문학맥주한잔', me: false },
  { text: '우리 에이스요 ㅋㅋ 오늘은 믿어봅니다', name: '재밥', me: false },
  { text: '1회부터 볼이 높은데요…', name: '취잡는막대기', me: false },
  { text: '최정 오늘 미쳤다 🔥🔥', name: '재밥', me: false },
  { text: '방금 그거 넘어간 거 맞죠?? 판독 가야죠', name: '공룡처제발', me: false },
  { text: '역전 투런!! 소리질러 📣📣', name: '문학맥주한잔', me: false },
  { text: '오늘 경기 좋은데요?', name: '취잡는막대기', me: false },
  { text: '투구수 벌써 92개인데 불펜 안 푸나요', name: '가나다라마', me: false },
  { text: '볼넷 13개! 니들이 사람이냐??', name: '공룡처제발', me: false },
  { text: '1, 3루에서 병살은 진짜 아프다…', name: '재밥', me: false },
  { text: '시프트 걸어놨더니 딱 그 자리로 가네 ㅋㅋㅋ', name: '문학맥주한잔', me: false },
  { text: '엘지는 안타 몰아친 날 다음날엔 꼭 빈타에 허덕이던데..', name: '가나다라마', me: false },
  { text: '9회만 잘 막으면 위닝시리즈입니다 제발', name: '취잡는막대기', me: false },
  { text: '오늘 경기 물잔하네요!', name: '', me: true },
];

/* ── 캐릭터 착장 ───────────────────────────────────────────────
   Figma 컴포넌트가 160×200 같은 좌표에 겹치는 레이어 구조라,
   유니폼·모자 파일만 바꿔 끼우면 그대로 갈아입는다.
   모자는 3종(red/blue/yellow) + 헬멧 3종뿐이라 구단 색에 가장
   가까운 것을 골라 매핑했다. */
export const TEAMS = [
  { id: 'kia',     name: 'KIA 타이거즈',  color: '#ea0029', cap: 'cap-red' },
  { id: 'samsung', name: '삼성 라이온즈',  color: '#074ca1', cap: 'cap-blue' },
  { id: 'lg',      name: 'LG 트윈스',     color: '#c30452', cap: 'cap-red' },
  { id: 'doosan',  name: '두산 베어스',    color: '#131230', cap: 'cap-blue' },
  { id: 'kt',      name: 'KT 위즈',       color: '#000000', cap: 'helmet-black' },
  { id: 'ssg',     name: 'SSG 랜더스',    color: '#ce0e2d', cap: 'cap-red' },
  { id: 'lotte',   name: '롯데 자이언츠',  color: '#041e42', cap: 'cap-blue' },
  { id: 'hanwha',  name: '한화 이글스',    color: '#fc4e00', cap: 'cap-red' },
  { id: 'nc',      name: 'NC 다이노스',    color: '#315288', cap: 'cap-blue' },
  { id: 'kiwoom',  name: '키움 히어로즈',  color: '#570514', cap: 'helmet-black' },
];

export const ITEMS = [
  { id: '',               label: '없음' },
  { id: 'item-balloon',   label: '응원봉' },
  { id: 'item-bat',       label: '배트' },
  { id: 'item-glove',     label: '글러브' },
  { id: 'item-megaphone', label: '확성기' },
  { id: 'item-ball',      label: '야구공' },
  { id: 'item-wand',      label: '요술봉' },
];

/* ── 앱 화면 미리보기 ───────────────────────────────────────────── */
export const SHOTS = [
  { src: 'login', alt: '로그인 화면', caption: '로그인' },
  { src: 'onboarding', alt: '응원 구단 선택 화면', caption: '구단 선택' },
  { src: 'quiz', alt: '경기 퀴즈 화면', caption: '퀴즈' },
  { src: 'lounge', alt: '라운지 채팅 화면', caption: '라운지' },
  { src: 'shop', alt: '캐릭터 꾸미기 화면', caption: '캐릭터 꾸미기' },
];
