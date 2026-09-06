import AsyncStorage from '@react-native-async-storage/async-storage';

/**
 * 알림 권한 안내 시트를 언제 다시 띄울지 기억하는 곳.
 *
 * 시스템 권한 대화상자는 한 번 거절당하면 다시 뜨지 않는다. 그래서 그 앞에 안내
 * 시트를 두어 "왜 필요한지"를 먼저 말하는데, 이 시트마저 열 때마다 뜨면 그게 더
 * 성가시다. 사용자가 미룬 시점을 남겨 한동안 묻지 않는다.
 */

const DISMISSED_AT_KEY = 'victoryfairy.reminderPrompt.dismissedAt';

/**
 * 미룬 뒤 다시 물어보기까지의 간격.
 *
 * 2주면 응원 구단 경기가 열 번 넘게 지나간다 — 알림이 있었으면 좋았을 상황을
 * 충분히 겪은 뒤라 다시 물을 만하다.
 */
const REPROMPT_INTERVAL_MS = 14 * 24 * 60 * 60 * 1000;

/** 저장소를 읽지 못하면(드문 경우) 묻지 않는 쪽으로 기운다 — 잘못 띄우는 편이 더 나쁘다. */
export async function isPromptSnoozedAsync(): Promise<boolean> {
  try {
    const dismissedAt = await AsyncStorage.getItem(DISMISSED_AT_KEY);
    if (!dismissedAt) {
      return false;
    }
    return Date.now() - Number(dismissedAt) < REPROMPT_INTERVAL_MS;
  } catch {
    return true;
  }
}

/** "다음에 할게요"를 눌렀을 때. 저장에 실패해도 이번 실행에서는 시트를 닫는다. */
export async function snoozePromptAsync(): Promise<void> {
  try {
    await AsyncStorage.setItem(DISMISSED_AT_KEY, String(Date.now()));
  } catch {
    // 저장에 실패하면 다음 실행에서 한 번 더 물어보게 될 뿐이다.
  }
}
