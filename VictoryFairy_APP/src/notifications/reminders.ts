import * as Notifications from 'expo-notifications';
import { Platform } from 'react-native';

import type { UpcomingGame } from './bridge';

/**
 * 응원 구단 경기 시작 30분 전 알림.
 *
 * 서버에서 밀어주는 원격 푸시가 아니라 **기기에 예약해 두는 로컬 알림**이다.
 * 경기 일정은 공개 API로 미리 알 수 있어 서버가 개입할 이유가 없고, 그래서
 * 백엔드에 푸시 토큰 저장·발송 경로를 만들지 않고도 동작한다. 원격 푸시가 필요해지는
 * 시점(경기 중 속보 등)에 `expo-notifications`의 토큰 등록을 여기에 덧붙이면 된다.
 */

/** 경기 시작 몇 분 전에 알릴지. 기획이 정해지면 이 값만 바꾼다. */
const REMINDER_LEAD_MS = 30 * 60 * 1000;

/** 안드로이드 알림 채널. 사용자가 시스템 설정에서 이 채널만 따로 끌 수 있다. */
const CHANNEL_ID = 'game-reminder';

/**
 * 우리가 예약한 알림임을 표시하는 꼬리표.
 *
 * 예약 목록에는 다른 곳에서 만든 알림도 섞일 수 있으므로, 취소할 때 이 표식이 있는
 * 것만 건드린다.
 */
const REMINDER_KIND = 'game-reminder';

/**
 * 한 번에 예약해 두는 최대 건수.
 *
 * iOS는 앱당 대기 중인 로컬 알림을 64개까지만 유지하고 그 뒤는 조용히 버린다.
 * 한 구단은 하루 한 경기라 조회 범위(7일)를 다 채워도 7건이지만, 더블헤더나
 * 조회 범위 확장에 대비해 상한을 둔다 — 넘치면 가까운 경기부터 남긴다.
 */
const MAX_SCHEDULED = 32;

/** 알림에 실어 두는 값. 다음 동기화 때 "이미 맞게 예약돼 있는지" 판단에 쓴다. */
interface ReminderData {
  kind: typeof REMINDER_KIND;
  gameId: string;
  /** 예약된 발송 시각(epoch ms). 경기 시각이 바뀌면 이 값이 달라진다. */
  fireAt: number;
}

/**
 * 앱이 떠 있는 동안 도착한 알림도 배너로 보여준다.
 *
 * 컴포넌트 밖(모듈 최상위)에서 한 번만 등록해야 한다 — 알림은 리액트 트리보다
 * 먼저 도착할 수 있다.
 */
Notifications.setNotificationHandler({
  handleNotification: async () => ({
    shouldShowBanner: true,
    shouldShowList: true,
    // 경기 시작 안내라 소리까지 낼 만큼 급하지 않다. 배지도 웹이 관리하지 않으므로 쓰지 않는다.
    shouldPlaySound: false,
    shouldSetBadge: false,
  }),
});

/**
 * 안드로이드 알림 채널 등록.
 *
 * 채널이 없으면 알림이 기본 채널로 묶여 사용자가 종류별로 끌 수 없다.
 * 같은 id로 다시 불러도 안전하고(이미 있으면 갱신), 권한을 묻기 전에 있어야 한다.
 */
export async function ensureNotificationChannelAsync(): Promise<void> {
  if (Platform.OS !== 'android') {
    return;
  }

  await Notifications.setNotificationChannelAsync(CHANNEL_ID, {
    name: '경기 알림',
    description: '응원 구단 경기 시작 30분 전 알림',
    importance: Notifications.AndroidImportance.DEFAULT,
  });
}

/** 지금 알림을 보낼 수 있는지. */
export async function hasNotificationPermissionAsync(): Promise<boolean> {
  const { granted } = await Notifications.getPermissionsAsync();
  return granted;
}

/**
 * 아직 한 번도 묻지 않은 상태인지.
 *
 * 사용자가 이미 거절했다면 시스템 대화상자는 다시 뜨지 않는다(설정에서만 켤 수 있다).
 * 그래서 안내 시트를 띄울 가치가 있는 건 이 상태뿐이다.
 */
export async function canAskNotificationPermissionAsync(): Promise<boolean> {
  const { granted, canAskAgain } = await Notifications.getPermissionsAsync();
  return !granted && canAskAgain;
}

/**
 * 시스템 권한 대화상자를 띄운다.
 *
 * 안드로이드 13 미만에는 알림 권한 개념이 없어 즉시 허용으로 돌아온다.
 */
export async function requestNotificationPermissionAsync(): Promise<boolean> {
  await ensureNotificationChannelAsync();

  const { granted } = await Notifications.requestPermissionsAsync({
    ios: { allowAlert: true, allowSound: false, allowBadge: false },
  });
  return granted;
}

/**
 * 예약 상태를 `games`에 맞춘다.
 *
 * 매번 전부 지우고 다시 넣지 않는다 — 그러면 동기화가 돌 때마다 예약이 잠깐 비고,
 * 마침 그 사이에 발송 시각이 지나면 알림이 사라진다. 대신 게임별로 대조해서
 * 없어졌거나 시각이 달라진 것만 취소하고 모자란 것만 새로 넣는다.
 *
 * 빈 배열을 넘기면 우리가 만든 예약이 전부 취소된다(로그아웃 · 구단 미선택).
 */
export async function syncGameRemindersAsync(games: UpcomingGame[]): Promise<void> {
  const desired = pickReminders(games);

  // 권한이 없으면 예약해 봐야 울리지 않는다. 남아 있던 예약만 정리하고 끝낸다.
  if (!(await hasNotificationPermissionAsync())) {
    desired.clear();
  }

  const scheduled = await Notifications.getAllScheduledNotificationsAsync();
  const alreadyScheduled = new Set<string>();

  for (const request of scheduled) {
    const data = request.content.data as Partial<ReminderData> | undefined;
    if (data?.kind !== REMINDER_KIND || typeof data.gameId !== 'string') {
      continue;
    }

    const wanted = desired.get(data.gameId);
    if (wanted && wanted.fireAt === data.fireAt) {
      alreadyScheduled.add(data.gameId);
      continue;
    }

    await Notifications.cancelScheduledNotificationAsync(request.identifier);
  }

  for (const [gameId, { game, fireAt }] of desired) {
    if (alreadyScheduled.has(gameId)) {
      continue;
    }

    await Notifications.scheduleNotificationAsync({
      content: {
        title: `${game.awayTeam} VS ${game.homeTeam}`,
        body: buildReminderBody(game),
        data: { kind: REMINDER_KIND, gameId, fireAt } satisfies ReminderData,
      },
      trigger: {
        type: Notifications.SchedulableTriggerInputTypes.DATE,
        date: fireAt,
        // 채널은 트리거에 지정한다. iOS에는 채널 개념이 없어 무시된다.
        channelId: CHANNEL_ID,
      },
    });
  }
}

/** 알릴 수 있는 경기만 골라 발송 시각을 붙인다. 가까운 경기 순으로 상한까지. */
function pickReminders(games: UpcomingGame[]): Map<string, { game: UpcomingGame; fireAt: number }> {
  const now = Date.now();

  const reminders = games
    .map((game) => ({ game, fireAt: parseSeoulDateTime(game.gameDate) - REMINDER_LEAD_MS }))
    // 시각을 못 읽었거나(NaN) 이미 30분 안쪽으로 들어온 경기는 알릴 시점이 지났다.
    .filter(({ fireAt }) => Number.isFinite(fireAt) && fireAt > now)
    .sort((a, b) => a.fireAt - b.fireAt)
    .slice(0, MAX_SCHEDULED);

  return new Map(reminders.map((reminder) => [reminder.game.gameId, reminder]));
}

/**
 * `2026-08-19T19:00:00`(오프셋 없는 KST)을 절대 시각으로 바꾼다.
 *
 * 오프셋을 붙이지 않으면 기기 시간대로 해석돼, 한국 밖에서는 몇 시간씩 어긋난 시각에
 * 알림이 뜬다. 형식이 어긋나면 `NaN`이고 호출부가 걸러낸다.
 */
function parseSeoulDateTime(gameDate: string): number {
  return Date.parse(`${gameDate.trim().replace(' ', 'T')}+09:00`);
}

/** 예) `19:00 잠실 · 30분 뒤에 시작해요` */
function buildReminderBody(game: UpcomingGame): string {
  // 오프셋이 없는 문자열이라 파싱하지 않고 잘라 쓴다(웹의 formatGameTime과 같은 방식).
  const startsAt = game.gameDate.slice(11, 16);
  const place = game.stadium ? `${startsAt} ${game.stadium}` : startsAt;
  return `${place} · 30분 뒤에 시작해요`;
}
