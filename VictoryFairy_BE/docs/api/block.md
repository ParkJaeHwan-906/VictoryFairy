# 차단(block) API 명세

> **도메인** `block` — 회원 간 차단 생성·목록 조회(신설). 차단 해제(unblock) API는 이번 스코프에 없다 — 한번 생긴 차단은 후속 작업 전까지 영구적이다.
> **모듈** user · **경로 접두사** `/api/users/me/blocks` · **엔드포인트** 2개
> **컨트롤러** `user/src/main/java/com/skhynix/user/block/controller/UserBlockController.java` (`@RequestMapping("/users/me/blocks")`)
> **최종 갱신** 2026-09-30 — 도메인 신설(`POST /api/users/me/blocks`·`GET /api/users/me/blocks`). 계약 원본 `docs/requirements/user/user-block.md`(승인됨 2026-09-30, USER-BLK-1~21).
> 공통 규약(응답 래퍼·JWT payload·401 4종·시스템 예외 래핑)은 [README.md](README.md)를 먼저 볼 것.
> **크로스 모듈 부수 효과**: 차단이 성립하면 [순위(ranking)](ranking.md)의 `GET /api/rankings/bq/top`·`GET /api/rankings/bq`·`GET /api/rankings/bq/me`와 quiz 모듈 `GET /rt/chat/rooms/{roomUid}/messages`(채팅 히스토리)에서 서로가 서로에게 보이지 않게 된다 — 이 두 문서의 해당 엔드포인트에 각각 한 줄 참고가 있다. 이 문서의 두 엔드포인트 자체의 요청/응답 스키마는 그 부수 효과와 무관하다.

## 엔드포인트 목록

| 메서드 | 경로 | 성공 | 용도 |
|---|---|---|---|
| POST | [/api/users/me/blocks](#post-apiusersmeblocks) | 200 | 대상 닉네임을 차단(최초·재요청 모두 멱등 200) |
| GET | [/api/users/me/blocks](#get-apiusersmeblocks) | 200 | 내가 차단한 대상 목록 조회 |

## 이 도메인의 특이사항

**차단 대상은 닉네임(`targetNickname`)으로만 식별한다.** 이 저장소의 어떤 응답도 다른 회원의 안정적 식별자(`id`·`uid`)를 노출하지 않는다는 기존 기조([채팅](chat.md)의 `senderNickname`·[순위](ranking.md)의 `BqRankingResponse`와 동일)를 그대로 따른 결정이다. 요청 시점에 그 닉네임의 `UserAccount`를 조회해 내부 FK로 차단 행을 만들고, **이후 대상이 닉네임을 바꿔도 이미 만들어진 차단 행은 FK로 원래 대상을 계속 가리킨다**(닉네임 재조회 없음).

**닉네임은 이 저장소에서 DB `UNIQUE` 제약이 아니다**(`existsByNickname`이 애플리케이션 검사로만 유일성을 지킨다는 기존 사실의 연장, `.claude/modules/user.md` 참고). 이론상 동일 닉네임 계정이 둘 이상 존재할 수 있고, 그 경우 `UserAccountRepository.findByNicknameAndExitAtIsNull`이 `IncorrectResultSizeDataAccessException`을 던진다 — 이 예외는 `GlobalExceptionHandler`의 개별 핸들러가 아니라 **catch-all**(`handleUnexpected`)에 잡혀 `ApiResponse` 래퍼가 붙은 **500**으로 응답된다. 요구사항 문서(`docs/requirements/user/user-block.md` "알려진 한계")가 이 경우의 동작을 규정하지 않는다고 명시했으므로, 이 500은 "의도된 에러 코드"가 아니라 **코드 상 실제로 관측되는 결과**를 정직하게 적어 둔 것이다. 닉네임 `UNIQUE` 제약 추가는 이번 스코프 밖이다.

**차단 주체는 언제나 access 토큰 principal 본인이다.** 두 엔드포인트 모두 경로·쿼리·본문에 계정 식별자를 받지 않는다 — `POST` 요청 본문에 `blockerId`류 필드를 추가로 보내도 무시된다.

**차단은 단방향으로 저장되지만 조회·필터는 양방향이다.** `UserBlock` 행 자체는 `blocker → blocked` 한 방향으로만 쌓이지만, `UserBlockRepository.findRelatedAccountIds`가 `blocker=나 OR blocked=나` 양쪽을 합쳐 "차단 관계자 전원"을 계산한다 — 이 저장소의 [순위](ranking.md)·[채팅](chat.md) 노출 차단이 공유하는 단일 진입점이다. 즉 A가 B를 차단하면 **B는 자신이 차단당한 사실도, 누가 차단했는지도 이 API로 알 수 없지만**, B가 보는 랭킹·채팅에서는 A가 조용히 빠진다.

**`SecurityConfig`를 건드리지 않는 것이 정답이다** — `/api/users/**`는 `permitAll` 목록에 없어 `anyRequest().authenticated()`에 자연히 걸린다(`/api/users/me`·`/api/support/**`·`/api/rankings/bq/**`와 같은 성격). 실제로 이번 구현도 `SecurityConfig` 무수정이다.

**DB 수준 중복 방지**: `user_blocks` 테이블에 `(blocker_id, blocked_id)` 복합 `UNIQUE`(`uk_user_blocks_blocker_blocked`)가 있다. 서비스의 `existsByBlocker_IdAndBlocked_Id` 사전 확인은 정상 경로에서 불필요한 INSERT 시도를 피하는 최적화일 뿐이고, 동시 요청(race)의 최종 방어선은 이 UNIQUE 제약이다.

**계정 하드 삭제 시 CASCADE.** 차단 주체·대상 어느 쪽이든 계정이 하드 삭제(탈퇴 30일 경과, 만료 데이터 정리 배치)되면 그 계정이 관여한 `user_blocks` 행도 FK `@OnDelete(CASCADE)`로 함께 사라진다(`UserBlock` 엔티티). 30일 소프트 삭제 유예 기간 중에는 계정이 아직 존재해 차단 필터가 그대로 유지된다.

---

## POST /api/users/me/blocks
> 최종 변경: 2026-09-30 — 신규 추가

대상 닉네임을 차단한다. `UserBlockController.block()` → `UserBlockService.block()`(클래스 레벨 `@Transactional`).

**인증 필요** — `Authorization: Bearer <accessToken>`.

**대상 계정(차단 주체)은 access 토큰에서만 정해진다.** 요청 본문에 `blockerId`/`blockerUid`류 필드가 없고, 있어도 무시된다.

**요청 본문** `BlockRequest`

| 필드 | 타입 | 제약 | 설명 |
|---|---|---|---|
| targetNickname | String | `@NotBlank`(메시지: `"차단할 사용자의 닉네임을 입력해 주세요."`) | 차단할 대상의 현재 닉네임 |

**판정 순서(고정 3단계)**: ①차단 주체 계정 조회(정상 경로에서는 항상 성공) → ②`targetNickname`의 **활성**(`exit_at IS NULL`) 계정 조회, 없으면 404 → ③자기 자신 여부(내부 id 비교), 같으면 400. 셋을 통과하면 기존 차단 존재 여부를 확인해 **없을 때만** 새 행을 만들고, 있으면 아무것도 하지 않은 채 같은 응답을 그대로 반환한다(멱등).

**응답 200 OK** `ApiResponse<BlockResponse>` — 최초 차단·재요청(이미 차단한 대상) 모두 **200**이다. 201로 고정하지 않는 것이 승인된 계약이다.

| 필드 | 타입 | 설명 |
|---|---|---|
| data.nickname | String | 차단된 대상의 닉네임(요청에 보낸 `targetNickname`과 같은 값 — 서버가 다시 조회해 확인한 값을 그대로 돌려준다) |

```json
{"success":true,"data":{"nickname":"홍길동"},"message":null}
```

**실패**

| 상태 | ErrorCode | 조건 |
|---|---|---|
| 401 | UNAUTHENTICATED | Authorization 헤더 없음/무효 토큰/refresh 토큰으로 요청/탈퇴한 계정의 access 토큰/비밀번호 변경 이전에 발급된 access 토큰 |
| 400 | (Bean Validation, ErrorCode 아님) | `targetNickname` 공백/빈 문자열/누락 → `data.targetNickname`에 `"차단할 사용자의 닉네임을 입력해 주세요."` |
| 404 | BLOCK_TARGET_NOT_FOUND | `targetNickname`에 해당하는 활성 계정이 없음(메시지: `"존재하지 않는 사용자입니다."` — 탈퇴 계정 점유 여부 등 세부 사유는 구분해 노출하지 않는다) |
| 400 | SELF_BLOCK_NOT_ALLOWED | `targetNickname`이 요청자 자신의 현재 닉네임과 같음(메시지: `"자기 자신은 차단할 수 없습니다."`. `BLOCK_TARGET_NOT_FOUND`로 흡수하지 않는다 — 본인 닉네임은 실재하는 닉네임이라 "존재하지 않음"이 거짓이기 때문) |
| 500 | (catch-all, ErrorCode 아님) | 동일 닉네임 계정이 둘 이상 존재하는 레이스가 실제로 발생한 경우(위 "이 도메인의 특이사항" 참고 — 정상 운영에서는 발생하지 않음) |

**예시**
```bash
curl -i -X POST https://victoryfairy.com/api/users/me/blocks \
  -H 'Authorization: Bearer eyJ...' \
  -H 'Content-Type: application/json' \
  -d '{"targetNickname":"홍길동"}'
```

자기 자신 차단 시도:
```json
{"success":false,"data":null,"message":"자기 자신은 차단할 수 없습니다."}
```

대상 미존재:
```json
{"success":false,"data":null,"message":"존재하지 않는 사용자입니다."}
```

---

## GET /api/users/me/blocks
> 최종 변경: 2026-09-30 — 신규 추가

내가 차단한 대상 전원을 조회한다(자신을 차단한 사람은 포함하지 않는다). `UserBlockController.getMyBlocks()` → `UserBlockService.getMyBlocks()`(`@Transactional(readOnly = true)`).

**인증 필요** — `Authorization: Bearer <accessToken>`.

**대상 계정은 access 토큰에서만 정해진다.** 파라미터 없음.

**요청**: 없음.

**응답 200 OK** `ApiResponse<List<BlockResponse>>`

| 필드 | 타입 | 설명 |
|---|---|---|
| data | array | 내가 차단한 대상 배열. **차단한 시각(`created_at`) 오름차순**(먼저 차단한 대상이 앞) — `findAllByBlocker_IdOrderByCreatedAtAsc` |
| data[].nickname | String | 차단 대상의 닉네임(조회 시점의 **현재** 닉네임 — 차단 이후 대상이 닉네임을 바꿨으면 바뀐 값이 나온다. FK로 계정을 가리키므로 행 자체는 그대로 원래 대상을 가리킨다) |

**차단 이력이 없으면 200 + 빈 배열**이다 — 404·에러가 아니다.

```json
{"success":true,"data":[{"nickname":"홍길동"},{"nickname":"김철수"}],"message":null}
```

차단 이력 없음:
```json
{"success":true,"data":[],"message":null}
```

**실패**

| 상태 | ErrorCode | 조건 |
|---|---|---|
| 401 | UNAUTHENTICATED | Authorization 헤더 없음/무효 토큰/refresh 토큰으로 요청/탈퇴한 계정의 access 토큰/비밀번호 변경 이전에 발급된 access 토큰 |

**예시**
```bash
curl -i https://victoryfairy.com/api/users/me/blocks \
  -H 'Authorization: Bearer eyJ...'
```

---

## 알려진 한계 (설계상 받아들인 것, 코드 확인됨)

- **차단 해제(unblock) API가 없다.** 한번 만들어진 `user_blocks` 행은 계정 하드 삭제로 인한 CASCADE 소멸 전까지 이 API로는 지울 방법이 없다 — 사용자 입장에서 차단은 사실상 영구적이다(후속 작업 예정, `docs/requirements/user/user-block.md` "차단 해제" 절 참고).
- **차단 대상은 자신이 차단당했다는 사실을 이 API로 알 수 없다.** `GET /api/users/me/blocks`는 "내가 차단한 목록"만 주고, "나를 차단한 사람"은 어떤 엔드포인트로도 노출되지 않는다.
- **동일 닉네임 계정이 여럿이면 어느 계정을 차단하는지 모호하다.** 위 "이 도메인의 특이사항" 참고 — 요구사항 문서가 의도적으로 미정의로 남긴 지점이며, 실제 코드 동작은 500이다.

## 관련 문서

- [순위(ranking)](ranking.md) — `GET /api/rankings/bq/top`·`GET /api/rankings/bq`·`GET /api/rankings/bq/me`가 이 도메인의 `UserBlockRepository.findRelatedAccountIds`로 차단 관계자를 모집단에서 제외한다.
- [채팅(chat)](chat.md)(quiz 모듈) — `GET /rt/chat/rooms/{roomUid}/messages`가 같은 조회로 차단 관계자가 보낸 메시지를 조회 시점에 숨긴다. 전송·SSE 경로는 영향받지 않는다.
- 요구사항: `docs/requirements/user/user-block.md`(USER-BLK-1~21, 승인됨 2026-09-30)
