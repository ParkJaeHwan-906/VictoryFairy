import { useBottomSheet } from '../hooks/useBottomSheet';
import '../styles/bottomSheet.css';
import '../styles/ConfirmSheet.css';

/**
 * ConfirmSheet — 되돌리기 어려운 동작을 한 번 더 묻는 확인 바텀시트.
 * Figma: SWM / [My] 로그아웃 확인 (node 1443:14171) · [My] 회원탈퇴 확인 (node 1443:14481)
 *
 * 두 디자인은 글만 다르고 뼈대가 같다 — 제목 한 줄, 설명 두 줄, 그리고 `취소 | 실행`
 * 두 칸 버튼. 그래서 시트를 각각 만들지 않고 문구만 받는 하나로 둔다.
 *
 * 닫기는 다른 시트들과 같다(딤 · 핸들 · Esc · 끌어내림 — `useBottomSheet`).
 * **취소도 결국 닫기**라, 취소 버튼은 `requestClose` 를 그대로 부른다 — 내려가는 모습 없이
 * 사라지면 딤을 눌러 닫을 때와 달라 보인다.
 */

type ConfirmSheetProps = {
  /** 큰 제목. 예: `로그아웃 하시겠어요?` */
  title: string;
  /**
   * 제목 아래 설명. **줄 단위로 받는다** — 디자인이 두 줄을 정해 끊어 두었고,
   * 한 문장으로 넘기면 화면 폭에 따라 끊기는 자리가 달라진다.
   */
  description: readonly string[];
  /** 실행 버튼의 글. 예: `로그아웃` · `회원 탈퇴` */
  confirmLabel: string;
  /** 실행 중일 때 실행 버튼에 대신 넣을 글. 없으면 `confirmLabel` 을 그대로 쓴다. */
  pendingLabel?: string;
  /** 응답을 기다리는 중. 두 버튼을 함께 잠가 연타와 중간 취소를 막는다. */
  isPending?: boolean;
  /** 실행 버튼을 눌렀을 때. 시트를 닫는 것은 부르는 쪽 몫이다(대개 화면이 바뀐다). */
  onConfirm: () => void;
  /** 딤 · 핸들 · Esc · 취소로 닫을 때 호출된다. */
  onClose: () => void;
};

export default function ConfirmSheet({
  title,
  description,
  confirmLabel,
  pendingLabel,
  isPending = false,
  onConfirm,
  onClose,
}: ConfirmSheetProps) {
  const sheet = useBottomSheet(onClose);

  return (
    <div className="confirm-sheet bottom-sheet-root" {...sheet.rootProps}>
      <button
        className="confirm-sheet__dim bottom-sheet-dim"
        type="button"
        {...sheet.dimProps}
        aria-label={`${title} 닫기`}
      />

      <section
        className="confirm-sheet__panel bottom-sheet-panel"
        role="dialog"
        aria-modal="true"
        aria-label={title}
        {...sheet.panelProps}
      >
        {/* 잡아서 아래로 끌면 닫힌다. 그냥 누르기만 해도 닫힌다. */}
        <button
          className="confirm-sheet__handle bottom-sheet-handle"
          type="button"
          {...sheet.handleProps}
        >
          <span className="confirm-sheet__handle-bar" aria-hidden="true" />
          <span className="confirm-sheet__sr-only">{title} 닫기</span>
        </button>

        <div className="confirm-sheet__body">
          <h2 className="confirm-sheet__title">{title}</h2>
          <p className="confirm-sheet__description">
            {description.map((line) => (
              <span key={line} className="confirm-sheet__description-line">
                {line}
              </span>
            ))}
          </p>
        </div>

        <div className="confirm-sheet__actions">
          <button
            className="confirm-sheet__button confirm-sheet__button--cancel"
            type="button"
            onClick={sheet.requestClose}
            disabled={isPending}
          >
            취소
          </button>
          <button
            className="confirm-sheet__button confirm-sheet__button--confirm"
            type="button"
            onClick={onConfirm}
            disabled={isPending}
          >
            {isPending ? (pendingLabel ?? confirmLabel) : confirmLabel}
          </button>
        </div>
      </section>
    </div>
  );
}
