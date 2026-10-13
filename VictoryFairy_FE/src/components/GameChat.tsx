import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react';
import { Link, useLocation } from 'react-router-dom';
import {
  ApiError,
  GAME_CHAT_MESSAGE_MAX_LENGTH,
  blockUser,
  createClientMsgId,
  getGameChatMessages,
  getGameChatRoom,
  isBlockTargetNotFound,
  isGameChatMessageInFlight,
  isGameChatMessageNotFound,
  isGameChatRateLimited,
  isGameChatRoomNotFound,
  isGameChatSelfReport,
  isSelfBlockNotAllowed,
  leaveGameChatRoom,
  likeGameChatRoom,
  reportGameChatMessage,
  sendGameChatMessage,
  subscribeGameChatRoom,
  toAssetUrl,
  validateGameChatMessageContent,
} from '../api';
import type { GameChatMessage, GameChatSubscription, SendGameChatMessageResult } from '../api';
import { getTeamDisplayByCode } from '../data/kboTeams';
import { ROUTES, type LoginPageState } from '../routes';
import { useMyNickname, useMyProfile } from '../stores/useAccountStore';
import profilePlaceholder from '../assets/profile_img.svg';
import ConfirmSheet from './ConfirmSheet';
import NoticeSheet from './NoticeSheet';
import '../styles/GameChat.css';

/** `sentAt`(`+09:00` 포함 ISO 문자열)의 `HH:mm`. 이미 KST 라 파싱하지 않고 잘라 쓴다. */
function formatSentAt(sentAt: string) {
  return sentAt.slice(11, 16);
}

/** 지금 시각을 서버 `sentAt` 과 같은 모양(KST, `+09:00`)으로. 내가 보낸 메시지에 붙인다. */
function nowInSeoulIso() {
  return new Date(Date.now() + 9 * 60 * 60 * 1000).toISOString().replace('Z', '+09:00');
}

/** 이미 그린 메시지에 새로 받은 것을 합친다. 같은 `msgId` 는 덮어쓰고 오름차순으로 둔다. */
function mergeMessages(prev: GameChatMessage[], incoming: GameChatMessage[]): GameChatMessage[] {
  const byId = new Map(prev.map((message) => [message.msgId, message]));
  for (const message of incoming) byId.set(message.msgId, message);
  return [...byId.values()].sort((a, b) => a.msgId - b.msgId);
}

/** 목록 끝에서 이만큼 안쪽에 있으면 "맨 아래를 보고 있다"고 보고 새 메시지를 따라 내려간다. */
const STICK_TO_BOTTOM_THRESHOLD_PX = 80;

/** 409(같은 메시지 처리 중)는 실패가 아니다 — 이만큼 기다렸다 같은 clientMsgId 로 다시 보낸다. */
const IN_FLIGHT_RETRY_DELAY_MS = 600;
const IN_FLIGHT_MAX_RETRIES = 3;

const wait = (ms: number) => new Promise<void>((resolve) => setTimeout(resolve, ms));

/**
 * 동시에 떠 있을 수 있는 응원 로고 수. 연타·여러 구단이 겹쳐도 DOM 이 한없이 늘지 않게
 * 넘치면 가장 오래된 것부터 지운다(서버도 구독자당 초당 10회 남짓으로 묶어 보낸다).
 */
const MAX_FLOATING_LIKES = 24;

/** 하트를 누르면 떠오르는 구단 로고 하나. 흔들림·경로는 하나씩 다르게 뽑는다. */
interface FloatingLike {
  id: number;
  /** 구단 로고. 모르는 코드면 null — 하트로 대신 띄운다. */
  logo: string | null;
  /** 좌우로 흔들리는 폭(px). 부호가 첫 방향이다. */
  sway: number;
  /** 다 올라갔을 때 출발점에서 비켜난 거리(px). */
  drift: number;
  /** 떠오르는 시간(ms). */
  duration: number;
}

/** min ~ max 사이 난수. */
const randomBetween = (min: number, max: number) => min + Math.random() * (max - min);

/** 하트 모양(24 x 24). 아이콘 에셋에 하트가 없어 직접 그린다. */
function HeartIcon({ className }: { className: string }) {
  return (
    <svg className={className} viewBox="0 0 24 24" aria-hidden="true">
      <path d="M12 21s-7.5-4.6-9.6-9.3C1 8.6 2.9 5 6.4 5c2.1 0 3.6 1.1 4.6 2.6l1 1.4 1-1.4C14 6.1 15.5 5 17.6 5 21.1 5 23 8.6 21.6 11.7 19.5 16.4 12 21 12 21z" />
    </svg>
  );
}

/** 화면 상태. `closed` 는 경기 당일이 아니거나 방이 없는 경우다. */
type ChatStatus = 'loading' | 'ready' | 'closed' | 'error';

/** 실시간 스트림 상태. 'live' 외에는 안내 줄을 띄운다. */
type LiveStatus = 'connecting' | 'live' | 'reconnecting' | 'offline';

type GameChatProps = {
  gameId: string;
  /** 오늘(KST) 경기인지. 서버의 채팅방은 오늘 경기에만 있어 아니면 API 를 부르지 않는다. */
  isOpenDay: boolean;
};

/**
 * 비로그인일 때 채팅 자리에 대신 서는 로그인 안내.
 *
 * 경기 상세는 로그인 없이 열리지만(App Store 심사 5.1.1(v)) 채팅은 계정 기능이다 — 읽기·구독부터
 * 인증 필수이고, 신고·차단도 계정 단위다. 그래서 API 를 부르지 않고 같은 틀 안에 안내만 둔다.
 * 로그인을 마치면 지금 보던 경기 상세로 돌아온다(`LoginPageState.from`).
 */
export function GameChatLoginPrompt() {
  const location = useLocation();
  const state: LoginPageState = { from: `${location.pathname}${location.search}` };

  return (
    <section className="game-chat" aria-label="경기 채팅">
      <div className="game-chat__head">
        <h2 className="game-chat__title">실시간 채팅</h2>
      </div>

      <div className="game-chat__messages">
        <p className="game-chat__notice">
          로그인하면 다른 팬들과 함께 응원할 수 있어요
          <br />
          <Link className="game-chat__login-link" to={ROUTES.login} state={state}>
            로그인하고 채팅 참여하기
          </Link>
        </p>
      </div>
    </section>
  );
}

/**
 * GameChat — 경기 상세 하단의 경기별 실시간 채팅(`docs/game-chat.md`).
 *
 * 생김새는 라운지 채팅(`LoungeChatSheet`)과 맞추고, 흐름은 새 API 계약을 따른다.
 * 1. 방 상세를 먼저 불러 방을 만들게 한다(히스토리는 방을 지연 생성하지 않는다).
 * 2. 최신 히스토리 30건을 그리고, 그중 가장 큰 msgId 를 `Last-Event-ID` 로 실어 구독한다 —
 *    히스토리와 구독 사이에 온 메시지를 서버가 메워 준다. 재연결 복구·중복 제거는 API 계층이 한다.
 * 3. `reset` 이 오면 목록을 비우고 히스토리를 다시 받는다. `deleted` 는 그 메시지를 지운다.
 *
 * 화면을 떠나면 스트림을 닫고 명시적 퇴장(DELETE)으로 서버 구독도 즉시 정리한다.
 */
export default function GameChat({ gameId, isOpenDay }: GameChatProps) {
  const myNickname = useMyNickname();
  const profile = useMyProfile();

  const [messages, setMessages] = useState<GameChatMessage[]>([]);
  const [status, setStatus] = useState<ChatStatus>(isOpenDay ? 'loading' : 'closed');
  const [liveStatus, setLiveStatus] = useState<LiveStatus>('connecting');
  /**
   * 방 접속 인원(SSE `presence`). 첫 값이 오기 전엔 null 이라 숨긴다. 재연결·끊김에도
   * 비우지 않는다 — Redis 장애 중엔 프레임이 안 오니 마지막 값을 들고 있어야 한다.
   */
  const [participantCount, setParticipantCount] = useState<number | null>(null);

  /** 지난 대화를 더 받을 커서(직전 페이지의 `nextCursor`). */
  const [cursor, setCursor] = useState<number | null>(null);
  const [hasOlder, setHasOlder] = useState(false);
  const [isLoadingOlder, setIsLoadingOlder] = useState(false);

  const [draft, setDraft] = useState('');
  const [isSending, setIsSending] = useState(false);
  const [sendError, setSendError] = useState<string | null>(null);
  /**
   * 실패한 전송의 본문과 clientMsgId. 같은 내용을 다시 보내면 **같은 id 를 재사용**한다 —
   * 503·네트워크 오류는 서버에 실제로 들어갔을 수도 있어, 새 id 로 보내면 두 번 올라간다.
   */
  const pendingRef = useRef<{ content: string; clientMsgId: string } | null>(null);

  /** "..." 메뉴(신고 · 차단). 라운지 채팅과 같은 동작이다. */
  const [menuTargetId, setMenuTargetId] = useState<number | null>(null);
  const [isReporting, setIsReporting] = useState(false);
  const [errorTargetId, setErrorTargetId] = useState<number | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);
  const [blockTarget, setBlockTarget] = useState<{ msgId: number; nickname: string } | null>(null);
  const [isBlocking, setIsBlocking] = useState(false);
  const [actionNotice, setActionNotice] = useState<string | null>(null);

  const listRef = useRef<HTMLOListElement>(null);
  const stickToBottomRef = useRef(true);

  /**
   * 좋아요 — 떠오르는 구단 로고들.
   *
   * 하트를 눌러도 **여기서 바로 띄우지 않는다.** 서버가 본인 좋아요도 SSE `likes` 로 되돌려
   * 주므로(docs/game-chat.md) 로컬에서도 띄우면 두 번 재생된다. 받은 신호만 그린다 —
   * 그래서 남이 누른 좋아요와 내 좋아요가 같은 모양으로 함께 올라온다.
   */
  const [floatingLikes, setFloatingLikes] = useState<FloatingLike[]>([]);
  const floatingIdRef = useRef(0);

  /** `likes` 이벤트 한 번 — 배열에 든 구단마다 로고를 하나씩 띄운다. */
  const spawnLikes = useCallback((teamCodes: string[]) => {
    const spawned = teamCodes.map((code): FloatingLike => {
      floatingIdRef.current += 1;
      return {
        id: floatingIdRef.current,
        logo: getTeamDisplayByCode(code)?.logo ?? null,
        sway: randomBetween(10, 18) * (Math.random() < 0.5 ? -1 : 1),
        drift: randomBetween(-24, 24),
        duration: Math.round(randomBetween(1200, 1600)),
      };
    });
    setFloatingLikes((prev) => [...prev, ...spawned].slice(-MAX_FLOATING_LIKES));
  }, []);

  /** 다 올라가 사라진 로고를 치운다(애니메이션 끝). */
  const removeFloatingLike = (id: number) => {
    setFloatingLikes((prev) => prev.filter((like) => like.id !== id));
  };

  /**
   * 하트 버튼. 202 는 "받았다"일 뿐이라 결과를 기다리지도, 실패를 알리지도 않는다 —
   * 초당 10회 초과·응원 구단 없음 등은 서버가 조용히 버린다(그 경우 로고도 안 올라온다).
   */
  const handleLike = () => {
    void likeGameChatRoom(gameId).catch(() => {});
  };

  /*
   * 방 확인 → 히스토리 → 구독. 경기가 바뀌면 이전 방을 끊고 처음부터 다시 한다.
   */
  useEffect(() => {
    setMessages([]);
    setCursor(null);
    setHasOlder(false);
    setSendError(null);
    setLiveStatus('connecting');
    setParticipantCount(null);
    pendingRef.current = null;
    stickToBottomRef.current = true;

    if (!isOpenDay) {
      setStatus('closed');
      return;
    }

    let alive = true;
    let subscription: GameChatSubscription | null = null;
    setStatus('loading');

    /** 최신 페이지로 목록을 갈아 끼운다. 첫 진입과 `reset` 이 함께 쓴다. */
    const loadLatest = async () => {
      const page = await getGameChatMessages(gameId);
      if (!alive) return null;
      setMessages(mergeMessages([], page.messages));
      setCursor(page.nextCursor);
      setHasOlder(page.hasNext);
      stickToBottomRef.current = true;
      return page;
    };

    const start = async () => {
      // 상세가 오늘 경기의 방을 만들어 둔다 — 히스토리는 방이 없으면 404 다.
      await getGameChatRoom(gameId);
      const page = await loadLatest();
      if (!alive || !page) return;
      setStatus('ready');

      const latestMsgId = page.messages.reduce<number | null>(
        (max, message) => (max === null || message.msgId > max ? message.msgId : max),
        null,
      );

      subscription = subscribeGameChatRoom(
        gameId,
        {
          onMessages: (incoming) => {
            if (alive) setMessages((prev) => mergeMessages(prev, incoming));
          },
          onLikes: (teamCodes) => {
            if (alive) spawnLikes(teamCodes);
          },
          onPresence: (count) => {
            if (alive) setParticipantCount(count);
          },
          onDeleted: (msgId) => {
            if (alive) setMessages((prev) => prev.filter((message) => message.msgId !== msgId));
          },
          onReset: () => {
            if (!alive) return;
            setMessages([]);
            void loadLatest().catch(() => {
              if (alive) setStatus('error');
            });
          },
          onOpen: () => {
            if (alive) setLiveStatus('live');
          },
          onReconnecting: () => {
            if (alive) setLiveStatus('reconnecting');
          },
          onError: (error) => {
            if (!alive) return;
            if (isGameChatRoomNotFound(error)) setStatus('closed');
            else setLiveStatus('offline');
          },
        },
        { lastMsgId: latestMsgId },
      );
    };

    start().catch((error: unknown) => {
      if (!alive) return;
      setStatus(isGameChatRoomNotFound(error) ? 'closed' : 'error');
    });

    return () => {
      alive = false;
      if (subscription) {
        subscription.close();
        // 전면 멱등이고 정의된 실패가 401뿐이라 결과는 보지 않는다(안 되면 서버 타임아웃이 회수).
        void leaveGameChatRoom(gameId).catch(() => {});
      }
    };
  }, [gameId, isOpenDay, spawnLikes]);

  /* 경기가 바뀌면 이전 경기의 로고가 남지 않게 비운다. */
  useEffect(() => {
    setFloatingLikes([]);
  }, [gameId]);

  /* 새 메시지가 붙으면 목록 끝으로 따라 내려간다(맨 아래를 보고 있을 때만). */
  useEffect(() => {
    const list = listRef.current;
    if (list && stickToBottomRef.current) list.scrollTop = list.scrollHeight;
  }, [messages]);

  const handleScroll = () => {
    const list = listRef.current;
    if (!list) return;
    const distanceFromBottom = list.scrollHeight - list.scrollTop - list.clientHeight;
    stickToBottomRef.current = distanceFromBottom < STICK_TO_BOTTOM_THRESHOLD_PX;
  };

  /** 지난 대화 30건을 더 받아 위에 붙이고, 읽던 자리는 그대로 둔다. */
  const loadOlderMessages = async () => {
    if (!hasOlder || isLoadingOlder || cursor === null) return;

    const heightBefore = listRef.current?.scrollHeight ?? 0;
    setIsLoadingOlder(true);
    try {
      const page = await getGameChatMessages(gameId, cursor);
      stickToBottomRef.current = false;
      setMessages((prev) => mergeMessages(prev, page.messages));
      setCursor(page.nextCursor);
      setHasOlder(page.hasNext);

      requestAnimationFrame(() => {
        const list = listRef.current;
        if (list) list.scrollTop += list.scrollHeight - heightBefore;
      });
    } catch {
      /* 다시 누르면 된다 — 실패를 알릴 만큼 중요한 조작은 아니다 */
    } finally {
      setIsLoadingOlder(false);
    }
  };

  /* ---------------------------------------------------------------- *
   * 전송
   * ---------------------------------------------------------------- */

  /** 409 는 첫 요청이 아직 처리 중이라는 뜻이라 잠시 뒤 같은 id 로 다시 보낸다. */
  const sendWithRetry = async (content: string, clientMsgId: string) => {
    for (let attempt = 0; ; attempt += 1) {
      try {
        return await sendGameChatMessage(gameId, { content, clientMsgId });
      } catch (error) {
        if (!isGameChatMessageInFlight(error) || attempt >= IN_FLIGHT_MAX_RETRIES) throw error;
        await wait(IN_FLIGHT_RETRY_DELAY_MS);
      }
    }
  };

  const appendMine = (sent: SendGameChatMessageResult) => {
    // 발신자에게는 SSE 에코가 없으므로 202 응답(마스킹된 본문)으로 직접 그린다.
    const mine: GameChatMessage = {
      msgId: sent.msgId,
      content: sent.content,
      senderNickname: myNickname ?? '',
      teamCode: null,
      profileImgUrl: profile?.profileImgUrl ?? null,
      sentAt: nowInSeoulIso(),
    };
    stickToBottomRef.current = true;
    setMessages((prev) => mergeMessages(prev, [mine]));
  };

  const handleSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (status !== 'ready' || isSending) return;

    const content = draft.trim();
    const invalidReason = validateGameChatMessageContent(content);
    if (invalidReason) {
      setSendError(content.length === 0 ? null : invalidReason);
      return;
    }

    const pending =
      pendingRef.current?.content === content
        ? pendingRef.current
        : { content, clientMsgId: createClientMsgId() };
    pendingRef.current = pending;

    setIsSending(true);
    setSendError(null);

    try {
      const sent = await sendWithRetry(pending.content, pending.clientMsgId);
      pendingRef.current = null;
      appendMine(sent);
      setDraft('');
    } catch (error) {
      if (isGameChatRoomNotFound(error)) {
        pendingRef.current = null;
        setStatus('closed');
      } else if (isGameChatRateLimited(error)) {
        setSendError('메시지를 너무 빠르게 보내고 있어요. 잠시 후 다시 보내주세요');
      } else if (error instanceof ApiError && error.status === 400) {
        // 본문 자체가 거절됐다 — 같은 id 를 다시 쓸 이유가 없다.
        pendingRef.current = null;
        setSendError(error.fieldErrors?.content ?? '보낼 수 없는 메시지예요');
      } else {
        // 503·409 재시도 소진·네트워크 오류 — 다시 보내면 같은 id 로 나가 중복되지 않는다.
        setSendError('메시지를 보내지 못했어요. 다시 시도해주세요');
      }
    } finally {
      setIsSending(false);
    }
  };

  /* ---------------------------------------------------------------- *
   * "..." 메뉴 — 신고 · 차단 (LoungeChatSheet 와 같은 흐름)
   * ---------------------------------------------------------------- */

  const closeMenu = useCallback(() => {
    setMenuTargetId(null);
    setErrorTargetId(null);
    setActionError(null);
    setBlockTarget(null);
    setActionNotice(null);
  }, []);

  /* 경기가 바뀌면 이전 방 메시지를 겨눈 메뉴가 남으면 안 된다. */
  useEffect(() => {
    closeMenu();
  }, [gameId, closeMenu]);

  /* 메뉴가 열려 있는 동안만 바깥 클릭 · Esc 를 감시해 닫는다. */
  useEffect(() => {
    if (menuTargetId === null) return;

    const handlePointerDown = (event: PointerEvent) => {
      const target = event.target;
      if (target instanceof Element && target.closest('.game-chat__menu-wrap')) return;
      setMenuTargetId(null);
    };
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setMenuTargetId(null);
    };

    document.addEventListener('pointerdown', handlePointerDown);
    document.addEventListener('keydown', handleKeyDown);
    return () => {
      document.removeEventListener('pointerdown', handlePointerDown);
      document.removeEventListener('keydown', handleKeyDown);
    };
  }, [menuTargetId]);

  const toggleMenu = (msgId: number) => {
    setErrorTargetId(null);
    setActionError(null);
    setMenuTargetId((current) => (current === msgId ? null : msgId));
  };

  /** 신고는 확인 없이 바로 나가고, 서버가 즉시 blind 한다. 내 화면에서는 직접 지운다. */
  const handleReport = async (msgId: number) => {
    if (isReporting) return;

    setIsReporting(true);
    setErrorTargetId(null);
    setActionError(null);

    try {
      await reportGameChatMessage(gameId, msgId);
      setMessages((prev) => prev.filter((message) => message.msgId !== msgId));
      closeMenu();
      setActionNotice('신고가 접수됐어요');
    } catch (error) {
      if (isGameChatRoomNotFound(error)) {
        setStatus('closed');
      } else if (isGameChatSelfReport(error)) {
        setErrorTargetId(msgId);
        setActionError('내가 보낸 메시지는 신고할 수 없어요');
      } else if (isGameChatMessageNotFound(error)) {
        // 방금 올라온 메시지는 적재가 늦어 404 일 수 있다 — 잠시 뒤 다시 누르면 된다.
        setErrorTargetId(msgId);
        setActionError('잠시 후 다시 신고해주세요');
      } else {
        setErrorTargetId(msgId);
        setActionError('신고하지 못했어요. 다시 시도해주세요');
      }
    } finally {
      setIsReporting(false);
    }
  };

  const openBlockConfirm = (message: GameChatMessage) => {
    setMenuTargetId(null);
    setErrorTargetId(null);
    setActionError(null);
    setBlockTarget({ msgId: message.msgId, nickname: message.senderNickname });
  };

  /**
   * 차단은 해제 API 가 없어 확인 시트를 거친다. 서버는 히스토리를 다시 부를 때만 걸러 주므로
   * 이미 그린 그 사람의 메시지는 여기서 치운다.
   */
  const handleBlockConfirm = async () => {
    if (!blockTarget || isBlocking) return;
    const { msgId, nickname } = blockTarget;

    setIsBlocking(true);
    try {
      await blockUser(nickname);
      setMessages((prev) => prev.filter((message) => message.senderNickname !== nickname));
      setBlockTarget(null);
      setActionNotice(`${nickname}님을 차단했어요`);
    } catch (error) {
      setBlockTarget(null);
      setErrorTargetId(msgId);
      if (isSelfBlockNotAllowed(error)) setActionError('나 자신은 차단할 수 없어요');
      else if (isBlockTargetNotFound(error)) setActionError('이미 탈퇴했거나 존재하지 않는 사용자예요');
      else setActionError('차단하지 못했어요. 다시 시도해주세요');
    } finally {
      setIsBlocking(false);
    }
  };

  /* ---------------------------------------------------------------- *
   * 렌더
   * ---------------------------------------------------------------- */

  const isReady = status === 'ready';
  const notice = {
    loading: '채팅방에 들어가는 중이에요',
    closed: '경기 채팅은 경기 당일에만 열려요',
    error: '채팅방을 불러오지 못했어요',
    ready: null,
  }[status];

  const liveNotice = {
    connecting: null,
    live: null,
    reconnecting: '실시간 연결이 끊겨 다시 연결하고 있어요',
    offline: '실시간 연결이 끊겼어요. 화면을 다시 열어주세요',
  }[liveStatus];

  return (
    <section className="game-chat" aria-label="경기 채팅">
      <div className="game-chat__head">
        <h2 className="game-chat__title">실시간 채팅</h2>
        {isReady && (
          <span
            className="game-chat__live"
            data-live={liveStatus === 'live' || undefined}
            aria-label={liveStatus === 'live' ? '실시간 연결됨' : '실시간 연결 중'}
          >
            LIVE
          </span>
        )}
        {isReady && participantCount !== null && (
          <span className="game-chat__presence" title="채팅방 접속 인원(근사치)">
            {participantCount.toLocaleString('ko-KR')}명
          </span>
        )}
      </div>

      {isReady && liveNotice && (
        <p className="game-chat__live-notice" role="status">
          {liveNotice}
        </p>
      )}

      <ol className="game-chat__messages" ref={listRef} onScroll={handleScroll}>
        {isReady && hasOlder && (
          <li className="game-chat__more-row">
            <button
              className="game-chat__more"
              type="button"
              onClick={() => void loadOlderMessages()}
              disabled={isLoadingOlder}
            >
              {isLoadingOlder ? '불러오는 중' : '지난 대화 더보기'}
            </button>
          </li>
        )}

        {!isReady && (
          <li className={`game-chat__notice${status === 'error' ? ' game-chat__notice--error' : ''}`}>
            {notice}
          </li>
        )}

        {isReady && messages.length === 0 && (
          <li className="game-chat__notice">아직 메시지가 없어요. 먼저 응원을 남겨보세요!</li>
        )}

        {isReady &&
          messages.map((message) => {
            // 닉네임은 중복될 수 없으므로 내 메시지 판별 기준으로 쓸 수 있다.
            if (message.senderNickname === myNickname) {
              return (
                <li className="game-chat__message game-chat__message--mine" key={message.msgId}>
                  <time className="game-chat__time" dateTime={message.sentAt}>
                    {formatSentAt(message.sentAt)}
                  </time>
                  <p className="game-chat__bubble game-chat__bubble--mine">{message.content}</p>
                </li>
              );
            }

            // 발신자 응원 구단(전송 시점 스냅샷). 경기 채팅이라 어느 쪽 팬인지가 중요하다.
            const team = getTeamDisplayByCode(message.teamCode);

            return (
              <li className="game-chat__message" key={message.msgId}>
                <img
                  className="game-chat__avatar"
                  src={toAssetUrl(message.profileImgUrl) ?? profilePlaceholder}
                  alt=""
                />
                <div className="game-chat__body">
                  <p className="game-chat__sender">
                    <span className="game-chat__sender-name">{message.senderNickname}</span>
                    {team && (
                      <img className="game-chat__team-logo" src={team.logo} alt={team.label} title={team.label} />
                    )}
                  </p>
                  <div className="game-chat__bubble-row">
                    <p className="game-chat__bubble">{message.content}</p>
                    <time className="game-chat__time" dateTime={message.sentAt}>
                      {formatSentAt(message.sentAt)}
                    </time>

                    <div className="game-chat__menu-wrap">
                      <button
                        className="game-chat__menu-trigger"
                        type="button"
                        aria-haspopup="menu"
                        aria-expanded={menuTargetId === message.msgId}
                        aria-label={`${message.senderNickname}님 메시지 더보기`}
                        onClick={() => toggleMenu(message.msgId)}
                      >
                        <span className="game-chat__menu-trigger-glyph" aria-hidden="true" />
                      </button>

                      {menuTargetId === message.msgId && (
                        <div className="game-chat__menu" role="menu">
                          <button
                            className="game-chat__menu-item"
                            type="button"
                            role="menuitem"
                            onClick={() => void handleReport(message.msgId)}
                            disabled={isReporting}
                          >
                            채팅 신고
                          </button>
                          <button
                            className="game-chat__menu-item game-chat__menu-item--danger"
                            type="button"
                            role="menuitem"
                            onClick={() => openBlockConfirm(message)}
                            disabled={isReporting}
                          >
                            이 사용자 차단
                          </button>
                        </div>
                      )}
                    </div>
                  </div>

                  {actionError && errorTargetId === message.msgId && (
                    <p className="game-chat__action-error" role="alert">
                      {actionError}
                    </p>
                  )}
                </div>
              </li>
            );
          })}
      </ol>

      {sendError && (
        <p className="game-chat__send-error" role="alert">
          {sendError}
        </p>
      )}

      <div className="game-chat__composer-row">
        {/*
          떠오르는 응원 로고. 하트 버튼 위에서 출발해 좌우로 흔들리며 140px 쯤 올라가다 사라진다.
          장식이라 보조기기에는 숨기고, 손가락도 가로채지 않는다(아래 메시지를 눌러야 한다).
        */}
        <div className="game-chat__likes" aria-hidden="true">
          {floatingLikes.map((like) => (
            <span
              className="game-chat__like"
              key={like.id}
              style={
                {
                  '--like-drift': `${like.drift}px`,
                  '--like-sway': `${like.sway}px`,
                  '--like-duration': `${like.duration}ms`,
                } as React.CSSProperties
              }
              onAnimationEnd={(event) => {
                // 안쪽 흔들림(무한 반복)이 아니라 바깥 상승이 끝났을 때만 치운다.
                if (event.target === event.currentTarget) removeFloatingLike(like.id);
              }}
            >
              <span className="game-chat__like-sway">
                {like.logo ? (
                  <img className="game-chat__like-logo" src={like.logo} alt="" />
                ) : (
                  <HeartIcon className="game-chat__like-heart" />
                )}
              </span>
            </span>
          ))}
        </div>

        <button
          className="game-chat__heart"
          type="button"
          onClick={handleLike}
          aria-label="응원하기"
          disabled={!isReady}
        >
          <HeartIcon className="game-chat__heart-icon" />
        </button>

        <form className="game-chat__composer" onSubmit={(event) => void handleSubmit(event)}>
          <input
            className="game-chat__input"
            type="text"
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
            placeholder={isReady ? '메세지를 입력해주세요' : '지금은 채팅할 수 없어요'}
            aria-label="메시지 입력"
            maxLength={GAME_CHAT_MESSAGE_MAX_LENGTH}
            autoComplete="off"
            disabled={!isReady}
          />
          <button
            className="game-chat__send"
            type="submit"
            aria-label="메시지 보내기"
            disabled={!isReady || isSending}
          >
            <span className="game-chat__send-icon" aria-hidden="true" />
          </button>
        </form>
      </div>

      {blockTarget && (
        <ConfirmSheet
          title={`${blockTarget.nickname}님을 차단하시겠어요?`}
          description={['차단하면 랭킹·채팅에서 이 사용자가 보이지 않아요', '이 작업은 되돌릴 수 없어요']}
          confirmLabel="차단"
          pendingLabel="차단 중"
          isPending={isBlocking}
          onConfirm={() => void handleBlockConfirm()}
          onClose={() => {
            if (isBlocking) return;
            setBlockTarget(null);
          }}
        />
      )}

      {actionNotice && <NoticeSheet message={actionNotice} onClose={() => setActionNotice(null)} />}
    </section>
  );
}
