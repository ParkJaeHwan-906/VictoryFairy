/**
 * 경기 화면의 날짜 계산.
 *
 * 기준 시각은 항상 `Asia/Seoul` 이다 — 서버가 판정하는 "오늘"이 그렇고(docs/game.md),
 * 기기 시계를 따라가면 자정 근처에서 화면이 서버와 다른 날짜를 가리킨다.
 *
 * `yyyy-MM-dd` 문자열을 그대로 다루고 `Date` 로 왕복하지 않는다.
 * `new Date('2026-08-01')` 은 UTC 자정으로 파싱돼 시간대에 따라 하루가 밀린다.
 */

const WEEKDAYS = ['일', '월', '화', '수', '목', '금', '토'];

const SEOUL_DATE = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Seoul',
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
});

/** 서울 기준 오늘(`yyyy-MM-dd`). `en-CA` 로케일이 곧 ISO 형식이다. */
export function getTodayInSeoul(): string {
  return SEOUL_DATE.format(new Date());
}

/** `yyyy-MM-dd` 에서 `days` 만큼 옮긴 날짜. UTC 로만 계산해 시간대 영향을 받지 않는다. */
export function shiftDate(date: string, days: number): string {
  const [year, month, day] = date.split('-').map(Number);
  const shifted = new Date(Date.UTC(year, month - 1, day + days));
  return shifted.toISOString().slice(0, 10);
}

/** 상단 바에 쓰는 표기. 예) `2026-07-23` → `2026.07.23(목)` */
export function formatDateLabel(date: string): string {
  const [year, month, day] = date.split('-').map(Number);
  const weekday = WEEKDAYS[new Date(Date.UTC(year, month - 1, day)).getUTCDay()];
  return `${date.replaceAll('-', '.')}(${weekday})`;
}

/**
 * 카드 머리말에 쓰는 표기. 예) `2026-07-23` → `2026년 7월 23일 (목)`
 *
 * `formatDateLabel`(`2026.07.23(목)`)과 같은 값을 다르게 읽는 두 표기다 —
 * 상단 바는 좁아서 점 표기를, 퀴즈 카드는 넉넉해서 풀어 쓴 표기를 쓴다.
 * 월·일에 0 을 붙이지 않는 것도 디자인 그대로다.
 */
export function formatKoreanDate(date: string): string {
  const [year, month, day] = date.split('-').map(Number);
  const weekday = WEEKDAYS[new Date(Date.UTC(year, month - 1, day)).getUTCDay()];
  return `${year}년 ${month}월 ${day}일 (${weekday})`;
}

/** `gameDate`(LocalDateTime 문자열)의 `HH:mm`. 오프셋이 없어 파싱하지 않고 잘라 쓴다. */
export function formatGameTime(gameDate: string): string {
  return gameDate.slice(11, 16);
}

/**
 * 요일 없이 날짜만 읽는 표기. 예) `2026-08-16` → `2026년 8월 16일`
 *
 * `formatKoreanDate`(`2026년 8월 16일 (토)`)와 갈라 쓴다 — 채팅 날짜 구분선은
 * 대화 사이에 가로선과 함께 끼는 한 줄이라 요일까지 넣으면 선이 그만큼 짧아진다.
 */
export function formatKoreanDay(date: string): string {
  const [year, month, day] = date.split('-').map(Number);
  return `${year}년 ${month}월 ${day}일`;
}
