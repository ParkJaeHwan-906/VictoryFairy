import HeartIcon from './HeartIcon';
import '../styles/FloatingHearts.css';

/** 떠오르는 하트 하나. 흔들림·경로는 하나씩 다르게 뽑는다. */
export interface FloatingHeart {
  id: number;
  /** 출발점 — 부모 위쪽 가운데에서 가로로 비켜난 거리(px, 음수면 왼쪽). */
  x: number;
  /** 출발점 — 부모 위쪽에서 아래로 내려온 거리(px). */
  y: number;
  /** 좌우로 흔들리는 폭(px). 부호가 첫 방향이다. */
  sway: number;
  /** 다 올라갔을 때 출발점에서 비켜난 거리(px). */
  drift: number;
  /** 떠오르는 시간(ms). */
  duration: number;
}

type FloatingHeartsProps = {
  hearts: FloatingHeart[];
  /** 다 올라가 사라진 하트를 치운다(애니메이션 끝). */
  onDone: (id: number) => void;
};

/**
 * 하트마다 정한 출발점(부모 위쪽 가운데 기준 x · y)에서 좌우로 흔들리며 올라가다 사라지는 하트들.
 * 부모는 `position: relative` 여야 한다. 장식이라 보조기기에는 숨기고, 손가락도 가로채지 않는다.
 */
export default function FloatingHearts({ hearts, onDone }: FloatingHeartsProps) {
  return (
    <span className="floating-hearts" aria-hidden="true">
      {hearts.map((heart) => (
        <span
          className="floating-hearts__item"
          key={heart.id}
          style={
            {
              '--heart-x': `${heart.x}px`,
              '--heart-y': `${heart.y}px`,
              '--heart-drift': `${heart.drift}px`,
              '--heart-sway': `${heart.sway}px`,
              '--heart-duration': `${heart.duration}ms`,
            } as React.CSSProperties
          }
          onAnimationEnd={(event) => {
            // 안쪽 흔들림(무한 반복)이 아니라 바깥 상승이 끝났을 때만 치운다.
            if (event.target === event.currentTarget) onDone(heart.id);
          }}
        >
          <span className="floating-hearts__sway">
            <HeartIcon className="floating-hearts__icon" />
          </span>
        </span>
      ))}
    </span>
  );
}
