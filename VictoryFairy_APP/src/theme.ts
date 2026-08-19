/**
 * 앱 셸(스플래시 · 로딩 · 오류 화면 · 알림 권한 시트)에서 쓰는 디자인 값.
 *
 * 화면 본문은 WebView가 그리므로 여기 값은 웹이 뜨기 전후, 그리고 웹이 만들 수 없는
 * 네이티브 표면에서만 보인다. `VictoryFairy_FE/src/styles/tokens.css`의 시맨틱 토큰에서
 * 가져왔으며, 웹과 앱의 경계가 눈에 띄지 않으려면 토큰이 바뀔 때 같이 고쳐야 한다.
 */
export const COLORS = {
  /** `--color-primary-normal` — 로딩 인디케이터 · 재시도 버튼 · 알림 CTA */
  primary: '#F04E23',
  /** `--color-background-basic` — WebView 뒤에 깔리는 바탕 */
  background: '#FFFFFF',
  /** `--color-surface` — 바텀시트 바탕. 지금은 배경과 같은 흰색이다. */
  surface: '#FFFFFF',
  /** `--color-label-normal` — 오류 제목 · 시트 제목 */
  labelNormal: '#313140',
  /** `--color-label-neutral` — 오류 설명 · 시트 설명 · 보조 버튼 */
  labelNeutral: '#6A6A7D',
  /** `--color-label-assistive` — 시트 상단 핸들 막대 */
  labelAssistive: '#D0D0DA',
  /** 주황 배경 위에 얹는 글자 */
  onPrimary: '#FFFFFF',
  /** `--color-dim` — 바텀시트가 덮는 딤(Static/black 50%) */
  dim: 'rgba(18, 18, 18, 0.5)',
} as const;

/**
 * `--radius-*` 중 앱이 쓰는 값.
 *
 * 웹의 바텀시트는 위 모서리만 40, CTA 버튼은 15다(`GameDetailSheet.css`).
 */
export const RADIUS = {
  sheet: 40,
  button: 15,
  full: 9999,
} as const;

/**
 * 웹의 타이포 스케일 중 앱이 쓰는 값.
 *
 * 웹은 Pretendard를 쓰지만 앱에는 폰트를 심지 않아 시스템 기본 서체로 그린다 —
 * 웹도 폰트 파일이 없어 같은 시스템 서체로 폴백하는 상태라 지금은 어긋나지 않는다.
 * FE에 Pretendard가 들어오면 앱에도 같이 넣어야 경계가 드러나지 않는다.
 */
export const TYPOGRAPHY = {
  /** Title/Title3 — SemiBold 18 / 1.4 / -1% */
  title3: { fontSize: 18, fontWeight: '600', lineHeight: 25, letterSpacing: -0.18 },
  /** Body/Body4 — Regular 14 / 1.5 */
  body4: { fontSize: 14, fontWeight: '400', lineHeight: 21 },
  /** Button/Button2 — Medium 16 / 1.4 */
  button2: { fontSize: 16, fontWeight: '500', lineHeight: 22 },
  /** Label/Label1 — Medium 14 / 1.4 */
  label1: { fontSize: 14, fontWeight: '500', lineHeight: 20 },
} as const;
