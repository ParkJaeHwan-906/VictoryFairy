import { useCallback, useEffect, useRef, useState } from 'react';
import type {
  PointerEvent as ReactPointerEvent,
  TransitionEvent as ReactTransitionEvent,
} from 'react';

/**
 * 모든 바텀시트가 함께 쓰는 열기/닫기 동작.
 *
 * 시트마다 따로 갖고 있던 것들(Esc 로 닫기 · 배경 스크롤 잠금 · 딤 클릭)이 이미 다섯 벌로
 * 복제돼 있었고, 여기에 **핸들 끌어내리기**와 **내려가며 닫히기**까지 각자 붙이면 다섯 곳이
 * 조금씩 달라진다. 그래서 동작만 이 훅으로 모으고, 생김새는 각 시트의 CSS 에 그대로 둔다.
 *
 * 쓰는 쪽은 돌려받은 props 를 자리마다 펼치기만 하면 된다(`bottomSheet.css` 의 공통 클래스와
 * 짝이다):
 *
 * ```tsx
 * const sheet = useBottomSheet(onClose);
 *
 * <div className="x-sheet bottom-sheet-root" {...sheet.rootProps}>
 *   <button className="x-sheet__dim bottom-sheet-dim" type="button" {...sheet.dimProps} />
 *   <section className="x-sheet__sheet bottom-sheet-panel" {...sheet.panelProps}>
 *     <button className="x-sheet__handle bottom-sheet-handle" type="button" {...sheet.handleProps}>
 * ```
 *
 * ── 닫기는 두 걸음이다 ──────────────────────────────────────────────
 * 딤을 눌러도 `onClose` 를 바로 부르지 않는다. 부르는 순간 시트를 띄운 쪽이 언마운트해
 * 그림이 사라지므로, 내려가는 모습을 보여줄 수 없기 때문이다. 그래서 먼저 시트를 화면
 * 아래로 내리고(`translateY(100%)`), 그 전환이 끝난 뒤에 `onClose` 를 부른다.
 */

/** 내려가며 닫히는 시간(ms). `bottomSheet.css` 의 transition 과 같은 값이어야 한다. */
const CLOSE_MS = 240;
/** 손을 뗐을 때 이만큼 내려와 있으면 닫는다. */
const CLOSE_DISTANCE = 88;
/** 조금만 끌었어도 이 속도(px/ms)보다 빠르게 튕겨 내리면 닫는다. */
const FLING_VELOCITY = 0.5;
/** 이보다 덜 움직였으면 끌기가 아니라 탭이다. */
const TAP_SLOP = 4;

/** 시트의 지금 상태. `dragging` 동안에는 CSS 전환을 끄고 손가락을 그대로 따라간다. */
type SheetPhase = 'idle' | 'dragging' | 'closing';

type Drag = {
  pointerId: number;
  /** 누른 지점. 내려온 거리는 항상 이 값에서 잰다(중간 값을 누적하면 오차가 쌓인다). */
  startY: number;
  lastY: number;
  lastTime: number;
  /** 지금 내려와 있는 거리(px). 위로는 끌리지 않아 0 이상이다. */
  offset: number;
  /** 마지막 두 지점으로 잰 속도(px/ms). 손을 뗄 때 튕김 판정에 쓴다. */
  velocity: number;
};

/** 움직임을 줄여 달라고 한 사용자에게는 내려가는 모습 없이 즉시 닫는다. */
function prefersReducedMotion() {
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches;
}

export function useBottomSheet(onClose: () => void) {
  const panelRef = useRef<HTMLElement | null>(null);
  const dragRef = useRef<Drag | null>(null);

  const [phase, setPhase] = useState<SheetPhase>('idle');
  /**
   * 같은 값을 ref 로도 들고 있는다 — 포인터 이벤트 처리기는 state 가 반영되기 전에도
   * 지금 상태를 정확히 알아야 한다(이미 닫는 중이면 새 끌기를 받지 않는다).
   */
  const phaseRef = useRef<SheetPhase>('idle');

  /** 끌어서 닫은 직후 따라오는 click 을 한 번 흘려보낸다 — 끌기는 탭이 아니다. */
  const skipClickRef = useRef(false);
  /** `onClose` 는 한 번만 부른다 — transitionend 와 보험 타이머가 같이 도착할 수 있다. */
  const closedRef = useRef(false);

  /* 시트를 띄운 쪽이 대개 인라인 화살표를 넘겨 매번 새 함수다 — 값만 최신으로 들고 있는다. */
  const onCloseRef = useRef(onClose);
  useEffect(() => {
    onCloseRef.current = onClose;
  });

  const goTo = useCallback((next: SheetPhase) => {
    phaseRef.current = next;
    setPhase(next);
  }, []);

  const finishClose = useCallback(() => {
    if (closedRef.current) return;
    closedRef.current = true;
    onCloseRef.current();
  }, []);

  /**
   * 닫기 시작. 딤 · 핸들 · Esc · 끌어내림이 모두 이 하나를 부른다.
   * 실제 `onClose` 는 내려가는 전환이 끝난 뒤(또는 보험 타이머에서) 불린다.
   */
  const requestClose = useCallback(() => {
    if (phaseRef.current === 'closing') return;
    dragRef.current = null;

    if (panelRef.current === null || prefersReducedMotion()) {
      finishClose();
      return;
    }

    goTo('closing');
  }, [finishClose, goTo]);

  /*
   * 시트를 실제로 움직이는 곳.
   *
   * 끄는 동안에는 `data-dragging` 이 전환을 꺼 두므로, 여기서 transform 을 바꾸려면
   * **그 속성이 지워진 화면이 한 번 그려진 뒤**여야 한다. 그래서 포인터 처리기에서 바로
   * 손대지 않고 상태가 바뀐 뒤인 이 effect 에서 손댄다 — 그러지 않으면 되돌아가는 것도
   * 내려가는 것도 전환 없이 순간이동한다.
   */
  useEffect(() => {
    const panel = panelRef.current;
    if (panel === null) return;

    if (phase === 'closing') {
      panel.style.transform = 'translateY(100%)';
      /* 다른 탭에 가 있는 등으로 transitionend 가 오지 않아도 시트는 반드시 닫힌다. */
      const timer = window.setTimeout(finishClose, CLOSE_MS + 80);
      return () => window.clearTimeout(timer);
    }

    /* 손을 뗀 자리에서 제자리로 되돌아간다(전환은 CSS 가 건다). */
    if (phase === 'idle') panel.style.transform = '';
    return undefined;
  }, [phase, finishClose]);

  /* Esc 로 닫기 — 시트가 떠 있는 동안에만 듣는다. */
  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') requestClose();
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [requestClose]);

  /* 시트가 떠 있는 동안 뒤 페이지가 같이 스크롤되지 않게 막는다. */
  useEffect(() => {
    const { overflow } = document.body.style;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = overflow;
    };
  }, []);

  const handlePointerDown = (event: ReactPointerEvent<HTMLElement>) => {
    if (phaseRef.current === 'closing') return;
    // 마우스는 왼쪽 버튼만 끌기로 본다.
    if (event.pointerType === 'mouse' && event.button !== 0) return;

    /* 앞선 끌기가 click 없이 끝났더라도 이번 탭까지 삼키지 않게 여기서 지운다. */
    skipClickRef.current = false;
    dragRef.current = {
      pointerId: event.pointerId,
      startY: event.clientY,
      lastY: event.clientY,
      lastTime: event.timeStamp,
      offset: 0,
      velocity: 0,
    };
    /* 손가락이 핸들 밖으로 나가도 계속 따라오게 한다. */
    event.currentTarget.setPointerCapture(event.pointerId);
    goTo('dragging');
  };

  const handlePointerMove = (event: ReactPointerEvent<HTMLElement>) => {
    const drag = dragRef.current;
    if (drag === null || drag.pointerId !== event.pointerId) return;

    const elapsed = event.timeStamp - drag.lastTime;
    if (elapsed > 0) drag.velocity = (event.clientY - drag.lastY) / elapsed;
    drag.lastY = event.clientY;
    drag.lastTime = event.timeStamp;
    /* 위로는 끌리지 않는다 — 올리면 시트 아래에 빈 자리가 생긴다. */
    drag.offset = Math.max(0, event.clientY - drag.startY);

    /*
     * state 가 아니라 DOM 을 직접 만진다 — 손가락이 움직일 때마다 시트 전체를 다시 그리면
     * 목록이 긴 시트(라운지 채팅 등)에서 눈에 띄게 늦게 따라온다.
     */
    const panel = panelRef.current;
    if (panel !== null) panel.style.transform = `translateY(${drag.offset}px)`;
  };

  const handlePointerUp = (event: ReactPointerEvent<HTMLElement>) => {
    const drag = dragRef.current;
    if (drag === null || drag.pointerId !== event.pointerId) return;

    dragRef.current = null;
    if (event.currentTarget.hasPointerCapture(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId);
    }
    /* 끌었으면 뒤따르는 click 은 탭이 아니다 — 되돌아온 시트를 닫아 버리지 않게 막는다. */
    skipClickRef.current = drag.offset > TAP_SLOP;

    const flung = drag.offset > TAP_SLOP && drag.velocity > FLING_VELOCITY;
    if (drag.offset > CLOSE_DISTANCE || flung) {
      requestClose();
      return;
    }

    goTo('idle');
  };

  /* 브라우저가 제스처를 가져가면 닫지 않고 제자리로 되돌린다. */
  const handlePointerCancel = (event: ReactPointerEvent<HTMLElement>) => {
    const drag = dragRef.current;
    if (drag === null || drag.pointerId !== event.pointerId) return;

    dragRef.current = null;
    skipClickRef.current = false;
    goTo('idle');
  };

  /** 핸들을 그냥 누르면(끌지 않으면) 닫는다 — 예전부터 있던 동작이다. */
  const handleClick = () => {
    if (skipClickRef.current) {
      skipClickRef.current = false;
      return;
    }
    requestClose();
  };

  const handleTransitionEnd = (event: ReactTransitionEvent<HTMLElement>) => {
    /* 시트 안쪽 요소들의 전환이 올라오는 것은 무시한다. */
    if (event.target !== event.currentTarget) return;
    if (event.propertyName !== 'transform') return;
    if (phaseRef.current !== 'closing') return;
    finishClose();
  };

  const closingAttribute = phase === 'closing' ? '' : undefined;

  return {
    /** 애니메이션을 거쳐 닫는다. 시트 안에서 따로 닫는 버튼을 둘 때 쓴다. */
    requestClose,
    /** 시트를 감싼 바깥 요소 — 딤을 함께 걷기 위해 상태만 내려 준다. */
    rootProps: { 'data-closing': closingAttribute },
    dimProps: { onClick: requestClose },
    panelProps: {
      ref: panelRef,
      'data-dragging': phase === 'dragging' ? '' : undefined,
      'data-closing': closingAttribute,
      onTransitionEnd: handleTransitionEnd,
    },
    handleProps: {
      onPointerDown: handlePointerDown,
      onPointerMove: handlePointerMove,
      onPointerUp: handlePointerUp,
      onPointerCancel: handlePointerCancel,
      onClick: handleClick,
    },
  };
}
