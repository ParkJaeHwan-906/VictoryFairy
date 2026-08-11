import {
  useCallback,
  useEffect,
  useRef,
  useState,
  type FormEvent,
  type PointerEvent as ReactPointerEvent,
} from 'react';
import {
  CHAT_MESSAGE_MAX_LENGTH,
  findMyTeamChatRoom,
  getChatMessages,
  isChatMessageNotFound,
  isChatRoomNotFound,
  isChatTeamMismatch,
  isSelfReport,
  isSupportTeamRequired,
  leaveChatRoom,
  reportChatMessage,
  sendChatMessage,
  subscribeChatRoom,
  validateChatMessageContent,
} from '../api';
import type { ChatMessage, ChatRoom } from '../api';
import { useAccountStore, useMyNickname, useSupportTeam } from '../stores/useAccountStore';
import profilePlaceholder from '../assets/profile_img.svg';
import '../styles/LoungeChatSheet.css';

/**
 * `createdAt`(LocalDateTime 문자열)에서 화면에 쓰는 `HH:mm` 만 잘라낸다.
 * 오프셋이 없는 문자열이라 Date 로 파싱하면 브라우저 타임존만큼 밀리므로 문자열을 그대로 자른다.
 */
function formatSentAt(createdAt: string) {
  return createdAt.slice(11, 16);
}

/** 목록 끝에서 이만큼 안쪽에 있으면 "맨 아래를 보고 있다"고 보고 새 메시지를 따라 내려간다. */
const STICK_TO_BOTTOM_THRESHOLD_PX = 80;

/**
 * 말풍선을 이만큼 누르고 있으면 신고 · 취소 아이콘이 나온다.
 *
 * 짧게 잡으면 목록을 넘기려다 메뉴가 뜬다. 아이콘을 누르면 확인 없이 바로 신고가
 * 나가므로, 모바일 기본값(500ms 안팎)보다 길게 잡아 실수로 열리는 쪽을 더 막는다.
 */
const LONG_PRESS_MS = 800;

/**
 * 누른 채 이만큼 움직이면 길게 누르기가 아니라 스크롤로 본다.
 *
 * 손가락은 누르는 동안에도 미세하게 움직인다 — 0 으로 두면 대부분의 길게 누르기가
 * 스크롤로 취소된다. 브라우저가 스크롤을 시작하면 `pointercancel` 도 오지만, 그건
 * 목록이 실제로 움직일 때뿐이라 맨 위·맨 아래에서는 오지 않는다.
 */
const PRESS_MOVE_TOLERANCE_PX = 10;

/**
 * 이미 그린 메시지에 새로 받은 것을 합친다. 같은 `id` 는 덮어쓴다.
 *
 * 히스토리(최신순)·SSE·전송 응답이 뒤섞여 들어오고 SSE 재연결 시 겹쳐서 오므로
 * 중복 제거가 필요하다. 정렬 키는 `createdAt` 이 아니라 `id` 다 —
 * 시각 문자열은 초 단위라 같은 값이 흔하지만 `id` 는 서버가 매기는 증가값이다.
 */
function mergeMessages(prev: ChatMessage[], incoming: ChatMessage[]): ChatMessage[] {
  const byId = new Map(prev.map((message) => [message.id, message]));
  for (const message of incoming) {
    byId.set(message.id, message);
  }
  return [...byId.values()].sort((a, b) => a.id - b.id);
}

/** 방을 찾고 히스토리를 받는 동안의 화면 상태. */
type ChatStatus = 'loading' | 'ready' | 'no-team' | 'no-room' | 'team-changed' | 'error';

/** 실시간 스트림 상태. 'live' 외에는 안내 줄을 띄운다. */
type LiveStatus = 'connecting' | 'live' | 'reconnecting' | 'offline';

type LoungeChatSheetProps = {
  /** 딤 클릭 · 핸들 클릭 · Esc 로 닫을 때 호출된다. */
  onClose: () => void;
};

/**
 * LoungeChatSheet — 라운지 채팅 바텀시트.
 * Figma: SWM / [Lounge] 라운지-챗 (시안) (node 744:21903)
 *
 * 라운지 메인의 채팅 버튼을 누르면 아래에서 올라온다.
 * 들어가는 방은 **내 응원 구단의 방**이다 — 전역 프로필의 응원 구단으로 방을 찾고,
 * 히스토리를 받은 뒤 SSE 로 새 메시지를 이어 받는다.
 */
export default function LoungeChatSheet({ onClose }: LoungeChatSheetProps) {
  const myNickname = useMyNickname();
  /** 서버가 "네 구단 정보가 틀렸다"(400·403)고 할 때만 쓴다 — 초기 로딩용이 아니다. */
  const fetchProfile = useAccountStore((state) => state.fetchProfile);
  /** 구단이 없는 것인지 프로필이 아직 안 온 것인지 가르는 데 쓴다. */
  const profileStatus = useAccountStore((state) => state.status);

  /** 들어갈 방을 정하는 값. 아직 고르지 않았거나 프로필 전이면 `null`. */
  const teamId = useSupportTeam()?.id ?? null;

  const [room, setRoom] = useState<ChatRoom | null>(null);
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [status, setStatus] = useState<ChatStatus>('loading');
  const [liveStatus, setLiveStatus] = useState<LiveStatus>('connecting');

  /** 지금까지 받아 온 히스토리 페이지 중 가장 오래된 페이지 번호. */
  const [oldestPage, setOldestPage] = useState(0);
  const [hasOlder, setHasOlder] = useState(false);
  const [isLoadingOlder, setIsLoadingOlder] = useState(false);

  const [draft, setDraft] = useState('');
  const [isSending, setIsSending] = useState(false);
  const [sendError, setSendError] = useState<string | null>(null);

  /*
   * 신고는 두 단계다 — 길게 누르는 중 → 신고 · 취소 아이콘.
   *
   * 아이콘을 누르면 확인 절차 없이 바로 신고가 나간다. 서버는 관리자 개입 없이 즉시
   * blind 처리하고 되돌릴 수 없으므로(`reportChatMessage` 주석), 실수를 막는 몫이
   * 전부 앞단계인 길게 누르기에 실린다 — `LONG_PRESS_MS` 를 넉넉히 잡은 이유다.
   */
  /** 지금 누르고 있는 메시지. 눌린 것을 보여주는 데만 쓴다. */
  const [pressingId, setPressingId] = useState<number | null>(null);
  /** 신고 · 취소 아이콘을 띄운 메시지. */
  const [reportTargetId, setReportTargetId] = useState<number | null>(null);
  const [isReporting, setIsReporting] = useState(false);
  const [reportError, setReportError] = useState<string | null>(null);

  const pressTimerRef = useRef<number | null>(null);
  /** 누르기 시작한 좌표. 여기서 얼마나 움직였는지로 스크롤인지 가른다. */
  const pressOriginRef = useRef<{ x: number; y: number } | null>(null);

  const listRef = useRef<HTMLOListElement>(null);
  /** 사용자가 맨 아래를 보고 있는지. 위로 올려 지난 대화를 읽는 중이면 끌어내리지 않는다. */
  const stickToBottomRef = useRef(true);

  /* Esc 로 닫기 — 시트가 떠 있는 동안에만 듣는다. */
  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        onClose();
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [onClose]);

  /* 시트가 떠 있는 동안 뒤 페이지가 같이 스크롤되지 않게 막는다. */
  useEffect(() => {
    const { overflow } = document.body.style;
    document.body.style.overflow = 'hidden';
    return () => {
      document.body.style.overflow = overflow;
    };
  }, []);

  /*
   * 구단이 없으면 들어갈 방이 없다 — 온보딩에서 아직 안 골랐다는 뜻이다.
   *
   * 단, **구단이 없는 것과 프로필을 아직 모르는 것은 다르다.** 둘 다 `teamId` 가
   * null 이라 구분하지 않으면 프로필이 오기 전에 구단 선택 안내가 뜬다. 실제로
   * 새로고침 직후 채팅을 열면 그랬다. 프로필이 `ready` 로 도착한 뒤에만 "구단 없음"
   * 으로 결론 내고, 그 전에는 로딩을 유지한다.
   *
   * 프로필은 여기서 받지 않는다. 보호 라우트(`ProtectedRoute`)가 채워 두므로 시트를
   * 열 때마다 `users/me` 를 다시 부를 이유가 없다.
   */
  useEffect(() => {
    if (teamId !== null) return;

    if (profileStatus === 'idle' || profileStatus === 'loading') {
      setStatus('loading');
      return;
    }

    // 프로필을 못 받아온 경우다. 구단을 안 골랐다고 안내하면 사실이 아니다.
    setStatus(profileStatus === 'error' ? 'error' : 'no-team');
  }, [teamId, profileStatus]);

  /*
   * 내 응원 구단의 방을 찾고 최신 히스토리 한 페이지를 받는다.
   *
   * 방 목록은 서버가 이미 응원 구단으로 좁혀 주므로(2026-08-04 구단 접근 제어) 여기서
   * 이름을 맞춰 볼 필요가 없다. teamId 는 "내가 아는 구단"이 서버 기준과 같은지 확인하는
   * 가드로 함께 보낸다 — 다르면 403 이 오고, 그건 프로필이 낡았다는 뜻이다.
   */
  useEffect(() => {
    if (teamId === null) {
      // 로그아웃 등으로 구단이 사라지면 이전 방의 구독부터 끊는다.
      setRoom(null);
      setMessages([]);
      return;
    }

    // 구단이 바뀌면 이전 방의 메시지가 잠깐이라도 보이면 안 된다.
    let alive = true;
    setStatus('loading');
    setRoom(null);
    setMessages([]);
    setOldestPage(0);
    setHasOlder(false);
    setSendError(null);
    setLiveStatus('connecting');
    stickToBottomRef.current = true;

    findMyTeamChatRoom(teamId)
      .then(async (found) => {
        if (!alive) return;
        if (!found) {
          setStatus('no-room');
          return;
        }

        const page = await getChatMessages(found.roomUid, 0);
        if (!alive) return;

        setRoom(found);
        setMessages(mergeMessages([], page.content));
        setHasOlder(page.hasNext);
        setStatus('ready');
      })
      .catch((error: unknown) => {
        if (!alive) return;

        if (isSupportTeamRequired(error)) {
          // 서버 기준으로는 응원 구단이 없다 — 우리가 들고 있던 프로필이 낡았다.
          setStatus('no-team');
        } else if (isChatTeamMismatch(error)) {
          // 다른 기기·화면에서 응원 구단을 바꿨다. 프로필을 다시 받아 두면 다음 열기는 맞는다.
          setStatus('team-changed');
        } else {
          setStatus('error');
        }

        if (isSupportTeamRequired(error) || isChatTeamMismatch(error)) {
          void fetchProfile();
        }
      });

    return () => {
      alive = false;
    };
  }, [teamId, fetchProfile]);

  /* 방이 정해지면 새 메시지를 SSE 로 이어 받는다. 시트를 닫으면 반드시 끊는다. */
  useEffect(() => {
    if (!room) return;

    const { roomUid } = room;
    let alive = true;

    const subscription = subscribeChatRoom(roomUid, {
      onMessage: (event) => {
        // 방을 옮기는 도중 늦게 도착한 이전 방의 이벤트를 걸러낸다.
        if (event.roomUid !== roomUid) return;
        setMessages((prev) => mergeMessages(prev, [event]));
      },
      onOpen: ({ reconnected }) => {
        setLiveStatus('live');
        if (!reconnected) return;

        // 서버가 `id:` 프레임을 주지 않아 Last-Event-ID 복구가 안 된다.
        // 끊긴 동안의 공백은 히스토리를 다시 받아 메운다(중복은 id 로 걸러진다).
        getChatMessages(roomUid, 0)
          .then((page) => {
            if (alive) setMessages((prev) => mergeMessages(prev, page.content));
          })
          .catch(() => {
            /* 다음 재연결에서 다시 메운다 */
          });
      },
      onReconnecting: () => setLiveStatus('reconnecting'),
      onError: () => setLiveStatus('offline'),
    });

    return () => {
      alive = false;
      subscription.close();
      /*
       * 스트림을 끊어도 서버 쪽 구독은 하트비트가 실패할 때까지(최대 30분) 남는다.
       * 명시적 퇴장으로 즉시 정리한다 — 전면 멱등이고 실패 응답이 없으므로 결과는 보지 않는다.
       */
      void leaveChatRoom(roomUid).catch(() => {
        /* 안 되면 서버의 타임아웃이 회수한다 */
      });
    };
  }, [room]);

  /* 새 메시지가 붙으면 목록 끝으로 따라 내려간다(맨 아래를 보고 있을 때만). */
  useEffect(() => {
    const list = listRef.current;
    if (list && stickToBottomRef.current) {
      list.scrollTop = list.scrollHeight;
    }
  }, [messages]);

  const handleScroll = () => {
    const list = listRef.current;
    if (!list) return;
    const distanceFromBottom = list.scrollHeight - list.scrollTop - list.clientHeight;
    stickToBottomRef.current = distanceFromBottom < STICK_TO_BOTTOM_THRESHOLD_PX;
  };

  /* ---------------------------------------------------------------- *
   * 길게 눌러 신고하기
   * ---------------------------------------------------------------- */

  /** 누르기를 접는다. 타이머만 끄고 이미 열린 메뉴는 건드리지 않는다. */
  const cancelPress = useCallback(() => {
    if (pressTimerRef.current !== null) {
      window.clearTimeout(pressTimerRef.current);
      pressTimerRef.current = null;
    }
    pressOriginRef.current = null;
    setPressingId(null);
  }, []);

  /** 열린 신고 UI 를 닫는다. */
  const closeReport = useCallback(() => {
    cancelPress();
    setReportTargetId(null);
    setReportError(null);
  }, [cancelPress]);

  /* 타이머가 남은 채로 시트가 닫히면 사라진 화면의 state 를 건드린다. */
  useEffect(() => cancelPress, [cancelPress]);

  /* 방이 바뀌면(구단 변경 등) 이전 방 메시지를 겨눈 메뉴가 남으면 안 된다. */
  useEffect(() => {
    closeReport();
  }, [room, closeReport]);

  const startPress = (messageId: number, event: ReactPointerEvent<HTMLElement>) => {
    // 마우스는 왼쪽 버튼만. 오른쪽 클릭은 브라우저 메뉴에 맡긴다.
    if (event.pointerType === 'mouse' && event.button !== 0) return;

    cancelPress();
    pressOriginRef.current = { x: event.clientX, y: event.clientY };
    setPressingId(messageId);

    pressTimerRef.current = window.setTimeout(() => {
      pressTimerRef.current = null;
      pressOriginRef.current = null;
      setPressingId(null);
      // 다른 메시지를 겨누고 있었다면 그쪽은 접는다 — 메뉴는 한 번에 하나다.
      setReportTargetId(messageId);
      setReportError(null);
    }, LONG_PRESS_MS);
  };

  const handlePressMove = (event: ReactPointerEvent<HTMLElement>) => {
    const origin = pressOriginRef.current;
    if (!origin) return;

    const moved = Math.hypot(event.clientX - origin.x, event.clientY - origin.y);
    if (moved > PRESS_MOVE_TOLERANCE_PX) cancelPress();
  };

  /**
   * 신고를 보낸다.
   *
   * 서버는 즉시 blind 처리하지만 **이미 그려진 메시지는 지워 주지 않는다**
   * (`reportChatMessage` 주석). 목록에서 빼는 건 여기 몫이다.
   */
  const handleReport = async (messageId: number) => {
    if (!room || isReporting) return;

    setIsReporting(true);
    setReportError(null);

    try {
      await reportChatMessage(room.roomUid, messageId);
      setMessages((prev) => prev.filter((message) => message.id !== messageId));
      closeReport();
    } catch (error) {
      if (isChatMessageNotFound(error)) {
        // 이미 지워졌거나 남이 먼저 신고해 blind 된 메시지다. 목적은 이뤄졌으므로 치운다.
        setMessages((prev) => prev.filter((message) => message.id !== messageId));
        closeReport();
      } else if (isChatRoomNotFound(error)) {
        setStatus('no-room');
        setRoom(null);
      } else if (isSupportTeamRequired(error) || isChatTeamMismatch(error)) {
        // 전송 실패와 같은 처리 — 이 방은 더 쓸 수 없다.
        setStatus(isSupportTeamRequired(error) ? 'no-team' : 'team-changed');
        setRoom(null);
        void fetchProfile();
      } else if (isSelfReport(error)) {
        // 내 메시지에는 아이콘을 띄우지 않으므로 정상 흐름에서는 오지 않는다.
        setReportError('내가 보낸 메시지는 신고할 수 없어요');
      } else {
        setReportError('신고하지 못했어요. 다시 시도해주세요');
      }
    } finally {
      setIsReporting(false);
    }
  };

  /**
   * 지난 대화 한 페이지(30개)를 더 받아 위에 붙인다.
   *
   * 페이징은 "지금 시점 기준 최신순"이라 대화가 오가는 동안 페이지 경계가 밀린다 —
   * 겹쳐 오는 메시지는 `id` 로 걸러지지만, 밀려서 건너뛴 메시지는 서버 계약상 되받을 수 없다.
   */
  const loadOlderMessages = useCallback(async () => {
    if (!room || !hasOlder || isLoadingOlder) return;

    const list = listRef.current;
    const heightBefore = list?.scrollHeight ?? 0;
    const nextPage = oldestPage + 1;

    setIsLoadingOlder(true);
    try {
      const page = await getChatMessages(room.roomUid, nextPage);
      setMessages((prev) => mergeMessages(prev, page.content));
      setOldestPage(nextPage);
      setHasOlder(page.hasNext);

      // 위에 붙은 높이만큼 스크롤을 내려 읽던 자리를 그대로 둔다.
      requestAnimationFrame(() => {
        const current = listRef.current;
        if (current) current.scrollTop += current.scrollHeight - heightBefore;
      });
    } catch {
      /* 버튼이 남아 있으니 다시 누르면 된다 */
    } finally {
      setIsLoadingOlder(false);
    }
  }, [room, hasOlder, isLoadingOlder, oldestPage]);

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (!room || isSending) return;

    const content = draft.trim();
    const invalidReason = validateChatMessageContent(content);
    if (invalidReason) {
      // 빈 입력은 안내할 것도 없이 그냥 무시한다.
      setSendError(content.length === 0 ? null : invalidReason);
      return;
    }

    setIsSending(true);
    setSendError(null);

    try {
      // 발신자에게는 SSE 에코가 오지 않으므로 전송 응답을 그대로 목록에 넣는다.
      const sent = await sendChatMessage(room.roomUid, { content });
      stickToBottomRef.current = true;
      setMessages((prev) => mergeMessages(prev, [sent]));
      setDraft('');
    } catch (error) {
      // 방이 사라졌거나(404) 그 사이 응원 구단이 바뀌었으면(400·403) 더 쓸 수 없는 방이다.
      if (isChatRoomNotFound(error)) {
        setStatus('no-room');
        setRoom(null);
      } else if (isSupportTeamRequired(error) || isChatTeamMismatch(error)) {
        setStatus(isSupportTeamRequired(error) ? 'no-team' : 'team-changed');
        setRoom(null);
        void fetchProfile();
      } else {
        setSendError('메시지를 보내지 못했어요. 다시 시도해주세요');
      }
    } finally {
      setIsSending(false);
    }
  };

  const isReady = status === 'ready';
  const notice = {
    loading: '채팅방을 불러오는 중이에요',
    'no-team': '응원 구단을 선택하면 구단 채팅방에 들어갈 수 있어요',
    'no-room': '아직 우리 구단 채팅방이 없어요',
    'team-changed': '응원 구단이 바뀌었어요. 채팅을 다시 열어주세요',
    error: '채팅방을 불러오지 못했어요',
    ready: null,
  }[status];

  const liveNotice = {
    connecting: null,
    live: null,
    reconnecting: '실시간 연결이 끊겨 다시 연결하고 있어요',
    offline: '실시간 연결이 끊겼어요. 채팅을 다시 열어주세요',
  }[liveStatus];

  return (
    <div className="lounge-chat">
      {/* 딤. 클릭하면 닫히지만 읽어 줄 내용은 없다. */}
      <button
        className="lounge-chat__dim"
        type="button"
        onClick={onClose}
        aria-label="라운지 채팅 닫기"
      />

      <section className="lounge-chat__sheet" role="dialog" aria-modal="true" aria-label="라운지 채팅">
        <button className="lounge-chat__handle" type="button" onClick={onClose}>
          <span className="lounge-chat__handle-bar" aria-hidden="true" />
          <span className="lounge-chat__handle-label">라운지 채팅 닫기</span>
        </button>

        {/* 방 이름을 받아 왔으면 그 이름을 쓴다(예: "두산 팩도방"). */}
        <h2 className="lounge-chat__title">{room?.name ?? '라운지 채팅'}</h2>

        {isReady && liveNotice && (
          <p className="lounge-chat__live-notice" role="status">
            {liveNotice}
          </p>
        )}

        <ol className="lounge-chat__messages" ref={listRef} onScroll={handleScroll}>
          {!isReady && (
            <li
              className={`lounge-chat__notice${status === 'error' ? ' lounge-chat__notice--error' : ''}`}
            >
              {notice}
            </li>
          )}

          {isReady && hasOlder && (
            <li className="lounge-chat__more">
              <button type="button" onClick={() => void loadOlderMessages()} disabled={isLoadingOlder}>
                {isLoadingOlder ? '불러오는 중…' : '이전 메시지 보기'}
              </button>
            </li>
          )}

          {isReady && messages.length === 0 && (
            <li className="lounge-chat__notice">아직 메시지가 없어요. 먼저 인사해보세요!</li>
          )}

          {isReady &&
            messages.map((message) =>
              // 닉네임은 중복될 수 없으므로 내 메시지 판별 기준으로 쓸 수 있다.
              message.senderNickname === myNickname ? (
                <li className="lounge-chat__message lounge-chat__message--mine" key={message.id}>
                  <time className="lounge-chat__time" dateTime={message.createdAt}>
                    {formatSentAt(message.createdAt)}
                  </time>
                  <p className="lounge-chat__bubble lounge-chat__bubble--mine">{message.content}</p>
                </li>
              ) : (
                <li className="lounge-chat__message" key={message.id}>
                  {/* 채팅 응답에는 발신자 프로필 사진이 없다(닉네임만 노출된다). */}
                  <img className="lounge-chat__avatar" src={profilePlaceholder} alt="" />
                  <div className="lounge-chat__body">
                    <p className="lounge-chat__sender">{message.senderNickname}</p>
                    <div className="lounge-chat__bubble-row">
                      {/*
                        길게 누르면 신고 아이콘이 나온다. 버튼으로 둔 건 키보드·스크린리더
                        에서도 닿게 하기 위해서다 — 길게 누르기에는 대응하는 키 입력이 없다.
                      */}
                      <button
                        className={`lounge-chat__bubble lounge-chat__bubble--pressable${
                          pressingId === message.id ? ' lounge-chat__bubble--pressing' : ''
                        }`}
                        type="button"
                        aria-haspopup="true"
                        aria-expanded={reportTargetId === message.id}
                        aria-label={`${message.senderNickname}님의 메시지. 길게 눌러 신고할 수 있어요`}
                        onPointerDown={(event) => startPress(message.id, event)}
                        onPointerMove={handlePressMove}
                        onPointerUp={cancelPress}
                        onPointerCancel={cancelPress}
                        onPointerLeave={cancelPress}
                        // 길게 누르면 모바일 브라우저가 자체 메뉴를 띄운다. 그건 우리 메뉴와 겹친다.
                        onContextMenu={(event) => event.preventDefault()}
                        onClick={(event) => {
                          // detail 0 은 키보드(Enter·Space)로 눌렀다는 뜻이다. 손가락 탭은 1 이상이라
                          // 짧게 스친 것만으로 메뉴가 열리지 않는다.
                          if (event.detail === 0) setReportTargetId(message.id);
                        }}
                      >
                        {message.content}
                      </button>
                      <time className="lounge-chat__time" dateTime={message.createdAt}>
                        {formatSentAt(message.createdAt)}
                      </time>

                      {/*
                        신고와 취소를 나란히 둔다. 누르면 확인 없이 바로 나가므로
                        취소를 같은 자리에 붙여, 잘못 열었을 때 손을 옮기지 않고 닫게 한다.
                      */}
                      {reportTargetId === message.id && (
                        <>
                          <button
                            className="lounge-chat__report-icon"
                            type="button"
                            aria-label="이 메시지 신고하기"
                            onClick={() => void handleReport(message.id)}
                            disabled={isReporting}
                          >
                            <span className="lounge-chat__report-icon-glyph" aria-hidden="true" />
                          </button>
                          <button
                            className="lounge-chat__report-icon lounge-chat__report-icon--cancel"
                            type="button"
                            aria-label="신고 취소"
                            onClick={closeReport}
                            disabled={isReporting}
                          >
                            <span className="lounge-chat__cancel-icon-glyph" aria-hidden="true" />
                          </button>
                        </>
                      )}
                    </div>

                    {reportError && reportTargetId === message.id && (
                      <p className="lounge-chat__report-error" role="alert">
                        {reportError}
                      </p>
                    )}
                  </div>
                </li>
              ),
            )}
        </ol>

        {sendError && (
          <p className="lounge-chat__send-error" role="alert">
            {sendError}
          </p>
        )}

        <form className="lounge-chat__composer" onSubmit={(event) => void handleSubmit(event)}>
          <input
            className="lounge-chat__input"
            type="text"
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
            placeholder="메세지를 입력해주세요"
            aria-label="메시지 입력"
            maxLength={CHAT_MESSAGE_MAX_LENGTH}
            autoComplete="off"
            disabled={!isReady}
          />
          <button
            className="lounge-chat__send"
            type="submit"
            aria-label="메시지 보내기"
            disabled={!isReady || isSending}
          >
            <span className="lounge-chat__send-icon" aria-hidden="true" />
          </button>
        </form>
      </section>
    </div>
  );
}
