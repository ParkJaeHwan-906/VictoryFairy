import type { Player } from './player';
import type { WornCharacterItem } from './character';

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
 * 키는 정확히 이 10개뿐이다(2026-08-20 에 `profileImgUrl` 이 붙어 5개 → 6개,
 * 2026-08-28 에 `characterImgUrl`·`characterItems` 가 붙어 6개 → 8개,
 * 2026-09-03 에 `quizAccuracy` 가 붙어 9개, 2026-09-04 에 `bqRank` 가 붙어 10개) —
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
  /**
   * 아바타 캐릭터 본체 이미지의 **EP**(`characters/{슬러그}.svg`). 2026-08-28 신설.
   * 프로필 사진(`profileImgUrl`)과 **별개 값**이다. `toAssetUrl()` 로 도메인을 붙인다.
   *
   * 가입에 성공하면 기본 캐릭터('승리요정')를 받으므로 보통 값이 있지만,
   * **드물게 `null` 일 수 있다** — 꾸미기 데이터가 아직 없는 환경에서 가입하면 지급이
   * 건너뛰어지기 때문이다(지급 실패가 회원가입을 막지 않는다). 그때도 응답은 200 이고
   * 서버가 다음 기동에 자동으로 채운다. 화면은 이 값이 비어 있을 수 있다는 것만 감안하면 된다.
   */
  characterImgUrl: string | null;
  /**
   * **지금 착용 중인** 아이템들. 없으면 `null` 이 아니라 **빈 배열**이다
   * (`characterImgUrl` 과 "없음" 표현이 비대칭인 점에 주의 — `supportTeam`/`supportPlayers`
   * 와 같은 패턴이다). 기본 의상이 켜진 채로 지급되므로 보통 1건 이상이다.
   *
   * 🖼️ 여기 실린 `imgUrl` 이 **캐릭터에 겹쳐 그릴 착용용**(`items/...`, 160×200)이다 —
   * 상점 목록(`GET /characters/items`)의 `displayImg`(`stores/...`, 80×80 진열용)와
   * 바꿔 쓰면 어긋난다. **부위 순으로 정렬돼 오므로 받은 순서 그대로 겹쳐 그리면 된다.**
   *
   * 착용 토글(`PUT /characters/items/active`)로 이 목록이 바뀌므로,
   * 토글 후 캐릭터 미리보기를 갱신하려면 `getMyProfile()` 을 다시 부른다.
   */
  characterItems: WornCharacterItem[];
  /**
   * 내 퀴즈 **누적** 정답률(`0`~`1`). 2026-09-03 신설.
   *
   * 2026-08-13 에 풀이 이력이 경기 단위로 좁혀지며 사라졌던 **계정 전체 정답률이
   * 여기로 돌아왔다** — 마이페이지의 "평균 정답률"이 쓸 값이 이것이다.
   *
   * ⚠️ **분모가 "푼 문제"가 아니라 "받은 문제"다.** 행은 `GET /quizzes/today` 가
   * 세트를 내주는 순간 생기므로, 세트를 받자마자 이 값을 읽으면 아직 안 푼 문제까지
   * 분모에 잡혀 **일시적으로 떨어졌다가 풀면서 회복된다**(버그가 아니다). 받고 한 문제도
   * 안 풀면 그 행은 영구히 오답으로 남는다 — "안 내면 오답" 정책의 결과다.
   *
   * 제출 행이 하나도 없으면 `null` 이 아니라 **`0`** 이다.
   *
   * 소수 넷째 자리에서 HALF_UP 반올림한 셋째 자리까지 오고 **후행 0 은 없다**
   * (`0.5` 는 `0.500` 이 아니다) — 백분율 표기와 자릿수 맞춤은 화면 몫이다.
   *
   * ⚠️ 퀴즈 풀이 이력의 `accuracy`(`QuizInningSummary`)와는 **다른 수다** —
   * 그쪽은 반올림 없는 경기 단위 값이다. 서버가 의도적으로 맞추지 않았으니 섞어 쓰지 않는다.
   */
  quizAccuracy: number;
  /**
   * **응원 구단 안에서** 내 BQ 순위. 2026-09-04 신설.
   *
   * `GET /rankings/bq/me` 의 `rank` 와 **항상 같은 값**이다(내림차순, 동점 공동 순위
   * 1·1·3, 배치는 계정 id 오름차순 — 규칙이 완전히 같다). 순위 숫자 하나만 필요한
   * 화면은 이 값으로 충분하고, 프로필 사진·닉네임까지 함께 필요하면 그쪽을 부른다.
   *
   * **활성 응원 구단이 없으면 `null`** 이다(`0` 도, 키 생략도 아니다) — `supportTeam`
   * 이 `null` 인 것과 같은 안전망이다. 누적 점수 행이 없어도 `bqScore: 0` 으로 순위는 매겨진다.
   */
  bqRank: number | null;
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
