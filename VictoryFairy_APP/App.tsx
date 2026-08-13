import { StatusBar } from 'expo-status-bar';
import { StyleSheet } from 'react-native';
import { SafeAreaProvider, SafeAreaView } from 'react-native-safe-area-context';

import WebAppView from './src/WebAppView';
import { COLORS } from './src/theme';

export default function App() {
  return (
    <SafeAreaProvider>
      {/* 웹이 밝은 테마 전용이라 상태바 글자는 어두운 색으로 고정한다. */}
      <StatusBar style="dark" />
      {/* 노치·홈 인디케이터를 피해 WebView를 안전 영역 안에만 그린다. 이러면
          웹의 env(safe-area-inset-*)는 0이 되므로 여백이 이중으로 잡히지 않는다.
          가로 방향은 portrait 고정이라 edges에서 뺐다. */}
      <SafeAreaView style={styles.safeArea} edges={['top', 'bottom']}>
        <WebAppView />
      </SafeAreaView>
    </SafeAreaProvider>
  );
}

const styles = StyleSheet.create({
  safeArea: {
    flex: 1,
    backgroundColor: COLORS.background,
  },
});
