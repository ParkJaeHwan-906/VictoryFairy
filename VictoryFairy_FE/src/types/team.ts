/**
 * 구단(KBO 팀). `GET /teams` 의 `data` 는 이 객체의 **배열**이다.
 *
 * `account.ts` 의 `SupportTeam` 과 형태가 같지만 별개 계약이다 —
 * 이쪽은 선택 가능한 전체 목록, 저쪽은 프로필에 박힌 응원 구단이다.
 * 한쪽 계약이 바뀌어도 다른 쪽을 따라 바꾸지 않는다.
 */
export interface Team {
  /** 구단 PK. 선수 목록 조회의 `teamId`, 응원 구단 선택의 `teamId` 가 이 값이다. */
  id: number;
  name: string;
}
