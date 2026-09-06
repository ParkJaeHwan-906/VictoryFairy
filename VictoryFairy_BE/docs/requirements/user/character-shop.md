# 캐릭터 꾸미기(상점·인벤토리) 요구사항
> 상태: **승인됨 (2026-08-28)** · 모듈: user + domain · 최종 수정: 2026-08-28 (초안 — 구현·컨테이너 검증 완료 후 동시 반영)
> 사용자가 아바타 캐릭터에 아이템을 입히고, 포인트로 새 아이템을 사는 기능이다. `me-profile.md`가 "`point`의 **증감 주체**는 범위 밖"으로 미뤄 둔 자리 중 **차감 쪽**을 이 문서가 채운다(적립은 여전히 퀴즈 보상이 소유한다).
> 초안 단계의 미해결 3건은 사용자가 확정했다: **전 아이템 100 포인트** · **아이템 표시명은 구단명이 아니라 색상명** · **기본 의상은 가입 시 무상 지급 + 즉시 착용**.

## 배경 / 목적
`users_account.point`는 퀴즈 정답 보상으로 쌓이기만 하고 **쓸 곳이 없었다.** 이 기능은 그 포인트에 첫 소비처를 만드는 일이며, 동시에 사용자마다 다른 시각적 정체성(아바타)을 준다.

계약의 실제 쟁점은 정상 경로가 아니라 넷이다.

1. **상점과 인벤토리가 같은 목록인가** — 다른 목록으로 나누면 "산 것/안 산 것"을 두 API가 각자 판정하게 되고, 클라이언트는 두 응답을 합쳐야 한다. 하나로 합치고 `having`으로 구분하는 쪽을 택했다(USER-CS-20).
2. **한 그림이 두 벌인 이유** — 디자이너 원본이 상점 진열용(80x80 단독)과 착용용(160x200 캐릭터 정합)으로 나뉘어 있다. 좌표계가 달라 한 컬럼으로 합칠 수 없다(결정 근거 2).
3. **사는 것과 입는 것은 다른 행위다** — 구매가 자동 착용이면 이미 입고 있던 같은 부위 아이템이 사용자 의사와 무관하게 벗겨진다. 구매는 꺼진 채로 들어온다(USER-CS-16).
4. **꾸미기 데이터 누락이 회원가입을 막아도 되는가** — 안 된다. 지급 대상 시드가 없으면 로그만 남기고 건너뛰며, 시드의 백필이 다음 기동에 복구한다(USER-CS-8·9, 결정 근거 5).

## 범위
- 포함
  - 신규 엔티티 5개(domain): `Character` · `ItemType` · `CharacterItem` · `UserCharacterInventory` · `UserCharacterItemInventory`
  - 신규 엔드포인트 3개(user): 목록 조회 · 구매 · 착용 토글
  - 기존 `GET /api/users/me` 응답 확장 2필드(`characterImgUrl` · `characterItems`)
  - 회원가입 두 경로(자체·소셜) 모두에 기본 캐릭터·기본 의상 지급
  - 시드 + 기존 전 계정 백필(`infra/sql/character-asset-init.sql`, 재실행 안전)
  - 신규 `ErrorCode` 4종(`:common`): `CHARACTER_ITEM_NOT_FOUND` · `CHARACTER_ITEM_NOT_OWNED` · `INSUFFICIENT_POINT` · `CHARACTER_ITEM_ALREADY_OWNED`
  - `UserAccount.deductPoint(long)` — 포인트 차감의 유일한 뮤테이터
- 제외
  - **캐릭터 자체를 사고파는 경로** — 캐릭터는 '승리요정' 하나뿐이고 전원에게 지급된다. `user_characters_inventory`는 캐릭터가 늘어날 자리를 미리 만들어 둔 것이며, 그 선택 API는 캐릭터가 둘 이상이 될 때 별도 요구사항이다
  - **아이템 판매·환불·기간제 아이템** — 아이템은 영구적으로 한 개만 사고 되팔 수 없다(USER-CS-14)
  - **아이템 선물·거래** — 요청에 없었다
  - **포인트 적립 규칙 변경** — 적립은 퀴즈 보상이 소유한다(`me-profile.md`). 이 문서는 차감만 만든다
  - **관리자용 카탈로그 편집 API** — 카탈로그는 시드가 소유한다. 아이템 추가는 SQL + S3 업로드이며 코드 변경이 필요 없다
  - **이미지 파일 자체의 업로드 경로** — 사람이 CLI로 올린다(`VictoryFairy_Infra/scripts/upload-character-assets.sh`). 앱은 그 접두사를 읽지도 쓰지도 않으며 IRSA 권한도 없다
  - **동시 구매 직렬화의 실측 증명** — 사용자가 "하나의 계정이 동시에 여러 요청으로 구매하는 경우는 없다"로 전제했다. 계정 행 잠금은 그럼에도 걸지만(결정 근거 4), 그 직렬화를 부하로 증명하는 것은 범위 밖이다

## 엔드포인트

| 메서드 | 경로 | 인증 | 요청 본문 | 성공 |
|---|---|---|---|---|
| GET | `/api/characters/items` | 필수(access) | 없음 | 200 `ApiResponse<List<CharacterItemResponse>>` |
| POST | `/api/characters/items/purchase` | 필수(access) | `{"characterItemId":8}` | 200 `ApiResponse<CharacterItemPurchaseResponse>` |
| PUT | `/api/characters/items/active` | 필수(access) | `{"characterItemId":8}` | 200 `ApiResponse<CharacterItemActiveResponse>` |

세 경로 모두 대상 계정을 본문이 아니라 access 토큰에서만 정한다(`support` 도메인과 같은 규칙).

## 선행 스키마 (이 문서가 계약으로 포함하는 DDL)

테이블 5개는 **user 앱이 만든다**(`ddl-auto: update`, dev·prod 공통). 사람이 미리 적용할 DDL은 없다 — `chat-init.sql`과 달리 시드 파일에 `CREATE TABLE`이 없는 이유다.

| 테이블 | 용도 | UNIQUE |
|---|---|---|
| `characters` | 아바타 캐릭터 | `uk_characters_name(name)` |
| `item_types` | 부위 코드(의상·모자·소품) | `uk_item_types_name(name)` |
| `character_items` | 아이템 카탈로그 | `uk_character_items_character_name(character_id, name)` |
| `user_characters_inventory` | 계정별 캐릭터 보유·사용 | `uk_..._account_character(user_account_id, character_id)` |
| `user_character_items_inventory` | 계정별 아이템 보유·착용 | `uk_..._account_item(user_account_id, character_item_id)` |

⚠ 테이블명이 `user_characters_inventory` / `user_character_items_inventory`로 복수형 위치가 서로 다른 것은 **사용자가 확정한 스키마 그대로**다(`user_support_team`·`quiz_type`과 같은 명시적 예외). 맞춰서 고치지 말 것.

---

## 요구사항 (EARS)

### 카탈로그 (USER-CS-1 ~ 7)

- **USER-CS-1** 시스템은 `characters`에 `name='승리요정'`, `img='characters/victory-fairy.svg'`인 행을 **정확히 하나** 가져야 한다.
- **USER-CS-2** 시스템은 `item_types`에 `의상`·`모자`·`소품` 세 행을 가져야 하며, 그 순서대로 id가 매겨져야 한다.
- **USER-CS-3** 시스템은 `character_items`에 아이템 23행(의상 11 · 모자 6 · 소품 6)을 가져야 한다.
- **USER-CS-4** 모든 `character_items` 행의 `price`는 100이어야 한다.
- **USER-CS-5** `character_items.display_img`·`using_img`·`characters.img`는 **BaseURL을 뺀 S3 오브젝트 키(EP)** 여야 한다 — 선행 슬래시·버킷명·`https://` 없음. `profileImgUrl`과 문자 그대로 같은 형태다.
- **USER-CS-6** 시드 스크립트가 여러 번 실행되어도 위 세 테이블의 행 수는 변하지 않아야 한다.
- **USER-CS-7** 아이템의 표시명은 구단명이 아니라 색상명이어야 한다(예: `[Uniform] Bears 1.svg` → `화이트 블루 라인 유니폼`).

### 가입 시 지급 (USER-CS-8 ~ 13)

- **USER-CS-8** 회원가입이 성공하면, 시스템은 그 계정에 `승리요정`을 `active=1`로 지급해야 한다.
- **USER-CS-9** 회원가입이 성공하면, 시스템은 그 계정에 `기본 의상`을 `active=1`로 지급해야 한다.
- **USER-CS-10** 지급은 **자체 가입과 소셜 가입 양쪽**에서 일어나야 하며, 계정·`users_bq` 행과 **같은 트랜잭션**이어야 한다.
- **USER-CS-11** 가입이 어떤 이유로든 거절되면, 지급도 일어나지 않아야 한다.
- **USER-CS-12** 지급 대상 시드(`승리요정` 또는 `기본 의상`)가 없으면, 시스템은 **예외를 던지지 않고** ERROR 로그를 남긴 뒤 가입을 그대로 성공시켜야 한다.
- **USER-CS-13** 시드 스크립트는 `users_account`의 **모든 행**에 대해 위 두 지급 행이 없으면 만들어야 한다(백필). 단 이미 같은 부위를 착용 중인 계정에는 `기본 의상`을 `active=0`으로 넣어야 한다.

### 구매 (USER-CS-14 ~ 19)

- **USER-CS-14** 시스템은 한 계정이 같은 아이템을 두 번 보유하는 것을 허용하지 않아야 한다(스키마 UNIQUE + 서비스 검사).
- **USER-CS-15** 구매가 성공하면, 시스템은 계정의 `point`에서 그 아이템의 `price`만큼을 차감하고 보유 행을 만들어야 한다.
- **USER-CS-16** 구매로 만들어지는 보유 행의 `active`는 **0**이어야 한다(사는 것과 입는 것은 다른 행위다).
- **USER-CS-17** 구매 응답은 아이템 id와 **차감 후 잔액**을 포함해야 한다.
- **USER-CS-18** 거절 사유의 판정 순서는 **존재(404) → 중복 보유(409) → 잔액(400)** 이어야 한다. 여러 사유가 동시에 성립해도 앞선 것이 응답을 결정한다.
- **USER-CS-19** 잔액이 가격과 **정확히 같으면** 구매가 성공해야 한다(거절 경계는 미만뿐이다).

### 목록 조회 (USER-CS-20 ~ 24)

- **USER-CS-20** 목록은 보유 여부와 무관하게 **카탈로그 전체**를 돌려주어야 하며, 보유 여부는 항목의 `having` 필드로만 구분되어야 한다.
- **USER-CS-21** 목록 항목은 `id`·`itemType`·`name`·`displayImg`·`price`·`having`·`active` **7개 키**를 가져야 한다.
- **USER-CS-22** 목록은 착용용 이미지(`using_img`)를 포함하지 않아야 한다 — 좌표계가 상점 격자와 맞지 않는다.
- **USER-CS-23** 목록의 순서는 **부위 id 오름차순, 같은 부위 안에서는 아이템 id 오름차순**이어야 한다.
- **USER-CS-24** 목록 조회는 SELECT 3회를 넘지 않아야 한다(카탈로그+부위 · 보유 id · 착용 id).

### 착용 토글 (USER-CS-25 ~ 30)

- **USER-CS-25** 시스템은 그 계정이 **보유한** 아이템에 대해서만 토글을 허용해야 한다. 보유하지 않은 아이템은 카탈로그에 있어도 404여야 한다.
- **USER-CS-26** 대상은 인벤토리 행 id가 아니라 **(계정, 아이템)** 조합으로 찾아야 한다 — 그 조회 자체가 소유권 검사를 겸한다.
- **USER-CS-27** 대상이 이미 `active=1`이면, 시스템은 그것을 0으로만 바꾸고 다른 아이템을 켜지 않아야 한다.
- **USER-CS-28** 대상이 `active=0`이면, 시스템은 **같은 부위**에서 켜져 있는 다른 행을 끈 뒤 대상을 켜야 한다.
- **USER-CS-29** 다른 부위에서 켜져 있는 아이템은 영향을 받지 않아야 한다.
- **USER-CS-30** 토글 응답은 **요청 후의 상태**(`active`)를 포함해야 한다 — 요청만으로는 켜기인지 끄기인지 알 수 없다.

### 내 프로필 조회 확장 (USER-CS-31 ~ 35)

- **USER-CS-31** `GET /api/users/me` 응답은 `characterImgUrl`(사용 중인 캐릭터의 EP)을 포함해야 한다.
- **USER-CS-32** `GET /api/users/me` 응답은 `characterItems`(착용 중인 아이템 목록)를 포함해야 한다.
- **USER-CS-33** `characterItems`의 각 항목은 `itemType`과 `imgUrl` 2개 키를 가져야 하며, `imgUrl`은 상점용이 아니라 **착용용**(`using_img`)이어야 한다.
- **USER-CS-34** `characterItems`는 **부위 id 오름차순**으로 정렬되어야 한다(클라이언트가 겹치는 순서를 정할 수 있도록).
- **USER-CS-35** 캐릭터를 지급받지 못한 계정도 이 응답은 **200**이어야 하며, `characterImgUrl`은 `null`, `characterItems`는 빈 배열이어야 한다.

### 인증 (USER-CS-36 ~ 37)

- **USER-CS-36** 세 엔드포인트 전부 유효한 access 토큰 없이는 401이어야 한다. `SecurityConfig`의 `permitAll` 목록에 이 경로들을 **추가하지 않는 것이 정상**이다.
- **USER-CS-37** 요청 본문의 `characterItemId`가 없으면 400이어야 하며, 서비스는 호출되지 않아야 한다.

---

## 결정 근거

**1. 상점과 인벤토리를 한 API로 합쳤다(USER-CS-20).** 나누면 "안 산 것만" / "산 것만" 두 목록이 되고, 인벤토리 화면에서 "이건 아직 안 샀어요"를 보여 주려면 클라이언트가 둘을 합쳐야 한다. `having` 하나로 두 화면이 같은 응답을 쓴다. 대신 카탈로그가 커지면 이 응답도 함께 커진다 — 23개 기준으로는 문제가 없고, 페이지네이션이 필요해지는 시점이 이 결정을 다시 볼 시점이다.

**2. `display_img`와 `using_img`를 나눈 것은 중복이 아니다.** 두 파일은 같은 그림이지만 좌표계가 다르다(상점 80x80 단독 / 착용 160x200 캐릭터 정합). 한 컬럼으로 합치면 상점에서 아이템이 화면 밖으로 밀려나거나 캐릭터 위에서 어긋난다. **디자이너 원본의 파일 이름은 두 벌이 서로 다르며**(상점=색상명, 착용=구단명), 무엇이 같은 그림인지는 파일명이 아니라 SVG의 `fill` 색상으로 대조해 확정했다 — 그 대조 결과가 `VictoryFairy_Infra/scripts/character-assets.tsv`이고, S3 키는 거기서 정한 공통 슬러그로 정규화했다.

**3. 아이템 표시명을 색상명으로 했다(USER-CS-7, 사용자 확정).** 착용본 파일명은 구단명(`[Uniform] Bears 1.svg`)이지만 디자이너가 상점본을 색상명으로 다시 지어 둔 것을 존중했다. 구단명을 쓰면 상점에서 자기 팀 유니폼을 바로 찾을 수 있는 대신 실제 KBO 구단 유니폼처럼 읽힌다.

**4. 구매가 계정 행을 잠근다(`findWithLockById`).** 사용자가 "동시 구매는 없다"로 전제했지만, 지키려는 것은 동시 **구매**가 아니라 구매와 **퀴즈 적립**이 겹치는 경우다. 둘 다 같은 잔액을 읽고 쓰므로 잠금 없이는 한쪽 갱신이 통째로 유실된다(적립 경로가 이미 같은 잠금을 요구한다). 잠금을 트랜잭션 맨 앞에 두는 것은 잠금 순서를 고정해 교착을 피하기 위해서다.

**5. 지급 실패가 가입을 막지 않는다(USER-CS-12).** 꾸미기 데이터 누락으로 서비스의 입구인 회원가입 전체를 500으로 세우는 것은 손해가 훨씬 크다. 조용히 넘어가는 것이 아닌 이유는 둘이다 — ERROR 로그가 그 사건을 남기고, 시드의 백필(USER-CS-13)이 매 기동마다 `users_account` 전 행을 훑어 **다음 배포에 자동 복구한다.** 그 자가 치유가 있기 때문에 `/users/me`의 안전망(USER-CS-35)도 영구 상태가 아니라 과도기 상태를 덮는 장치다.

**6. "부위당 하나"는 스키마가 아니라 서비스 정책이다.** 부위는 인벤토리 테이블이 아니라 `character_items.item_type_id`에 있어 UNIQUE로 표현할 수 없고, MySQL에는 부분 UNIQUE(`WHERE active = 1`)도 없다. 그래서 토글 서비스가 유일한 강제 지점이며, 리포지토리가 `Optional`을 반환하는 것으로만 그 전제가 드러난다(정책이 깨진 데이터는 조용히 흘러가지 않고 예외로 드러난다).

**7. 토글이 PUT이지만 멱등이 아니다(사용자 확정).** 같은 요청을 두 번 보내면 켜졌다 꺼진다. 그래서 응답이 결과 상태를 돌려준다(USER-CS-30) — 클라이언트는 요청을 세는 대신 응답을 본다.

**8. 목록 응답에 `price`와 `active`를 더했다.** 사용자가 열거한 5개 키(`id`·`itemType`·`name`·`displayImg`·`having`)에 둘을 더한 것이다. 가격 없이는 상점을 그릴 수 없고, 착용 여부 없이는 인벤토리의 토글 상태를 그릴 수 없다 — 둘 다 이 목록 말고는 알 길이 없다(`/users/me`는 착용 중인 것의 **이미지**만 주고 아이템 id를 주지 않는다).

**9. `item_type_id` 컬럼명.** 사용자가 준 스키마는 `item_type`이었으나 저장소의 FK 규약(`quiz_type_id`·`game_status_id`)과 같은 파일의 `character_id`에 맞춰 `_id`를 붙였다.

---

## 알려진 한계

- **이미지가 CloudFront로 서빙되려면 인프라 변경이 함께 배포되어야 한다.** `victoryfairy-asset` 버킷 정책과 CloudFront behavior가 `user-profile-img/`·`temp/` 두 접두사만 알고 있어, `characters/`·`items/`·`stores/`는 그대로면 CloudFront에서 403이다. 대응 코드는 `VictoryFairy_Infra`(브랜치 `hwannee/infra/feat-character-asset-cdn`)에 있고 **아직 apply되지 않았다.** BE만 배포하면 API는 정상이고 이미지만 깨진다.
- **탈퇴 계정에도 백필이 행을 만든다(USER-CS-13).** "`users_account` 전 행에 하나씩"이라는 규칙에 예외를 두지 않은 결과이며, 탈퇴 계정은 이 데이터를 읽는 경로가 없어(로그인 불가) 무해하다.
- **`(알수없음)` 더미 계정은 첫 기동에서 지급을 못 받는다.** 그 계정은 시드(`spring.sql.init`)보다 **뒤에** 만들어지므로(`UnknownAccountBootstrapper`는 `ApplicationRunner`), 두 번째 기동의 백필에서 채워진다. 실측으로 확인했다.
- **카탈로그가 커지면 목록 응답도 커진다.** 페이지네이션이 없다(결정 근거 1).
- **아이템 그림을 교체하면 CDN 무효화가 필요하다.** S3 키가 UUID가 아니라 고정 슬러그라 같은 키를 덮어쓴다. 그래서 CloudFront 캐시·브라우저 캐시를 모두 하루로 잡았고(1년 immutable이 아니다), 즉시 반영이 필요하면 `aws cloudfront create-invalidation --paths '/characters/*' '/items/*' '/stores/*'`가 필요하다.
- **S3 슬러그 `peak`와 표시명 `응원봉`이 서로 다른 말이다.** 원본 파일명(`Name=Peak.svg` / `[Item]Peak 1.svg`)에서 슬러그를 뽑았고 표시명은 사용자가 `응원봉`으로 확정했다. 슬러그를 맞춰 바꾸지 않은 것은 그것이 이미 올라간 S3 키이기 때문이며, 슬러그는 파일을 찾는 이름일 뿐 사용자에게 보이지 않는다.
- ⚠ **아이템의 `name`을 바꾸는 것은 시드에서 안전한 편집이 아니다.** 카탈로그 anti-join의 기준이 `(character_id, name)`이라, 이미 시드가 적용된 DB에서 이름만 고치면 **같은 이미지를 가진 행이 하나 더 생긴다**(옛 행은 남는다). `응원 피켓`→`응원봉` 변경은 어디에도 적용되기 전이라 문제가 없었지만, 배포 이후의 이름 변경은 시드가 아니라 `UPDATE` 마이그레이션이어야 한다.

## 검증 상태 (2026-08-28)

- 단위·슬라이스 테스트: `:domain:test` · `:user:test` 전량 통과(신규 34건 포함).
- **컨테이너 실측**(`Dockerfile` 이미지 + prod 프로파일 + 빈 MySQL): 기동 성공 → 시드가 1/3/23행 생성 → 백필이 기존 계정을 덮음 → 목록·구매(성공/409/404/400/검증400)·토글(켜기/같은 부위 자동 off/끄기/404)·`/users/me`·비인증 401 전부 기대대로 응답. **재기동 시 카탈로그 행 수 불변**(재실행 안전)이고 첫 기동에 누락됐던 더미 계정이 두 번째 기동에서 채워지는 것까지 확인.
- 미검증: EKS 실경로(ALB·Ingress·TLS)와 CloudFront 서빙(위 "알려진 한계" 첫 항목).
