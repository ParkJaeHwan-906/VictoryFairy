import { useEffect, useRef } from 'react';
import { Animated, Easing, Modal, Pressable, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';

import { COLORS, RADIUS, TYPOGRAPHY } from '../theme';

/**
 * 알림 권한을 묻기 전에 이유를 먼저 말하는 바텀시트.
 *
 * 앱이 직접 그리는 몇 안 되는 화면이다. 웹에 만들 수 없어서다 — 시스템 권한
 * 대화상자는 거절당하면 다시 뜨지 않으므로, 그 앞에 한 겹을 두어 "무엇을 위한
 * 알림인지" 알고 고르게 한다.
 *
 * 디자인은 웹의 경기 상세 바텀시트(`VictoryFairy_FE/src/styles/GameDetailSheet.css`)를
 * 그대로 따른다 — 딤 50%, 위 모서리 40, 핸들 48×6, 높이 60의 CTA. 웹 화면 위에
 * 겹쳐 뜨는 표면이라 여기서 어긋나면 앱이 만든 것이라는 게 곧바로 드러난다.
 */

/** 시트가 올라오는 거리·시간. 웹의 `game-sheet-rise`(0.24s ease-out)와 맞췄다. */
const RISE_DISTANCE = 360;
const RISE_DURATION_MS = 240;

interface PermissionSheetProps {
  /** "알림 받기" — 시스템 권한 대화상자로 넘어간다. */
  onAllow: () => void;
  /** "다음에 할게요" · 딤 탭 · 안드로이드 백 버튼. */
  onSnooze: () => void;
}

export default function PermissionSheet({ onAllow, onSnooze }: PermissionSheetProps) {
  const insets = useSafeAreaInsets();
  const rise = useRef(new Animated.Value(0)).current;

  useEffect(() => {
    Animated.timing(rise, {
      toValue: 1,
      duration: RISE_DURATION_MS,
      easing: Easing.out(Easing.cubic),
      useNativeDriver: true,
    }).start();
  }, [rise]);

  const translateY = rise.interpolate({
    inputRange: [0, 1],
    outputRange: [RISE_DISTANCE, 0],
  });

  return (
    // 시스템 알림 대화상자와 같은 층에 떠야 해서 Modal로 띄운다. WebView 위에 그냥
    // 겹쳐 두면 웹의 스크롤·탭이 뒤에서 계속 먹는다.
    <Modal
      visible
      transparent
      // 딤과 시트를 따로 움직여야 해서 기본 전환 대신 직접 애니메이션한다.
      animationType="none"
      statusBarTranslucent
      onRequestClose={onSnooze}
    >
      <Animated.View style={[styles.dim, { opacity: rise }]}>
        {/* 딤을 눌러 닫기. 화면에는 어두운 면으로만 보이므로 레이블을 따로 준다. */}
        <Pressable
          style={StyleSheet.absoluteFill}
          onPress={onSnooze}
          accessibilityRole="button"
          accessibilityLabel="닫기"
        />
      </Animated.View>

      <View style={styles.anchor} pointerEvents="box-none">
        <Animated.View
          style={[styles.sheet, { paddingBottom: 22 + insets.bottom, transform: [{ translateY }] }]}
        >
          <View style={styles.handle}>
            <View style={styles.handleBar} />
          </View>

          <Text style={styles.title} accessibilityRole="header">
            경기 시작 30분 전에 알려드릴게요
          </Text>
          <Text style={styles.description}>
            응원 구단 경기가 있는 날, 시작 30분 전에 알림을 보내드려요.{'\n'}
            알림은 기기 설정에서 언제든 끌 수 있어요.
          </Text>

          <Pressable
            onPress={onAllow}
            accessibilityRole="button"
            style={({ pressed }) => [styles.cta, pressed && styles.pressed]}
          >
            <Text style={styles.ctaLabel}>알림 받기</Text>
          </Pressable>
          <Pressable
            onPress={onSnooze}
            accessibilityRole="button"
            style={({ pressed }) => [styles.snooze, pressed && styles.pressed]}
          >
            <Text style={styles.snoozeLabel}>다음에 할게요</Text>
          </Pressable>
        </Animated.View>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  dim: {
    position: 'absolute',
    top: 0,
    right: 0,
    bottom: 0,
    left: 0,
    backgroundColor: COLORS.dim,
  },
  // 시트를 바닥에 붙인다. 딤은 아래 깔린 Pressable이 받아야 하므로 이 층은 통과시킨다.
  anchor: {
    flex: 1,
    justifyContent: 'flex-end',
  },
  sheet: {
    // 웹은 402px(디자인 기준 프레임)에서 폭을 멈춘다. 태블릿을 막아 두었으니 실제로는
    // 항상 화면 폭이지만, 기준을 남겨 웹과 같은 규칙임을 드러낸다.
    width: '100%',
    maxWidth: 402,
    alignSelf: 'center',
    paddingHorizontal: 24,
    borderTopLeftRadius: RADIUS.sheet,
    borderTopRightRadius: RADIUS.sheet,
    backgroundColor: COLORS.surface,
  },
  handle: {
    alignItems: 'center',
    paddingVertical: 12,
  },
  handleBar: {
    width: 48,
    height: 6,
    borderRadius: RADIUS.full,
    backgroundColor: COLORS.labelAssistive,
  },
  title: {
    marginTop: 8,
    color: COLORS.labelNormal,
    ...TYPOGRAPHY.title3,
  },
  description: {
    marginTop: 12,
    color: COLORS.labelNeutral,
    ...TYPOGRAPHY.body4,
  },
  cta: {
    height: 60,
    marginTop: 28,
    alignItems: 'center',
    justifyContent: 'center',
    borderRadius: RADIUS.button,
    backgroundColor: COLORS.primary,
  },
  ctaLabel: {
    color: COLORS.onPrimary,
    ...TYPOGRAPHY.button2,
  },
  snooze: {
    height: 48,
    alignItems: 'center',
    justifyContent: 'center',
  },
  snoozeLabel: {
    color: COLORS.labelNeutral,
    ...TYPOGRAPHY.label1,
  },
  pressed: {
    opacity: 0.8,
  },
});
