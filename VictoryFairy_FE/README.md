# VictoryFairy_FE

VictoryFairy 웹 프론트엔드. React + TypeScript + Vite.

이 앱이 화면의 전부다 — 안드로이드·iOS 앱(`../VictoryFairy_APP`)은 배포된 이 웹을
WebView로 감싼 것이라, **여기를 고치면 앱을 다시 설치하지 않아도 반영된다.**
`main`에 머지되면 `deploy-fe.yml`이 S3에 올리고 CloudFront가 서비스한다.

## 실행

```bash
npm install
npm run dev        # Vite 개발 서버 (5173)
npm run typecheck  # tsc -b
npm run lint       # oxlint
npm run build      # tsc -b && vite build
```

## 디렉터리 소유권

작업은 계층별 에이전트로 나뉜다(`.claude/agents/`). 어느 폴더를 건드리는지가 곧 담당이다.

| 경로 | 무엇 | 담당 |
|---|---|---|
| `src/components`·`src/pages` | 컴포넌트·페이지 구조, 라우팅, 커스텀 훅 | `react-agent` |
| `src/styles` | 화면당 CSS 파일 하나 + `global.css`·`tokens.css` | `css-agent` |
| `src/api`·`src/types` | Axios 클라이언트·엔드포인트 함수·응답 타입 | `api-agent` |
| `src/stores` | Zustand 전역 상태 | `store-agent` |

변경 후에는 `/verify`(`fe-verifier`)로 타입체크→린트→빌드 스모크까지 확인한다.

## 스타일 규약

CSS 프레임워크를 쓰지 않는다. 색·반경·타이포 값은 `src/styles/tokens.css`의 커스텀
프로퍼티로만 쓴다 — Figma `SWM` 파일의 Variables/Styles를 옮긴 것이라 하드코딩하면
디자인 원본과 끊긴다. 같은 값을 `VictoryFairy_APP/src/theme.ts`가 복사해 쓰므로,
토큰이 바뀌면 그쪽도 함께 고쳐야 웹과 앱의 경계가 눈에 띄지 않는다.

## API

백엔드 계약은 `../VictoryFairy_BE/docs/api/`가 단일 출처다(도메인별 문서 + Notion 미러).
`user` 앱은 `/api`, `quiz` 앱은 `/rt` 접두사를 쓴다.
