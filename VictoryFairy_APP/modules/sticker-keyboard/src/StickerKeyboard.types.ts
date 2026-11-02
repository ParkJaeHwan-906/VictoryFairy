/**
 * 키보드가 쓸 스티커 한 장. 웹이 보낸 목록을 그대로 옮긴다.
 *
 * 그림은 `url` 에서 앱이 내려받아 기기에 둔다. 키보드는 WebView 없이 뜨므로 그때그때
 * 웹에 물어볼 수 없어서다.
 */
export interface StickerKeyboardSource {
  /** 목록 안에서 겹치지 않는 값. 예: `character-01` */
  id: string;
  /** 키보드 위 탭을 묶는 값. 같은 값끼리 한 탭이 된다. */
  categoryId: string;
  /** 탭 이름. 예: `캐릭터` */
  categoryLabel: string;
  /** 스티커 이름. 넣었을 때 안내 문구와 접근성 이름에 쓴다. */
  name: string;
  /** PNG 의 절대 주소. */
  url: string;
}

/** 키보드가 지금 쓸 수 있는 상태인지. */
export interface StickerKeyboardStatus {
  /** 설정 → 키보드에서 켜 두었는지. 꺼져 있으면 키보드 전환 목록에 나타나지 않는다. */
  enabled: boolean;
  /** 지금 기본 키보드로 골라 둔 상태인지. */
  selected: boolean;
  /** 받아 둔 스티커 수. 0 이면 키보드에 빈 안내가 뜬다. */
  stickerCount: number;
}
