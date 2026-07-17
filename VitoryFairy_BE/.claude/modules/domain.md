# domain 모듈

> domain 작업 시에만 로드되는 슬림 컨텍스트. (공통: `com.skhynix` 독립 앱, `:common`=ApiResponse/BusinessException/ErrorCode, MySQL+spring-dotenv, prod `ddl-auto=none`)

## 책임
프로젝트 전체 JPA 엔티티·리포지토리를 담는 `java-library` 모듈. 자체 실행 앱이 아니라 **포트 없음**. `user`·`quiz`·`create` 세 모듈이 이 모듈을 참조해 엔티티를 공유한다.

## 패키지 구성 (`domain/src/main/java/com/skhynix/domain/`)
- `user` — `User`, `UserAccount`, `UserRefreshToken`, `Gender`(enum) + 각 리포지토리
- `team` — `Team` + `TeamRepository`
- `player` — `Player` + `PlayerRepository`
- `stadium` — `Stadium` + `StadiumRepository`
- `game` — `Game`, `GameStatus`(코드 테이블) + `GameRepository`, `GameStatusRepository`
- `record` — `BatResult`, `BatterRecord`, `PitchResult`, `PitcherRecord` + 각 리포지토리 (야구 기록 도메인, `game_id` FK로 `Game` 참조)

## 엔티티 → 테이블 (클래스 단수, 테이블 복수형)
`User`→`users` · `UserAccount`→`users_account` · `UserRefreshToken`→`users_refreshtoken` · `Team`→`teams` · `Player`→`players` · `Stadium`→`stadiums` · `Game`→`games` · `GameStatus`→`game_statuses` · `BatResult`→`bat_results` · `PitchResult`→`pitch_results` · `BatterRecord`→`batter_records` · `PitcherRecord`→`pitcher_records`

## FK 관계 (전부 단방향, 역방향 `@OneToMany` 없음)
- `UserAccount` → `User` (`@OneToOne` LAZY, optional=false, unique 조인 컬럼) + `@OnDelete(CASCADE)`
- `UserRefreshToken` → `UserAccount` (`@ManyToOne` LAZY, optional=false) + `@OnDelete(CASCADE)`
- `Player` → `Team` (`@ManyToOne` LAZY, optional=false) + `@OnDelete(CASCADE)`
- `Game` → `Team`(home/away, 둘 다 optional=false) / `Stadium`(optional=true, nullable) / `GameStatus`(optional=false) — **`@OnDelete` 없음**
- `BatterRecord`/`PitcherRecord` → `Player`, `Game`, `BatResult`/`PitchResult` (`@ManyToOne` LAZY, optional=false 셋 다) + `@OnDelete(CASCADE)`

**CASCADE 정책 기준**: 팀·구장·경기상태 같은 **마스터 데이터**는 삭제돼도 그걸 참조하는 경기·기록이 연쇄 삭제되면 안 되므로 `@OnDelete` 없음. `BatterRecord`/`PitcherRecord`(원본 `Player`/`Game`/결과코드가 사라지면 기록도 같이 사라져야 함)처럼 **기록 계열**은 CASCADE. 새 FK 추가 시 이 기준으로 판단할 것.

## 엔티티 작성 컨벤션 (새 엔티티 추가 시 따를 것)
- `@Entity` + `@Table(name = 복수형_스네이크)`, `@Getter`, `@NoArgsConstructor(access = PROTECTED)`. `@Setter` 두지 않음.
- PK: `@GeneratedValue(IDENTITY)` + `@Column(name = "id")`
- 모든 컬럼에 `length`/`nullable` 명시, 기본은 `nullable = false`
- 생성은 `private` 생성자 + `@Builder`. **타임스탬프는 생성자 파라미터로 받지 않는다** (Hibernate가 자동 채움)
- 타임스탬프 정책: **마스터/기준 데이터**(`User`, `UserAccount`, `Team`, `Player`, `Stadium`, `GameStatus`, `PitchResult`, `BatResult`)와 **갱신되는 엔티티**(`Game` — 진행 중 점수·상태가 바뀜)는 `@CreationTimestamp created_at`(updatable=false) + `@UpdateTimestamp updated_at` 둘 다. **기록성 엔티티**(`UserRefreshToken`, `BatterRecord`, `PitcherRecord` — 한 번 쌓이면 수정 안 함)는 `created_at`만.
- `Game`에는 **`winner` 컬럼이 없다** — 승자는 별도 컬럼으로 저장하지 않고 `home_score`/`away_score` + `game_status`에서 파생시키는 설계다(정규화, 스코어와 승자 불일치 방지).
- Enum은 `@Enumerated(ORDINAL)` + `columnDefinition = "TINYINT"`, **선언 순서 변경 금지** (예: `Gender` MALE=0/FEMALE=1)

## 의존
- `api project(':common')`, `implementation spring-boot-starter-data-jpa`
- `testImplementation` 은 `spring-boot-starter-test`가 아니라 `junit-jupiter` + `assertj-core`만 (아래 테스트 현황 참고)
- 역방향 의존 없음 (`user`/`quiz`/`create` → `domain`, 이 방향만)

## 주의 / 열려있는 것
- `UserRepository`(existsByEmail/existsByTel), `UserAccountRepository`(findByUser_Email/existsByNickname), `UserRefreshTokenRepository`(findByRefreshToken/deleteByUserAccount/expireValidTokens), `StadiumRepository`(findByName), `GameStatusRepository`(findByName) 외에는 **전부 `JpaRepository` 뼈대뿐** — `TeamRepository`/`PlayerRepository`/`BatResultRepository`/`BatterRecordRepository`/`PitcherRecordRepository`/`PitchResultRepository`/`GameRepository`는 커스텀 조회 메서드 없음. `findByName` 둘은 시드/크롤러의 lookup-or-create 용.
- `team`/`player`/`stadium`/`game`/`record` 엔티티를 소비하는 서비스·컨트롤러는 아직 없음 (현재 `user` 모듈만 이 모듈의 엔티티를 실사용)
- `Team.name`에 unique 제약은 **의도적으로** 걸지 않음
- 테스트 현황: `GameTest`(3케이스)·`StadiumTest`(1케이스)는 Spring 컨텍스트/DB 없이 Builder 필드 배선만 확인하는 순수 단위 테스트. `record` 계열은 테스트 없음. DB(H2/Testcontainers/구동 중인 MySQL)가 없어 `@DataJpaTest` 라운드트립 검증은 보류 상태 — FK 제약, nullable 컬럼 매핑, 코드 테이블 FK 저장·복원은 미검증.
- **`game_statuses` 시드 없음**: SCHEDULED/FINISHED/DRAW/CANCELED 4행을 채우는 마이그레이션/시드 도구가 아직 없다. `Game.gameStatus`가 non-null FK라 **시드 전에는 `Game` 저장 자체가 불가능**. 또한 이 4개 목록에 실시간 "진행 중"(IN_PROGRESS)이 빠져 있어, 소스(네이버) 쪽 상태값을 확인해 목록을 보강해야 한다.
- **py-collector와 테이블 스키마 충돌 (미해결)**: `py-collector/deploy/sql/schema.sql`이 정의하는 `teams`(`team_code` VARCHAR(4) PK) / `players`(`player_id` VARCHAR(16) PK) / `games`(`game_id` VARCHAR(20) PK, `stadium` varchar 컬럼, `winner` 컬럼)는 domain JPA의 BIGINT auto-increment PK + FK 정규화 구조와 이름은 같고 구조가 다르다. 기록 테이블도 crawler는 `game_batting`/`game_pitching`, domain은 `batter_records`/`pitcher_records`로 갈린다. **현재 방침(사용자 결정): domain 스키마를 기준으로 간다.** crawler 쪽 정합은 별도 처리 예정. 참고로 `user` dev 프로파일은 `ddl-auto: create`라 같은 DB를 공유하면 데이터가 재생성될 위험이 있다.
