/**
 * 앱 셸(스플래시 · 로딩 · 오류 화면)에서 쓰는 색상.
 *
 * 화면 본문은 WebView가 그리므로 여기 값은 웹이 뜨기 전후에만 보인다.
 * `VictoryFairy_FE/src/styles/tokens.css`의 시맨틱 토큰에서 가져왔으며,
 * 웹과 앱의 경계가 눈에 띄지 않으려면 토큰이 바뀔 때 같이 고쳐야 한다.
 */
export const COLORS = {
  /** `--color-primary-normal` — 로딩 인디케이터 · 재시도 버튼 */
  primary: '#F04E23',
  /** `--color-background-basic` — WebView 뒤에 깔리는 바탕 */
  background: '#FFFFFF',
  /** `--color-label-normal` — 오류 제목 */
  labelNormal: '#313140',
  /** `--color-label-neutral` — 오류 설명 */
  labelNeutral: '#6A6A7D',
  /** 주황 배경 위에 얹는 글자 */
  onPrimary: '#FFFFFF',
} as const;
