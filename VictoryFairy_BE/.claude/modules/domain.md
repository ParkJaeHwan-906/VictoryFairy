# domain 모듈

> domain 작업 시에만 로드되는 슬림 컨텍스트. (공통: `com.skhynix` 독립 앱, `:common`=ApiResponse/BusinessException/ErrorCode, MySQL+spring-dotenv, prod `ddl-auto=none`)

## 책임
프로젝트 전체 JPA 엔티티·리포지토리를 담는 `java-library` 모듈. 자체 실행 앱이 아니라 **포트 없음**. `user`·`quiz` 두 모듈이 이 모듈을 참조해 엔티티를 공유한다.

## 패키지 구성 (`domain/src/main/java/com/skhynix/domain/`)
- `user` — `User`, `UserAccount`, `UserRefreshToken`, `Gender`(enum) + 각 리포지토리
- `team` — `Team` + `TeamRepository`
- `player` — `Player` + `PlayerRepository`
- `stadium` — `Stadium` + `StadiumRepository`
- `game` — `Game`, `GameStatus`(코드 테이블) + `GameRepository`, `GameStatusRepository`
- `record` — `BatResult`, `BatterRecord`, `PitchResult`, `PitcherRecord` + 각 리포지토리 (야구 기록 도메인, `game_id` FK로 `Game` 참조)
- `chat` — `Chatroom`, `Chat` + `ChatroomRepository`, `ChatRepository` (`Chat`은 `Chatroom` 종속 하위 개념이라 별도 최상위 도메인 대신 `chat` 패키지로 통합 — `record` 패키지가 연관 엔티티를 묶은 선례와 동일 논리)

## 엔티티 → 테이블 (클래스 단수, 테이블 복수형)
`User`→`users` · `UserAccount`→`users_account` · `UserRefreshToken`→`users_refreshtoken` · `Team`→`teams` · `Player`→`players` · `Stadium`→`stadiums` · `Game`→`games` · `GameStatus`→`game_statuses` · `BatResult`→`bat_results` · `PitchResult`→`pitch_results` · `BatterRecord`→`batter_records` · `PitcherRecord`→`pitcher_records` · `Chatroom`→`chatrooms` · `Chat`→`chats`

## FK 관계 (전부 단방향, 역방향 `@OneToMany` 없음)
- `UserAccount` → `User` (`@OneToOne` LAZY, optional=false, unique 조인 컬럼) + `@OnDelete(CASCADE)`
- `UserRefreshToken` → `UserAccount` (`@ManyToOne` LAZY, optional=false) + `@OnDelete(CASCADE)`
- `Player` → `Team` (`@ManyToOne` LAZY, optional=false) + `@OnDelete(CASCADE)`
- `Game` → `Team`(home/away, 둘 다 optional=false) / `Stadium`(optional=true, nullable) / `GameStatus`(optional=false) — **`@OnDelete` 없음**
- `BatterRecord`/`PitcherRecord` → `Player`, `Game`, `BatResult`/`PitchResult` (`@ManyToOne` LAZY, optional=false 셋 다) + `@OnDelete(CASCADE)`
- `Chatroom` → `Team` (`@ManyToOne` LAZY, optional=false) + `@OnDelete(CASCADE)`
- `Chatroom` → `UserAccount`(owner, `@ManyToOne` LAZY, optional=false) — **`@OnDelete` 없음**
- `Chat` → `Chatroom`/`UserAccount` (`@ManyToOne` LAZY, optional=false 전부) + `@OnDelete(CASCADE)`

**CASCADE 정책 기준**: 팀·구장·경기상태 같은 **마스터 데이터**는 삭제돼도 그걸 참조하는 경기·기록이 연쇄 삭제되면 안 되므로 `@OnDelete` 없음. 반면 참조 엔티티에 완전히 종속되어 "삭제 시 함께 사라져도 되는" 데이터는 CASCADE — `BatterRecord`/`PitcherRecord`(원본 `Player`/`Game`/결과코드가 사라지면 기록도 같이 사라져야 함), `Chatroom → Team`(채팅방은 팀 종속 데이터, 사용자 결정)·`Chat → Chatroom`/`UserAccount`가 이 경우다. 즉 마스터데이터라고 무조건 non-cascade인 게 아니라 **"삭제되면 함께 사라져도 되는 종속 데이터인가"**가 기준이다. 새 FK 추가 시 이 기준으로 판단할 것. 같은 `Chatroom` 엔티티 안에서도 FK마다 정책이 다를 수 있다: `owner`(소유자 `UserAccount`)는 방이 소유자 계정 삭제와 무관하게 보존돼야 하는 공용 자원이고, 계정 삭제가 소프트삭제(`exit_at`)라 고아 FK가 애초에 생기지 않으므로 `Game → Team`과 같은 non-cascade다.

## 엔티티 작성 컨벤션 (새 엔티티 추가 시 따를 것)
- `@Entity` + `@Table(name = 복수형_스네이크)`, `@Getter`, `@NoArgsConstructor(access = PROTECTED)`. `@Setter` 두지 않음.
- PK: `@GeneratedValue(IDENTITY)` + `@Column(name = "id")` — **내부 전용**, API·URL에 노출하지 않는다
- 외부 노출이 필요하면 `id`와 별개로 `uid` 컬럼을 둔다(현재 `UserAccount`, `Chatroom` 두 곳에서 사용): `VARCHAR(36)` + UUID v4(`UUID.randomUUID().toString()`)를 **애플리케이션에서** 생성, private 생성자 본문에서 채우고 `@Builder` 파라미터로는 받지 않는다(타임스탬프와 같은 계열의 컨벤션). 목적은 순차 PK 열거 방지(예: SSE 구독 URL). Hibernate `@UuidGenerator`는 쓰지 않음. `Chat`은 uid 없음 — 개별 메시지는 외부 식별자 노출 요건이 없다.
- 모든 컬럼에 `length`/`nullable` 명시, 기본은 `nullable = false`
- 생성은 `private` 생성자 + `@Builder`. **타임스탬프는 생성자 파라미터로 받지 않는다** (Hibernate가 자동 채움)
- 타임스탬프 정책: **마스터/기준 데이터**(`User`, `UserAccount`, `Team`, `Player`, `Stadium`, `GameStatus`, `PitchResult`, `BatResult`)와 **갱신되는 엔티티**(`Game` — 진행 중 점수·상태가 바뀜, `Chatroom` — 참여인원/이름/소프트삭제로 갱신, `Chat` — blind/소프트삭제로 갱신)는 `@CreationTimestamp created_at`(updatable=false) + `@UpdateTimestamp updated_at` 둘 다. **기록성 엔티티**(`UserRefreshToken`, `BatterRecord`, `PitcherRecord` — 한 번 쌓이면 수정 안 함)는 `created_at`만.
- `Game`에는 **`winner` 컬럼이 없다** — 승자는 별도 컬럼으로 저장하지 않고 `home_score`/`away_score` + `game_status`에서 파생시키는 설계다(정규화, 스코어와 승자 불일치 방지).
- Enum은 `@Enumerated(ORDINAL)` + `columnDefinition = "TINYINT"`, **선언 순서 변경 금지** (예: `Gender` MALE=0/FEMALE=1)
- 상태 전이가 필요하면 `@Setter` 대신 **엔티티가 자신의 전이를 책임지는 의도 노출 메서드**를 둔다(예: `UserAccount.withdraw(LocalDateTime)`/`isWithdrawn()` — 이미 전이된 상태면 no-op으로 최초 값을 보존; 같은 패턴으로 `Chatroom.join()`/`leave()`(0 하한)/`delete(LocalDateTime)`/`isDeleted()`, `Chat.blind()`/`unblind()`/`delete(LocalDateTime)`/`isDeleted()`)
- 인덱스가 필요하면 `@Table(indexes = {@Index(...)})`로 명시한다 — domain 최초 사례는 `Chat`의 `idx_chats_chatroom_created(chatroom_id, created_at)`(히스토리 페이징: chatroom_id 등치 필터 + created_at DESC 정렬을 인덱스만으로 만족). prod는 `ddl-auto=none`이라 인덱스도 마이그레이션 DDL에 수동 포함해야 한다.

## 의존
- `api project(':common')`, `implementation spring-boot-starter-data-jpa`
- `testImplementation` 은 `spring-boot-starter-test`가 아니라 `junit-jupiter` + `assertj-core`만 (아래 테스트 현황 참고)
- 역방향 의존 없음 (`user`/`quiz`/`web-support` → `domain`, 이 방향만)

## 주의 / 열려있는 것
- `UserRepository`(existsByEmail/existsByTel), `UserAccountRepository`(findByUser_EmailAndExitAtIsNull/existsByNickname/**findActiveIdByUid**), `UserRefreshTokenRepository`(findByRefreshToken/deleteByUserAccount/expireValidTokens), `StadiumRepository`(findByName), `GameStatusRepository`(findByName), `ChatroomRepository`(아래), `ChatRepository`(아래) 외에는 **전부 `JpaRepository` 뼈대뿐** — `TeamRepository`/`PlayerRepository`/`BatResultRepository`/`BatterRecordRepository`/`PitcherRecordRepository`/`PitchResultRepository`/`GameRepository`는 커스텀 조회 메서드 없음. `findByName` 둘은 시드/크롤러의 lookup-or-create 용. `UserRefreshTokenRepository.deleteByUserAccount`는 **소비처가 0개인 죽은 메서드**(`logout`은 `findByRefreshToken`→`delete`를 씀)
- `ChatroomRepository`는 `findAllByDeletedAtIsNull`(목록용)·`findByUidAndDeletedAtIsNull`(uid로 소프트삭제 제외 조회 — 조회·구독·전송·히스토리 경로의 404 판정 기준)을 갖는다. `ChatRepository`는 히스토리용 `@Query`(파생 쿼리명 대신 명시 — `userAccount` fetch join으로 N+1 회피 + 별도 `countQuery`로 count에서 조인 제거, blind=false·deletedAt is null·최신순)와 신고용 `findByIdAndChatroom`(room-스코프 조회로 다른 방 메시지 PK 지목 차단)을 갖는다.
- `UserAccountRepository.findActiveIdByUid(String)`은 `Optional<Long>`을 반환하는 `@Query("select ua.id from UserAccount ua where ua.uid = :uid and ua.exitAt is null")` — **파생 쿼리명이 아니라 명시적 `@Query`인 이유**: Spring Data의 메서드 이름 파싱은 `By` 앞 subject에서 `Distinct`/`First|Top`만 인식하고 나머지(`Id` 포함)는 버리므로 이름만으로는 엔티티 전체가 반환된다. **`exit_at is null` 조건 때문에 커버링 인덱스를 의도적으로 포기했다**(옛 `findIdByUid`는 `uid` unique 인덱스만으로 커버링됐으나 `exit_at`이 인덱스에 없어 클러스터드 인덱스 조회가 부활). 그럼에도 감수하는 이유: 되살아나는 페이지는 대개 버퍼 풀 상주라 실이득 손실이 작고, 탈퇴 즉시 차단의 정확성(access 토큰이 stateless 3h라 이 조회가 유일한 차단점)이 우선이며, `(uid, exit_at)` 복합 인덱스는 100만 행 기준 ~45MB 대비 버퍼 풀 히트 1회 절약뿐이라 남는 게 없다(근거: `docs/requirements/user/withdraw.md` "결정 근거 2", 다시 조사하지 말 것). `user` 모듈의 `JwtAuthenticationFilter`가 uid→id 해석에 사용
- `UserAccountRepository.findByUser_EmailAndExitAtIsNull`은 탈퇴 계정을 "못 찾음"으로 흡수해 미가입 이메일과 로그인 응답을 동일하게 만드는 용도(`user` 모듈 `AuthService.login`이 사용)
- `team`/`player`/`stadium`/`game`/`record`/`chat` 엔티티를 소비하는 서비스·컨트롤러는 아직 없음 (현재 `user` 엔티티만 `user` 모듈과 `web-support`(`JwtAuthenticationFilter`가 `UserAccountRepository` 사용)가 실사용; 채팅용 앱 모듈 자체가 미착수)
- `Team.name`에 unique 제약은 **의도적으로** 걸지 않음
- `@Column`에 `columnDefinition`을 지정하면 **`length`가 DDL에서 무시된다** (실측: `UserAccount.uid`에서 `length`를 바꿔도 생성 DDL은 `columnDefinition`의 `VARCHAR(36)` 그대로). 길이를 바꾸려면 `length`와 `columnDefinition` 두 곳을 같이 고쳐야 함
- `UserAccount.uid`는 JWT subject로는 쓰이기 시작했으나(`user` 모듈) API 응답·URL에 노출하는 작업은 아직 미착수. **prod DDL 반영도 미착수**(`ddl-auto=none`, Flyway 없음 — prod `users_account`에 `uid` 컬럼이 없으므로 **이 상태로 배포하면 인증 전체가 실패한다**, 배포 선행 조건)
- 같은 계열의 배포 선행 조건: `chatrooms`/`chats` 테이블도 아직 prod DDL에 없다(`chatrooms`엔 `owner_account_id` 컬럼 포함, `chats`엔 `idx_chats_chatroom_created(chatroom_id, created_at)` 인덱스 포함). `ddl-auto=none` + Flyway 부재이므로 배포 전 두 테이블을 만드는 마이그레이션이 별도로 필요하다. `Chatroom.owner`가 non-null FK라 **방 시드보다 owner로 쓸 계정(시스템/admin 계정) 행이 먼저 존재해야** 한다(배포 순서 제약, `game_statuses` 시드-우선 제약과 같은 종류)
- `UserAccount.exit_at`은 실사용 컬럼이다(`withdraw()`로만 기록, `findActiveIdByUid`/`findByUser_EmailAndExitAtIsNull`이 조회 조건으로 사용). `@Builder`는 `exitAt`을 파라미터로 받지 않아 생성 시점부터 탈퇴 상태인 계정을 만들 수 없다(`uid`와 같은 이유로 빌더에서 제외)
- 탈퇴한 계정의 email·tel·nickname은 영구 점유되어 **재가입이 불가**하다. `users.email`/`users.tel` UNIQUE + MySQL의 partial unique index 부재 + `UserAccount`↔`User` `@OneToOne(unique)`가 겹쳐 앱 코드만으로는 풀 수 없는 스키마 제약이다 — 근거는 `docs/requirements/user/withdraw.md`("결정 근거 1")에 있으니 다시 조사하지 말 것
- 테스트 현황: `domain/src/test` 소스셋이 생겨 `GameTest`(3케이스)·`StadiumTest`(1케이스)·`ChatroomTest`(8케이스, Builder 배선·owner 동일 인스턴스 배선·uid 생성·join/leave/delete 전이)·`ChatTest`(4케이스, Builder 배선·blind/unblind 토글·delete 전이·이미 삭제된 메시지 재삭제 no-op)가 존재한다(Spring 컨텍스트/DB 없이 필드 배선·전이 로직만 확인하는 순수 단위 테스트). `record` 계열은 테스트 없음. `UserAccountWithdrawTest`는 여전히 `domain` 패키지 이름을 그대로 쓴 채 `user/src/test`에 얹혀 있다. 저장소 전체에 H2/Testcontainers/구동 중인 MySQL이 없어 `@DataJpaTest` 라운드트립 검증(FK 제약, nullable 컬럼 매핑, 코드 테이블 FK 저장·복원)은 여전히 보류 상태
- **`game_statuses` 시드 없음**: 아래 5행을 채우는 마이그레이션/시드 도구가 아직 없다. `Game.gameStatus`가 non-null FK라 **시드 전에는 `Game` 저장 자체가 불가능**.
  `INSERT INTO game_statuses (name) VALUES ('SCHEDULED'),('IN_PROGRESS'),('FINISHED'),('DRAW'),('CANCELED');`
- **상태값 ↔ 네이버 API 매핑** (원천: py-collector `docs/data-formats.md` "경기 상태 판정"): `SCHEDULED`=`statusCode "BEFORE"` / `IN_PROGRESS`=`"LIVE"` / `FINISHED`=`"RESULT"` / `DRAW`=`"RESULT"`+양팀 동점 / `CANCELED`=`cancel == true`. **취소 경기는 `statusCode`가 `"RESULT"`가 아니라 `"BEFORE"`로 오고 점수가 0-0 껍데기**이므로 CANCELED 는 반드시 `cancel` 플래그로 판정할 것. 네이버가 내려주는 `suspended`(서스펜디드 — 중단 후 재개)는 아직 목록에 없다. 코드 테이블이라 필요해지면 행만 추가하면 되고 배포는 필요 없다.
- **py-collector와 테이블 스키마 충돌 (미해결)**: `py-collector/deploy/sql/schema.sql`이 정의하는 `teams`(`team_code` VARCHAR(4) PK) / `players`(`player_id` VARCHAR(16) PK) / `games`(`game_id` VARCHAR(20) PK, `stadium` varchar 컬럼, `winner` 컬럼)는 domain JPA의 BIGINT auto-increment PK + FK 정규화 구조와 이름은 같고 구조가 다르다. 기록 테이블도 crawler는 `game_batting`/`game_pitching`, domain은 `batter_records`/`pitcher_records`로 갈린다. **현재 방침(사용자 결정): domain 스키마를 기준으로 간다.** crawler 쪽 정합은 별도 처리 예정. 참고로 `user` dev 프로파일은 `ddl-auto: create`라 같은 DB를 공유하면 데이터가 재생성될 위험이 있다.
