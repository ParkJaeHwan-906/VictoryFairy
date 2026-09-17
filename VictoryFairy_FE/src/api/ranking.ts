import type { AxiosResponse } from 'axios';
import { userClient } from './httpClient';
import type { ApiResponse } from '../types/api';
import type { BqRankingEntry } from '../types/ranking';

/**
 * 순위 API (user 모듈).
 *
 * 내 응원 구단 **안에서** BQ 점수로 매긴 순위다. 세 엔드포인트 전부 **인증이 필수이고
 * 파라미터가 0개**라 `requiresAuth: true` 외에는 아무것도 실어 보내지 않는다 —
 * 구단도 대상 계정도 access 토큰의 활성 응원 구단으로만 정해진다(docs/ranking.md).
 *
 * ── ⚠️ 응원 구단이 없어도 실패가 아니다 ────────────────────────────────
 * 이 도메인에는 `SUPPORT_TEAM_REQUIRED` 같은 에러 코드가 없다. 구단을 고르지 않은
 * 계정도 **200** 을 받고, 목록은 `[]`, 내 순위는 `null` 이 온다. 그래서 이 모듈에는
 * 다른 도메인들이 갖고 있는 `isXxx` 판별 함수도 `ERROR_MESSAGE` 상수도 없다 —
 * 문서에 적힌 실패는 401(미인증)과 405(메서드) 둘뿐이고, 둘 다 화면이 따로 가려낼
 * 것이 아니라 httpClient 와 공통 실패 문구가 맡는 몫이다.
 *
 * **빈 결과를 "구단 없음"으로 단정할 수는 없다.** 구단이 있어도 모집단이 비어 있을
 * 수 있어 `[]` 는 두 상황에 걸친다. 가르려면 프로필의 `supportTeam` 을 봐야 한다
 * (`GET /games/support` 의 빈 배열을 다루는 방식과 같다).
 * ──────────────────────────────────────────────────────────────────────
 *
 * ── 🔁 셋은 각각 별개 스냅샷이다 ───────────────────────────────────────
 * 캐시도 스냅샷도 없어서, 세 경로를 연달아 부르는 사이 누군가 BQ 를 적립하면 서로
 * 어긋날 수 있다. 그래서 **TOP 3 와 TOP 10 을 함께 부르지 않는다** — TOP 10 응답의
 * 앞 3건이 곧 TOP 3 이므로, 시상대와 목록이 한 화면에 있으면 `getBqRanking()` 한 번으로
 * 끝내는 편이 왕복도 적고 두 목록이 어긋날 일도 없다. `getBqTopRanking()` 은
 * **시상대만 있는 화면**(홈)을 위한 것이다.
 * ──────────────────────────────────────────────────────────────────────
 */

/** ApiResponse 로 감싸인 성공 응답의 `data` 를 벗겨낸다(team·character 와 같은 방식). */
function unwrap<T>(res: AxiosResponse<ApiResponse<T>>): T {
  return res.data.data as T;
}

/**
 * GET /rankings/bq/top — 내 응원 구단 안 TOP 3.
 *
 * `rank` 오름차순 최대 3건. 모집단이 3명 미만이면 있는 만큼만 오고, 응원 구단이
 * 없으면 빈 배열이다(둘 다 정상 200).
 *
 * 동점자가 상한에서 잘려도 항목 수는 3을 넘지 않는다 — 1·1·1 이 세 건 오면
 * 4위 이하가 아예 없는 것이 아니라 **3위 자리까지만 보여 준 것**이다.
 */
export function getBqTopRanking(): Promise<BqRankingEntry[]> {
  return userClient
    .get<ApiResponse<BqRankingEntry[]>>('/rankings/bq/top', { requiresAuth: true })
    .then(unwrap);
}

/**
 * GET /rankings/bq — 내 응원 구단 안 TOP 10.
 *
 * 항목 모양은 TOP 3 와 완전히 같고 상한만 10건이다. **본인이 10위 안에 있어도 다른
 * 항목과 구분되지 않는다**(`isMe` 같은 키가 없다) — 내 줄을 강조하려면
 * `getMyBqRanking()` 을 따로 부른다.
 *
 * 11위 이하를 받을 방법은 없다(페이지네이션 범위 밖).
 */
export function getBqRanking(): Promise<BqRankingEntry[]> {
  return userClient
    .get<ApiResponse<BqRankingEntry[]>>('/rankings/bq', { requiresAuth: true })
    .then(unwrap);
}

/**
 * GET /rankings/bq/me — 내 순위. **배열이 아니라 객체 하나**다.
 *
 * 10위 안이든 300위 밖이든 항상 실제 숫자가 온다 — 상한값으로 뭉개거나 `null` 로
 * 바꾸지 않는다. 누적 점수 행이 없는 계정도 `bqScore: 0` 으로 순위가 매겨진다.
 *
 * `null` 은 실패가 아니라 **응원 구단이 없다**는 뜻이다(빈 객체도 `rank: 0` 도 아니다).
 */
export function getMyBqRanking(): Promise<BqRankingEntry | null> {
  return userClient
    .get<ApiResponse<BqRankingEntry | null>>('/rankings/bq/me', { requiresAuth: true })
    .then(unwrap);
}
