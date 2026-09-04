import { Link, useNavigate } from 'react-router-dom';
import { NOTICES, formatNoticeDate, isNewNotice } from '../data/notices';
import { noticeDetailPath } from '../routes';
import '../styles/NoticePage.css';

/**
 * NoticePage — 공지사항 목록.
 * Figma: SWM / [My] 공지사항 (node 1365:17785)
 *
 * 마이페이지 "센터 > 공지사항"으로 들어와 뒤로가기로 돌아간다. 글은 서버가 아니라
 * `data/notices.ts` 에 붙박이로 있다 — 그렇게 둔 이유는 그 파일 머리말에 적어 두었다.
 */
export default function NoticePage() {
  const navigate = useNavigate();

  return (
    <div className="notice-page">
      <header className="notice-page__topbar">
        <button
          className="notice-page__back"
          type="button"
          onClick={() => navigate(-1)}
          aria-label="뒤로 가기"
        >
          <span className="notice-page__back-icon" aria-hidden="true" />
        </button>
        <h1 className="notice-page__topbar-title">공지사항</h1>
        {/* 디자인상 opacity 0 인 빈 버튼 — 타이틀을 가운데 두기 위한 자리 */}
        <span className="notice-page__topbar-spacer" aria-hidden="true" />
      </header>

      <ul className="notice-page__list">
        {NOTICES.map((notice) => (
          <li key={notice.id}>
            <Link className="notice-page__item" to={noticeDetailPath(notice.id)}>
              <p className="notice-page__item-title">{notice.title}</p>
              <p className="notice-page__item-meta">
                <span>{formatNoticeDate(notice.publishedAt)}</span>
                {/*
                  글자 N 이 아니라 "새 글"이라는 표식이다 — 그대로 읽어 주면 날짜 뒤에
                  뜻 없는 "엔"이 붙으므로 배지는 감추고 대신 읽을 말을 붙인다.
                */}
                {isNewNotice(notice.publishedAt) && (
                  <span className="notice-page__item-new">
                    <span aria-hidden="true">N</span>
                    <span className="notice-page__sr-only">새 공지</span>
                  </span>
                )}
              </p>
            </Link>
          </li>
        ))}
      </ul>
    </div>
  );
}
