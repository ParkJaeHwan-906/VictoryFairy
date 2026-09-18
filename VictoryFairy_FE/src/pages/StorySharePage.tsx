import { useEffect, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import type { StoryShareState } from '../routes';
import { useAccountStore, useMyProfile } from '../stores/useAccountStore';
import {
  isInApp,
  isInstagramShareAvailable,
  requestInstagramShare,
  subscribeInstagramShareResult,
} from '../utils/instagramShare';
import type { InstagramShareOutcome } from '../utils/instagramShare';
import { findShareSticker, SHARE_STICKERS } from '../utils/shareStickers';
import type { ShareSticker, ShareStickerPlan } from '../utils/shareStickers';
import { renderStickerImage } from '../utils/stickerCanvas';
import '../styles/StorySharePage.css';

/**
 * StorySharePage — 인스타그램 스토리 공유.
 *
 * 디자인이 아직 없어 다른 전체 화면들(알림 설정 · 캐릭터 꾸미기)의 상단 바와
 * 토큰을 그대로 따랐다. NavBar 가 없는 전체 화면이고 뒤로가기로 돌아간다.
 *
 * ── 이 화면이 실제로 하는 일 ──────────────────────────────────────────
 * 스티커 이미지를 **만드는 것까지**가 웹의 몫이다. 인스타로 건네는 것은 앱만 할 수
 * 있어서(`utils/instagramShare.ts` 머리말), 화면은 만든 그림을 앱에 넘기고 결과를
 * 돌려받아 문구만 바꾼다.
 *
 * 미리보기는 DOM 으로 따로 그리지 않고 **실제로 넘길 PNG 를 그대로 띄운다** —
 * 두 벌로 그리면 "화면에서 보던 것과 올라간 것이 다르다"가 생기고, 그 차이는
 * 인스타로 넘어간 뒤에야 보인다.
 * ──────────────────────────────────────────────────────────────────────
 *
 * ── 스티커 종류를 화면이 알지 못한다 ──────────────────────────────────
 * 지금은 내 캐릭터 하나뿐이고 경기 결과 · 퀴즈 결과가 뒤따를 예정이라,
 * 목록(`utils/shareStickers.ts`)을 순회하고 고른 항목의 `build()` 를 부르기만 한다.
 * 종류가 하나뿐이면 고르는 줄을 그리지 않는다 — 고를 것이 없는 선택지는 화면에서
 * 자리만 차지한다.
 * ──────────────────────────────────────────────────────────────────────
 */

/** 결과별로 사용자에게 할 말. `opened` 는 화면을 떠난 뒤라 할 말이 없다. */
const OUTCOME_MESSAGE: Record<Exclude<InstagramShareOutcome, 'opened'>, string> = {
  'instagram-missing': '인스타그램이 설치되어 있지 않아요.',
  'not-configured': '지금은 공유할 수 없어요. 앱을 최신 버전으로 업데이트해 주세요.',
  failed: '공유하지 못했어요. 잠시 후 다시 시도해 주세요.',
};

export default function StorySharePage() {
  const navigate = useNavigate();
  const location = useLocation();

  const profile = useMyProfile();
  const fetchProfile = useAccountStore((state) => state.fetchProfile);

  /**
   * 브라우저인지 앱인지는 첫 렌더에 이미 정해져 있고 바뀌지 않는다.
   * 렌더마다 다시 묻지 않도록 state 초기값으로 한 번만 읽는다(알림 설정과 같은 방식).
   */
  const [isApp] = useState(isInApp);

  const [sticker, setSticker] = useState<ShareSticker>(() =>
    findShareSticker((location.state as Partial<StoryShareState> | null)?.stickerId),
  );

  /** 만들어 둔 스티커 PNG(data URL). 미리보기와 공유가 같은 값을 쓴다. */
  const [preview, setPreview] = useState<string | null>(null);
  const [isRendering, setIsRendering] = useState(false);
  /** 그리지 못한 이유. 사용자가 고칠 수 있는 일이 아니라 문구 하나로 끝낸다. */
  const [renderError, setRenderError] = useState<string | null>(null);
  /** 앱에 넘긴 뒤 답을 기다리는 중인지. */
  const [isSharing, setIsSharing] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  // 새로고침으로 들어오면 스토어가 비어 있다 — 캐릭터 그림이 프로필에 실려 있어
  // 이것부터 채워야 스티커를 만들 수 있다.
  useEffect(() => {
    if (!profile) {
      void fetchProfile();
    }
  }, [fetchProfile, profile]);

  const plan: ShareStickerPlan | null = profile ? sticker.build(profile) : null;

  /*
   * 고른 스티커가 바뀌거나 프로필이 채워지면 다시 그린다.
   *
   * `plan` 을 의존성으로 두지 않는 것은 `build()` 가 렌더마다 새 객체를 만들기 때문이다
   * — 그대로 넣으면 그리기가 끝나 state 가 바뀔 때마다 다시 그려 무한히 돈다.
   */
  useEffect(() => {
    if (!profile) {
      return;
    }

    const target = sticker.build(profile);

    if (!target) {
      setPreview(null);
      setRenderError(null);
      return;
    }

    let isStale = false;

    setIsRendering(true);
    setRenderError(null);

    renderStickerImage(target.canvas)
      .then((dataUrl) => {
        if (!isStale) {
          setPreview(dataUrl);
        }
      })
      .catch((error: unknown) => {
        if (!isStale) {
          setPreview(null);
          setRenderError(
            error instanceof Error ? error.message : '스티커를 만들지 못했어요.',
          );
        }
      })
      .finally(() => {
        if (!isStale) {
          setIsRendering(false);
        }
      });

    // 그리는 도중 스티커를 바꾸면 늦게 끝난 앞 그림이 새 미리보기를 덮을 수 있다.
    return () => {
      isStale = true;
    };
  }, [profile, sticker]);

  /*
   * 결과는 앱이 전역 함수를 불러 알려준다. 화면이 떠 있는 동안 계속 듣는 이유는,
   * 인스타로 넘어갔다가 돌아오는 사이에도 이 화면이 그대로 남아 있기 때문이다.
   */
  useEffect(() => {
    return subscribeInstagramShareResult((outcome) => {
      setIsSharing(false);

      // 편집기가 열렸으면 사용자는 이미 인스타에 있다 — 돌아왔을 때 축하 문구가
      // 남아 있으면 올리지 않고 취소한 사람에게는 틀린 말이 된다.
      setMessage(outcome === 'opened' ? null : OUTCOME_MESSAGE[outcome]);
    });
  }, []);

  const handleShare = () => {
    if (!preview || !plan) {
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
    requestInstagramShare({
      stickerBase64: preview,
      backgroundTopColor: plan.backgroundTopColor,
      backgroundBottomColor: plan.backgroundBottomColor,
    });
  };

  const isEmpty = Boolean(profile) && !plan;

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
        <h1 className="story-share-page__topbar-title">스토리 공유</h1>
        {/* 디자인상 opacity 0 인 빈 버튼 — 타이틀을 가운데 두기 위한 자리 */}
        <span className="story-share-page__topbar-spacer" aria-hidden="true" />
      </header>

      {/*
        인스타에서 보게 될 모습 그대로다 — 스토리 비율(9:16) 판에 스티커가 정한
        그라데이션을 깔고 그 위에 만든 PNG 를 얹는다. 배경색을 인라인으로 주는 것은
        스티커마다 다른 값이라 CSS 로 미리 적어 둘 수 없기 때문이다.
      */}
      <div
        className="story-share-page__stage"
        style={
          plan
            ? {
                background: `linear-gradient(180deg, ${plan.backgroundTopColor} 0%, ${plan.backgroundBottomColor} 100%)`,
              }
            : undefined
        }
      >
        {preview && <img className="story-share-page__sticker" src={preview} alt="공유할 스티커 미리보기" />}

        {/*
          프로필을 아직 못 받았을 때도 같은 문구로 덮는다 — 사용자 입장에서는 "그림이
          준비되는 중"이라는 점이 같고, 비어 있는 판은 로딩인지 고장인지 구분되지 않는다.
        */}
        {(isRendering || !profile) && (
          <p className="story-share-page__stage-status">스티커를 만드는 중이에요.</p>
        )}

        {isEmpty && (
          <p className="story-share-page__stage-status">아직 공유할 수 있는 스티커가 없어요.</p>
        )}

        {renderError && (
          <p className="story-share-page__stage-status" role="alert">
            {renderError}
          </p>
        )}
      </div>

      {/*
        고를 것이 하나뿐이면 줄을 그리지 않는다. 종류가 늘면 저절로 나타난다 —
        꾸미기 화면의 부위 탭과 같은 모양이라 따로 익힐 것이 없다.
      */}
      {SHARE_STICKERS.length > 1 && (
        <nav className="story-share-page__tabs" aria-label="스티커 고르기">
          {SHARE_STICKERS.map((item) => (
            <button
              className={`story-share-page__tab${
                item.id === sticker.id ? ' story-share-page__tab--active' : ''
              }`}
              key={item.id}
              type="button"
              onClick={() => setSticker(item)}
              aria-current={item.id === sticker.id}
            >
              {item.label}
            </button>
          ))}
        </nav>
      )}

      <p className="story-share-page__description">{sticker.description}</p>

      {!isApp && (
        <p className="story-share-page__note">스토리 공유는 앱에서만 할 수 있어요.</p>
      )}

      {message && (
        <p className="story-share-page__note story-share-page__note--alert" role="status">
          {message}
        </p>
      )}

      <button
        className="story-share-page__submit"
        type="button"
        onClick={handleShare}
        disabled={!isApp || !preview || isSharing}
      >
        {isSharing ? '인스타그램을 여는 중' : '인스타그램 스토리로 공유'}
      </button>
    </main>
  );
}
