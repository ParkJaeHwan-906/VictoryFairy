<<<<<<< HEAD
/**
 * 응원 구단. 프로필에서는 아직 고르지 않았을 수 있어 nullable 로 들어간다.
 *
 * `types/team.ts` 의 `Team` 과 형태가 같지만 별개 계약이라 별도로 둔다.
 */
=======
/** 응원 구단. 프로필에서는 아직 고르지 않았을 수 있어 nullable 로 들어간다. */
>>>>>>> cec34b275b3d5a57b5aea96505a82dd82c076ebd
export interface SupportTeam {
  id: number;
  name: string;
}

/**
 * GET /users/me 의 data.
 *
 * 키는 정확히 이 4개뿐이다 — 계정 PK·UUID·이메일·전화번호·탈퇴 시각 같은 값은
 * 응답 어디에도 없다. 새 필드가 필요하면 백엔드 계약부터 바뀌어야 한다.
 */
export interface MyProfile {
  nickname: string;
  /** 온보딩 중(구단 미선택)이면 오류가 아니라 `null` 이다. */
  supportTeam: SupportTeam | null;
  point: number;
  /** 누적 점수 행이 아직 없어도 `null` 이 아니라 `0` 으로 온다. */
  bqScore: number;
}
