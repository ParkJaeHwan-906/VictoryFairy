import { useBottomSheet } from '../hooks/useBottomSheet';
import type { ShareSticker } from '../utils/shareStickers';
import '../styles/bottomSheet.css';
import '../styles/StickerShareSheet.css';

/**
 * StickerShareSheet — 고른 스티커를 크게 보여 주고 인스타 스토리로 넘기는 바텀시트.
 * Figma: SWM / [Sticker] 승요 스티커 - 캐릭터 스티커 복사화면 (node 1733:28405)
 *
 * 네 탭(캐릭터 · 에셋 · 텍스트 · 숫자)의 복사 화면은 위 작은 제목과 그림 · 문구만 다르고
 * 뼈대가 같아 하나로 둔다.
 *
 * 버튼 글은 디자인대로 **복사하기**다. 실제로 하는 일은 스티커를 앱에 넘겨 인스타 스토리
 * 편집기를 여는 것이고(iOS 는 붙여넣기판을 거친다), 넘기는 일과 결과 처리는 화면
 * (`StorySharePage`)이 맡는다 — 시트는 누른 것만 알린다.
 */

type StickerShareSheetProps = {
  /** 맨 위 작은 제목. 예: `캐릭터 스티커` */
  title: string;
  sticker: ShareSticker;
  /** 앱에 넘긴 뒤 답을 기다리는 중. 복사하기를 잠가 연타를 막는다. */
  isSharing: boolean;
  /** 넘기지 못한 이유. 문구 아래 한 줄로 띄운다. */
  message: string | null;
  onShare: () => void;
  /** 딤 · 핸들 · Esc · 취소로 닫을 때 호출된다. */
  onClose: () => void;
};

export default function StickerShareSheet({
  title,
  sticker,
  isSharing,
  message,
  onShare,
  onClose,
}: StickerShareSheetProps) {
  const sheet = useBottomSheet(onClose);

  return (
    <div className="sticker-share-sheet bottom-sheet-root" {...sheet.rootProps}>
      <button
        className="sticker-share-sheet__dim bottom-sheet-dim"
        type="button"
        {...sheet.dimProps}
        aria-label={`${title} 닫기`}
      />

      <section
        className="sticker-share-sheet__panel bottom-sheet-panel"
        role="dialog"
        aria-modal="true"
        aria-label={sticker.name}
        {...sheet.panelProps}
      >
        {/* 잡아서 아래로 끌면 닫힌다. 그냥 누르기만 해도 닫힌다. */}
        <button
          className="sticker-share-sheet__handle bottom-sheet-handle"
          type="button"
          {...sheet.handleProps}
        >
          <span className="sticker-share-sheet__handle-bar" aria-hidden="true" />
          <span className="sticker-share-sheet__sr-only">{title} 닫기</span>
        </button>

        <p className="sticker-share-sheet__eyebrow">{title}</p>

        <div className="sticker-share-sheet__preview">
          <img className="sticker-share-sheet__image" src={sticker.src} alt="" />
        </div>

        <div className="sticker-share-sheet__body">
          <h2 className="sticker-share-sheet__name">{sticker.name}</h2>
          <p className="sticker-share-sheet__description">
            {sticker.description.map((line) => (
              <span key={line} className="sticker-share-sheet__description-line">
                {line}
              </span>
            ))}
          </p>

          {message && (
            <p className="sticker-share-sheet__message" role="status">
              {message}
            </p>
          )}
        </div>

        <div className="sticker-share-sheet__actions">
          <button
            className="sticker-share-sheet__button sticker-share-sheet__button--cancel"
            type="button"
            onClick={sheet.requestClose}
          >
            취소
          </button>
          <button
            className="sticker-share-sheet__button sticker-share-sheet__button--confirm"
            type="button"
            onClick={onShare}
            disabled={isSharing}
          >
            {isSharing ? '여는 중' : '복사하기'}
          </button>
        </div>
      </section>
    </div>
  );
}
