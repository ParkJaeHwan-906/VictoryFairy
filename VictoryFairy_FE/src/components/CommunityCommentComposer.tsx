import { useEffect, useRef, useState, type FormEvent } from 'react';
import { COMMUNITY_COMMENT_CONTENT_MAX_LENGTH, COMMUNITY_COMMENT_IMAGE_MAX } from '../api';
import type { CommunityComment, CommunityReply } from '../api';
import { useCommunityImageUploads } from '../hooks/useCommunityImageUploads';
import CommunityImagePicker, { CommunityImageFileButton } from './CommunityImagePicker';
import '../styles/CommunityCommentComposer.css';

/**
 * 입력창이 지금 무엇을 쓰고 있는지.
 * - `new` 새 최상위 댓글 · `reply` 그 댓글의 답글 · `edit` 그 댓글(또는 답글) 고치기
 */
export type CommunityComposerMode =
  | { type: 'new' }
  | { type: 'reply'; parent: CommunityComment }
  | { type: 'edit'; target: CommunityReply };

type CommunityCommentComposerProps = {
  mode: CommunityComposerMode;
  /** 답글·수정을 그만두고 새 댓글로 돌아간다. */
  onCancelMode: () => void;
  /** 보내기. 실패하면 던진다 — 입력은 그대로 두고 받은 문구를 위에 띄운다. */
  onSubmit: (content: string, imageUrls: string[]) => Promise<void>;
  /** 실패를 화면 문구로 옮긴다. */
  describeError: (error: unknown) => string;
  /** 글이 숨겨졌거나 없어져 더는 쓸 수 없을 때. */
  disabled?: boolean;
};

/**
 * CommunityCommentComposer — 글 상세 아래에 붙는 댓글 입력창.
 *
 * 라운지 채팅 입력창(`.lounge-chat__composer`)과 같은 모양이고, 그 위에 답글 · 수정 중임을
 * 알리는 띠와 첨부 사진 줄이 얹힌다.
 *
 * ── 계약 ──────────────────────────────────────────────────────────────
 * - 답글은 따로 경로가 없고 `parentCommentId` 를 실어 보내는 댓글이다(깊이 1).
 * - 수정은 **전체 교체** — 남길 기존 사진 EP 도 `imageUrls` 에 다시 실어야 한다.
 *   그래서 수정 모드로 들어오면 기존 사진을 첨부 줄에 그대로 채워 둔다.
 * - 사진은 댓글당 3장, 내용은 1000자까지.
 * ──────────────────────────────────────────────────────────────────────
 */
export default function CommunityCommentComposer({
  mode,
  onCancelMode,
  onSubmit,
  describeError,
  disabled = false,
}: CommunityCommentComposerProps) {
  const [draft, setDraft] = useState('');
  const [isSending, setIsSending] = useState(false);
  const [sendError, setSendError] = useState<string | null>(null);
  const images = useCommunityImageUploads(COMMUNITY_COMMENT_IMAGE_MAX);
  const inputRef = useRef<HTMLTextAreaElement>(null);

  const { reset: resetImages } = images;
  const editTarget = mode.type === 'edit' ? mode.target : null;
  const modeKey =
    mode.type === 'new' ? 'new' : `${mode.type}-${mode.type === 'reply' ? mode.parent.commentId : mode.target.commentId}`;

  /*
   * 모드가 바뀌면 입력을 갈아 끼운다 — 수정이면 그 댓글의 내용 · 사진으로, 아니면 비운다.
   * 답글로 바꿀 때는 쓰던 글을 남겨 둔다(같은 글을 답글로 옮겨 달려는 경우가 흔하다).
   */
  /*
   * 의존값은 모두 모드가 바뀔 때만 바뀐다 — `editTarget` 은 모드 state 가 쥔 객체라 댓글
   * 목록을 다시 받아도 그대로이고, `resetImages` 는 고정 참조다. 그래서 같은 모드에서 다시
   * 그려지는 것만으로 쓰던 글이 덮이지 않는다.
   */
  const modeType = mode.type;
  const previousModeTypeRef = useRef(modeType);
  useEffect(() => {
    const wasEdit = previousModeTypeRef.current === 'edit';
    previousModeTypeRef.current = modeType;

    if (editTarget !== null) {
      setDraft(editTarget.content ?? '');
      resetImages(editTarget.imageUrls);
    } else if (wasEdit) {
      setDraft('');
      resetImages();
    }
    setSendError(null);
    if (modeType !== 'new') inputRef.current?.focus();
  }, [modeKey, modeType, editTarget, resetImages]);

  /* 내용 길이에 맞춰 입력창 높이를 늘인다(최대 높이는 CSS 가 막는다). */
  useEffect(() => {
    const input = inputRef.current;
    if (input === null) return;
    input.style.height = 'auto';
    input.style.height = `${input.scrollHeight}px`;
  }, [draft]);

  const trimmed = draft.trim();
  const canSend = !disabled && !isSending && !images.isUploading && trimmed.length > 0;

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!canSend) return;

    setIsSending(true);
    setSendError(null);

    try {
      await onSubmit(trimmed, images.endpoints);
      setDraft('');
      resetImages();
    } catch (cause: unknown) {
      setSendError(describeError(cause));
    } finally {
      setIsSending(false);
    }
  };

  const placeholder = disabled
    ? '댓글을 쓸 수 없는 게시글이에요'
    : mode.type === 'reply'
      ? '답글을 입력해주세요'
      : '댓글을 입력해주세요';

  return (
    <div className="community-composer">
      {mode.type !== 'new' && (
        <div className="community-composer__mode">
          <p className="community-composer__mode-text">
            {mode.type === 'reply' ? (
              <>
                <strong>{mode.parent.author?.nickname ?? '댓글'}</strong>님에게 답글 남기는 중
              </>
            ) : (
              '댓글 수정 중'
            )}
          </p>
          <button
            className="community-composer__mode-cancel"
            type="button"
            onClick={onCancelMode}
            aria-label={mode.type === 'reply' ? '답글 취소' : '수정 취소'}
          >
            <span className="community-composer__mode-cancel-icon" aria-hidden="true" />
          </button>
        </div>
      )}

      {(sendError ?? images.error) !== null && (
        <p className="community-composer__error" role="alert">
          {sendError ?? images.error}
        </p>
      )}

      <div className="community-composer__images">
        <CommunityImagePicker
          items={images.items}
          maxCount={COMMUNITY_COMMENT_IMAGE_MAX}
          canAddMore={images.canAddMore}
          onAddFiles={images.addFiles}
          onRemove={images.remove}
          size="small"
          hideAddTile
          disabled={isSending}
        />
      </div>

      <form className="community-composer__form" onSubmit={(event) => void handleSubmit(event)}>
        <CommunityImageFileButton
          className="community-composer__photo"
          label={`사진 첨부 (${images.items.length}/${COMMUNITY_COMMENT_IMAGE_MAX})`}
          onAddFiles={images.addFiles}
          disabled={disabled || isSending || !images.canAddMore}
        />
        <textarea
          ref={inputRef}
          className="community-composer__input"
          rows={1}
          value={draft}
          onChange={(event) => {
            setDraft(event.target.value);
            setSendError(null);
          }}
          placeholder={placeholder}
          aria-label={mode.type === 'reply' ? '답글 입력' : '댓글 입력'}
          maxLength={COMMUNITY_COMMENT_CONTENT_MAX_LENGTH}
          disabled={disabled}
        />
        <button
          className="community-composer__send"
          type="submit"
          aria-label={mode.type === 'edit' ? '댓글 수정하기' : '댓글 보내기'}
          disabled={!canSend}
        >
          <span className="community-composer__send-icon" aria-hidden="true" />
        </button>
      </form>
    </div>
  );
}
