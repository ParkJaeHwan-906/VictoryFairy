/**
 * `positionName`(API 원문) → 화면에 쓰는 한글 포지션 이름.
 *
 * API 는 값을 좁혀주지 않는다(docs/game.md). 실측 70종 중 약어는 12종뿐이고
 * 나머지 58종은 한자·한글 원문 2글자라, 약어만 처리하면 교체 출전 선수에서 깨진다.
 * 그래서 세 갈래로 받는다: 약어 → 단일 표기 → 2글자 조합, 어디에도 없으면 원문 그대로.
 */

/** 약어 12종. `PH`·`PR` 은 수비 위치가 아니라 출전 형태다. */
const ABBREVIATION_LABEL: Record<string, string> = {
  P: '투수',
  C: '포수',
  '1B': '1루수',
  '2B': '2루수',
  '3B': '3루수',
  SS: '유격수',
  LF: '좌익수',
  CF: '중견수',
  RF: '우익수',
  DH: '지명타자',
  PH: '대타',
  PR: '대주자',
};

/** 네이버 박스스코어의 단일 표기. 2글자 조합은 이 표기 2개를 이어붙인 것이다. */
const SINGLE_LABEL: Record<string, string> = {
  투: '투수',
  포: '포수',
  一: '1루수',
  二: '2루수',
  三: '3루수',
  유: '유격수',
  좌: '좌익수',
  중: '중견수',
  우: '우익수',
  지: '지명타자',
  타: '대타',
  주: '대주자',
};

export interface PositionDisplay {
  /** 칸에 그리는 짧은 이름. */
  label: string;
  /** 툴팁용 전체 설명. 포지션이 바뀐 선수만 label 과 다르다. */
  detail: string;
}

/**
 * 표시용 포지션 이름. 값이 없으면 `null` 이라 호출부가 칸을 비울 수 있다.
 *
 * 2글자 조합(`주좌` = 대주자 → 좌익수)은 **앞 글자**를 label 로 쓴다 —
 * 선발 라인업이 보여줄 것은 그 선수가 시작한 포지션이고, 좁은 칸(140px)에
 * 두 이름을 다 넣으면 이름과 겹치기 때문이다. 바뀐 뒤 포지션은 detail 로 남긴다.
 */
export function getPositionDisplay(positionName: string | null): PositionDisplay | null {
  if (!positionName) return null;

  const abbreviation = ABBREVIATION_LABEL[positionName];
  if (abbreviation) return { label: abbreviation, detail: abbreviation };

  const single = SINGLE_LABEL[positionName];
  if (single) return { label: single, detail: single };

  const chars = [...positionName];
  if (chars.length === 2) {
    const from = SINGLE_LABEL[chars[0]];
    const to = SINGLE_LABEL[chars[1]];
    if (from && to) return { label: from, detail: `${from} → ${to}` };
  }

  // 매핑에 없는 표기. 목록에서 사라지는 것보다 원문이라도 보이는 편이 낫다.
  return { label: positionName, detail: positionName };
}
