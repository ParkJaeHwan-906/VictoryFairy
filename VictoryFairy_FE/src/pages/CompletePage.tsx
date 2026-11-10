import { useNavigate } from 'react-router-dom';
import signupCompleteAnimation from '../assets/animation/signup-complete.webp';
import CharacterAnimation from '../components/CharacterAnimation';
import { ROUTES } from '../routes';
import '../styles/CompletePage.css';

/**
 * CompletePage — 온보딩 완료 화면.
 * Figma: SWM / [Onboarding] 온보딩 완료 (node 296:1596)
 *
 * 구단·선수 저장이 모두 끝난 뒤에 보여주는 마지막 단계다. 이 화면 자체는 아무것도
 * 저장하지 않는다 — 앞 단계에서 저장이 성공했을 때만 도달하므로 여기서 다시 확인할 것이 없다.
 *
 * 뒤로 가기로 앞 단계에 돌아가지 못하도록 진입·이탈 모두 이력을 교체한다
 * (선수 선택 화면은 저장된 선수를 다시 불러오지 않아, 돌아가면 비어 보인다).
 */
export default function CompletePage() {
  const navigate = useNavigate();

  return (
    <div className="complete-page">
      <h1 className="complete-page__heading">
        승리요정을 시작하기 위한
        <br />
        모든 단계를 완료했어요!
      </h1>

      {/* 디자인의 자리표시 상자(250 x 280) 자리에 가입 완료 캐릭터 애니메이션을 넣는다 */}
      <div className="complete-page__graphic">
        <CharacterAnimation src={signupCompleteAnimation} className="complete-page__character" />
      </div>

      <p className="complete-page__description">
        BQ지수를 통해 이닝 시간에도 즐겁게
        <br />
        나만의 승요를 키워보세요!
      </p>

      <button
        className="complete-page__submit"
        type="button"
        onClick={() => navigate(ROUTES.main, { replace: true })}
      >
        승리요정 시작하기
      </button>
    </div>
  );
}
