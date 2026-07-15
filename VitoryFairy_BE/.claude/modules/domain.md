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
- PK: `@GeneratedValue(IDENTITY)` + `@Column(name = "id")`
- 모든 컬럼에 `length`/`nullable` 명시, 기본은 `nullable = false`
- 생성은 `private` 생성자 + `@Builder`. **타임스탬프는 생성자 파라미터로 받지 않는다** (Hibernate가 자동 채움)
- 타임스탬프 정책: **마스터/기준 데이터**(`User`, `UserAccount`, `Team`, `Player`, `PitchResult`, `BatResult`)는 `@CreationTimestamp created_at`(updatable=false) + `@UpdateTimestamp updated_at` 둘 다. **기록성 엔티티**(`UserRefreshToken`, `BatterRecord`, `PitcherRecord` — 한 번 쌓이면 수정 안 함)는 `created_at`만.
- Enum은 `@Enumerated(ORDINAL)` + `columnDefinition = "TINYINT"`, **선언 순서 변경 금지** (예: `Gender` MALE=0/FEMALE=1)

## 의존
- `api project(':common')`, `implementation spring-boot-starter-data-jpa`
- 역방향 의존 없음 (`user`/`quiz`/`create` → `domain`, 이 방향만)

## 주의 / 열려있는 것
- `UserRepository`(existsByEmail/existsByTel), `UserAccountRepository`(findByUser_Email/existsByNickname), `UserRefreshTokenRepository`(findByRefreshToken/deleteByUserAccount/expireValidTokens) 외에는 **전부 `JpaRepository` 뼈대뿐** — `TeamRepository`/`PlayerRepository`/`BatResultRepository`/`BatterRecordRepository`/`PitcherRecordRepository`/`PitchResultRepository`는 커스텀 조회 메서드 없음
- `team`/`player`/`record` 엔티티를 소비하는 서비스·컨트롤러는 아직 없음 (현재 `user` 모듈만 이 모듈의 엔티티를 실사용)
- `Team.name`에 unique 제약은 **의도적으로** 걸지 않음
