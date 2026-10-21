/**
 * 인스타그램 스토리 편집기를 열면서 얹어 보낼 스티커 한 장.
 *
 * 인스타는 배경(background)과 스티커(sticker) 두 겹을 받는데, 배경은 화면에 고정되고
 * 스티커만 사용자가 끌어 옮기고 크기·회전을 조절할 수 있다. 우리가 보내려는 건
 * "사용자가 자기 스토리에 얹는 우리 그림"이므로 스티커 겹만 쓰고, 배경은 색 두 개로
 * 만든 그라데이션에 맡긴다. 배경 이미지까지 보내면 사용자가 찍어 둔 사진 대신
 * 우리 이미지가 화면을 채워 버린다.
 */
export interface InstagramStickerShareOptions {
  /**
   * Meta 개발자 대시보드에 등록한 앱 ID(`source_application`).
   *
   * 2023년 1월부터 이 값이 없으면 인스타가 공유 요청을 그냥 무시한다. 비밀이 아니라
   * 공유 요청의 출처 표시라서 앱에 평문으로 들어가도 된다.
   */
  appId: string;

  /**
   * 스티커 이미지의 base64. `data:` 접두사 없이 본문만 넣는다.
   *
   * 권장 크기는 640×480이고 JPG·PNG를 받는다. 투명 배경이 필요하므로 PNG로 그린다.
   */
  stickerBase64: string;

  /** 배경 그라데이션 위쪽 색(`#RRGGBB`). 없으면 인스타 기본 배경. */
  backgroundTopColor?: string;

  /** 배경 그라데이션 아래쪽 색(`#RRGGBB`). 없으면 인스타 기본 배경. */
  backgroundBottomColor?: string;
}

/**
 * 공유 시도의 결과.
 *
 * 스토리 편집기까지만 우리 몫이다. 사용자가 실제로 올렸는지는 인스타 안에서 일어나는
 * 일이라 알 수 없고, 알려주지도 않는다 — `opened`는 "올렸다"가 아니라 "넘겼다"다.
 */
export type InstagramShareResult =
  /** 인스타 스토리 편집기가 열렸다. */
  | 'opened'
  /** 기기에 인스타그램이 없다. 사용자에게 설치를 안내할 자리다. */
  | 'instagram-missing';
