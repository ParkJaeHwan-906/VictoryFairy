import { Link } from 'react-router-dom';
import { toAssetUrl } from '../api';
import type { QuizRoomSummary } from '../api';
import { quizRoomPath } from '../routes';
import { fallbackToPlaceholder, profilePlaceholder } from '../utils/avatar';
import { formatCommunityRelativeTime } from '../utils/community';
import '../styles/QuizRoomCard.css';

type QuizRoomCardProps = {
  room: QuizRoomSummary;
  /**
   * 인기 묶음에서의 순위(1부터). 전체 목록의 카드에는 없다.
   * 서버가 순위 필드를 주지 않아 **배열 순서 + 1** 을 그대로 넘겨받는다(재정렬 금지).
   */
  rank?: number;
};

/**
 * QuizRoomCard — 라운지 퀴즈방 탭의 방 한 장. 인기 묶음과 전체 목록이 함께 쓴다.
 *
 * 디자인 시안이 없는 화면이라 게시판 글 한 줄(`CommunityPostCard`)의 글자 위계를 따른다:
 * 제목 Body1 Medium · 소개/메타 Label2 · 숫자 Caption. 게시판과 달리 썸네일이 없고, 대신
 * 소개 두 줄과 작성자 사진이 들어간다(작성자가 곧 출제자라 누구 문제인지가 고르는 이유가 된다).
 *
 * 숫자 셋은 **응답 시점 값**이다 — 특히 `solvingCount` 는 Redis 장애 시 0 으로 채워 오므로
 * 0 을 따로 꾸미지 않고 그대로 적는다(실패처럼 보이면 안 된다).
 */
export default function QuizRoomCard({ room, rank }: QuizRoomCardProps) {
  const isRanked = rank !== undefined;

  return (
    <li className={`quiz-room-card${isRanked ? ' quiz-room-card--popular' : ''}`}>
      <Link className="quiz-room-card__body" to={quizRoomPath(room.roomId)}>
        {isRanked && (
          <span className="quiz-room-card__rank">
            <span className="quiz-room-card__sr-only">주간 인기</span>
            {rank}
            <span className="quiz-room-card__sr-only">위</span>
          </span>
        )}

        <div className="quiz-room-card__main">
          <p className="quiz-room-card__title">{room.title}</p>
          {/* 소개는 최대 500자라 카드에서는 두 줄로 자른다(CSS line-clamp). 전문은 상세에서 본다. */}
          <p className="quiz-room-card__description">{room.description}</p>

          <div className="quiz-room-card__meta">
            <img
              className="quiz-room-card__avatar"
              src={toAssetUrl(room.author.profileImgUrl) ?? profilePlaceholder}
              onError={fallbackToPlaceholder}
              alt=""
              loading="lazy"
            />
            <span className="quiz-room-card__author">{room.author.nickname}</span>
            <span aria-hidden="true">·</span>
            <time dateTime={room.createdAt}>{formatCommunityRelativeTime(room.createdAt)}</time>
          </div>

          <p className="quiz-room-card__stats">
            <span className="quiz-room-card__stat quiz-room-card__stat--quiz">
              <span className="quiz-room-card__stat-icon quiz-room-card__stat-icon--quiz" aria-hidden="true" />
              {room.quizCount}문항
            </span>
            <span className="quiz-room-card__stat quiz-room-card__stat--like">
              <span className="quiz-room-card__stat-icon quiz-room-card__stat-icon--like" aria-hidden="true" />
              <span className="quiz-room-card__sr-only">추천</span>
              {room.likeCount}
            </span>
            <span className="quiz-room-card__stat quiz-room-card__stat--solving">
              <span className="quiz-room-card__stat-icon quiz-room-card__stat-icon--solving" aria-hidden="true" />
              풀이 중 {room.solvingCount}명
            </span>
          </p>
        </div>
      </Link>
    </li>
  );
}
