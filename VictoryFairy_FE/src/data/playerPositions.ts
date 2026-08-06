/**
 * `playerPosition`(선수 목록 조회의 enum) → 화면에 쓰는 한글 이름.
 *
 * 경기 라인업의 `positionName`(`P`·`1B`·`주좌` 같은 수비 위치, `data/positions.ts`)과는
 * **다른 축이다.** 저쪽은 한 경기에서의 수비 위치라 실측 70종에 원문 표기까지 섞이지만,
 * 이쪽은 선수의 대분류 4종뿐이다. 값 체계가 다르므로 두 매핑을 합치면 안 된다.
 */
const POSITION_LABEL: Record<string, string> = {
  PITCHER: '투수',
  CATCHER: '포수',
  INFIELDER: '내야수',
  OUTFIELDER: '외야수',
};

/**
 * 표시용 포지션 이름. 값이 없으면 `null` 이라 호출부가 칸을 비울 수 있다.
 *
 * 1군 이력이 없는 선수는 `null` 이고, 이는 드문 예외가 아니다 —
 * 2026-08-06 실측 기준 558명 중 260명(약 47%)이 포지션·등번호 모두 `null` 이다(docs/player.md).
 *
 * `PlayerPosition` 타입을 4종으로 닫지 않았듯 여기서도 닫지 않는다.
 * 매핑에 없는 값이 오면 목록에서 사라지는 것보다 원문이라도 보이는 편이 낫다.
 */
export function getPlayerPositionLabel(position: string | null): string | null {
  if (!position) return null;
  return POSITION_LABEL[position] ?? position;
}
