import { useRef, type ChangeEvent } from 'react';
import { COMMUNITY_IMAGE_ACCEPT } from '../api';
import type { CommunityImageItem } from '../hooks/useCommunityImageUploads';
import '../styles/CommunityImagePicker.css';

type CommunityImagePickerProps = {
  items: readonly CommunityImageItem[];
  maxCount: number;
  canAddMore: boolean;
  onAddFiles: (files: File[]) => void;
  onRemove: (id: string) => void;
  /** `large` 는 글쓰기 화면, `small` 은 댓글 입력창 위에 붙는 줄이다. */
  size?: 'large' | 'small';
  /** 추가 칸을 숨긴다 — 댓글 입력창은 사진 버튼이 입력창 안에 따로 있다. */
  hideAddTile?: boolean;
  disabled?: boolean;
};

/**
 * CommunityImagePicker — 첨부 사진 줄(가로 스크롤). 사진마다 지우기 버튼, 끝에 추가 칸.
 *
 * 상태(업로드·EP)는 `useCommunityImageUploads` 가 들고, 여기는 그리기와 파일 선택창만 맡는다.
 */
export default function CommunityImagePicker({
  items,
  maxCount,
  canAddMore,
  onAddFiles,
  onRemove,
  size = 'large',
  hideAddTile = false,
  disabled = false,
}: CommunityImagePickerProps) {
  const inputRef = useRef<HTMLInputElement>(null);

  const handleChange = (event: ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(event.target.files ?? []);
    // 값을 비워야 같은 파일을 다시 골랐을 때도 change 가 온다(실패 후 재시도가 그렇다).
    event.target.value = '';
    if (files.length > 0) onAddFiles(files);
  };

  if (hideAddTile && items.length === 0) return null;

  return (
    <ul className={`community-image-picker community-image-picker--${size}`}>
      {!hideAddTile && (
        <li>
          <button
            className="community-image-picker__add"
            type="button"
            onClick={() => inputRef.current?.click()}
            disabled={disabled || !canAddMore}
            aria-label={`사진 추가 (${items.length}/${maxCount})`}
          >
            <span className="community-image-picker__add-icon" aria-hidden="true" />
            <span className="community-image-picker__count" aria-hidden="true">
              {items.length}/{maxCount}
            </span>
          </button>
          <input
            ref={inputRef}
            type="file"
            accept={COMMUNITY_IMAGE_ACCEPT}
            multiple
            onChange={handleChange}
            hidden
          />
        </li>
      )}

      {items.map((item, index) => (
        <li className="community-image-picker__item" key={item.id}>
          <img
            className={`community-image-picker__thumb${
              item.isUploading ? ' community-image-picker__thumb--uploading' : ''
            }`}
            src={item.previewUrl}
            alt={`첨부 사진 ${index + 1}`}
          />
          {item.isUploading && (
            <span className="community-image-picker__spinner" role="status">
              <span className="community-image-picker__sr-only">사진을 올리는 중</span>
            </span>
          )}
          <button
            className="community-image-picker__remove"
            type="button"
            onClick={() => onRemove(item.id)}
            disabled={disabled}
            aria-label={`첨부 사진 ${index + 1} 빼기`}
          >
            <span className="community-image-picker__remove-icon" aria-hidden="true" />
          </button>
        </li>
      ))}
    </ul>
  );
}

/** 입력창 안의 사진 버튼처럼 추가 칸 없이 선택창만 필요한 곳에서 쓴다. */
export function CommunityImageFileButton({
  onAddFiles,
  disabled,
  className,
  label,
}: {
  onAddFiles: (files: File[]) => void;
  disabled?: boolean;
  className: string;
  label: string;
}) {
  const inputRef = useRef<HTMLInputElement>(null);

  return (
    <>
      <button
        className={className}
        type="button"
        onClick={() => inputRef.current?.click()}
        disabled={disabled}
        aria-label={label}
      >
        <span className={`${className}-icon`} aria-hidden="true" />
      </button>
      <input
        ref={inputRef}
        type="file"
        accept={COMMUNITY_IMAGE_ACCEPT}
        multiple
        onChange={(event) => {
          const files = Array.from(event.target.files ?? []);
          event.target.value = '';
          if (files.length > 0) onAddFiles(files);
        }}
        hidden
      />
    </>
  );
}
