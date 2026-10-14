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
import '../styles/StorySharePage.css';

/**
 * StorySharePage — 승리요정 스티커(인스타그램 스토리 공유).
 * Figma: SWM / [Sticker] 승요 스티커 메인-캐릭터 (1698:17508) · 메인-에셋 · 메인-텍스트 · 메인-숫자
 *
 * 위에서부터 상단 바 → 소개 배너 → 탭(캐릭터 · 에셋 · 텍스트 · 숫자) → 스티커 3열 격자다.
 * 스티커를 누르면 복사 바텀시트(`StickerShareSheet`)가 올라오고, 복사하기를 누르면
 * 그 그림을 앱에 넘겨 인스타 스토리 편집기를 연다.
 *
 * ── 이 화면이 실제로 하는 일 ──────────────────────────────────────────
 * 스티커 그림은 번들된 PNG 그대로다(`utils/shareStickers.ts`). 웹은 그 파일을 base64 로
 * 읽어 앱에 넘기기만 하고, 인스타로 건네는 것은 앱의 몫이다(`utils/instagramShare.ts`).
 * **배경색은 넘기지 않는다** — 스티커만 사진 위에 얹힌다.
 * ──────────────────────────────────────────────────────────────────────
 */

/** 결과별로 사용자에게 할 말. `opened` 는 인스타로 넘어간 뒤라 할 말이 없다. */
const OUTCOME_MESSAGE: Record<Exclude<InstagramShareOutcome, 'opened'>, string> = {
  'instagram-missing': '인스타그램이 설치되어 있지 않아요.',
  'not-configured': '지금은 공유할 수 없어요. 앱을 최신 버전으로 업데이트해 주세요.',
  failed: '공유하지 못했어요. 잠시 후 다시 시도해 주세요.',
};

const APP_ONLY_MESSAGE = '스토리 공유는 앱에서만 할 수 있어요.';

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
  const [message, setMessage] = useState<string | null>(null);

  /*
   * 결과는 앱이 전역 함수를 불러 알려준다. 화면이 떠 있는 동안 계속 듣는 이유는,
   * 인스타로 넘어갔다가 돌아오는 사이에도 이 화면이 그대로 남아 있기 때문이다.
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

  const handleShare = async () => {
    if (!selected || isSharing) {
      return;
    }

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
      const stickerBase64 = await loadStickerBase64(selected.src);
      requestInstagramShare({ stickerBase64 });
    } catch {
      setIsSharing(false);
      setMessage(OUTCOME_MESSAGE.failed);
    }
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

      {/* 배경 · 문구 · 휴대폰 그림까지 한 장에 담긴 배너다. 문구는 alt 로 남긴다. */}
      <img
        className="story-share-page__hero"
        src={bannerImage}
        alt="오늘의 야구 순간을 승요와 함께! 직관부터 집관까지, 스티커로 내 이야기를 꾸며보세요."
      />

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
          onShare={() => void handleShare()}
          onClose={closeSheet}
        />
      )}
    </main>
  );
}
