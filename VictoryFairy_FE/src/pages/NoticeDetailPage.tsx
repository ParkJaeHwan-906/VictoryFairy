import { Navigate, useNavigate, useParams } from 'react-router-dom';
import { findNotice, formatNoticeDate } from '../data/notices';
import { ROUTES } from '../routes';
import '../styles/NoticeDetailPage.css';

/**
 * NoticeDetailPage — 공지사항 한 건.
 * Figma: SWM / [My] 공지사항-게시물 (node 1430:17961)
 *
 * 목록에서 글을 눌러 들어온다. 글은 `data/notices.ts` 에 붙박이라 로딩도 실패도 없고,
 * 없는 주소로 들어오는 경우만 다루면 된다.
 */
export default function NoticeDetailPage() {
  const navigate = useNavigate();
  const { noticeId } = useParams();
  const notice = noticeId === undefined ? undefined : findNotice(noticeId);

  /*
   * 지워졌거나 주소를 잘못 친 글. 빈 화면을 보여 주는 대신 목록으로 돌려보낸다.
   * `replace` 라 뒤로가기를 눌렀을 때 없는 글로 다시 들어오지 않는다.
   */
  if (notice === undefined) return <Navigate to={ROUTES.notice} replace />;

  return (
    <div className="notice-detail-page">
      <header className="notice-detail-page__topbar">
        <button
          className="notice-detail-page__back"
          type="button"
          onClick={() => navigate(-1)}
          aria-label="뒤로 가기"
        >
          <span className="notice-detail-page__back-icon" aria-hidden="true" />
        </button>
        {/*
          디자인은 여기에도 "공지사항"을 두고 글 제목은 본문 첫 줄에 크게 놓는다 —
          제목 역할(h1)은 그 큰 글자 쪽이라, 상단 바는 그냥 현재 위치를 알리는 글로 둔다.
        */}
        <p className="notice-detail-page__topbar-title">공지사항</p>
        {/* 디자인상 opacity 0 인 빈 버튼 — 타이틀을 가운데 두기 위한 자리 */}
        <span className="notice-detail-page__topbar-spacer" aria-hidden="true" />
      </header>

      <div className="notice-detail-page__head">
        <h1 className="notice-detail-page__title">{notice.title}</h1>
        <p className="notice-detail-page__date">{formatNoticeDate(notice.publishedAt)}</p>
      </div>

      {/* 덩어리 사이 간격이 디자인의 빈 줄을 대신한다(`NoticeBlock` 주석 참고) */}
      <article className="notice-detail-page__body">
        {notice.body.map((block, index) =>
          block.type === 'paragraph' ? (
            <p className="notice-detail-page__paragraph" key={index}>
              {block.text}
            </p>
          ) : (
            <div className="notice-detail-page__list-block" key={index}>
              {block.title !== undefined && (
                <p className="notice-detail-page__list-title">{block.title}</p>
              )}
              <ul className="notice-detail-page__list">
                {block.items.map((item) => (
                  <li key={item}>{item}</li>
                ))}
              </ul>
            </div>
          ),
        )}
      </article>
    </div>
  );
}
