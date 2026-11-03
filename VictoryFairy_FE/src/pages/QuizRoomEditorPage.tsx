import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import {
  ApiError,
  QUIZ_ROOM_DESCRIPTION_MAX_LENGTH,
  QUIZ_ROOM_OPTION_MAX,
  QUIZ_ROOM_OPTION_MAX_LENGTH,
  QUIZ_ROOM_OPTION_MIN,
  QUIZ_ROOM_QUESTION_MAX_LENGTH,
  QUIZ_ROOM_QUIZ_MAX,
  QUIZ_ROOM_TITLE_MAX_LENGTH,
  createQuizRoom,
} from '../api';
import type { QuizRoomCreateQuizItem, QuizType } from '../api';
import ConfirmSheet from '../components/ConfirmSheet';
import { communityQuizRoomsPath, quizRoomPath } from '../routes';
import '../styles/QuizRoomEditorPage.css';

/**
 * QuizRoomEditorPage — 퀴즈방 만들기.
 *
 * NavBar 없는 전체 화면이고 상단 바 · 입력칸 · 하단 CTA 는 게시글 작성(`CommunityPostEditorPage`)과
 * 같은 모양이다. 그 아래로 문항 카드가 1~20장 쌓인다.
 *
 * ── 검사는 서버와 같은 키로 한다 ─────────────────────────────────────
 * 서버의 400 은 `data` 에 "위반한 키 → 메시지"를 담아 온다(`title`, `quizzes[0].question`,
 * `quizzes[1].optionsValid` …). 화면 검사도 **같은 키**로 오류를 만들어, 어느 쪽에서 왔든
 * 같은 자리(그 칸 바로 아래)에 뜨게 한다. 서버 쪽이 우선이고, 그 칸을 고치면 지운다.
 * 화면 검사는 첫 제출을 누른 뒤부터 보인다 — 쓰기 시작하자마자 빨간 줄이 깔리면 안 된다.
 *
 * ── 쓰던 것을 뒤로가기로 잃지 않게 한다 ──────────────────────────────
 * 문항이 많아 쓰는 데 오래 걸리는데, 시스템 뒤로가기 한 번이면 전부 날아간다. 게시글 작성에는
 * 이런 장치가 없어 퀴즈 풀이(`QuizPage`)가 쓰는 방식을 그대로 가져왔다 — 같은 주소로 자리를
 * 하나 더 쌓아 두고, 뒤로가기가 그 자리를 먹으면 무언가 썼을 때만 한 번 더 묻는다.
 * ──────────────────────────────────────────────────────────────────────
 */

/** 화면이 들고 있는 문항 하나. 유형을 오가도 보기를 잃지 않게 객관식 보기를 늘 들고 있는다. */
type DraftQuiz = {
  /** 목록 key. 문항을 지우면 index 가 밀리므로 따로 둔다. */
  key: number;
  type: QuizType;
  question: string;
  /** 객관식 보기. O/X 에서는 보내지 않는다(서버가 O·X 로 고정 저장). */
  options: string[];
  /** 정답 보기 번호(0부터). 아직 고르지 않았으면 `null`. */
  answer: number | null;
};

/** "키 → 메시지". 서버 400 의 `data` 와 같은 모양이다. */
type FieldErrorMap = Record<string, string>;

const QUIZ_TYPES: readonly { value: QuizType; label: string }[] = [
  { value: '객관식', label: '객관식' },
  { value: 'O/X', label: 'O/X' },
];

/** O/X 의 보기. 서버 약속(0=O, 1=X)과 같은 순서다. */
const OX_LABELS = ['O', 'X'] as const;

/** 나가기 전에 한 번 더 묻는 문구. 줄은 `ConfirmSheet` 규약대로 끊어 둔다. */
const LEAVE_CONFIRM = {
  title: '퀴즈방 만들기를 그만둘까요?',
  description: ['지금 나가면 쓰던 내용이 사라져요.', '이 작업은 되돌릴 수 없어요.'],
  confirmLabel: '나가기',
} as const;

const SUBMIT_FAILED_MESSAGE = '퀴즈방을 만들지 못했어요. 잠시 후 다시 시도해 주세요.';
const INVALID_INPUT_MESSAGE = '입력한 내용을 다시 확인해 주세요.';

/** 키 조립. 서버 키와 글자 하나까지 같아야 같은 자리에 뜬다. */
const quizKey = (index: number, field?: string) =>
  field === undefined ? `quizzes[${index}]` : `quizzes[${index}].${field}`;

/** 객관식 보기 하나가 서버 조건(공백만 아님 · 100자 이하)을 지키는지. */
function isValidOption(option: string): boolean {
  const trimmed = option.trim();
  return trimmed.length > 0 && trimmed.length <= QUIZ_ROOM_OPTION_MAX_LENGTH;
}

/**
 * 화면 검사. 서버 Bean Validation 과 같은 기준·같은 키로 오류를 만든다.
 *
 * 서버처럼 보기 묶음이 이미 틀렸으면 정답 범위(`answerInRange`)는 따로 잡지 않는다 —
 * 보기를 고치면 범위가 저절로 맞는 경우가 많아, 한꺼번에 띄우면 헷갈리기만 한다.
 */
function validateDraft(title: string, description: string, quizzes: DraftQuiz[]): FieldErrorMap {
  const errors: FieldErrorMap = {};

  const trimmedTitle = title.trim();
  if (trimmedTitle.length === 0) errors.title = '제목을 입력해 주세요.';
  else if (trimmedTitle.length > QUIZ_ROOM_TITLE_MAX_LENGTH) {
    errors.title = `제목은 ${QUIZ_ROOM_TITLE_MAX_LENGTH}자까지 쓸 수 있어요.`;
  }

  const trimmedDescription = description.trim();
  if (trimmedDescription.length === 0) errors.description = '소개를 입력해 주세요.';
  else if (trimmedDescription.length > QUIZ_ROOM_DESCRIPTION_MAX_LENGTH) {
    errors.description = `소개는 ${QUIZ_ROOM_DESCRIPTION_MAX_LENGTH}자까지 쓸 수 있어요.`;
  }

  if (quizzes.length === 0) errors.quizzes = '문항을 1개 이상 만들어 주세요.';
  else if (quizzes.length > QUIZ_ROOM_QUIZ_MAX) {
    errors.quizzes = `문항은 ${QUIZ_ROOM_QUIZ_MAX}개까지 만들 수 있어요.`;
  }

  quizzes.forEach((quiz, index) => {
    const question = quiz.question.trim();
    if (question.length === 0) errors[quizKey(index, 'question')] = '문제를 입력해 주세요.';
    else if (question.length > QUIZ_ROOM_QUESTION_MAX_LENGTH) {
      errors[quizKey(index, 'question')] = `문제는 ${QUIZ_ROOM_QUESTION_MAX_LENGTH}자까지 쓸 수 있어요.`;
    }

    const optionCount = quiz.type === 'O/X' ? OX_LABELS.length : quiz.options.length;

    if (
      quiz.type === '객관식' &&
      (quiz.options.length < QUIZ_ROOM_OPTION_MIN ||
        quiz.options.length > QUIZ_ROOM_OPTION_MAX ||
        !quiz.options.every(isValidOption))
    ) {
      errors[quizKey(index, 'optionsValid')] =
        `보기는 ${QUIZ_ROOM_OPTION_MIN}~${QUIZ_ROOM_OPTION_MAX}개, 각 1~${QUIZ_ROOM_OPTION_MAX_LENGTH}자로 입력해 주세요.`;
      if (quiz.answer === null) errors[quizKey(index, 'answer')] = '정답을 골라 주세요.';
      return;
    }

    if (quiz.answer === null) errors[quizKey(index, 'answer')] = '정답을 골라 주세요.';
    else if (quiz.answer < 0 || quiz.answer >= optionCount) {
      errors[quizKey(index, 'answerInRange')] = '정답은 보기 중에서 골라 주세요.';
    }
  });

  return errors;
}

/**
 * 요청 본문의 문항. 앞뒤 공백은 잘라 보낸다 — 서버는 값을 다듬지 않고 그대로 저장하므로
 * 여기서 자르지 않으면 풀이 화면에 공백이 그대로 남는다.
 * 검사를 통과한 뒤에만 부르므로 `answer` 는 언제나 값이 있다.
 */
function toRequestQuiz(quiz: DraftQuiz): QuizRoomCreateQuizItem {
  const answer = quiz.answer ?? 0;
  const question = quiz.question.trim();

  return quiz.type === 'O/X'
    ? { type: 'O/X', question, answer }
    : { type: '객관식', question, options: quiz.options.map((option) => option.trim()), answer };
}

/** 서버 400 이 아닌 실패를 한 줄로. 5xx · 네트워크는 서버 문구 대신 재시도 안내를 쓴다. */
function toSubmitMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return SUBMIT_FAILED_MESSAGE;
  if (error.isNetworkError || (error.status !== null && error.status >= 500)) {
    return SUBMIT_FAILED_MESSAGE;
  }
  return error.message || SUBMIT_FAILED_MESSAGE;
}

export default function QuizRoomEditorPage() {
  const navigate = useNavigate();
  const location = useLocation();

  /**
   * 들어오기 전 화면이 있었는지. 주소를 직접 쳐서 들어왔으면 되돌아갈 곳이 없어 퀴즈방 탭으로 보낸다.
   * 아래에서 자리를 하나 더 쌓으면 key 가 바뀌므로 처음 값만 붙잡아 둔다.
   */
  const [canGoBack] = useState(() => location.key !== 'default');

  const nextKeyRef = useRef(1);
  const makeQuiz = (): DraftQuiz => ({
    key: nextKeyRef.current++,
    type: '객관식',
    question: '',
    options: Array.from({ length: QUIZ_ROOM_OPTION_MIN }, () => ''),
    answer: null,
  });

  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [quizzes, setQuizzes] = useState<DraftQuiz[]>(() => [
    { key: 0, type: '객관식', question: '', options: ['', ''], answer: null },
  ]);

  const [isSubmitting, setIsSubmitting] = useState(false);
  /** 한 번이라도 제출을 눌렀는지. 그 뒤부터 화면 검사 결과를 보인다(머리말 참고). */
  const [hasTriedSubmit, setHasTriedSubmit] = useState(false);
  const [serverErrors, setServerErrors] = useState<FieldErrorMap>({});
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [isLeaveConfirmOpen, setIsLeaveConfirmOpen] = useState(false);

  /**
   * 제출 잠금. `isSubmitting` state 만으로는 같은 틱의 연타(Enter 연속 등)를 막지 못한다 —
   * state 는 다음 렌더에야 반영되기 때문이다.
   */
  const submittingRef = useRef(false);
  const formRef = useRef<HTMLFormElement | null>(null);

  const clientErrors = validateDraft(title, description, quizzes);
  const errorOf = (key: string): string | undefined =>
    serverErrors[key] ?? (hasTriedSubmit ? clientErrors[key] : undefined);

  const isDirty =
    title.length > 0 ||
    description.length > 0 ||
    quizzes.length > 1 ||
    quizzes.some((quiz) => quiz.question.length > 0 || quiz.options.some((option) => option.length > 0));

  /* ---------------------------------------------------------------- *
   * 뒤로가기 지키기 — QuizPage 와 같은 방식(머리말 참고)
   * ---------------------------------------------------------------- */

  /** 뒤로가기를 받아 낼 자리를 이미 쌓았는지. StrictMode 가 효과를 두 번 태워도 하나만 쌓는다. */
  const backGuardRef = useRef(false);
  /** 이미 이 화면을 떠나는 중인지. 떠나면서 생긴 `popstate` 를 다시 붙잡지 않게 한다. */
  const leavingRef = useRef(false);
  /** `popstate` 는 렌더 밖에서 와 state 를 읽을 수 없다 — 최신 값을 옮겨 둔다. */
  const dirtyRef = useRef(false);
  useEffect(() => {
    dirtyRef.current = isDirty;
  }, [isDirty]);

  useEffect(() => {
    if (!backGuardRef.current) {
      window.history.pushState(window.history.state, '', window.location.href);
      backGuardRef.current = true;
    }

    const handlePopState = () => {
      if (leavingRef.current) return;

      // 쓴 것이 없으면 잃을 것도 없다 — 묻지 않고 한 칸 더 물러난다(덫은 방금 먹혔다).
      if (!dirtyRef.current) {
        leavingRef.current = true;
        navigate(-1);
        return;
      }

      // 먹힌 자리를 다시 쌓는다 — 다음 뒤로가기도 같은 자리에서 걸려야 한다.
      window.history.pushState(window.history.state, '', window.location.href);
      setIsLeaveConfirmOpen(true);
    };

    window.addEventListener('popstate', handlePopState);
    return () => {
      window.removeEventListener('popstate', handlePopState);
    };
  }, [navigate]);

  /** 쌓아 둔 자리까지 두 칸을 되돌려 실제로 이 화면을 뜬다. */
  const leave = () => {
    leavingRef.current = true;
    if (canGoBack) navigate(-2);
    else navigate(communityQuizRoomsPath, { replace: true });
  };

  const handleBack = () => {
    if (!isDirty) {
      leave();
      return;
    }
    setIsLeaveConfirmOpen(true);
  };

  /**
   * 만든 방 상세로 바꿔 끼운다.
   *
   * 덫 자리에 그대로 `replace` 하면 그 아래 원래 자리(만들기 주소)가 남아, 상세에서 뒤로 가면
   * 빈 만들기 화면이 다시 뜬다. 그래서 덫을 한 칸 걷어 낸 뒤(`back`) 원래 자리를 상세로 바꾼다 —
   * 상세에서 뒤로 가면 만들기 전 화면(퀴즈방 탭)으로 돌아간다.
   */
  const replaceWithRoom = (roomId: number) => {
    leavingRef.current = true;
    const handleBackDone = () => {
      window.removeEventListener('popstate', handleBackDone);
      navigate(quizRoomPath(roomId), { replace: true });
    };
    window.addEventListener('popstate', handleBackDone);
    window.history.back();
  };

  /* ---------------------------------------------------------------- *
   * 입력
   * ---------------------------------------------------------------- */

  /** 서버 오류 중 `prefix` 로 시작하는 키를 지운다 — 그 칸을 고쳤으니 옛 사유는 맞지 않는다. */
  const clearServerErrors = (prefix: string) => {
    setServerErrors((prev) => {
      const keys = Object.keys(prev).filter((key) => key.startsWith(prefix));
      if (keys.length === 0) return prev;
      const next = { ...prev };
      for (const key of keys) delete next[key];
      return next;
    });
    setSubmitError(null);
  };

  const updateQuiz = (index: number, patch: (quiz: DraftQuiz) => DraftQuiz) => {
    setQuizzes((prev) => prev.map((quiz, at) => (at === index ? patch(quiz) : quiz)));
    clearServerErrors(quizKey(index));
  };

  const addQuiz = () => {
    if (quizzes.length >= QUIZ_ROOM_QUIZ_MAX) return;
    setQuizzes((prev) => [...prev, makeQuiz()]);
    // 서버 오류 키는 index 로 붙어 있어 문항 수가 바뀌면 자리가 어긋난다 — 통째로 비운다.
    setServerErrors({});
    setSubmitError(null);
  };

  const removeQuiz = (index: number) => {
    if (quizzes.length <= 1) return;
    setQuizzes((prev) => prev.filter((_, at) => at !== index));
    setServerErrors({});
    setSubmitError(null);
  };

  /** 유형을 바꾸면 정답은 다시 고르게 한다 — 같은 번호가 다른 뜻이 된다(1번 보기 ↔ X). */
  const changeType = (index: number, type: QuizType) => {
    updateQuiz(index, (quiz) => (quiz.type === type ? quiz : { ...quiz, type, answer: null }));
  };

  const changeOption = (index: number, optionIndex: number, value: string) => {
    updateQuiz(index, (quiz) => ({
      ...quiz,
      options: quiz.options.map((option, at) => (at === optionIndex ? value : option)),
    }));
  };

  const addOption = (index: number) => {
    updateQuiz(index, (quiz) =>
      quiz.options.length >= QUIZ_ROOM_OPTION_MAX ? quiz : { ...quiz, options: [...quiz.options, ''] },
    );
  };

  /** 보기를 지우면 정답 번호도 따라 옮긴다 — 지운 보기가 정답이었으면 다시 고르게 한다. */
  const removeOption = (index: number, optionIndex: number) => {
    updateQuiz(index, (quiz) => {
      if (quiz.options.length <= QUIZ_ROOM_OPTION_MIN) return quiz;

      let answer = quiz.answer;
      if (answer === optionIndex) answer = null;
      else if (answer !== null && answer > optionIndex) answer -= 1;

      return { ...quiz, options: quiz.options.filter((_, at) => at !== optionIndex), answer };
    });
  };

  /** 제출이 막혔을 때 첫 오류 칸으로 데려간다 — 문항이 많으면 오류가 화면 밖에 있다. */
  const focusFirstError = () => {
    window.requestAnimationFrame(() => {
      const target = formRef.current?.querySelector<HTMLElement>('[aria-invalid="true"], [data-field-error]');
      target?.scrollIntoView({ block: 'center', behavior: 'smooth' });
      if (target instanceof HTMLInputElement || target instanceof HTMLTextAreaElement) {
        target.focus({ preventScroll: true });
      }
    });
  };

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (submittingRef.current) return;

    setHasTriedSubmit(true);
    setSubmitError(null);

    if (Object.keys(clientErrors).length > 0) {
      focusFirstError();
      return;
    }

    submittingRef.current = true;
    setIsSubmitting(true);

    try {
      const { roomId } = await createQuizRoom({
        title: title.trim(),
        description: description.trim(),
        quizzes: quizzes.map(toRequestQuiz),
      });
      // 성공하면 화면이 바뀐다 — 잠금은 풀지 않는다(바뀌는 사이 다시 눌리지 않게).
      replaceWithRoom(roomId);
    } catch (cause: unknown) {
      submittingRef.current = false;
      setIsSubmitting(false);

      /*
       * 400 Bean Validation — 키별 사유를 그 칸 아래에 띄운다. 깨진 JSON 이면 맵이 null 이라
       * 아래 일반 문구로 떨어진다(화면이 만든 본문이라 사실상 일어나지 않는다).
       */
      if (cause instanceof ApiError && cause.status === 400 && cause.fieldErrors !== null) {
        setServerErrors(cause.fieldErrors);
        setSubmitError(INVALID_INPUT_MESSAGE);
        focusFirstError();
        return;
      }

      setSubmitError(toSubmitMessage(cause));
    }
  };

  /*
   * 화면이 칸을 갖고 있지 않은 서버 키(예: 모르는 새 키)는 맨 아래 한 줄로 모아 보인다 —
   * 그 사유가 어디에도 안 뜨면 사용자는 왜 안 되는지 알 수 없다.
   */
  const placedKeys = new Set<string>(['title', 'description', 'quizzes']);
  quizzes.forEach((_, index) => {
    for (const field of [undefined, 'type', 'question', 'optionsValid', 'answer', 'answerInRange']) {
      placedKeys.add(quizKey(index, field));
    }
  });
  const strayServerErrors = Object.entries(serverErrors)
    .filter(([key]) => !placedKeys.has(key))
    .map(([, message]) => message);

  const titleError = errorOf('title');
  const descriptionError = errorOf('description');
  const quizzesError = errorOf('quizzes');

  return (
    <div className="quiz-room-editor-page">
      <header className="quiz-room-editor-page__topbar">
        <button
          className="quiz-room-editor-page__back"
          type="button"
          onClick={handleBack}
          aria-label="뒤로 가기"
        >
          <span className="quiz-room-editor-page__back-icon" aria-hidden="true" />
        </button>
        <h1 className="quiz-room-editor-page__topbar-title">퀴즈방 만들기</h1>
        {/* 타이틀을 가운데 두기 위한 빈 자리(다른 화면들과 같다) */}
        <span className="quiz-room-editor-page__topbar-spacer" aria-hidden="true" />
      </header>

      <form
        className="quiz-room-editor-page__form"
        ref={formRef}
        onSubmit={(event) => void handleSubmit(event)}
        noValidate
      >
        <div className="quiz-room-editor-page__group">
          <div className="quiz-room-editor-page__label-row">
            <label className="quiz-room-editor-page__label" htmlFor="quiz-room-editor-title">
              제목
            </label>
            <span className="quiz-room-editor-page__counter">
              {title.length}/{QUIZ_ROOM_TITLE_MAX_LENGTH}
            </span>
          </div>
          <input
            className="quiz-room-editor-page__input"
            id="quiz-room-editor-title"
            type="text"
            value={title}
            onChange={(event) => {
              setTitle(event.target.value);
              clearServerErrors('title');
            }}
            placeholder="퀴즈방 제목을 입력해주세요"
            maxLength={QUIZ_ROOM_TITLE_MAX_LENGTH}
            autoComplete="off"
            aria-invalid={titleError !== undefined}
            aria-describedby={titleError !== undefined ? 'quiz-room-editor-title-error' : undefined}
          />
          <FieldError id="quiz-room-editor-title-error" message={titleError} />
        </div>

        <div className="quiz-room-editor-page__group">
          <div className="quiz-room-editor-page__label-row">
            <label className="quiz-room-editor-page__label" htmlFor="quiz-room-editor-description">
              소개
            </label>
            <span className="quiz-room-editor-page__counter">
              {description.length}/{QUIZ_ROOM_DESCRIPTION_MAX_LENGTH}
            </span>
          </div>
          <textarea
            className="quiz-room-editor-page__textarea"
            id="quiz-room-editor-description"
            value={description}
            onChange={(event) => {
              setDescription(event.target.value);
              clearServerErrors('description');
            }}
            placeholder="어떤 퀴즈인지 간단히 소개해주세요"
            maxLength={QUIZ_ROOM_DESCRIPTION_MAX_LENGTH}
            aria-invalid={descriptionError !== undefined}
            aria-describedby={
              descriptionError !== undefined ? 'quiz-room-editor-description-error' : undefined
            }
          />
          <FieldError id="quiz-room-editor-description-error" message={descriptionError} />
        </div>

        <section className="quiz-room-editor-page__quizzes" aria-labelledby="quiz-room-editor-quizzes-title">
          <div className="quiz-room-editor-page__label-row">
            <h2 className="quiz-room-editor-page__label" id="quiz-room-editor-quizzes-title">
              문항
            </h2>
            <span className="quiz-room-editor-page__counter">
              {quizzes.length}/{QUIZ_ROOM_QUIZ_MAX}
            </span>
          </div>
          <FieldError id="quiz-room-editor-quizzes-error" message={quizzesError} />

          <ol className="quiz-room-editor-page__quiz-list">
            {quizzes.map((quiz, index) => (
              <QuizDraftCard
                key={quiz.key}
                quiz={quiz}
                index={index}
                canRemove={quizzes.length > 1}
                disabled={isSubmitting}
                errorOf={errorOf}
                onChangeType={(type) => changeType(index, type)}
                onChangeQuestion={(value) => updateQuiz(index, (current) => ({ ...current, question: value }))}
                onChangeOption={(optionIndex, value) => changeOption(index, optionIndex, value)}
                onAddOption={() => addOption(index)}
                onRemoveOption={(optionIndex) => removeOption(index, optionIndex)}
                onSelectAnswer={(answer) => updateQuiz(index, (current) => ({ ...current, answer }))}
                onRemove={() => removeQuiz(index)}
              />
            ))}
          </ol>

          <button
            className="quiz-room-editor-page__add-quiz"
            type="button"
            onClick={addQuiz}
            disabled={isSubmitting || quizzes.length >= QUIZ_ROOM_QUIZ_MAX}
          >
            <span className="quiz-room-editor-page__add-icon" aria-hidden="true" />
            {quizzes.length >= QUIZ_ROOM_QUIZ_MAX
              ? `문항은 ${QUIZ_ROOM_QUIZ_MAX}개까지 만들 수 있어요`
              : '문항 추가'}
          </button>
        </section>

        {(submitError !== null || strayServerErrors.length > 0) && (
          <p className="quiz-room-editor-page__hint quiz-room-editor-page__hint--error" role="alert">
            <span className="quiz-room-editor-page__hint-icon" aria-hidden="true" />
            {[submitError, ...strayServerErrors].filter((line) => line !== null).join(' ')}
          </p>
        )}

        <button className="quiz-room-editor-page__submit" type="submit" disabled={isSubmitting}>
          {isSubmitting ? '만드는 중...' : '퀴즈방 만들기'}
        </button>
      </form>

      {/* 나가기는 곧 화면이 바뀌는 일이라 시트를 따로 닫지 않는다. */}
      {isLeaveConfirmOpen && (
        <ConfirmSheet
          {...LEAVE_CONFIRM}
          onConfirm={leave}
          onClose={() => setIsLeaveConfirmOpen(false)}
        />
      )}
    </div>
  );
}

/** 칸 아래 오류 한 줄. 메시지가 없으면 그리지 않는다. */
function FieldError({ id, message }: { id: string; message: string | undefined }) {
  if (message === undefined) return null;

  return (
    <p
      className="quiz-room-editor-page__hint quiz-room-editor-page__hint--error"
      id={id}
      // 칸이 없는 오류(보기 묶음 · 정답)도 "첫 오류로 데려가기"가 찾을 수 있게 표식을 단다.
      data-field-error
    >
      <span className="quiz-room-editor-page__hint-icon" aria-hidden="true" />
      {message}
    </p>
  );
}

type QuizDraftCardProps = {
  quiz: DraftQuiz;
  index: number;
  canRemove: boolean;
  disabled: boolean;
  errorOf: (key: string) => string | undefined;
  onChangeType: (type: QuizType) => void;
  onChangeQuestion: (value: string) => void;
  onChangeOption: (optionIndex: number, value: string) => void;
  onAddOption: () => void;
  onRemoveOption: (optionIndex: number) => void;
  onSelectAnswer: (answer: number) => void;
  onRemove: () => void;
};

/**
 * 문항 카드 한 장 — 유형 · 문제 · 보기(객관식) 또는 O/X · 정답.
 *
 * 정답은 보기 옆의 동그라미로 고른다(객관식). 보기 글을 쓰는 칸과 정답 고르기를 한 버튼으로
 * 묶지 않는 이유 — 글을 쓰려고 칸을 누른 것이 정답 선택으로 새면 안 된다.
 */
function QuizDraftCard({
  quiz,
  index,
  canRemove,
  disabled,
  errorOf,
  onChangeType,
  onChangeQuestion,
  onChangeOption,
  onAddOption,
  onRemoveOption,
  onSelectAnswer,
  onRemove,
}: QuizDraftCardProps) {
  const idBase = `quiz-room-editor-q${quiz.key}`;
  const order = index + 1;

  const cardError = errorOf(quizKey(index));
  const typeError = errorOf(quizKey(index, 'type'));
  const questionError = errorOf(quizKey(index, 'question'));
  const optionsError = quiz.type === '객관식' ? errorOf(quizKey(index, 'optionsValid')) : undefined;
  const answerError = errorOf(quizKey(index, 'answer')) ?? errorOf(quizKey(index, 'answerInRange'));

  return (
    <li className="quiz-room-editor-page__quiz">
      <div className="quiz-room-editor-page__quiz-head">
        <p className="quiz-room-editor-page__quiz-order">Q{order}.</p>
        {canRemove && (
          <button
            className="quiz-room-editor-page__quiz-remove"
            type="button"
            onClick={onRemove}
            disabled={disabled}
            aria-label={`${order}번 문항 삭제`}
          >
            <span className="quiz-room-editor-page__remove-icon" aria-hidden="true" />
          </button>
        )}
      </div>
      <FieldError id={`${idBase}-error`} message={cardError} />

      <div className="quiz-room-editor-page__types" role="radiogroup" aria-label={`${order}번 문항 유형`}>
        {QUIZ_TYPES.map((option) => (
          <button
            key={option.value}
            className={`quiz-room-editor-page__type${
              quiz.type === option.value ? ' quiz-room-editor-page__type--active' : ''
            }`}
            type="button"
            role="radio"
            aria-checked={quiz.type === option.value}
            onClick={() => onChangeType(option.value)}
            disabled={disabled}
          >
            {option.label}
          </button>
        ))}
      </div>
      <FieldError id={`${idBase}-type-error`} message={typeError} />

      <div className="quiz-room-editor-page__label-row">
        <label className="quiz-room-editor-page__sub-label" htmlFor={`${idBase}-question`}>
          문제
        </label>
        <span className="quiz-room-editor-page__counter">
          {quiz.question.length}/{QUIZ_ROOM_QUESTION_MAX_LENGTH}
        </span>
      </div>
      <textarea
        className="quiz-room-editor-page__textarea quiz-room-editor-page__textarea--question"
        id={`${idBase}-question`}
        value={quiz.question}
        onChange={(event) => onChangeQuestion(event.target.value)}
        placeholder="문제를 입력해주세요"
        maxLength={QUIZ_ROOM_QUESTION_MAX_LENGTH}
        rows={2}
        disabled={disabled}
        aria-invalid={questionError !== undefined}
        aria-describedby={questionError !== undefined ? `${idBase}-question-error` : undefined}
      />
      <FieldError id={`${idBase}-question-error`} message={questionError} />

      {quiz.type === '객관식' ? (
        <fieldset className="quiz-room-editor-page__options" disabled={disabled}>
          <legend className="quiz-room-editor-page__sub-label">보기 · 정답</legend>
          <ul className="quiz-room-editor-page__option-list" role="radiogroup" aria-label={`${order}번 문항 정답`}>
            {quiz.options.map((option, optionIndex) => {
              const isAnswer = quiz.answer === optionIndex;
              const isInvalid = optionsError !== undefined && !isValidOption(option);

              return (
                // 보기는 지우면 뒤 보기가 앞으로 당겨지는 단순한 줄이라 index 를 key 로 쓴다.
                <li
                  key={optionIndex}
                  className={`quiz-room-editor-page__option${isAnswer ? ' quiz-room-editor-page__option--answer' : ''}`}
                >
                  <button
                    className={`quiz-room-editor-page__answer-radio${
                      isAnswer ? ' quiz-room-editor-page__answer-radio--checked' : ''
                    }`}
                    type="button"
                    role="radio"
                    aria-checked={isAnswer}
                    aria-label={`${optionIndex + 1}번 보기를 정답으로`}
                    onClick={() => onSelectAnswer(optionIndex)}
                  >
                    <span className="quiz-room-editor-page__answer-radio-dot" aria-hidden="true" />
                  </button>
                  <input
                    className="quiz-room-editor-page__input quiz-room-editor-page__input--option"
                    type="text"
                    value={option}
                    onChange={(event) => onChangeOption(optionIndex, event.target.value)}
                    placeholder={`보기 ${optionIndex + 1}`}
                    maxLength={QUIZ_ROOM_OPTION_MAX_LENGTH}
                    autoComplete="off"
                    aria-label={`${order}번 문항 ${optionIndex + 1}번 보기`}
                    aria-invalid={isInvalid}
                  />
                  {quiz.options.length > QUIZ_ROOM_OPTION_MIN && (
                    <button
                      className="quiz-room-editor-page__option-remove"
                      type="button"
                      onClick={() => onRemoveOption(optionIndex)}
                      aria-label={`${optionIndex + 1}번 보기 삭제`}
                    >
                      <span className="quiz-room-editor-page__remove-icon" aria-hidden="true" />
                    </button>
                  )}
                </li>
              );
            })}
          </ul>

          {quiz.options.length < QUIZ_ROOM_OPTION_MAX && (
            <button className="quiz-room-editor-page__add-option" type="button" onClick={onAddOption}>
              <span className="quiz-room-editor-page__add-icon" aria-hidden="true" />
              보기 추가
            </button>
          )}
          <FieldError id={`${idBase}-options-error`} message={optionsError} />
        </fieldset>
      ) : (
        <fieldset className="quiz-room-editor-page__options" disabled={disabled}>
          <legend className="quiz-room-editor-page__sub-label">정답</legend>
          {/* O/X 의 보기는 서버가 O(0)·X(1)로 고정한다 — 정답만 고른다. */}
          <div className="quiz-room-editor-page__ox" role="radiogroup" aria-label={`${order}번 문항 정답`}>
            {OX_LABELS.map((label, optionIndex) => (
              <button
                key={label}
                className={`quiz-room-editor-page__ox-choice quiz-room-editor-page__ox-choice--${label.toLowerCase()}${
                  quiz.answer === optionIndex ? ' quiz-room-editor-page__ox-choice--checked' : ''
                }`}
                type="button"
                role="radio"
                aria-checked={quiz.answer === optionIndex}
                onClick={() => onSelectAnswer(optionIndex)}
              >
                {label}
              </button>
            ))}
          </div>
        </fieldset>
      )}
      <FieldError id={`${idBase}-answer-error`} message={answerError} />
    </li>
  );
}
