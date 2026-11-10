import { useEffect, useRef, useState } from 'react';
import '../styles/CharacterAnimation.css';

/**
 * CharacterAnimation — 자잘한 안내 화면에 들어가는 캐릭터 애니메이션.
 *
 * 원본 mp4(`src/assets/animation/*.mp4`)에서 배경을 걷어 낸 **투명 애니메이션 WebP**
 * (512 x 512, 25fps)를 그린다. mp4 는 투명을 담지 못하고, 투명 WebM(VP9 alpha)은 iOS
 * Safari/WKWebView 가 재생하지 못해 모든 기기에서 투명하게 나오는 WebP 를 쓴다.
 * 반복 여부는 파일에 박혀 있다(완료·타임아웃은 1회, 나머지는 무한 반복).
 *
 * ── 왜 blob URL 로 그리나 ───────────────────────────────────────────────
 * 브라우저는 같은 주소의 움직이는 이미지끼리 재생 상태를 공유한다. 한 번만 도는 파일을
 * 같은 주소로 다시 붙이면 처음부터 돌지 않고 **이미 끝난 마지막 장면**이 뜬다
 * (타임아웃은 문제마다 다시 나온다). 붙일 때마다 새 blob URL 을 만들어 매번 처음부터
 * 돌게 한다. 바이트는 HTTP 캐시에서 오므로 다시 받지 않는다.
 * ──────────────────────────────────────────────────────────────────────
 */
type Props = {
  /** `import xxx from '../assets/animation/....webp'` 로 받은 주소. */
  src: string;
  /** 한 바퀴 길이(ms). `onEnded` 를 쓸 때만 필요하다. */
  durationMs?: number;
  /** 이미지가 뜬 뒤 `durationMs` 가 지나면 불린다. 이미지는 마지막 장면에 멈춰 있다. */
  onEnded?: () => void;
  className?: string;
};

export default function CharacterAnimation({ src, durationMs, onEnded, className }: Props) {
  const [url, setUrl] = useState<string | null>(null);
  const onEndedRef = useRef(onEnded);

  useEffect(() => {
    onEndedRef.current = onEnded;
  }, [onEnded]);

  useEffect(() => {
    let alive = true;
    let objectUrl: string | null = null;

    fetch(src)
      .then((response) => (response.ok ? response.blob() : Promise.reject(new Error())))
      .then((blob) => {
        if (!alive) return;
        objectUrl = URL.createObjectURL(blob);
        setUrl(objectUrl);
      })
      // 못 받아도 원래 주소로는 그려 본다 — 처음부터 안 돌 수는 있어도 빈 자리보다 낫다.
      .catch(() => {
        if (alive) setUrl(src);
      });

    return () => {
      alive = false;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [src]);

  /* 이미지가 실제로 뜬 시점부터 잰다 — 받는 동안의 시간을 재생 시간으로 치지 않는다. */
  const [loadedAt, setLoadedAt] = useState<number | null>(null);

  useEffect(() => {
    if (loadedAt === null || durationMs === undefined) return;

    const timer = window.setTimeout(() => onEndedRef.current?.(), durationMs);
    return () => {
      window.clearTimeout(timer);
    };
  }, [loadedAt, durationMs]);

  return (
    <span className={className ? `character-animation ${className}` : 'character-animation'}>
      {url !== null && (
        <img
          className="character-animation__image"
          src={url}
          alt=""
          // 장식이다 — 같은 내용을 옆 문구가 이미 말한다.
          aria-hidden="true"
          draggable={false}
          onLoad={() => setLoadedAt(Date.now())}
        />
      )}
    </span>
  );
}
