import '../styles/LedNumber.css';

/**
 * LedNumber — 야구장 전광판을 흉내 낸 점(dot) 숫자.
 * Figma: SWM / [Game] 퀴즈 결과 (node 597:9739) 의 `ETC/Number_LED`·`ETC/Sign_LED`
 *
 * 한 글자는 4px 점을 1px 간격으로 6칸 x 10줄 놓은 격자다(29 x 49).
 * 퍼센트 기호만 5 x 5(24 x 24)로 따로 있고 아래쪽에 맞춰 선다.
 *
 * ⚠️ **0·1·8 만 디자인에서 그대로 옮긴 값이고 나머지는 같은 규칙으로 채워 넣었다.**
 * Figma 에는 화면에 실제로 찍힌 숫자(100·80%)의 점 배치만 있어 0~9 글꼴이 없다.
 * 획이 2점 두께이고 위·아래 줄이 한 칸씩 안으로 들어가는 규칙을 따랐으므로 인상은 같지만,
 * 디자이너가 원본 글꼴을 주면 아래 표만 갈아 끼우면 된다.
 */

/** 한 줄에 `1` 이면 켜진 점. 6칸 x 10줄. */
const DIGIT_DOTS: Record<string, readonly string[]> = {
  // ↓ 디자인에서 그대로 옮긴 세 글자
  0: ['011110', '111111', '110011', '110011', '110011', '110011', '110011', '110011', '111111', '011110'],
  1: ['001100', '111100', '111100', '001100', '001100', '001100', '001100', '001100', '111111', '111111'],
  8: ['011110', '111111', '110011', '110011', '011110', '011110', '110011', '110011', '111111', '011110'],
  // ↓ 같은 규칙으로 채운 나머지
  2: ['011110', '111111', '110011', '000011', '000110', '001100', '011000', '110000', '111111', '111111'],
  3: ['011110', '111111', '000011', '000011', '001111', '001111', '000011', '110011', '111111', '011110'],
  4: ['110011', '110011', '110011', '110011', '111111', '111111', '000011', '000011', '000011', '000011'],
  5: ['111111', '111111', '110000', '110000', '111110', '111111', '000011', '110011', '111111', '011110'],
  6: ['011110', '111111', '110000', '110000', '111110', '111111', '110011', '110011', '111111', '011110'],
  7: ['111111', '111111', '000011', '000011', '000110', '001100', '001100', '001100', '001100', '001100'],
  9: ['011110', '111111', '110011', '110011', '111111', '011111', '000011', '000011', '111111', '011110'],
};

/** 퍼센트 기호. 5칸 x 5줄(디자인 그대로). */
const PERCENT_DOTS: readonly string[] = ['11001', '11010', '00100', '01011', '10011'];

type LedNumberProps = {
  /** 표시할 값. 자릿수만큼 글자가 늘어난다(앞자리를 0 으로 채우지 않는다). */
  value: number;
  /** 켜진 점 색. 디자인은 전광판 왼쪽(획득 BQ)이 주황, 오른쪽 정답률이 흰색이다. */
  tone: 'primary' | 'white';
  /** 뒤에 붙는 기호. 지금은 퍼센트뿐이다. */
  suffix?: 'percent';
  /**
   * 보조기기가 읽을 문구.
   *
   * 점 격자는 화면에서만 숫자로 보인다 — 마크업으로는 수백 개의 빈 칸이라
   * 읽어 줄 것이 없다. 그래서 격자는 통째로 숨기고 이 문구만 남긴다.
   */
  label: string;
};

/** 점 격자 하나. `rows` 의 `1` 만 켠다. */
function DotGrid({ rows, name }: { rows: readonly string[]; name: string }) {
  const columns = rows[0].length;

  return (
    <span
      className="led-number__grid"
      data-glyph={name}
      style={{ '--led-columns': columns, '--led-rows': rows.length } as React.CSSProperties}
    >
      {rows.flatMap((row, rowIndex) =>
        [...row].map((dot, columnIndex) => (
          <span
            className="led-number__dot"
            data-on={dot === '1' || undefined}
            key={`${rowIndex}-${columnIndex}`}
          />
        )),
      )}
    </span>
  );
}

export default function LedNumber({ value, tone, suffix, label }: LedNumberProps) {
  /* 음수·소수는 이 전광판이 표현할 수 없다 — 들어오면 0 으로 접는다. */
  const digits = [...String(Math.max(0, Math.round(value)))];

  return (
    <span className="led-number" data-tone={tone}>
      {/* 점 격자는 그림이라 읽히면 안 된다. 값은 아래 문구가 대신 알린다. */}
      <span className="led-number__glyphs" aria-hidden="true">
        {digits.map((digit, index) => (
          <DotGrid key={`${digit}-${index}`} name={digit} rows={DIGIT_DOTS[digit] ?? DIGIT_DOTS[0]} />
        ))}
        {suffix === 'percent' && <DotGrid name="percent" rows={PERCENT_DOTS} />}
      </span>
      <span className="led-number__label">{label}</span>
    </span>
  );
}
