/**
 * 프로필 사진이 없거나 **깨졌을 때** 세울 기본 아바타.
 *
 * `null` 을 자리표시로 바꾸는 것은 어느 화면이나 하고 있었지만, 순위표에는 한 가지가
 * 더 있다. 서버가 `profileImgUrl` 의 실존을 확인하지 않는다 — 탈퇴 계정의 이미지는
 * S3 에서 지워지는데 컬럼 값은 그대로 남아, **값이 있는데 404 인 항목**이 정상적으로
 * 섞여 온다. 순위 항목마다 스토리지 존재 확인을 하면 SELECT 횟수 고정 계약이 깨지므로
 * 그대로 내보내고 **로드 실패를 프론트가 받는다**는 것이 계약이다(docs/ranking.md).
 *
 * 그 처리를 두 곳(시상대 · 목록 행)이 똑같이 해야 해서 여기로 모았다.
 */
import type { SyntheticEvent } from 'react';
import profilePlaceholder from '../assets/profile_img.svg';

export { profilePlaceholder };

/**
 * `<img onError>` 에 그대로 건다. 깨진 사진을 기본 아바타로 바꾼다.
 *
 * 바꿨다는 표식을 엘리먼트에 남긴다. 기본 아바타마저 실패하면 `onError` 가 다시 불리고,
 * 그때 또 같은 주소를 넣으면 무한히 돈다. `src` 를 비교해 막을 수는 없다 — 대입한 값은
 * 상대 경로인데 읽을 때는 절대 URL 로 돌아와 언제나 서로 달라 보인다.
 */
export function fallbackToPlaceholder(event: SyntheticEvent<HTMLImageElement>): void {
  const image = event.currentTarget;

  if (image.dataset.avatarFallback === 'done') {
    return;
  }

  image.dataset.avatarFallback = 'done';
  image.src = profilePlaceholder;
}
