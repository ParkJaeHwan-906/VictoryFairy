import { Fragment, type ReactNode } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { POLICY_CONTACT_EMAIL, type PolicyBlock, type PolicyDocument } from '../data/policies';
import { ROUTES } from '../routes';
import '../styles/PolicyDocumentView.css';

/**
 * PolicyDocumentView — 약관 문서 한 건을 읽는 전체 화면.
 *
 * 개인정보 처리방침(`PrivacyPolicyPage`)과 서비스 이용약관(`TermsOfServicePage`)이 함께 쓴다.
 * 두 화면은 글만 다르고 생김새가 같아서, 페이지는 문서를 고르기만 하고 그리는 일은 여기서 한다.
 * 틀은 공지사항 글(`NoticeDetailPage`)을 따랐다 — 상단 바 · 제목 묶음 · 흰 바탕 본문.
 *
 * 글은 `data/policies.ts` 에 붙박이라 로딩도 실패도 없다.
 */

/** `2026-08-13` → `2026.08.13`. 공지사항 게시일과 같은 꼴이다. */
function formatEffectiveDate(date: string): string {
  return date.replaceAll('-', '.');
}

/**
 * 글 속의 문의처 메일 주소를 메일 링크로 바꾼다.
 *
 * 원문에서 주소는 "…as8542994@gmail.com으로 문의해…"처럼 조사와 붙어 있다 — 주소 문자열로
 * 잘라야 조사까지 링크에 딸려 가지 않는다.
 */
function linkContactEmail(text: string): ReactNode {
  const parts = text.split(POLICY_CONTACT_EMAIL);
  if (parts.length === 1) return text;

  return parts.map((part, index) => (
    <Fragment key={index}>
      {index > 0 && (
        <a className="policy-document__mail" href={`mailto:${POLICY_CONTACT_EMAIL}`}>
          {POLICY_CONTACT_EMAIL}
        </a>
      )}
      {part}
    </Fragment>
  ));
}

/** 앞머리의 굵은 말과 본문. 둘은 한 줄로 이어 읽힌다. */
function LeadText({ lead, text }: { lead?: string; text: string }) {
  return (
    <>
      {lead !== undefined && <strong className="policy-document__lead">{lead} </strong>}
      {linkContactEmail(text)}
    </>
  );
}

function Block({ block }: { block: PolicyBlock }) {
  if (block.type === 'list') {
    return (
      <ul className="policy-document__list">
        {block.items.map((item, index) => (
          <li key={index}>
            {typeof item === 'string' ? linkContactEmail(item) : <LeadText {...item} />}
          </li>
        ))}
      </ul>
    );
  }

  const className = block.strong
    ? 'policy-document__paragraph policy-document__paragraph--strong'
    : 'policy-document__paragraph';

  return (
    <p className={className}>
      <LeadText lead={block.lead} text={block.text} />
    </p>
  );
}

export default function PolicyDocumentView({ policy }: { policy: PolicyDocument }) {
  const navigate = useNavigate();
  const location = useLocation();

  /*
   * 약관은 로그인 없이도 열리는 화면이라 주소로 곧장 들어올 수 있다. 그때는 돌아갈 앞 화면이
   * 없으니(`location.key` 가 `default`) 홈으로 보낸다 — 홈도 로그인 없이 열린다.
   */
  const handleBack = () => {
    if (location.key !== 'default') navigate(-1);
    else navigate(ROUTES.main, { replace: true });
  };

  return (
    <div className="policy-document">
      <header className="policy-document__topbar">
        <button
          className="policy-document__back"
          type="button"
          onClick={handleBack}
          aria-label="뒤로 가기"
        >
          <span className="policy-document__back-icon" aria-hidden="true" />
        </button>
        {/* 공지사항 글과 같다 — 제목 역할(h1)은 아래 큰 글자이고, 이쪽은 현재 위치를 알린다 */}
        <p className="policy-document__topbar-title">{policy.title}</p>
        <span className="policy-document__topbar-spacer" aria-hidden="true" />
      </header>

      <div className="policy-document__head">
        <h1 className="policy-document__title">승리요정 {policy.title}</h1>
        <p className="policy-document__date">
          시행일 {formatEffectiveDate(policy.effectiveDate)}
        </p>
      </div>

      <article className="policy-document__body">
        {policy.intro.map((block, index) => (
          <Block block={block} key={index} />
        ))}

        {policy.sections.map((section) => (
          <section className="policy-document__section" key={section.title}>
            <h2 className="policy-document__section-title">{section.title}</h2>
            {section.body.map((block, index) => (
              <Block block={block} key={index} />
            ))}
          </section>
        ))}
      </article>
    </div>
  );
}
