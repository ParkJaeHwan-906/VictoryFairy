import kiaLogo from '../assets/logo/KIA.png';
import ktLogo from '../assets/logo/KT.png';
import lgLogo from '../assets/logo/LG.png';
import ncLogo from '../assets/logo/NC.png';
import ssgLogo from '../assets/logo/SSG.png';
import doosanLogo from '../assets/logo/두산베어스.png';
import lotteLogo from '../assets/logo/롯데자이언츠.png';
import samsungLogo from '../assets/logo/삼성 라이온즈.png';
import kiwoomLogo from '../assets/logo/키움 히어로즈.png';
import hanwhaLogo from '../assets/logo/한화 이글스.png';

/**
 * 구단 표시 정보(로고 · 정식 명칭).
 *
 * `GET /teams` 는 `{ id, name }` 만 주고 `name` 은 짧은 이름("KIA", "두산")이다.
 * 반면 디자인은 로고와 정식 명칭("KIA 타이거즈")을 보여주므로, 그 둘을
 * 서버가 아니라 여기서 붙인다. **키는 서버의 짧은 `name`** 이다.
 *
 * `id` 로 키를 잡지 않는 이유 — PK 채번은 API 계약이 아니다(docs/team.md).
 */
export interface TeamDisplay {
  /** 카드에 쓰는 정식 명칭 */
  label: string;
  /** src/assets/logo 의 PNG. Figma `Image/Team/*` 를 그대로 내보낸 것이다. */
  logo: string;
}

const TEAM_DISPLAY: Record<string, TeamDisplay> = {
  KIA: { label: 'KIA 타이거즈', logo: kiaLogo },
  KT: { label: 'KT 위즈', logo: ktLogo },
  LG: { label: 'LG 트윈스', logo: lgLogo },
  NC: { label: 'NC 다이노스', logo: ncLogo },
  SSG: { label: 'SSG 랜더스', logo: ssgLogo },
  두산: { label: '두산 베어스', logo: doosanLogo },
  롯데: { label: '롯데 자이언츠', logo: lotteLogo },
  삼성: { label: '삼성 라이온즈', logo: samsungLogo },
  키움: { label: '키움 히어로즈', logo: kiwoomLogo },
  한화: { label: '한화 이글스', logo: hanwhaLogo },
};

/**
 * 서버 `name` 에 대응하는 표시 정보. 모르는 구단(신생·제휴 등)이면 `undefined` 다.
 * 호출부는 그 경우 로고 없이 `name` 만 그려서, 목록에서 통째로 사라지지 않게 한다.
 */
export function getTeamDisplay(name: string): TeamDisplay | undefined {
  return TEAM_DISPLAY[name];
}
