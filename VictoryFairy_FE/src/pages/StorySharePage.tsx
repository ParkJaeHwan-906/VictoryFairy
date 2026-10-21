import { useEffect, useState } from 'react';
import type { MouseEvent } from 'react';
import { useNavigate } from 'react-router-dom';
import StickerShareSheet from '../components/StickerShareSheet';
import bannerImage from '../assets/stickers/banner/sticker-banner.svg';
import {
  isInApp,
  isInstagramShareAvailable,
  requestInstagramShare,
  subscribeInstagramShareResult,
} from '../utils/instagramShare';
import type { InstagramShareOutcome } from '../utils/instagramShare';
import { loadStickerBase64, STICKER_CATEGORIES } from '../utils/shareStickers';
import type { ShareSticker, StickerCategory } from '../utils/shareStickers';
import {
  openStickerKeyboardSettings,
  requestStickerKeyboardStatus,
  shareWithStickerKeyboard,
  subscribeStickerKeyboardStatus,
} from '../utils/stickerKeyboard';
import type { StickerKeyboardShareOutcome, StickerKeyboardStatus } from '../utils/stickerKeyboard';
import '../styles/StorySharePage.css';

/**
 * StorySharePage — 승리요정 스티커(인스타그램 스토리 공유).
 * Figma: SWM / [Sticker] 승요 스티커 메인-캐릭터 (1698:17508) · 메인-에셋 · 메인-텍스트 · 메인-숫자
 *
 * 위에서부터 상단 바 → (담은 스티커) → 소개 배너 → 탭(캐릭터 · 에셋 · 텍스트 · 숫자) →
 * 스티커 3열 격자다. 스티커를 누르면 복사 바텀시트(`StickerShareSheet`)가 올라온다.
 *
 * ── 복사하기가 하는 일은 앱에 따라 다르다 ─────────────────────────────
 * - **키보드가 있는 앱(안드로이드)**: 복사하기는 스티커를 위쪽 트레이에 **담는다**. 담은
 *   스티커는 ✕로 뺄 수 있고, 'SNS에 올리기'를 누르면 앱이 담은 스티커만 승요 스티커
 *   키보드에 채운 뒤 인스타를 연다. 사용자는 스토리 글쓰기 칸에서 키보드로 하나씩 넣는다
 *   — 각각 따로 움직이는 스티커가 된다(`utils/stickerKeyboard.ts`).
 * - **그 밖의 앱(iOS · 키보드 이전 버전)**: 예전대로 그 한 장을 앱에 넘겨 인스타 스토리
 *   편집기를 연다(`utils/instagramShare.ts`). **배경색은 넘기지 않는다.**
 *
 * 어느 쪽인지는 앱의 답으로 안다. 키보드가 있는 앱은 상태를 물으면 답하고, 없는 앱은
 * 답하지 않는다 — 답이 오기 전에는 예전 방식이다.
 * ──────────────────────────────────────────────────────────────────────
 */

/** 결과별로 사용자에게 할 말. `opened` 는 인스타로 넘어간 뒤라 할 말이 없다. */
const OUTCOME_MESSAGE: Record<Exclude<InstagramShareOutcome, 'opened'>, string> = {
  'instagram-missing': '인스타그램이 설치되어 있지 않아요.',
  'not-configured': '지금은 공유할 수 없어요. 앱을 최신 버전으로 업데이트해 주세요.',
  failed: '공유하지 못했어요. 잠시 후 다시 시도해 주세요.',
};

const KEYBOARD_OUTCOME_MESSAGE: Record<Exclude<StickerKeyboardShareOutcome, 'opened'>, string> = {
  'instagram-missing': '인스타그램이 설치되어 있지 않아요.',
  failed: '스티커를 준비하지 못했어요. 잠시 후 다시 시도해 주세요.',
};

const APP_ONLY_MESSAGE = '스토리 공유는 앱에서만 할 수 있어요.';

const KEYBOARD_OFF_MESSAGE = '먼저 승요 스티커 키보드를 켜 주세요.';

export default function StorySharePage() {
  const navigate = useNavigate();

  /**
   * 브라우저인지 앱인지는 첫 렌더에 이미 정해져 있고 바뀌지 않는다.
   * 렌더마다 다시 묻지 않도록 state 초기값으로 한 번만 읽는다(알림 설정과 같은 방식).
   */
  const [isApp] = useState(isInApp);

  const [category, setCategory] = useState<StickerCategory>(STICKER_CATEGORIES[0]);
  /** 바텀시트에 띄운 스티커. `null` 이면 시트가 닫혀 있다. */
  const [selected, setSelected] = useState<ShareSticker | null>(null);
  /** 앱에 넘긴 뒤(또는 그림을 읽는 중) 답을 기다리는 중인지. */
  const [isSharing, setIsSharing] = useState(false);
  /** 바텀시트 안에 띄우는 한 줄. */
  const [message, setMessage] = useState<string | null>(null);

  /** 승요 스티커 키보드 상태. 앱이 답하기 전이면 `null` — 그동안은 예전 공유 방식이다. */
  const [keyboardStatus, setKeyboardStatus] = useState<StickerKeyboardStatus | null>(null);
  /** 담은 스티커. 담은 순서대로 트레이와 키보드에 놓인다. */
  const [tray, setTray] = useState<readonly ShareSticker[]>([]);
  /** 올리기를 누른 뒤 앱의 답을 기다리는 중인지. 처음 한 번은 그림을 받느라 몇 초 걸린다. */
  const [isUploading, setIsUploading] = useState(false);
  /** 트레이 아래에 띄우는 한 줄. */
  const [trayMessage, setTrayMessage] = useState<string | null>(null);

  const usesKeyboard = keyboardStatus?.available === true;

  /*
   * 키보드 상태는 앱이 전역 함수를 불러 알려준다. 화면을 열 때 한 번 묻고, 키보드를
   * 켰는지는 설정 화면에 다녀와야 바뀌므로 화면으로 돌아올 때마다 다시 묻는다.
   */
  useEffect(() => {
    if (!isApp) {
      return;
    }

    const unsubscribe = subscribeStickerKeyboardStatus((status) => {
      setKeyboardStatus(status);

      if (!status.shareOutcome) {
        return;
      }

      setIsUploading(false);
      setTrayMessage(status.shareOutcome === 'opened' ? null : KEYBOARD_OUTCOME_MESSAGE[status.shareOutcome]);
    });
    requestStickerKeyboardStatus();

    const handleVisibilityChange = () => {
      if (document.visibilityState === 'visible') {
        requestStickerKeyboardStatus();
      }
    };
    document.addEventListener('visibilitychange', handleVisibilityChange);

    return () => {
      unsubscribe();
      document.removeEventListener('visibilitychange', handleVisibilityChange);
    };
  }, [isApp]);

  /*
   * 예전 방식(한 장 공유)의 결과. 화면이 떠 있는 동안 계속 듣는 이유는, 인스타로
   * 넘어갔다가 돌아오는 사이에도 이 화면이 그대로 남아 있기 때문이다.
   */
  useEffect(() => {
    return subscribeInstagramShareResult((outcome) => {
      setIsSharing(false);

      // 편집기가 열렸으면 할 일은 끝났다 — 돌아왔을 때 시트가 남아 있지 않게 걷는다.
      if (outcome === 'opened') {
        setSelected(null);
        setMessage(null);
        return;
      }

      setMessage(OUTCOME_MESSAGE[outcome]);
    });
  }, []);

  const handleTabClick = (next: StickerCategory, event: MouseEvent<HTMLButtonElement>) => {
    setCategory(next);
    // 탭 줄이 화면보다 넓다(숫자 탭이 걸친다) — 누른 탭이 가려지지 않게 끌어 온다.
    event.currentTarget.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: 'smooth' });
  };

  const openSheet = (sticker: ShareSticker) => {
    setMessage(null);
    setSelected(sticker);
  };

  const closeSheet = () => {
    setSelected(null);
    setMessage(null);
    setIsSharing(false);
  };

  /** 트레이에 담는다. 같은 스티커를 두 번 담아도 키보드에는 한 칸이면 되므로 거른다. */
  const addToTray = (sticker: ShareSticker) => {
    if (tray.some((item) => item.id === sticker.id)) {
      setMessage('이미 담은 스티커예요.');
      return;
    }

    setTray([...tray, sticker]);
    setTrayMessage(null);
    closeSheet();
  };

  const removeFromTray = (sticker: ShareSticker) => {
    setTray(tray.filter((item) => item.id !== sticker.id));
    setTrayMessage(null);
  };

  const handleUpload = () => {
    if (tray.length === 0 || isUploading || !keyboardStatus) {
      return;
    }

    // 키보드가 꺼져 있으면 인스타에 가도 넣을 방법이 없다. 켜는 곳부터 보낸다.
    if (!keyboardStatus.enabled) {
      setTrayMessage(KEYBOARD_OFF_MESSAGE);
      openStickerKeyboardSettings();
      return;
    }

    setTrayMessage(null);
    setIsUploading(true);
    shareWithStickerKeyboard(tray);
  };

  /** 예전 방식 — 그 한 장을 앱에 넘겨 인스타 스토리 편집기를 연다. */
  const shareSingle = async (sticker: ShareSticker) => {
    if (!isApp) {
      setMessage(APP_ONLY_MESSAGE);
      return;
    }

    /*
     * 설치 여부는 **누르는 이 시점에** 확인한다. 앱은 문서를 띄울 때 이 값을 놓는데,
     * 화면이 먼저 그려질 수도 있어 첫 렌더에 읽으면 "아직 모름"을 "없음"으로 오해한다.
     */
    if (!isInstagramShareAvailable()) {
      setMessage(OUTCOME_MESSAGE['instagram-missing']);
      return;
    }

    setMessage(null);
    setIsSharing(true);

    try {
      const stickerBase64 = await loadStickerBase64(sticker.src);
      requestInstagramShare({ stickerBase64 });
    } catch {
      setIsSharing(false);
      setMessage(OUTCOME_MESSAGE.failed);
    }
  };

  const handleCopy = () => {
    if (!selected || isSharing) {
      return;
    }

    if (usesKeyboard) {
      addToTray(selected);
      return;
    }

    void shareSingle(selected);
  };

  return (
    <main className="story-share-page">
      <header className="story-share-page__topbar">
        <button
          className="story-share-page__back"
          type="button"
          onClick={() => navigate(-1)}
          aria-label="뒤로 가기"
        >
          <span className="story-share-page__back-icon" aria-hidden="true" />
        </button>
        <h1 className="story-share-page__topbar-title">승리요정 스티커</h1>
        {/* 도움말 — 무엇을 띄울지 아직 정해지지 않아 아이콘 자리만 둔다. */}
        <span className="story-share-page__help" aria-hidden="true">
          <span className="story-share-page__help-icon" />
        </span>
      </header>

      {/*
       * 담은 스티커 트레이 — 디자인 전 시험용. 상단 바 아래에 붙어 스크롤해도 남는다.
       * 카카오톡 친구 초대처럼 작은 아이콘이 쌓이고, ✕로 뺀다.
       */}
      {usesKeyboard && tray.length > 0 && (
        <section className="story-share-page__tray" aria-label="담은 스티커">
          <div className="story-share-page__tray-row">
            <ul className="story-share-page__tray-list">
              {tray.map((sticker) => (
                <li className="story-share-page__tray-item" key={sticker.id}>
                  <img className="story-share-page__tray-image" src={sticker.src} alt={sticker.name} />
                  <button
                    className="story-share-page__tray-remove"
                    type="button"
                    onClick={() => removeFromTray(sticker)}
                    aria-label={`${sticker.name} 빼기`}
                  />
                </li>
              ))}
            </ul>
            <button
              className="story-share-page__tray-upload"
              type="button"
              onClick={handleUpload}
              disabled={isUploading}
            >
              {isUploading ? '준비 중' : `SNS에 올리기 ${tray.length}`}
            </button>
          </div>
          {trayMessage && (
            <p className="story-share-page__tray-message" role="status">
              {trayMessage}
            </p>
          )}
        </section>
      )}

      {/* 배경 · 문구 · 휴대폰 그림까지 한 장에 담긴 배너다. 문구는 alt 로 남긴다. */}
      <img
        className="story-share-page__hero"
        src={bannerImage}
        alt="오늘의 야구 순간을 승요와 함께! 직관부터 집관까지, 스티커로 내 이야기를 꾸며보세요."
      />

      {/*
       * 승요 스티커 키보드 안내 — 디자인이 아직 없어 시험용으로 최소한만 둔다.
       * 꺼져 있으면 설정으로 보내고, 켜져 있으면 인스타에서 쓰는 법만 알려 준다.
       */}
      {usesKeyboard && (
        <section className="story-share-page__keyboard" aria-label="승요 스티커 키보드">
          <div className="story-share-page__keyboard-text">
            <p className="story-share-page__keyboard-title">승요 스티커 키보드</p>
            <p className="story-share-page__keyboard-description">
              {keyboardStatus.enabled
                ? '스티커를 담고 ‘SNS에 올리기’를 누른 뒤, 인스타 스토리 글쓰기에서 키보드를 ‘승요 스티커’로 바꿔 주세요.'
                : '키보드를 켜 두면 담은 스티커를 인스타 스토리에 여러 개 붙일 수 있어요.'}
            </p>
          </div>
          {!keyboardStatus.enabled && (
            <button
              className="story-share-page__keyboard-button"
              type="button"
              onClick={openStickerKeyboardSettings}
            >
              켜기
            </button>
          )}
        </section>
      )}

      <div className="story-share-page__tabs" role="tablist" aria-label="스티커 종류">
        {STICKER_CATEGORIES.map((item) => {
          const isActive = item.id === category.id;

          return (
            <button
              className={`story-share-page__tab${isActive ? ' story-share-page__tab--active' : ''}`}
              key={item.id}
              id={`story-share-tab-${item.id}`}
              type="button"
              role="tab"
              aria-selected={isActive}
              aria-controls="story-share-panel"
              onClick={(event) => handleTabClick(item, event)}
            >
              {item.label}
            </button>
          );
        })}
      </div>

      <ul
        className="story-share-page__grid"
        id="story-share-panel"
        role="tabpanel"
        aria-labelledby={`story-share-tab-${category.id}`}
      >
        {category.stickers.map((sticker) => (
          <li className="story-share-page__cell" key={sticker.id}>
            <button
              className="story-share-page__item"
              type="button"
              onClick={() => openSheet(sticker)}
              aria-label={sticker.name}
            >
              <img
                className="story-share-page__item-image"
                src={sticker.src}
                alt=""
                loading="lazy"
                decoding="async"
              />
            </button>
          </li>
        ))}
      </ul>

      {selected && (
        <StickerShareSheet
          title={category.sheetTitle}
          sticker={selected}
          isSharing={isSharing}
          message={message}
          onShare={handleCopy}
          onClose={closeSheet}
        />
      )}
    </main>
  );
}
