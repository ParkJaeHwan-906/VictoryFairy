import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { toAssetUrl } from '../api';
import type { CommunityPostSummary } from '../api';
import { communityPostPath } from '../routes';
import { formatCommunityRelativeTime } from '../utils/community';
import '../styles/CommunityPostCard.css';

type CommunityPostCardProps = {
  post: CommunityPostSummary;
  /**
   * 신고로 숨겨진 글(내 게시글 목록에만 온다). 상세가 410 이라 **눌러 들어갈 수 없다** —
   * 링크 대신 그냥 줄로 그리고 숨김 표식을 붙인다.
   */
  blinded?: boolean;
  /** 줄 오른쪽 아래에 덧붙일 조작(내 게시글의 삭제 등). 링크 밖에 놓인다. */
  action?: ReactNode;
};

/**
 * CommunityPostCard — 게시판 · 내 게시글이 함께 쓰는 글 한 줄.
 *
 * 디자인 시안이 없는 화면이라 공지사항 목록(`NoticePage`)의 글 한 줄과 라운지 랭킹 줄의
 * 글자 위계를 그대로 따른다: 제목 Body1 Medium · 메타 Label2 · 보조 Caption.
 * 목록 응답에는 본문이 없어(docs/community.md) 제목 · 첫 이미지 · 숫자만 그린다.
 */
export default function CommunityPostCard({ post, blinded = false, action }: CommunityPostCardProps) {
  const thumbnail = toAssetUrl(post.thumbnailUrl);

  const body = (
    <>
      <div className="community-post-card__main">
        <p className="community-post-card__category">
          {post.categoryName}
          {blinded && <span className="community-post-card__blinded">신고로 숨겨짐</span>}
        </p>
        <p className="community-post-card__title">{post.title}</p>
        <p className="community-post-card__meta">
          <span className="community-post-card__author">{post.author.nickname}</span>
          <span aria-hidden="true">·</span>
          <time dateTime={post.createdAt}>{formatCommunityRelativeTime(post.createdAt)}</time>
          <span aria-hidden="true">·</span>
          <span>조회 {post.viewCount}</span>
        </p>
        <p className="community-post-card__stats">
          <span className="community-post-card__stat">
            <span
              className="community-post-card__stat-icon community-post-card__stat-icon--like"
              aria-hidden="true"
            />
            <span className="community-post-card__sr-only">추천</span>
            {post.likeCount}
          </span>
          <span className="community-post-card__stat">
            <span
              className="community-post-card__stat-icon community-post-card__stat-icon--comment"
              aria-hidden="true"
            />
            <span className="community-post-card__sr-only">댓글</span>
            {post.commentCount}
          </span>
        </p>
      </div>

      {thumbnail !== null && (
        <img className="community-post-card__thumbnail" src={thumbnail} alt="" loading="lazy" />
      )}
    </>
  );

  return (
    <li className={`community-post-card${blinded ? ' community-post-card--blinded' : ''}`}>
      {blinded ? (
        <div className="community-post-card__body">{body}</div>
      ) : (
        <Link className="community-post-card__body" to={communityPostPath(post.postId)}>
          {body}
        </Link>
      )}

      {action !== undefined && <div className="community-post-card__action">{action}</div>}
    </li>
  );
}
