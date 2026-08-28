# 캐릭터 꾸미기(character) API 명세

> **도메인** `character` — 아바타 캐릭터에 입히는 아이템의 상점·인벤토리·착용 상태.
> **모듈** user (포트 8080) · **경로 접두사** `/api/characters/items` · **엔드포인트** 3개
> **컨트롤러** `user/src/main/java/com/skhynix/user/character/controller/CharacterItemController.java` (`@RequestMapping("/characters/items")`)
> **최종 갱신** 2026-08-28 — 도메인 신설. 상점·인벤토리 통합 목록 1개, 구매 1개, 착용 토글 1개. 같은 날 [account.md](account.md)의 `GET /api/users/me` 응답에 `characterImgUrl`·`characterItems` 두 필드가 함께 추가됐다.
> **요구사항** `docs/requirements/user/character-shop.md` (USER-CS-1 ~ 37)
> 공통 규약(응답 래퍼·JWT·401 정책)은 [README.md](README.md)를 먼저 볼 것.

## 엔드포인트 목록

| 메서드 | 경로 | 성공 | 용도 |
|---|---|---|---|
| GET | [/api/characters/items](#get-apicharactersitems) | 200 | 상점 + 인벤토리 **통합** 목록 |
| POST | [/api/characters/items/purchase](#post-apicharactersitemspurchase) | 200 | 아이템 구매(1건, 포인트 차감) |
| PUT | [/api/characters/items/active](#put-apicharactersitemsactive) | 200 | 착용 on/off 토글 |

## 이 도메인의 특이사항

**3개 전부 인증 필수.** `permitAll` 목록에 없어 `anyRequest().authenticated()`에 걸린다 — `SecurityConfig`에 아무 규칙도 추가하지 않은 것이 정상이다(응원·계정 도메인과 같은 방식). 대상 계정은 요청 본문이 아니라 access 토큰에서만 정해진다.

**상점과 인벤토리는 같은 목록이다.** `GET /api/characters/items`는 보유 여부와 무관하게 카탈로그 **전체**를 돌려주고, 산 것은 `having: true`로만 구분된다. "안 산 것만" 걸러 주는 API는 없다 — 인벤토리 화면도 이 응답 하나로 그린다.

⚠ **이미지는 두 벌이고 용도가 다르다.** 상점 목록은 진열용(`displayImg`, 80x80 단독 좌표계)만 주고, 캐릭터에 겹쳐 그릴 착용용은 [`GET /api/users/me`](account.md)의 `characterItems[].imgUrl`(160x200 캐릭터 정합 좌표계)이 준다. **서로 바꿔 쓰면 상점에서 아이템이 밀려나거나 캐릭터 위에서 어긋난다.**

**모든 이미지 값은 BaseURL을 뺀 EP다** — `profileImgUrl`과 문자 그대로 같은 규칙이다(선행 슬래시·버킷명·`https://` 없음). 클라이언트가 `https://victoryfairy.com/` + 값을 이어 붙인다.

**사는 것과 입는 것은 다른 행위다.** 구매한 아이템은 **꺼진 채로** 인벤토리에 들어온다. 자동 착용은 이미 입고 있던 같은 부위 아이템을 사용자 의사와 무관하게 벗기기 때문이다.

**착용은 부위(`itemType`)당 하나다.** 꺼진 아이템을 켜면 같은 부위에서 켜져 있던 아이템이 **자동으로 꺼진다.** 다른 부위는 영향을 받지 않는다. 현재 부위는 `의상`·`모자`·`소품` 3종이지만 **닫힌 집합이 아니다**(DB 행이라 코드 변경 없이 늘어날 수 있다).

**아이템은 영구적으로 한 개만 산다.** 재구매는 409이고, 판매·환불 경로는 없다. 착용 해제는 행 삭제가 아니라 `active`를 끄는 것이다.

**전 아이템 100 포인트다(2026-08-28 기준).** 잔액은 `users_account.point`이며 [`GET /api/users/me`](account.md)의 `point`와 같은 값이다.

---

## GET /api/characters/items
> 최종 변경: 2026-08-28 (신설)

상점 + 인벤토리 통합 목록. `CharacterItemController` → `CharacterItemService.findAll()`. 요구사항: USER-CS-20 ~ 24, 36.

**요청** 본문·쿼리 파라미터 없음. 대상 계정은 access 토큰에서만 정해진다.

**응답 200 OK** `ApiResponse<List<CharacterItemResponse>>`

```json
{
  "success": true,
  "data": [
    {"id":1,"itemType":"의상","name":"기본 의상","displayImg":"stores/cloth/basic.svg","price":100,"having":true,"active":true},
    {"id":2,"itemType":"의상","name":"블랙 라인 유니폼","displayImg":"stores/cloth/uniform-blackline.svg","price":100,"having":false,"active":false},
    {"id":12,"itemType":"모자","name":"블루 캡","displayImg":"stores/head/cap-blue.svg","price":100,"having":false,"active":false}
  ],
  "message": null
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| id | Long | 아이템 PK. 구매·토글 요청의 `characterItemId`가 이 값이다 |
| itemType | String | 부위(`의상`·`모자`·`소품`). **닫힌 집합이 아니다** — 모르는 값이 와도 깨지지 않게 다룰 것 |
| name | String | 표시명. 유니폼은 구단명이 아니라 **색상명**이다(`화이트 블루 라인 유니폼`) |
| displayImg | String | **상점 진열용** 이미지 EP. 착용용이 아니다 |
| price | long | 구매 가격(포인트). 현재 전 항목 100 |
| having | boolean | 이 계정이 보유했는가 |
| active | boolean | 이 계정이 착용 중인가. `having=false`면 항상 `false` |

**항목의 키는 정확히 이 7개다.** 착용용 이미지(`usingImg`)는 **일부러 빼 두었다** — 좌표계가 상점 격자와 맞지 않아 여기서 쓰면 어긋나고, 실제로 필요한 시점(`/users/me`)에 그쪽이 준다.

**정렬은 부위 → 아이템 id 순이다(USER-CS-23).** 부위가 1차 키라 목록이 부위별로 묶여 나오며, 같은 요청은 언제나 같은 순서다. 현재 시드 기준으로 id 1~11이 의상, 12~17이 모자, 18~23이 소품이다.

**빈 배열이 나올 수 있다.** 카탈로그 시드가 아직 적용되지 않은 환경에서는 `data: []`이며 200이다(404가 아니다).

**실패**

| 상황 | 상태 | 본문 |
|---|---|---|
| 토큰 없음·만료·refresh 토큰 사용 | 401 | `{"success":false,"data":null,"message":"인증이 필요합니다."}` |

---

## POST /api/characters/items/purchase
> 최종 변경: 2026-08-28 (신설)

아이템 구매. `CharacterItemController` → `CharacterItemService.purchase()`. 요구사항: USER-CS-14 ~ 19, 36 ~ 37.

**한 번에 한 개만 산다.** 목록을 받지 않는 것이 의도다 — 부분 실패의 의미를 정의하지 않아도 된다.

**요청** `CharacterItemRequest`

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| characterItemId | Long | **예** | 구매할 아이템 PK(목록의 `data[].id`) |

```json
{"characterItemId": 8}
```

**응답 200 OK** `ApiResponse<CharacterItemPurchaseResponse>`

```json
{"success":true,"data":{"characterItemId":8,"remainingPoint":150},"message":null}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| characterItemId | Long | 방금 구매한 아이템 PK |
| remainingPoint | long | **차감 후** 잔액. `GET /api/users/me`를 다시 칠 필요가 없도록 함께 준다 |

⚠ **구매한 아이템은 착용되지 않는다.** 응답 직후의 상태는 `having=true, active=false`다. 입히려면 [착용 토글](#put-apicharactersitemsactive)을 한 번 더 보내야 한다.

**실패 — 판정 순서가 고정돼 있다(USER-CS-18)**

| 순서 | 상황 | 상태 | message |
|---|---|---|---|
| 0 | 토큰 없음·만료 | 401 | `인증이 필요합니다.` |
| 0 | `characterItemId` 누락 | 400 | `입력값이 올바르지 않습니다.` (`data`에 위반 필드) |
| 1 | 그런 아이템이 없음 | 404 | `존재하지 않는 아이템입니다.` |
| 2 | 이미 보유 중 | 409 | `이미 보유한 아이템입니다.` |
| 3 | 포인트 부족 | 400 | `보유 포인트가 부족합니다.` |

**여러 사유가 동시에 성립해도 앞선 것이 응답을 결정한다.** 잔액이 0인 사용자가 이미 가진 아이템을 다시 사려 하면 "포인트가 부족합니다"가 아니라 **409**다 — 잔액을 먼저 보면 사용자가 원인을 오해한다.

**잔액 경계는 "미만"만 거절이다(USER-CS-19).** 잔액이 가격과 정확히 같으면 구매가 성공하고 잔액은 0이 된다.

---

## PUT /api/characters/items/active
> 최종 변경: 2026-08-28 (신설)

보유 아이템의 착용 on/off 토글. `CharacterItemController` → `CharacterItemService.toggleActive()`. 요구사항: USER-CS-25 ~ 30, 36 ~ 37.

**요청** `CharacterItemRequest` — 구매와 문자 그대로 같은 본문이다.

| 필드 | 타입 | 필수 | 설명 |
|---|---|---|---|
| characterItemId | Long | **예** | 토글할 아이템 PK. **인벤토리 행 id가 아니다** |

```json
{"characterItemId": 8}
```

인벤토리 행 id를 받지 않는 것은 의도다 — 그것은 남의 행을 가리킬 수 있는 식별자다. 서버가 (계정, 아이템)으로 찾으므로 그 조회 자체가 소유권 검사를 겸한다.

**응답 200 OK** `ApiResponse<CharacterItemActiveResponse>`

```json
{"success":true,"data":{"characterItemId":8,"active":true},"message":null}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| characterItemId | Long | 요청한 아이템 PK |
| active | boolean | **요청 후의 상태**. `true`면 지금 착용 중이다 |

**동작 규칙 — 프론트가 반드시 알아야 할 것**

| 요청 시점의 상태 | 결과 | 같은 부위의 다른 아이템 |
|---|---|---|
| 꺼져 있음, 같은 부위에 켜진 것 없음 | 켜진다 (`active: true`) | 변화 없음 |
| 꺼져 있음, 같은 부위에 켜진 것 있음 | 켜진다 (`active: true`) | **자동으로 꺼진다** |
| 켜져 있음 | 꺼진다 (`active: false`) | **아무것도 켜지 않는다** |

⚠ **PUT이지만 멱등이 아니다.** 같은 요청을 두 번 보내면 켜졌다 꺼진다. 그래서 결과 상태를 응답이 돌려준다 — 요청을 세지 말고 응답의 `active`를 볼 것.

⚠ **끄기는 끄기만 한다.** 착용 중인 의상을 끄면 그 부위는 아무것도 입지 않은 상태가 되며, 서버가 기본 의상을 대신 입혀 주지 않는다.

⚠ **다른 부위는 건드리지 않는다.** 모자를 바꿔도 의상·소품의 착용 상태는 그대로다. 배타 조건의 단위는 계정이 아니라 **(계정, 부위)** 다.

**착용 상태가 바뀌면 [`GET /api/users/me`](account.md)의 `characterItems`가 함께 바뀐다** — 캐릭터를 다시 그려야 한다면 그쪽을 재조회한다.

**실패**

| 상황 | 상태 | message |
|---|---|---|
| 토큰 없음·만료 | 401 | `인증이 필요합니다.` |
| `characterItemId` 누락 | 400 | `입력값이 올바르지 않습니다.` |
| 보유하지 않은 아이템(카탈로그에는 있음) | 404 | `보유하지 않은 아이템입니다.` |

**"없는 아이템"과 "안 산 아이템"의 문구가 다른 것은 의도다.** 목록 API가 카탈로그 전체를 `having`과 함께 돌려주므로 클라이언트는 둘을 이미 구분할 수 있고, 한 문구로 뭉치면 프론트가 "구매하기"를 띄울지 "다시 시도"를 띄울지 판단할 근거를 잃는다.
