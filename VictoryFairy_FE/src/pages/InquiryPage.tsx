import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import faqFrame from '../assets/faq_frame.svg';
import '../styles/InquiryPage.css';

/**
 * InquiryPage — 문의하기(자주 묻는 질문).
 * Figma: SWM / [My] 문의하기-펼침 (node 1440:13805)
 *
 * 화면은 둘로 나뉜다. 위쪽 배너는 **구글 폼으로 내보내는 링크**이고, 아래는 답이 이미
 * 정해진 질문들을 접었다 펴는 목록이다. 문의를 받을 서버가 없어 폼으로 대신하는 구조라,
 * 배너가 이 화면의 유일한 "보내기" 통로다.
 *
 * 목록은 화면 안에 고정 문구로 두었다 — 아직 FAQ 를 주는 API 가 없고, 글이 바뀌는 주기도
 * 배포보다 느리다. 서버가 생기면 `FAQ_ITEMS` 를 조회 결과로 갈아 끼우면 된다.
 */

/**
 * 배너를 누르면 열리는 구글 폼.
 *
 * 배너 그림(`faq_frame.svg`)은 그라데이션 · 문구 · 일러스트가 한 장으로 합쳐진 402x100
 * 에셋이라, 이 화면에서는 링크로 감싸기만 한다. 문구를 고치려면 에셋을 다시 받아야 한다.
 */
const GOOGLE_FORM_URL =
  'https://docs.google.com/forms/d/e/1FAIpQLSdlWPwCIV-uF4GNJ_11prYhlKOtq2Rd39ltB-p0ZKnvmW8lsA/viewform?usp=dialog';

/** 배너 그림 안에 그려진 글. 그림으로는 읽을 수 없으니 대체 텍스트로 그대로 옮긴다. */
const BANNER_ALT =
  '궁금한 점에 대한 적절한 답변이 없나요? 승리요정에게 궁금하거나 제보할 사항을 전달해주세요!';

const FAQ_ITEMS: readonly { question: string; answer: string }[] = [
  {
    question: '경기 퀴즈는 언제 참여할 수 있나요?',
    answer:
      '경기가 진행되는 동안 이닝별로 새로운 퀴즈에 참여할 수 있어요. 진행 중인 경기의 퀴즈는 경기 탭에서 확인해 주세요.',
  },
  {
    question: '퀴즈에서 얻은 포인트는 어디에 사용되나요?',
    answer:
      '퀴즈를 맞히면 포인트를 획득할 수 있어요. 획득한 포인트는 승리요정 랭킹에 반영되며, 라운지에서 내 순위를 확인할 수 있어요.',
  },
  {
    question: '퀴즈 정답은 언제 확인할 수 있나요?',
    answer:
      '경기가 종료된 후 퀴즈 결과에서 내가 참여한 문제와 정답 여부를 확인할 수 있어요. 이닝별 정답률도 함께 확인할 수 있습니다.',
  },
  {
    question: '응원 구단이나 선호 선수를 변경할 수 있나요?',
    answer:
      '네, 마이페이지에서 응원 구단과 선호 선수를 변경할 수 있어요. 변경한 정보는 이후 승리요정 이용에 반영됩니다.',
  },
  {
    question: '라운지에서는 무엇을 할 수 있나요?',
    answer:
      '라운지에서는 다른 야구팬들과 실시간으로 이야기를 나누고, 퀴즈 포인트를 기준으로 한 승리요정 랭킹도 확인할 수 있어요.',
  },
  {
    question: '부적절한 채팅을 발견하면 어떻게 하나요?',
    answer:
      '욕설이나 비방 등 이용에 불편을 주는 채팅을 발견했다면 신고 기능을 이용해 주세요. 운영 정책에 따라 확인 후 조치할 예정이에요.',
  },
];

export default function InquiryPage() {
  const navigate = useNavigate();

  /**
   * 펼쳐 둔 질문들.
   *
   * 하나만 열리는 아코디언이 아니다 — 디자인이 두 개를 함께 펼친 모습이고, 답을 나란히
   * 놓고 비교하려는 것이 이 목록에서는 자연스럽다.
   */
  const [openQuestions, setOpenQuestions] = useState<ReadonlySet<string>>(new Set());

  const toggle = (question: string) => {
    setOpenQuestions((open) => {
      const next = new Set(open);
      if (!next.delete(question)) next.add(question);
      return next;
    });
  };

  return (
    <div className="inquiry-page">
      <header className="inquiry-page__topbar">
        <button
          className="inquiry-page__back"
          type="button"
          onClick={() => navigate(-1)}
          aria-label="뒤로 가기"
        >
          <span className="inquiry-page__back-icon" aria-hidden="true" />
        </button>
        <h1 className="inquiry-page__topbar-title">문의하기</h1>
        {/* 디자인상 opacity 0 인 빈 버튼 — 타이틀을 가운데 두기 위한 자리 */}
        <span className="inquiry-page__topbar-spacer" aria-hidden="true" />
      </header>

      {/* 앱을 떠나는 링크라 새 탭으로 연다 — 쓰던 자리를 잃지 않는다 */}
      <a
        className="inquiry-page__banner"
        href={GOOGLE_FORM_URL}
        target="_blank"
        rel="noreferrer"
      >
        <img className="inquiry-page__banner-image" src={faqFrame} alt={BANNER_ALT} />
      </a>

      <h2 className="inquiry-page__section-title">자주 묻는 질문</h2>

      <ul className="inquiry-page__list">
        {FAQ_ITEMS.map(({ question, answer }) => {
          const isOpen = openQuestions.has(question);

          return (
            <li className="inquiry-page__item" key={question}>
              <button
                className="inquiry-page__question"
                type="button"
                aria-expanded={isOpen}
                onClick={() => toggle(question)}
              >
                <span className="inquiry-page__question-body">
                  {/* 글이 아니라 목록의 표식이다 — 읽어 주면 물음마다 "큐"가 끼어든다 */}
                  <span className="inquiry-page__question-mark" aria-hidden="true">
                    Q.
                  </span>
                  <span className="inquiry-page__question-text">{question}</span>
                </span>
                <span className="inquiry-page__question-arrow" aria-hidden="true" />
              </button>

              {isOpen && (
                <div className="inquiry-page__answer">
                  <p className="inquiry-page__answer-text">{answer}</p>
                </div>
              )}
            </li>
          );
        })}
      </ul>
    </div>
  );
}
