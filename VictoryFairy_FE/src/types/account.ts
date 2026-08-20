import type { Player } from './player';

/**
 * 응원 구단. 프로필에서는 아직 고르지 않았을 수 있어 nullable 로 들어간다.
 *
 * `types/team.ts` 의 `Team` 과 형태가 같지만 별개 계약이라 별도로 둔다.
 */
export interface SupportTeam {
  id: number;
  name: string;
}

/**
 * GET /users/me 의 data.
 *
 * 키는 정확히 이 6개뿐이다(2026-08-20 에 `profileImgUrl` 이 붙어 5개 → 6개) —
 * 계정 PK·UUID·비밀번호 해시·이메일·전화번호·탈퇴 시각 같은 값은 응답 어디에도 없다.
 * 새 필드가 필요하면 백엔드 계약부터 바뀌어야 한다.
 */
export interface MyProfile {
  nickname: string;
  /** 온보딩 중(구단 미선택)이면 오류가 아니라 `null` 이다. */
  supportTeam: SupportTeam | null;
  /**
   * 현재 응원 중인 선수 전체(`playerName` 오름차순). 없으면 `null` 이 아니라 **빈 배열**이다
   * — 단일 값인 `supportTeam` 과 "없음" 표현이 비대칭인 점에 주의.
   *
   * 응원 선수 추가/취소 API 와 같은 선수 객체를 재사용한다.
   * 길이 상한(4)은 `POST /support/players` 가 추가 시점에만 강제하므로
   * 이 목록이 4 이하라고 가정하면 안 된다(상한 도입 이전 계정은 초과분이 그대로 온다).
   */
  supportPlayers: Player[];
  point: number;
  /** 누적 점수 행이 아직 없어도 `null` 이 아니라 `0` 으로 온다. */
  bqScore: number;
  /**
   * 프로필 이미지의 **EP**(BaseURL 을 뺀 오브젝트 키, `user-profile-img/{uuid}.ext`).
   * 2026-08-20 신설.
   *
   * **사진이 없으면 `null`** 이다 — 빈 문자열도, 기본 이미지 주소도 아니다.
   * 화면에 쓰려면 `toAssetUrl()` 로 도메인을 붙이고, `null` 이면 자리표시 이미지로 대신한다.
   *
   * 가입할 때 넘긴 `temp/` EP 와는 **문자열이 완전히 다르다**(접두사도 파일 UUID 도
   * 새로 생성된다). 가입 직후에는 반드시 이 값을 다시 받아 화면에 반영해야 한다.
   */
  profileImgUrl: string | null;
}

/* ------------------------------------------------------------------ *
 * 요청 DTO — 프로필 수정
 * ------------------------------------------------------------------ */

/**
 * PATCH /users/me/nickname 의 본문.
 *
 * 정책은 회원가입과 같다(1~10자, 한글·영문·숫자만) — `POST /auth/nickname/validate` 로
 * 미리 검사한 값을 그대로 보내면 된다.
 */
export interface ChangeNicknameRequest {
  nickname: string;
}

/**
 * PATCH /users/me/password 의 본문. 둘 다 평문이다.
 *
 * `currentPassword` 에는 검증 애노테이션이 없어 누락·`null` 도 400 "불일치"로 떨어진다.
 * `newPassword` 정책은 회원가입과 같다(8~12자, 영문·숫자·특수문자 각 1자 이상).
 */
export interface ChangePasswordRequest {
  currentPassword: string;
  newPassword: string;
}

/* ------------------------------------------------------------------ *
 * 응답 DTO
 * ------------------------------------------------------------------ */

/**
 * 닉네임 변경 쿨다운(429) 실패 응답의 `data`.
 *
 * 이 저장소에서 **실패 응답의 `data` 가 `null` 이 아닌 첫 사례**다(그 외 실패는 `null`
 * 또는 Bean Validation 의 `FieldErrors`). 키는 정확히 `nextChangeableAt` 하나다.
 */
export interface NicknameChangeCooldown {
  /** `+09:00` 오프셋을 포함한 ISO-8601 문자열. 예) `2026-09-16T14:03:21+09:00` */
  nextChangeableAt: string;
}
