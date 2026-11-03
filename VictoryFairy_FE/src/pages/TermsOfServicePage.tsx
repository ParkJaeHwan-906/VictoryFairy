import PolicyDocumentView from '../components/PolicyDocumentView';
import { TERMS_OF_SERVICE } from '../data/policies';

/**
 * TermsOfServicePage — 서비스 이용약관.
 *
 * 마이페이지 "센터 > 서비스 이용약관"으로 들어온다. 로그인 없이도 열린다 — 가입하기 전에
 * 읽을 수 있어야 하는 글이다. 그리는 일은 개인정보 처리방침과 함께 `PolicyDocumentView` 가 한다.
 */
export default function TermsOfServicePage() {
  return <PolicyDocumentView policy={TERMS_OF_SERVICE} />;
}
