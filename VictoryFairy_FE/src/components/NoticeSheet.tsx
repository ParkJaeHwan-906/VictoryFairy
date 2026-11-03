import { useEffect } from 'react';
import { useBottomSheet } from '../hooks/useBottomSheet';
import '../styles/bottomSheet.css';
import '../styles/NoticeSheet.css';

/**
 * NoticeSheet — 결과를 짧게 알리고 스스로 내려가는 안내 바텀시트.
 *
 * `ConfirmSheet` 와 달리 아무것도 묻지 않는다 — 방금 끝난 일(신고 접수 · 차단 완료 등)을
 * 알리기만 하므로 포커스를 가두는 `role="dialog"` 대신 `role="status"` + `aria-live="polite"`
 * 로 알린다. 취소 · 실행 버튼도 없다.
 *
 * `durationMs` 가 지나면 스스로 `requestClose` 를 불러 내려간다(다른 시트들과 같은
 * 240ms 내려가기 애니메이션을 그대로 탄다). 그 전에 사용자가 딤을 누르거나 핸들을
 * 당기거나 Esc 를 눌러 먼저 닫아도 된다 — `requestClose` 는 이미 닫는 중이면 아무것도
 * 하지 않으므로(`useBottomSheet` 의 `closedRef`/`phaseRef` 가드) 타이머와 겹쳐 불려도
 * 안전하다. 실제 부모 state 정리는 애니메이션이 끝난 뒤 오는 `onClose` 에서만 한다.
 */

const DEFAULT_DURATION_MS = 2000;

type NoticeSheetProps = {
  /** 보여줄 한 줄(또는 두 줄) 안내 문구. */
  message: string;
  /** 내려가는 애니메이션이 끝난 뒤 호출된다 — 이 시점에 부모가 state 를 정리한다. */
  onClose: () => void;
  /** 자동으로 닫히기까지의 시간(ms). 기본 2000. */
  durationMs?: number;
};

export default function NoticeSheet({
  message,
  onClose,
  durationMs = DEFAULT_DURATION_MS,
}: NoticeSheetProps) {
  const sheet = useBottomSheet(onClose);

  /*
   * durationMs 뒤 스스로 닫는다. `sheet.requestClose` 는 렌더마다 새로 만들어지지 않는
   * 안정된 참조라 의존성에 넣어도 타이머가 다시 걸리지 않는다.
   */
  useEffect(() => {
    const timer = window.setTimeout(sheet.requestClose, durationMs);
    return () => window.clearTimeout(timer);
  }, [durationMs, sheet.requestClose]);

  return (
    <div className="notice-sheet bottom-sheet-root" {...sheet.rootProps}>
      {/* 딤. 눌러 먼저 닫을 수 있지만 읽어 줄 내용은 없다. */}
      <button
        className="notice-sheet__dim bottom-sheet-dim"
        type="button"
        {...sheet.dimProps}
        aria-label="안내 닫기"
      />

      <section
        className="notice-sheet__panel bottom-sheet-panel"
        role="status"
        aria-live="polite"
        {...sheet.panelProps}
      >
        {/* 잡아서 아래로 끌거나 그냥 눌러도 닫힌다. */}
        <button
          className="notice-sheet__handle bottom-sheet-handle"
          type="button"
          {...sheet.handleProps}
        >
          <span className="notice-sheet__handle-bar" aria-hidden="true" />
          <span className="notice-sheet__sr-only">안내 닫기</span>
        </button>

        <p className="notice-sheet__message">{message}</p>
      </section>
    </div>
  );
}
