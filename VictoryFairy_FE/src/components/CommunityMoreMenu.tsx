import { useEffect, useRef, useState } from 'react';
import '../styles/CommunityMoreMenu.css';

export type CommunityMenuItem = {
  label: string;
  onSelect: () => void;
  /** 신고·삭제처럼 되돌리기 어려운 조작 — 글자색으로 한 번 더 알린다. */
  danger?: boolean;
};

type CommunityMoreMenuProps = {
  /** 트리거 버튼의 읽을 이름. 예: `게시글 더보기` */
  label: string;
  items: readonly CommunityMenuItem[];
  disabled?: boolean;
};

/**
 * CommunityMoreMenu — "⋯" 트리거와 그 아래 붙는 작은 팝오버 메뉴.
 *
 * 라운지 채팅 말풍선의 신고 · 차단 메뉴(`LoungeChatSheet` 의 `lounge-chat__menu`)와 같은
 * 모양 · 같은 닫기 규칙이다: 열려 있는 동안만 바깥 누르기 · Esc 를 감시해 닫는다.
 * 항목을 고르면 먼저 닫고 나서 `onSelect` 를 부른다 — 대개 확인 시트가 뒤따라 뜬다.
 */
export default function CommunityMoreMenu({ label, items, disabled = false }: CommunityMoreMenuProps) {
  const [isOpen, setIsOpen] = useState(false);
  const wrapRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!isOpen) return;

    const handlePointerDown = (event: PointerEvent) => {
      if (event.target instanceof Node && wrapRef.current?.contains(event.target)) return;
      setIsOpen(false);
    };
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setIsOpen(false);
    };

    document.addEventListener('pointerdown', handlePointerDown);
    document.addEventListener('keydown', handleKeyDown);
    return () => {
      document.removeEventListener('pointerdown', handlePointerDown);
      document.removeEventListener('keydown', handleKeyDown);
    };
  }, [isOpen]);

  if (items.length === 0) return null;

  return (
    <div className="community-more-menu" ref={wrapRef}>
      <button
        className="community-more-menu__trigger"
        type="button"
        aria-haspopup="menu"
        aria-expanded={isOpen}
        aria-label={label}
        onClick={() => setIsOpen((open) => !open)}
        disabled={disabled}
      >
        <span className="community-more-menu__glyph" aria-hidden="true" />
      </button>

      {isOpen && (
        <div className="community-more-menu__list" role="menu">
          {items.map((item) => (
            <button
              key={item.label}
              className={`community-more-menu__item${item.danger ? ' community-more-menu__item--danger' : ''}`}
              type="button"
              role="menuitem"
              onClick={() => {
                setIsOpen(false);
                item.onSelect();
              }}
            >
              {item.label}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
