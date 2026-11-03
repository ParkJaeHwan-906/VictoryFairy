import type { CommunityReaction } from '../api';
import '../styles/CommunityReactionButtons.css';

type CommunityReactionButtonsProps = {
  myReaction: CommunityReaction | null;
  likeCount: number;
  dislikeCount: number;
  /** 누른 버튼. 같은 버튼을 다시 누른 경우(취소)도 그대로 넘긴다 — `NONE` 변환은 부르는 쪽 몫. */
  onPress: (pressed: CommunityReaction) => void;
  disabled?: boolean;
  /** `large` 는 글 본문 아래 가운데, `small` 은 댓글 줄 안. */
  size?: 'large' | 'small';
};

/**
 * CommunityReactionButtons — 추천 · 비추천 두 버튼.
 *
 * 눌린 쪽은 주황 테두리 · 주황 글자로 바뀐다(`aria-pressed`). 비추천은 같은 엄지 그림을
 * 뒤집어 쓴다 — 저장소에 내린 엄지 아이콘이 따로 없다.
 */
export default function CommunityReactionButtons({
  myReaction,
  likeCount,
  dislikeCount,
  onPress,
  disabled = false,
  size = 'large',
}: CommunityReactionButtonsProps) {
  return (
    <div className={`community-reaction community-reaction--${size}`}>
      <button
        className={`community-reaction__button${myReaction === 'LIKE' ? ' community-reaction__button--active' : ''}`}
        type="button"
        aria-pressed={myReaction === 'LIKE'}
        onClick={() => onPress('LIKE')}
        disabled={disabled}
      >
        <span className="community-reaction__icon" aria-hidden="true" />
        <span className="community-reaction__sr-only">추천</span>
        {likeCount}
      </button>
      <button
        className={`community-reaction__button${
          myReaction === 'DISLIKE' ? ' community-reaction__button--active' : ''
        }`}
        type="button"
        aria-pressed={myReaction === 'DISLIKE'}
        onClick={() => onPress('DISLIKE')}
        disabled={disabled}
      >
        <span className="community-reaction__icon community-reaction__icon--down" aria-hidden="true" />
        <span className="community-reaction__sr-only">비추천</span>
        {dislikeCount}
      </button>
    </div>
  );
}
