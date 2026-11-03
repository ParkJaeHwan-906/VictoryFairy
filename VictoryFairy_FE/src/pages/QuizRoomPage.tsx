import { useEffect, useRef, useState } from 'react';
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom';
import {
  getQuizRoom,
  getQuizRoomQuizzes,
  isQuizRoomAlreadySubmitted,
  isQuizRoomNotFound,
  isQuizRoomOptionNotFound,
  isQuizRoomQuizNotFound,
  leaveQuizRoom,
  setQuizRoomLike,
  submitQuizRoomAnswer,
  subscribeQuizRoom,
  toAssetUrl,
} from '../api';
import type { QuizOption, QuizRoomDetail, QuizRoomQuiz, QuizRoomResult, QuizRoomVoteOption } from '../api';
import NoticeSheet from '../components/NoticeSheet';
import QuizRoomResultView from '../components/QuizRoomResultView';
import QuizRoomVoteBars from '../components/QuizRoomVoteBars';
import { communityQuizRoomsPath } from '../routes';
import { fallbackToPlaceholder, profilePlaceholder } from '../utils/avatar';
import { formatCommunityDateTime } from '../utils/community';
import '../styles/QuizRoomPage.css';

/**
 * QuizRoomPage — 퀴즈방 상세 · 풀이 · 결과(`docs/quiz-room.md`).
 *
 * NavBar 없는 전체 화면이다. 상단 바 오른쪽에 "풀이 중 N명"이 실시간으로 붙고, 본문은
 * 소개(시작 전) → 풀이(한 문항씩) → 결과 순서로 바뀐다. 디자인 시안이 없어 상단 바 · 머리말은
 * 게시글 상세(`CommunityPostDetailPage`)를, 문항 카드는 오늘의 퀴즈(`QuizCard`)를 따른다.
 *
 * ── 결과는 단 한 번 온다 ──────────────────────────────────────────────
 * 풀이 이력 조회 API 가 없다. **마지막 답 제출 응답의 `result` 가 결과를 받는 유일한 기회**라,
 * 받는 즉시 화면 state 와 sessionStorage(방 id 별) 두 곳에 남긴다. 새로고침하거나 같은 방에
 * 다시 들어와도 저장된 결과가 있으면 곧바로 결과 화면을 연다.
 *
 * ── 진행 상태도 서버가 알려 주지 않는다 ───────────────────────────────
 * 퀴즈 리스트에는 내가 어디까지 풀었는지가 없다. 그래서 화면은 언제나 1번부터 차례로 보여 주고,
 * 이미 답한 문항은 제출 때 오는 **409 로 알아채** 조용히 다음으로 넘긴다(오류가 아니다).
 * 끝까지 갔는데 결과가 없으면 예전에 다 풀었던 방이다 — 결과는 다시 볼 수 없다고 안내한다.
 *
 * ── 실시간(SSE) ──────────────────────────────────────────────────────
 * 들어오면 구독하고, 떠날 때 **`close()` 를 먼저 불러 재연결을 멈춘 뒤** 명시적 퇴장(DELETE)을
 * 보낸다(실패는 무시 — 하트비트 만료가 회수한다). `presence` 는 마지막 값을 계속 보여 주고
 * (첫 값 전에는 상세의 `solvingCount`), `votes` 는 문항별로 **통째로 덮어쓴다**(증분 아님).
 * 분포는 아직 안 푼 문항에도 오지만 **내가 답한 문항에서만** 보여 준다 — 답을 고르기 전에
 * 남들의 선택이 보이면 풀이가 투표가 된다.
 * ──────────────────────────────────────────────────────────────────────
 */

/** 상세를 그릴 수 있는지. */
type RoomStatus = 'loading' | 'ready' | 'notFound' | 'error';

/** 실시간 스트림 상태. 'live' 와 'connecting' 외에는 안내 줄을 띄운다(GameChat 과 같다). */
type LiveStatus = 'connecting' | 'live' | 'reconnecting' | 'offline';

/**
 * 이번 방문에서 알게 된 문항 하나의 상태.
 * - `answered` — 방금 내가 답했다(고른 보기를 안다).
 * - `already` — 409. 예전에 답했지만 무엇을 골랐는지는 모른다.
 * - `missing` — 404 QUIZ_NOT_FOUND. 이 방의 문항이 아니라고 한다 — 넘어갈 수밖에 없다.
 */
type QuizProgress = { kind: 'answered'; myOption: number } | { kind: 'already' } | { kind: 'missing' };

/** 결과 보관 키. 방마다 따로 둔다. */
const RESULT_STORAGE_PREFIX = 'victoryfairy:quiz-room-result:';

const SUBMIT_FAILED_MESSAGE = '답을 제출하지 못했어요. 잠시 후 다시 시도해 주세요.';
const ALREADY_ANSWERED_NOTICE = '이미 답한 문제라 다음 문제로 넘어갔어요.';

/**
 * 저장해 둔 결과를 읽는다. 없거나 모양이 이상하면 `null`.
 *
 * sessionStorage 는 사파리 사생활 보호 모드 등에서 접근만 해도 던질 수 있어 try 로 감싼다.
 * 모양 검사는 화면이 그릴 때 깨지지 않을 만큼만 본다(앞 버전이 남긴 값일 수 있다).
 */
function readSavedResult(roomId: number): QuizRoomResult | null {
  try {
    const raw = window.sessionStorage.getItem(`${RESULT_STORAGE_PREFIX}${roomId}`);
    if (raw === null) return null;

    const parsed = JSON.parse(raw) as Partial<QuizRoomResult> | null;
    if (
      typeof parsed !== 'object' ||
      parsed === null ||
      typeof parsed.correctCount !== 'number' ||
      typeof parsed.total !== 'number' ||
      !Array.isArray(parsed.quizzes)
    ) {
      return null;
    }
    return parsed as QuizRoomResult;
  } catch {
    return null;
  }
}

/** 결과를 남긴다. 저장이 안 돼도(용량 · 사생활 보호 모드) 화면 state 에는 이미 있으니 조용히 넘긴다. */
function saveResult(roomId: number, result: QuizRoomResult): void {
  try {
    window.sessionStorage.setItem(`${RESULT_STORAGE_PREFIX}${roomId}`, JSON.stringify(result));
  } catch {
    /* 저장 실패는 이 방문의 화면에 영향이 없다 */
  }
}

/** 제출 실패(409 · 404 방 제외)를 한 줄로. */
function toSubmitMessage(error: unknown): string {
  if (isQuizRoomOptionNotFound(error)) return '선택한 보기를 찾을 수 없어요. 다시 골라 주세요.';
  if (isQuizRoomQuizNotFound(error)) return '이 문제를 찾을 수 없어요. 다음 문제로 넘어가 주세요.';
  return SUBMIT_FAILED_MESSAGE;
}

/** O/X 로 그릴 수 있는 문항인지. 서버 약속(0=O, 1=X)과 다르게 오면 보기 글 그대로 그린다. */
function isOxPair(quiz: QuizRoomQuiz): boolean {
  return (
    quiz.type === 'O/X' &&
    quiz.options.length === 2 &&
    quiz.options[0].no === 0 &&
    quiz.options[1].no === 1
  );
}

/**
 * 주소의 roomId 가 바뀌면 화면을 통째로 새로 만든다 — 앞 방의 풀이 상태 · 결과 · 구독이
 * 다음 방으로 새지 않게(`key`).
 */
export default function QuizRoomPage() {
  const { roomId: rawRoomId } = useParams();
  return <QuizRoomScreen key={rawRoomId} rawRoomId={rawRoomId} />;
}

function QuizRoomScreen({ rawRoomId }: { rawRoomId: string | undefined }) {
  const roomId = Number(rawRoomId);
  const isValidId = Number.isInteger(roomId) && roomId > 0;

  const navigate = useNavigate();
  const location = useLocation();

  const [room, setRoom] = useState<QuizRoomDetail | null>(null);
  const [status, setStatus] = useState<RoomStatus>(isValidId ? 'loading' : 'notFound');
  const [quizzes, setQuizzes] = useState<QuizRoomQuiz[] | null>(null);

  /** 결과. 저장해 둔 것이 있으면 처음부터 채운다(머리말 "결과는 단 한 번 온다"). */
  const [result, setResult] = useState<QuizRoomResult | null>(() =>
    isValidId ? readSavedResult(roomId) : null,
  );
  /**
   * 결과 화면을 보여 줄지. 마지막 답을 내자마자 넘기지 않고 그 문항의 선택율을 먼저 보여 준
   * 뒤 "결과 보기"로 넘긴다 — 결과 자체는 받는 즉시 위 state 와 저장소에 남는다.
   */
  const [isResultOpen, setIsResultOpen] = useState(() => result !== null);

  const [liveStatus, setLiveStatus] = useState<LiveStatus>('connecting');
  /** SSE `presence`. 첫 값이 오기 전엔 null — 그동안은 상세의 `solvingCount` 를 보여 준다. */
  const [presence, setPresence] = useState<number | null>(null);
  /** 문항별 최신 SSE `votes`. 같은 문항은 통째로 덮어쓴다. */
  const [votesByQuiz, setVotesByQuiz] = useState<Record<number, QuizRoomVoteOption[]>>({});

  const [isSolving, setIsSolving] = useState(false);
  const [index, setIndex] = useState(0);
  const [progress, setProgress] = useState<Record<number, QuizProgress>>({});
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  /** 이미 답한 문항이라 넘어갔다는 안내. 오류가 아니라 알림이다(`QuizPage` 의 skipNotice 와 같다). */
  const [skipNotice, setSkipNotice] = useState<string | null>(null);

  const [isLikePending, setIsLikePending] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);

  /** 화면을 떠난 뒤 도착한 응답이 state 를 건드리지 않게 막는다. */
  const aliveRef = useRef(true);
  useEffect(() => {
    aliveRef.current = true;
    return () => {
      aliveRef.current = false;
    };
  }, []);

  /** 제출 · 추천 잠금. state 는 다음 렌더에야 반영돼 같은 틱의 연타를 막지 못한다. */
  const submittingRef = useRef(false);
  const likePendingRef = useRef(false);

  /*
   * 상세(+ 풀 문항)를 받는다. 저장된 결과가 있으면 문항은 필요 없다.
   * 결과 유무는 들어온 순간 한 번만 본다 — 풀다가 결과가 생겨도 다시 받을 이유가 없다.
   */
  const [needsQuizzes] = useState(() => result === null);
  useEffect(() => {
    if (!isValidId) return;

    let alive = true;

    Promise.all([getQuizRoom(roomId), needsQuizzes ? getQuizRoomQuizzes(roomId) : Promise.resolve(null)])
      .then(([detail, list]) => {
        if (!alive) return;
        setRoom(detail);
        setQuizzes(list);
        setStatus('ready');
      })
      .catch((cause: unknown) => {
        if (!alive) return;
        setStatus(isQuizRoomNotFound(cause) ? 'notFound' : 'error');
      });

    return () => {
      alive = false;
    };
  }, [isValidId, needsQuizzes, roomId]);

  /* 실시간 구독. 떠날 때 close → leave 순서를 지킨다(머리말 참고). */
  useEffect(() => {
    if (!isValidId) return;

    let alive = true;

    const subscription = subscribeQuizRoom(roomId, {
      onPresence: (count) => {
        if (alive) setPresence(count);
      },
      onVotes: (votes) => {
        if (alive) setVotesByQuiz((prev) => ({ ...prev, [votes.quizId]: votes.options }));
      },
      onOpen: () => {
        if (alive) setLiveStatus('live');
      },
      onReconnecting: () => {
        if (alive) setLiveStatus('reconnecting');
      },
      onError: (error) => {
        if (!alive) return;
        // 방이 없으면 상세도 같은 404 를 받는다 — 어느 쪽이 먼저 와도 같은 안내가 된다.
        if (isQuizRoomNotFound(error)) setStatus('notFound');
        else setLiveStatus('offline');
      },
    });

    return () => {
      alive = false;
      subscription.close();
      // 전면 멱등이고 정의된 실패가 401 뿐이라 결과는 보지 않는다(안 되면 하트비트 만료가 회수).
      void leaveQuizRoom(roomId).catch(() => {});
    };
  }, [isValidId, roomId]);

  /* ---------------------------------------------------------------- *
   * 추천 — 토글이 아니라 지정(PUT)이다
   * ---------------------------------------------------------------- */

  const handleLike = async () => {
    if (room === null || likePendingRef.current) return;

    likePendingRef.current = true;
    setIsLikePending(true);

    try {
      // 지금의 반대 값을 **지정**한다. 응답의 liked · likeCount 가 정본이라 그대로 덮어쓴다.
      const next = await setQuizRoomLike(roomId, !room.liked);
      if (!aliveRef.current) return;
      setRoom((prev) => (prev === null ? prev : { ...prev, liked: next.liked, likeCount: next.likeCount }));
    } catch (cause: unknown) {
      if (!aliveRef.current) return;
      if (isQuizRoomNotFound(cause)) setStatus('notFound');
      else setNotice('추천을 반영하지 못했어요. 잠시 후 다시 시도해 주세요.');
    } finally {
      likePendingRef.current = false;
      if (aliveRef.current) setIsLikePending(false);
    }
  };

  /* ---------------------------------------------------------------- *
   * 풀이
   * ---------------------------------------------------------------- */

  const quiz = isSolving && quizzes !== null ? (quizzes[index] ?? null) : null;
  const current = quiz !== null ? progress[quiz.quizId] : undefined;
  const isEnd = isSolving && quizzes !== null && index >= quizzes.length;

  /**
   * 다음 문항으로. 결과를 이미 받았으면 남은 문항이 있어도 결과 화면을 연다 — 결과가 왔다는 건
   * 방의 모든 문항에 답이 쌓였다는 뜻이라, 남은 문항은 눌러 봐야 409 뿐이다.
   */
  const goNext = () => {
    setSubmitError(null);
    if (result !== null) {
      setIsResultOpen(true);
      return;
    }
    setIndex((prev) => prev + 1);
  };

  const handleSelect = async (option: QuizOption) => {
    if (quiz === null || current !== undefined || submittingRef.current) return;

    submittingRef.current = true;
    setIsSubmitting(true);
    setSubmitError(null);
    // 앞 문항에서 남은 안내는 지운다 — 이번 문항 이야기로 오해되면 안 된다.
    setSkipNotice(null);

    const target = quiz;

    try {
      const answer = await submitQuizRoomAnswer(roomId, target.quizId, option.no);
      if (!aliveRef.current) return;

      setProgress((prev) => ({ ...prev, [target.quizId]: { kind: 'answered', myOption: option.no } }));

      /*
       * 결과는 이 응답에만 있다 — 받는 즉시 state 와 저장소 두 곳에 남긴다.
       * 화면에서는 아직 마지막 문항이 아닐 수도 있다(지난 방문에 뒤쪽 문항을 먼저 풀어 둔 경우).
       * 그래도 방은 다 푼 것이라 아래 버튼이 "결과 보기"로 바뀐다.
       */
      if (answer.result !== null) {
        saveResult(roomId, answer.result);
        setResult(answer.result);
      }
    } catch (cause: unknown) {
      if (!aliveRef.current) return;

      if (isQuizRoomNotFound(cause)) {
        setStatus('notFound');
        return;
      }

      /*
       * 409 는 실패가 아니라 "예전에 이미 답한 문항"이다 — 진행 조회 API 가 없어 여기서야
       * 알게 된다. 오류로 띄우지 않고 표시만 남긴 채 다음으로 넘긴다.
       * 고른 답은 알 수 없으므로 이 문항의 선택율은 보여 주지 않는다.
       */
      if (isQuizRoomAlreadySubmitted(cause)) {
        setProgress((prev) => ({ ...prev, [target.quizId]: { kind: 'already' } }));
        setSkipNotice(ALREADY_ANSWERED_NOTICE);
        goNext();
        return;
      }

      // 이 방의 문항이 아니라고 한다 — 다시 골라도 같으니 넘어갈 수 있게 표시한다.
      if (isQuizRoomQuizNotFound(cause)) {
        setProgress((prev) => ({ ...prev, [target.quizId]: { kind: 'missing' } }));
      }

      // 그 밖(400 보기 · 네트워크 · 5xx)은 답이 저장되지 않았다 — 같은 문항에서 다시 고르게 한다.
      setSubmitError(toSubmitMessage(cause));
    } finally {
      submittingRef.current = false;
      if (aliveRef.current) setIsSubmitting(false);
    }
  };

  /* ---------------------------------------------------------------- *
   * 그리기
   * ---------------------------------------------------------------- */

  const solvingCount = presence ?? room?.solvingCount ?? null;

  const handleBack = () => {
    if (location.key !== 'default') navigate(-1);
    else navigate(communityQuizRoomsPath);
  };

  const topbar = (
    <header className="quiz-room-page__topbar">
      <button className="quiz-room-page__back" type="button" onClick={handleBack} aria-label="뒤로 가기">
        <span className="quiz-room-page__back-icon" aria-hidden="true" />
      </button>
      <h1 className="quiz-room-page__topbar-title">퀴즈방</h1>
      <div className="quiz-room-page__topbar-side">
        {status === 'ready' && solvingCount !== null && (
          // 값이 바뀔 때만 오는 숫자라 바뀌면 조용히 읽어 준다.
          <p className="quiz-room-page__presence" aria-live="polite">
            <span className="quiz-room-page__presence-dot" aria-hidden="true" />
            풀이 중 {solvingCount}명
          </p>
        )}
      </div>
    </header>
  );

  const sheets = notice !== null && <NoticeSheet message={notice} onClose={() => setNotice(null)} />;

  if (status !== 'ready' || room === null) {
    return (
      <div className="quiz-room-page">
        {topbar}
        <div className="quiz-room-page__state">
          {status === 'loading' && <p className="quiz-room-page__state-text">퀴즈방을 불러오는 중입니다.</p>}

          {status === 'notFound' && (
            <>
              <span className="quiz-room-page__state-icon" aria-hidden="true" />
              <p className="quiz-room-page__state-title">존재하지 않는 퀴즈방이에요.</p>
            </>
          )}

          {status === 'error' && (
            <p className="quiz-room-page__state-text quiz-room-page__state-text--error">
              퀴즈방을 불러오지 못했습니다. 잠시 후 다시 시도해주세요.
            </p>
          )}

          {status !== 'loading' && (
            <Link className="quiz-room-page__state-link" to={communityQuizRoomsPath} replace>
              퀴즈방 목록으로 가기
            </Link>
          )}
        </div>
        {sheets}
      </div>
    );
  }

  const likeButton = (
    <button
      className={`quiz-room-page__like${room.liked ? ' quiz-room-page__like--active' : ''}`}
      type="button"
      aria-pressed={room.liked}
      onClick={() => void handleLike()}
      disabled={isLikePending}
    >
      <span className="quiz-room-page__like-icon" aria-hidden="true" />
      <span className="quiz-room-page__sr-only">추천</span>
      {room.likeCount}
    </button>
  );

  /** 방 머리말. 소개 · 결과 · 다 푼 안내 화면이 함께 쓴다(풀이 중에는 문항에 자리를 내준다). */
  const roomHead = (
    <div className="quiz-room-page__head">
      {room.isAuthor && <p className="quiz-room-page__author-badge">내가 만든 퀴즈방</p>}
      <h2 className="quiz-room-page__title">{room.title}</h2>

      <div className="quiz-room-page__author">
        <img
          className="quiz-room-page__avatar"
          src={toAssetUrl(room.author.profileImgUrl) ?? profilePlaceholder}
          onError={fallbackToPlaceholder}
          alt=""
        />
        <div className="quiz-room-page__author-text">
          <p className="quiz-room-page__nickname">{room.author.nickname}</p>
          <p className="quiz-room-page__meta">
            <time dateTime={room.createdAt}>{formatCommunityDateTime(room.createdAt)}</time>
            <span aria-hidden="true">·</span>
            <span>{room.quizCount}문항</span>
          </p>
        </div>
      </div>
    </div>
  );

  const liveLine =
    liveStatus === 'reconnecting' || liveStatus === 'offline' ? (
      <p className="quiz-room-page__live quiz-room-page__live--error" role="status">
        {liveStatus === 'reconnecting'
          ? '실시간 연결이 끊겨 다시 연결하는 중이에요.'
          : '실시간 정보를 받을 수 없어요. 풀이는 계속할 수 있어요.'}
      </p>
    ) : null;

  /* ── 결과 ───────────────────────────────────────────────────────── */
  if (result !== null && isResultOpen) {
    return (
      <div className="quiz-room-page">
        {topbar}
        {liveLine}
        <article className="quiz-room-page__article">
          {roomHead}
          <div className="quiz-room-page__actions">{likeButton}</div>
        </article>
        <QuizRoomResultView result={result} votesByQuiz={votesByQuiz} />
        <Link className="quiz-room-page__cta quiz-room-page__cta--secondary" to={communityQuizRoomsPath}>
          다른 퀴즈방 보러 가기
        </Link>
        {sheets}
      </div>
    );
  }

  /* ── 끝까지 갔는데 결과가 없다 — 예전에 다 푼 방 ─────────────────── */
  if (isEnd) {
    const progressList = Object.values(progress);
    const isAllAlready = progressList.length > 0 && progressList.every((item) => item.kind === 'already');

    return (
      <div className="quiz-room-page">
        {topbar}
        {liveLine}
        <article className="quiz-room-page__article">
          {roomHead}
          <div className="quiz-room-page__actions">{likeButton}</div>
        </article>
        <div className="quiz-room-page__state quiz-room-page__state--done">
          <span className="quiz-room-page__state-icon" aria-hidden="true" />
          <p className="quiz-room-page__state-title">
            {isAllAlready ? '이미 다 푼 퀴즈방이에요.' : '결과를 받지 못했어요.'}
          </p>
          <p className="quiz-room-page__state-text">
            {/* 이력 조회 API 가 없어 결과는 마지막 답을 낸 그 순간에만 받을 수 있다. */}
            퀴즈방 결과는 처음 다 풀었을 때만 볼 수 있어요.
          </p>
          <Link className="quiz-room-page__state-link" to={communityQuizRoomsPath}>
            다른 퀴즈방 보러 가기
          </Link>
        </div>
        {sheets}
      </div>
    );
  }

  /* ── 풀이 ───────────────────────────────────────────────────────── */
  if (quiz !== null && quizzes !== null) {
    const total = quizzes.length;
    const isOx = isOxPair(quiz);
    const myOption = current?.kind === 'answered' ? current.myOption : null;
    const isLocked = isSubmitting || current !== undefined;
    /* 방을 다 풀었으면(결과 수신) 남은 문항이 있어도 결과로 간다(위 handleSelect 주석 참고). */
    const nextLabel = result !== null ? '결과 보기' : index + 1 >= total ? '마치기' : '다음 문제';

    return (
      <div className="quiz-room-page quiz-room-page--solving">
        {topbar}
        {liveLine}

        <div className="quiz-room-page__progress">
          <p className="quiz-room-page__progress-title">{room.title}</p>
          <p className="quiz-room-page__progress-count">
            {index + 1} / {total}
          </p>
          <progress className="quiz-room-page__progress-bar" value={index + 1} max={total} aria-label="풀이 진행" />
        </div>

        <section className="quiz-room-page__quiz" aria-labelledby="quiz-room-page-question">
          <p className="quiz-room-page__quiz-order">Q{index + 1}.</p>
          <p className="quiz-room-page__question" id="quiz-room-page-question">
            {quiz.question}
          </p>

          <ul className={`quiz-room-page__options${isOx ? ' quiz-room-page__options--ox' : ''}`}>
            {quiz.options.map((option) => {
              const isSelected = myOption === option.no;
              const modifiers = [
                isOx ? ` quiz-room-page__option--${option.no === 0 ? 'o' : 'x'}` : '',
                isSelected ? ' quiz-room-page__option--selected' : '',
              ].join('');

              return (
                <li key={option.no}>
                  <button
                    className={`quiz-room-page__option${modifiers}`}
                    type="button"
                    disabled={isLocked}
                    aria-pressed={isSelected}
                    onClick={() => void handleSelect(option)}
                  >
                    {isOx ? (
                      <>
                        <span className="quiz-room-page__option-glyph" aria-hidden="true" />
                        <span className="quiz-room-page__sr-only">{option.text}</span>
                      </>
                    ) : (
                      option.text
                    )}
                  </button>
                </li>
              );
            })}
          </ul>
        </section>

        {isSubmitting && (
          <p className="quiz-room-page__status" role="status">
            답을 제출하는 중이에요
          </p>
        )}

        {/* 넘어간 문항 안내. 되돌릴 일이 아니라 경고(alert)가 아니라 알림(status)이다. */}
        {skipNotice !== null && (
          <p className="quiz-room-page__status quiz-room-page__status--notice" role="status">
            {skipNotice}
          </p>
        )}

        {submitError !== null && (
          <p className="quiz-room-page__status quiz-room-page__status--error" role="alert">
            {submitError}
          </p>
        )}

        {/* 답한 문항에서만 선택율을 보여 준다(머리말 "실시간" 참고). */}
        {current?.kind === 'answered' && (
          <section className="quiz-room-page__vote" aria-label="다른 사람들의 선택">
            <p className="quiz-room-page__vote-title">다른 사람들은 이렇게 골랐어요</p>
            <QuizRoomVoteBars options={quiz.options} votes={votesByQuiz[quiz.quizId]} myOption={myOption} />
          </section>
        )}

        {current !== undefined && (
          <button className="quiz-room-page__cta" type="button" onClick={goNext}>
            {nextLabel}
          </button>
        )}

        {sheets}
      </div>
    );
  }

  /* ── 소개(시작 전) ──────────────────────────────────────────────── */
  const canStart = quizzes !== null && quizzes.length > 0;

  return (
    <div className="quiz-room-page">
      {topbar}
      {liveLine}

      <article className="quiz-room-page__article">
        {roomHead}
        <p className="quiz-room-page__description">{room.description}</p>
        <div className="quiz-room-page__actions">{likeButton}</div>
      </article>

      <div className="quiz-room-page__start">
        <p className="quiz-room-page__start-hint">
          한 번 고른 답은 바꿀 수 없어요.
          <br />
          결과는 다 풀었을 때 한 번만 보여 드려요.
        </p>
        {quizzes !== null && quizzes.length === 0 && (
          <p className="quiz-room-page__status">풀 수 있는 문제가 없어요.</p>
        )}
        <button
          className="quiz-room-page__cta"
          type="button"
          onClick={() => setIsSolving(true)}
          disabled={!canStart}
        >
          퀴즈 풀기
        </button>
      </div>

      {sheets}
    </div>
  );
}
