import { useCallback, useEffect, useRef, useState } from 'react';
import { toAssetUrl, uploadCommunityImage } from '../api';
import { toCommunityMessage, validateCommunityImageFile } from '../utils/community';

/**
 * 글·댓글에 붙일 이미지 목록 하나.
 *
 * - `endpoint` — 서버에 보낼 EP. 새로 고른 사진은 업로드가 끝나야 생기고(`temp/…`),
 *   수정 화면이 넘겨받은 기존 사진은 처음부터 있다(`community/…`).
 * - `previewUrl` — 화면에 그릴 주소. 새 사진은 기기 안의 `blob:` 주소를 쓴다 — temp EP 는
 *   곧 `community/…` 로 옮겨지는 임시 키라, 화면이 그 주소에 기대지 않게 한다.
 */
export type CommunityImageItem = {
  id: string;
  endpoint: string | null;
  previewUrl: string;
  isUploading: boolean;
};

let sequence = 0;
function nextId() {
  sequence += 1;
  return `community-image-${sequence}`;
}

function fromEndpoints(endpoints: readonly string[]): CommunityImageItem[] {
  return endpoints.map((endpoint) => ({
    id: nextId(),
    endpoint,
    previewUrl: toAssetUrl(endpoint) ?? '',
    isUploading: false,
  }));
}

/**
 * 커뮤니티 이미지 첨부 — 고르는 즉시 한 장씩 선업로드하고 EP 를 모은다.
 *
 * ── 계약 ──────────────────────────────────────────────────────────────
 * - 업로드(`POST /community/images`)는 **한 번에 한 장**이다. 다섯 장이면 다섯 번 부른다.
 * - 받은 `temp/…` EP 를 글·댓글 요청의 `imageUrls[]` 에 실어야 확정된다. 확정되지 않은
 *   임시 객체는 서버가 정리하므로, 첨부를 빼거나 화면을 떠나도 따로 지울 일이 없다.
 * - 수정은 **전체 교체**라, 남길 기존 `community/…` EP 도 `endpoints` 에 그대로 들어간다.
 * - 상한(게시글 5 · 댓글 3)은 서버도 400 으로 막지만, 고를 때 먼저 잘라 왕복을 아낀다.
 * ──────────────────────────────────────────────────────────────────────
 */
export function useCommunityImageUploads(maxCount: number, initialEndpoints: readonly string[] = []) {
  const [items, setItems] = useState<CommunityImageItem[]>(() => fromEndpoints(initialEndpoints));
  const [error, setError] = useState<string | null>(null);

  /** 화면을 떠난 뒤 끝난 업로드가 state 를 건드리지 않게 막는다. */
  const aliveRef = useRef(true);
  /** 만든 `blob:` 주소. 지우거나 떠날 때 돌려줘야 메모리가 새지 않는다. */
  const objectUrlsRef = useRef(new Set<string>());

  useEffect(() => {
    aliveRef.current = true;
    const objectUrls = objectUrlsRef.current;
    return () => {
      aliveRef.current = false;
      objectUrls.forEach((url) => URL.revokeObjectURL(url));
      objectUrls.clear();
    };
  }, []);

  const releasePreview = (url: string) => {
    if (!objectUrlsRef.current.has(url)) return;
    URL.revokeObjectURL(url);
    objectUrlsRef.current.delete(url);
  };

  const addFiles = useCallback(
    (files: readonly File[]) => {
      setError(null);

      const room = maxCount - items.length;
      if (room <= 0) {
        setError(`사진은 ${maxCount}장까지 올릴 수 있어요.`);
        return;
      }

      const picked = files.slice(0, room);
      if (files.length > room) setError(`사진은 ${maxCount}장까지 올릴 수 있어요.`);

      picked.forEach((file) => {
        const invalid = validateCommunityImageFile(file);
        if (invalid !== null) {
          setError(invalid);
          return;
        }

        const previewUrl = URL.createObjectURL(file);
        objectUrlsRef.current.add(previewUrl);
        const id = nextId();

        setItems((prev) => [...prev, { id, endpoint: null, previewUrl, isUploading: true }]);

        uploadCommunityImage(file)
          .then((endpoint) => {
            if (!aliveRef.current) return;
            setItems((prev) =>
              prev.map((item) => (item.id === id ? { ...item, endpoint, isUploading: false } : item)),
            );
          })
          .catch((cause: unknown) => {
            if (!aliveRef.current) return;
            releasePreview(previewUrl);
            setItems((prev) => prev.filter((item) => item.id !== id));
            setError(
              toCommunityMessage(cause, '사진을 올리지 못했어요. 잠시 후 다시 시도해 주세요.', 'image'),
            );
          });
      });
    },
    [items.length, maxCount],
  );

  const remove = useCallback((id: string) => {
    setError(null);
    setItems((prev) => {
      const target = prev.find((item) => item.id === id);
      if (target) releasePreview(target.previewUrl);
      return prev.filter((item) => item.id !== id);
    });
  }, []);

  /** 다른 글·댓글로 옮겨 갈 때(댓글 입력창의 수정 모드 등) 목록을 통째로 바꾼다. */
  const reset = useCallback((endpoints: readonly string[] = []) => {
    objectUrlsRef.current.forEach((url) => URL.revokeObjectURL(url));
    objectUrlsRef.current.clear();
    setItems(fromEndpoints(endpoints));
    setError(null);
  }, []);

  const endpoints = items.flatMap((item) => (item.endpoint === null ? [] : [item.endpoint]));
  const isUploading = items.some((item) => item.isUploading);

  return {
    items,
    /** 요청 본문 `imageUrls` 에 그대로 싣는 값(올린 순서). 업로드 중인 사진은 아직 없다. */
    endpoints,
    isUploading,
    canAddMore: items.length < maxCount,
    error,
    addFiles,
    remove,
    reset,
  };
}
