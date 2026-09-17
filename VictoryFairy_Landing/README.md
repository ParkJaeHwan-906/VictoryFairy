# 승리요정 랜딩페이지

앱 다운로드 유도용 단일 페이지. **React 18 + Vite** 로 되어 있고, 빌드 결과(`dist/`)는
그냥 정적 파일이라 S3·Vercel·Netlify 어디에 올려도 그대로 돈다.

## 시작

```bash
npm install
npm run dev       # http://localhost:5500 — 저장하면 즉시 반영(HMR)
npm run build     # dist/ 생성
npm run preview   # dist/ 를 그대로 띄워 배포본 최종 확인
```

## 구조

```
index.html            Vite 엔트리. <title> · meta · og:image · 폰트 CDN 은 여기
src/
  main.jsx            React 마운트
  App.jsx             섹션 순서 — 섹션을 빼거나 순서를 바꾸려면 이 파일만 고친다
  content.jsx         ★ 모든 문구 · 수치 · 링크 · 데이터. 카피 수정은 여기서 끝난다
  styles.css          전체 스타일 (앱 tokens.css 값을 그대로 옮김)
  components/         섹션별 컴포넌트. content.jsx 의 값을 그리기만 한다
  hooks/
    useReveal.js      스크롤 리빌 (IntersectionObserver)
    useCountUp.js     숫자 카운트업
public/assets/        빌드 시 그대로 복사된다 (경로는 코드에서 /assets/… 절대경로)
  brand/              logo · wordmark · stadium(구장 배경) · og
  character/          승요 레이어 25종 (기본 · 모자/헬멧 6 · 유니폼 11 · 아이템 6)
  screens/            앱 화면 5종
  teams/              KBO 10개 구단 로고
```

**어디를 고쳐야 하나**

| 하고 싶은 것 | 고칠 곳 |
|---|---|
| 문구 · 숫자 · 채팅 · 퀴즈 내용 | `src/content.jsx` |
| 스토어 링크 | `src/content.jsx` 의 `STORE_LINKS` |
| 섹션 순서 · 추가 · 삭제 | `src/App.jsx` |
| 색 · 여백 · 레이아웃 | `src/styles.css` |
| 페이지 제목 · OG 태그 | `index.html` |

## 배포

빌드 산출물은 `dist/` 하나뿐이라 어디든 올라간다.

- **Vercel / Netlify** — 저장소를 연결하면 Vite 를 자동 인식한다.
  (수동 설정 시 Build `npm run build`, Output `dist`)
- **S3 · Cloudflare Pages 등** — `npm run build` 후 `dist/` 내용물을 그대로 업로드.
- **GitHub Pages 처럼 하위 경로**(`https://…/repo/`)에 올린다면
  `vite.config.js` 의 `base` 를 `'/repo/'` 로 바꾼다. 루트 배포면 손댈 필요 없다.

## 배포 전에 반드시 바꿀 것

| 위치 | 지금 | 바꿀 것 |
|---|---|---|
| `src/content.jsx` 의 `STORE_LINKS` | `'#'` | 실제 App Store / Google Play URL |
| 스토어 배지 (`src/components/StoreBadges.jsx`) | 자체 제작 SVG | Apple·Google 공식 배지 에셋 |
| `index.html` 의 `og:image` | 자동 생성한 `/assets/brand/og.png` | 확정 대표 이미지가 생기면 교체 |

## 색을 바꿀 때

`styles.css` 의 `:root` 값은 앱 `VictoryFairy_FE/src/styles/tokens.css` 에서 옮겨 온 것이고,
그 원본은 Figma `SWM` 파일의 Variables 다. **여기서만 고치면 앱과 어긋난다** — 토큰이 바뀌면
Figma → 앱 tokens.css → 이 파일 순서로 함께 고친다.

## 캐릭터 레이어

`public/assets/character/` 의 SVG 는 전부 160×200 좌표계에 그려져 있어 **같은 자리에 겹치면
하나의 캐릭터가 된다**(기본 → 모자 → 유니폼 → 아이템 순). Figma 원본에 있던 회색 배경
사각형(`#F5F5F5`)은 겹칠 수 있도록 제거해 두었다. 새 코스튬을 Figma 에서 뽑을 때도
그 배경 사각형을 지워야 한다.

구단 ↔ 모자·유니폼 매핑은 `src/content.jsx` 의 `TEAMS` 배열 하나에서 관리한다.

## 구장 배경이 두 종류인 이유

- `stadium.svg` — 원본. 홈플레이트가 **오른쪽**(x≈300/402)에 있다. 캐릭터 섹션은
  캐릭터를 그 플레이트 위에 세우므로 이 파일을 쓴다.
- `stadium-band.svg` — 원본에서 플레이트(y≥181)를 잘라낸 판. 히어로는 캐릭터를
  **가운데** 세우는데 원본을 쓰면 플레이트와 어긋나고, 화면 폭에 따라 크롭이 달라져
  플레이트가 들락날락한다. 그래서 아예 없는 판을 따로 둔다.

## 알려진 한계

- **JS 가 꺼져 있으면 아무것도 보이지 않는다.** React CSR 이라 그렇다. 검색·SNS 미리보기는
  `index.html` 의 meta 태그로 나가므로 영향이 없지만, 본문 SEO 가 중요해지면
  프리렌더(`vite-plugin-prerender` 등)나 Next.js 이전을 검토할 것.
- 랭킹/BQ 수치와 채팅 내용은 **연출용 예시**다. 실제 API 를 붙이지 않는다.
