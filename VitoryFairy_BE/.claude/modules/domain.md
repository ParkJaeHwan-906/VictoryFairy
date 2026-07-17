# domain 모듈

> domain 작업 시에만 로드되는 슬림 컨텍스트. (공통: `com.skhynix` 독립 앱, `:common`=ApiResponse/BusinessException/ErrorCode, MySQL+spring-dotenv, prod `ddl-auto=none`)

## 책임
프로젝트 전체 JPA 엔티티·리포지토리를 담는 `java-library` 모듈. 자체 실행 앱이 아니라 **포트 없음**. `user`·`quiz`·`create` 세 모듈이 이 모듈을 참조해 엔티티를 공유한다.

## 패키지 구성 (`domain/src/main/java/com/skhynix/domain/`)
- `user` — `User`, `UserAccount`, `UserRefreshToken`, `Gender`(enum) + 각 리포지토리
- `team` — `Team` + `TeamRepository`
- `player` — `Player` + `PlayerRepository`
- `record` — `BatResult`, `BatterRecord`, `PitchResult`, `PitcherRecord` + 각 리포지토리 (야구 기록 도메인, 이번 세션에서 신규 추가)

## 엔티티 → 테이블 (클래스 단수, 테이블 복수형)
`User`→`users` · `UserAccount`→`users_account` · `UserRefreshToken`→`users_refreshtoken` · `Team`→`teams` · `Player`→`players` · `BatResult`→`bat_results` · `PitchResult`→`pitch_results` · `BatterRecord`→`batter_records` · `PitcherRecord`→`pitcher_records`

## FK 관계 (전부 단방향, 역방향 `@OneToMany` 없음)
- `UserAccount` → `User` (`@OneToOne` LAZY, optional=false, unique 조인 컬럼) + `@OnDelete(CASCADE)`
- `UserRefreshToken` → `UserAccount` (`@ManyToOne` LAZY, optional=false) + `@OnDelete(CASCADE)`
- `Player` → `Team` (`@ManyToOne` LAZY, optional=false) + `@OnDelete(CASCADE)`
- `BatterRecord` → `Player`, `BatResult` (`@ManyToOne` LAZY, optional=false 둘 다) + `@OnDelete(CASCADE)`
- `PitcherRecord` → `Player`, `PitchResult` (`@ManyToOne` LAZY, optional=false 둘 다) + `@OnDelete(CASCADE)`

## 엔티티 작성 컨벤션 (새 엔티티 추가 시 따를 것)
- `@Entity` + `@Table(name = 복수형_스네이크)`, `@Getter`, `@NoArgsConstructor(access = PROTECTED)`. `@Setter` 두지 않음.
- PK: `@GeneratedValue(IDENTITY)` + `@Column(name = "id")` — **내부 전용**, API·URL에 노출하지 않는다
- 외부 노출이 필요하면 `id`와 별개로 `uid` 컬럼을 둔다(예: `UserAccount.uid`): `VARCHAR(36)` + UUID v4(`UUID.randomUUID().toString()`)를 **애플리케이션에서** 생성, private 생성자 본문에서 채우고 `@Builder` 파라미터로는 받지 않는다(타임스탬프와 같은 계열의 컨벤션). 목적은 순차 PK 열거 방지. Hibernate `@UuidGenerator`는 쓰지 않음.
- 모든 컬럼에 `length`/`nullable` 명시, 기본은 `nullable = false`
- 생성은 `private` 생성자 + `@Builder`. **타임스탬프는 생성자 파라미터로 받지 않는다** (Hibernate가 자동 채움)
- 타임스탬프 정책: **마스터/기준 데이터**(`User`, `UserAccount`, `Team`, `Player`, `PitchResult`, `BatResult`)는 `@CreationTimestamp created_at`(updatable=false) + `@UpdateTimestamp updated_at` 둘 다. **기록성 엔티티**(`UserRefreshToken`, `BatterRecord`, `PitcherRecord` — 한 번 쌓이면 수정 안 함)는 `created_at`만.
- Enum은 `@Enumerated(ORDINAL)` + `columnDefinition = "TINYINT"`, **선언 순서 변경 금지** (예: `Gender` MALE=0/FEMALE=1)
- 상태 전이가 필요하면 `@Setter` 대신 **엔티티가 자신의 전이를 책임지는 의도 노출 메서드**를 둔다(예: `UserAccount.withdraw(LocalDateTime)`/`isWithdrawn()` — 이미 전이된 상태면 no-op으로 최초 값을 보존)

## 의존
- `api project(':common')`, `implementation spring-boot-starter-data-jpa`
- 역방향 의존 없음 (`user`/`quiz`/`create` → `domain`, 이 방향만)

## 주의 / 열려있는 것
- `UserRepository`(existsByEmail/existsByTel), `UserAccountRepository`(findByUser_EmailAndExitAtIsNull/existsByNickname/**findActiveIdByUid**), `UserRefreshTokenRepository`(findByRefreshToken/deleteByUserAccount/expireValidTokens) 외에는 **전부 `JpaRepository` 뼈대뿐** — `TeamRepository`/`PlayerRepository`/`BatResultRepository`/`BatterRecordRepository`/`PitcherRecordRepository`/`PitchResultRepository`는 커스텀 조회 메서드 없음. `UserRefreshTokenRepository.deleteByUserAccount`는 **소비처가 0개인 죽은 메서드**(`logout`은 `findByRefreshToken`→`delete`를 씀)
- `UserAccountRepository.findActiveIdByUid(String)`은 `Optional<Long>`을 반환하는 `@Query("select ua.id from UserAccount ua where ua.uid = :uid and ua.exitAt is null")` — **파생 쿼리명이 아니라 명시적 `@Query`인 이유**: Spring Data의 메서드 이름 파싱은 `By` 앞 subject에서 `Distinct`/`First|Top`만 인식하고 나머지(`Id` 포함)는 버리므로 이름만으로는 엔티티 전체가 반환된다. **`exit_at is null` 조건 때문에 커버링 인덱스를 의도적으로 포기했다**(옛 `findIdByUid`는 `uid` unique 인덱스만으로 커버링됐으나 `exit_at`이 인덱스에 없어 클러스터드 인덱스 조회가 부활). 그럼에도 감수하는 이유: 되살아나는 페이지는 대개 버퍼 풀 상주라 실이득 손실이 작고, 탈퇴 즉시 차단의 정확성(access 토큰이 stateless 3h라 이 조회가 유일한 차단점)이 우선이며, `(uid, exit_at)` 복합 인덱스는 100만 행 기준 ~45MB 대비 버퍼 풀 히트 1회 절약뿐이라 남는 게 없다(근거: `docs/requirements/user/withdraw.md` "결정 근거 2", 다시 조사하지 말 것). `user` 모듈의 `JwtAuthenticationFilter`가 uid→id 해석에 사용
- `UserAccountRepository.findByUser_EmailAndExitAtIsNull`은 탈퇴 계정을 "못 찾음"으로 흡수해 미가입 이메일과 로그인 응답을 동일하게 만드는 용도(`user` 모듈 `AuthService.login`이 사용)
- `team`/`player`/`record` 엔티티를 소비하는 서비스·컨트롤러는 아직 없음 (현재 `user` 모듈만 이 모듈의 엔티티를 실사용)
- `Team.name`에 unique 제약은 **의도적으로** 걸지 않음
- `@Column`에 `columnDefinition`을 지정하면 **`length`가 DDL에서 무시된다** (실측: `UserAccount.uid`에서 `length`를 바꿔도 생성 DDL은 `columnDefinition`의 `VARCHAR(36)` 그대로). 길이를 바꾸려면 `length`와 `columnDefinition` 두 곳을 같이 고쳐야 함
- `UserAccount.uid`는 JWT subject로는 쓰이기 시작했으나(`user` 모듈) API 응답·URL에 노출하는 작업은 아직 미착수. **prod DDL 반영도 미착수**(`ddl-auto=none`, Flyway 없음 — prod `users_account`에 `uid` 컬럼이 없으므로 **이 상태로 배포하면 인증 전체가 실패한다**, 배포 선행 조건)
- `UserAccount.exit_at`은 이제 실사용 컬럼이다(`withdraw()`로 기록, `findActiveIdByUid`/`findByUser_EmailAndExitAtIsNull`이 조회 조건으로 사용). `@Builder`가 `exitAt`을 파라미터로 계속 받아 생성 시점부터 탈퇴 계정을 만들 수도 있는 상태다 — `signup`은 안 넘겨 실질적 피해는 없지만 `withdraw()`가 생긴 지금은 어색한 잔재
- 탈퇴한 계정의 email·tel·nickname은 영구 점유되어 **재가입이 불가**하다. `users.email`/`users.tel` UNIQUE + MySQL의 partial unique index 부재 + `UserAccount`↔`User` `@OneToOne(unique)`가 겹쳐 앱 코드만으로는 풀 수 없는 스키마 제약이다 — 근거는 `docs/requirements/user/withdraw.md`("결정 근거 1")에 있으니 다시 조사하지 말 것
- `domain/src/test` 소스셋이 여전히 존재하지 않고 저장소 전체에 H2도 없음(DB를 실제로 띄우는 테스트는 저장소 전체에 없음). 엔티티 단위 테스트(`UserAccountWithdrawTest`)조차 `domain` 패키지 이름을 그대로 쓴 채 `user/src/test`에 얹혀 있다. `@DataJpaTest` 도입 시 H2는 `CHARACTER SET ascii COLLATE ascii_bin`을 파싱 못 해 스키마 생성이 실패하므로 Testcontainers MySQL이 정합적
