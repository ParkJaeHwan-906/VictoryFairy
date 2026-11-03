import PolicyDocumentView from '../components/PolicyDocumentView';
import { PRIVACY_POLICY } from '../data/policies';

/**
 * PrivacyPolicyPage — 개인정보 처리방침.
 *
 * 마이페이지 "센터 > 개인정보처리방침"으로 들어온다. 로그인 없이도 열린다 — 가입하기 전에
 * 읽을 수 있어야 하는 글이다. 그리는 일은 서비스 이용약관과 함께 `PolicyDocumentView` 가 한다.
 */
export default function PrivacyPolicyPage() {
  return <PolicyDocumentView policy={PRIVACY_POLICY} />;
}
