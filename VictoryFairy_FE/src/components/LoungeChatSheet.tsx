import { useEffect, useRef, useState, type FormEvent } from 'react';
import { LOUNGE_CHAT_MESSAGES } from '../data/loungeChat';
import profilePlaceholder from '../assets/profile_img.svg';
import type { LoungeChatMessage } from '../types/community';
import '../styles/LoungeChatSheet.css';

/**
 * `createdAt`(LocalDateTime 문자열)에서 화면에 쓰는 `HH:mm` 만 잘라낸다.
 * 오프셋이 없는 문자열이라 Date 로 파싱하면 브라우저 타임존만큼 밀리므로 문자열을 그대로 자른다.
 */
function formatSentAt(createdAt: string) {
  return createdAt.slice(11, 16);
}

type LoungeChatSheetProps = {
  /** 딤 클릭 · 핸들 클릭 · Esc 로 닫을 때 호출된다. */
  onClose: () => void;
};

/**
 * LoungeChatSheet — 라운지 채팅 바텀시트.
 * Figma: SWM / [Lounge] 라운지-챗 (시안) (node 744:21903)
 *
 * 라운지 메인의 채팅 버튼을 누르면 아래에서 올라온다.
 * 메시지는 아직 더미 데이터이며, 보낸 글은 화면에만 덧붙는다.
 */
export default function LoungeChatSheet({ onClose }: LoungeChatSheetProps) {
  // TODO: api-agent - chat 히스토리 조회 + SSE 구독으로 교체한다.
  const [messages, setMessages] = useState<LoungeChatMessage[]>(LOUNGE_CHAT_MESSAGES);
  const [draft, setDraft] = useState('');
  const listRef = useRef<HTMLOListElement>(null);

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

  /* 새 메시지가 붙으면 목록 끝으로 따라 내려간다. */
  useEffect(() => {
    const list = listRef.current;
    if (list) {
      list.scrollTop = list.scrollHeight;
    }
  }, [messages]);

  const handleSubmit = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();

    const content = draft.trim();
    if (!content) {
      return;
    }

    // TODO: api-agent - POST /chat/rooms/{roomUid}/messages 연결 시 서버 응답으로 대체한다.
    const now = new Date();
    const createdAt = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}-${String(
      now.getDate(),
    ).padStart(2, '0')}T${String(now.getHours()).padStart(2, '0')}:${String(
      now.getMinutes(),
    ).padStart(2, '0')}:00`;

    setMessages((prev) => [
      ...prev,
      {
        id: (prev.at(-1)?.id ?? 0) + 1,
        senderNickname: '나',
        content,
        createdAt,
        isMine: true,
      },
    ]);
    setDraft('');
  };

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

        <h2 className="lounge-chat__title">라운지 채팅</h2>

        <ol className="lounge-chat__messages" ref={listRef}>
          {messages.map((message) =>
            message.isMine ? (
              <li className="lounge-chat__message lounge-chat__message--mine" key={message.id}>
                <time className="lounge-chat__time" dateTime={message.createdAt}>
                  {formatSentAt(message.createdAt)}
                </time>
                <p className="lounge-chat__bubble lounge-chat__bubble--mine">{message.content}</p>
              </li>
            ) : (
              <li className="lounge-chat__message" key={message.id}>
                <img
                  className="lounge-chat__avatar"
                  src={message.avatarUrl ?? profilePlaceholder}
                  alt=""
                />
                <div className="lounge-chat__body">
                  <p className="lounge-chat__sender">{message.senderNickname}</p>
                  <div className="lounge-chat__bubble-row">
                    <p className="lounge-chat__bubble">{message.content}</p>
                    <time className="lounge-chat__time" dateTime={message.createdAt}>
                      {formatSentAt(message.createdAt)}
                    </time>
                  </div>
                </div>
              </li>
            ),
          )}
        </ol>

        <form className="lounge-chat__composer" onSubmit={handleSubmit}>
          <input
            className="lounge-chat__input"
            type="text"
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
            placeholder="메세지를 입력해주세요"
            aria-label="메시지 입력"
            maxLength={500}
            autoComplete="off"
          />
          <button className="lounge-chat__send" type="submit" aria-label="메시지 보내기">
            <span className="lounge-chat__send-icon" aria-hidden="true" />
          </button>
        </form>
      </section>
    </div>
  );
}
