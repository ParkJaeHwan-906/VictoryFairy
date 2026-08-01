# 경기 상태 동기화 + 선수 기록 재적재 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 취소·예정·진행 경기를 포함한 경기 상태 주기 동기화(games_sync 잡), game_lineups.position의 FK 분리, batter/pitcher 경기 기록 집계형 재적재를 구현한다.

**Architecture:** dev_be 파생 브랜치에서 JPA 엔티티(스키마 진실)를 먼저 바꾸고, 1회성 마이그레이션 SQL을 준비한 뒤, 수집기(py-collector)가 새 스키마로 적재하도록 확장한다. 수집기의 `parse_record()`는 이미 `BattingRow`/`PitchingRow` 집계를 파싱하고 있으므로 DB upsert 경로만 추가하면 된다. games_sync는 네이버 schedule API(하루 단위)를 주기 폴링해 games 행의 상태를 갱신한다.

**Tech Stack:** Python 3.12 (pymysql, httpx) / Java 21 + Spring Data JPA (dev_be) / MySQL 8.0

**Worktrees (2개):**
- **AI**: `/Users/sotaeho/PycharmProjects/VictoryFairy-games-records` — 브랜치 `sotaeho/ai/feat-games-status-records` (wiki-quiz 브랜치 `sotaeho/ai/feat-llm-wiki-quiz` 위에 스택). Task 5~11.
- **BE**: `/Users/sotaeho/PycharmProjects/VictoryFairy-be-records` — 브랜치 `sotaeho/be/feat-position-record-aggregate` (origin/dev_be 파생). Task 1~4.

## Global Constraints

- **취소 판정은 `cancel` 플래그 최우선.** 취소 경기는 `statusCode:"BEFORE"` + `cancel:true` + 0-0 점수 + **`winner:"DRAW"`** 로 온다 (2026-07-08 `20260708NCHH02026` 라이브 실측). `winner`/`statusCode` 필드만으로 상태·무승부를 판정하면 오답.
- **schedule API는 하루 단위 조회만.** `fromDate~toDate` 광범위(2개월) 조회는 경기 4개만 반환하는 실측 결함이 있다.
- **상태 어휘 5종 고정:** `SCHEDULED` / `IN_PROGRESS` / `FINISHED` / `DRAW` / `CANCELED` (dev_be `GameStatus` javadoc과 동일). 미지 statusCode는 적재하지 않고 warning 로그 후 skip.
- **games_sync는 기존 데이터를 저하시키지 않는다:** 점수는 `COALESCE(VALUES(...), 기존값)`, stadium_id는 갱신하지 않음(records 잡 소유).
- **exporter의 game_result는 `FINISHED`/`DRAW` 상태만 내보낸다** (SCHEDULED/CANCELED 행이 퀴즈 통계를 오염시키면 안 됨).
- **스키마 진실은 domain JPA 엔티티.** py-collector에 DDL 사본(schema.sql류) 금지. 1회성 마이그레이션 SQL은 dev_be `VictoryFairy_BE/infra/sql/`에 둔다.
- **BE 컨벤션 (`.claude/modules/domain.md`) 준수:** 테이블 복수형 스네이크, `@NoArgsConstructor(PROTECTED)`, private 생성자 + `@Builder`, 타임스탬프는 빌더 파라미터 금지, 모든 컬럼 `length`/`nullable` 명시, UNIQUE는 `@Table(uniqueConstraints=...)` 명시, `@Setter` 금지.
- **수집기 멱등:** 자연키(naver_game_id, game_id+player_id) upsert, 재실행 안전.
- **선수 자연키는 `kbo_player_id` 단일:** 네이버 pcode == KBO playerId 실측(2026-07 박스스코어·로스터 교집합 228명 전수 일치)을 스키마 전제로 승격, `players.naver_pcode` 컬럼 폐기. resolve 시 DB 이름과 API 이름이 다르면 warning 로그(동치 전제 훼손 감지 신호).
- **테스트:** py-collector는 `pytest -q` (worktree의 `VictoryFairy_AI/py-collector`에서), BE는 `JAVA_HOME=$(brew --prefix openjdk@21) ./gradlew :domain:test` (worktree의 `VictoryFairy_BE`에서).
- 커밋 메시지 끝에 `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

---

### Task 1: [BE] Position 엔티티 + GameLineup FK 전환

**Files:**
- Create: `VictoryFairy_BE/domain/src/main/java/com/skhynix/domain/game/entity/Position.java`
- Create: `VictoryFairy_BE/domain/src/main/java/com/skhynix/domain/game/repository/PositionRepository.java`
- Modify: `VictoryFairy_BE/domain/src/main/java/com/skhynix/domain/game/entity/GameLineup.java` (`position` String → `Position` FK)
- Test: `VictoryFairy_BE/domain/src/test/java/com/skhynix/domain/game/entity/GameLineupTest.java` (기존 파일 갱신)

**Interfaces:**
- Produces: `positions` 테이블 (id BIGINT PK, name VARCHAR(100) NOT NULL, created_at/updated_at), `game_lineups.position_id` BIGINT NULL FK — Task 4 마이그레이션 SQL과 Task 6 수집기 SQL이 이 구조에 의존.

- [ ] **Step 1: Position 엔티티 작성** — `GameStatus.java`를 그대로 본뜬 코드 테이블. javadoc에 값의 원천 명시:

```java
package com.skhynix.domain.game.entity;

// imports는 GameStatus.java와 동일 구성

/**
 * 수비 포지션 코드 테이블. 값은 네이버 record API 박스스코어의 {@code pos} 표기 그대로다
 * (중/포/지/투/좌/우/유/一/二/三 … 표기, 대타는 "타", 대주자는 "주").
 * py-collector 가 lookup-or-insert 로 행을 만들며, {@link GameLineup}이 {@code position_id} FK로 참조한다.
 * "타"/"주"는 수비 위치가 아니라 출전 형태 표기임에 주의 — 원천 표기를 가공 없이 보존하는 설계다.
 */
@Entity
@Table(name = "positions")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Position {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "name", length = 100, nullable = false)
    private String name;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private Position(String name) {
        this.name = name;
    }
}
```

- [ ] **Step 2: PositionRepository** — `GameStatusRepository` 선례 그대로 (`findByName`은 시드/크롤러 lookup-or-create 용):

```java
public interface PositionRepository extends JpaRepository<Position, Long> {
    Optional<Position> findByName(String name);
}
```

- [ ] **Step 3: GameLineup 수정** — `position` String 컬럼 제거, FK로 교체. `GameStatus` 참조와 동일 논리로 **`@OnDelete` 없음** (마스터 데이터 — 포지션 삭제가 라인업 연쇄삭제로 이어지면 안 됨). nullable 유지(현재도 position NULL 가능):

```java
    @ManyToOne(fetch = FetchType.LAZY, optional = true)
    @JoinColumn(name = "position_id", nullable = true)
    private Position position;
```

빌더 파라미터 타입도 `String position` → `Position position`으로 변경. 클래스 javadoc의 position 설명을 "코드 테이블 FK, 원천 표기는 `Position` javadoc 참고"로 갱신.

- [ ] **Step 4: GameLineupTest 갱신** — 기존 빌더 배선 테스트에서 `position("중")` 류를 `Position.builder().name("중").build()` 인스턴스로 교체, `assertThat(lineup.getPosition().getName()).isEqualTo("중")` + `position(null)` 허용 케이스 확인. 신규 `PositionTest`(빌더 배선 1케이스)도 추가.

- [ ] **Step 5: 테스트 실행** — `JAVA_HOME=$(brew --prefix openjdk@21) ./gradlew :domain:test` → PASS 확인.

- [ ] **Step 6: Commit** — `feat(domain): 포지션 코드 테이블 분리 — game_lineups.position을 positions FK로 전환`

### Task 2: [BE] BatterRecord/PitcherRecord 집계형 재설계 + BatResult/PitchResult 삭제

**Files:**
- Modify: `VictoryFairy_BE/domain/src/main/java/com/skhynix/domain/record/entity/BatterRecord.java`
- Modify: `VictoryFairy_BE/domain/src/main/java/com/skhynix/domain/record/entity/PitcherRecord.java`
- Delete: `record/entity/BatResult.java`, `record/entity/PitchResult.java`, `record/repository/BatResultRepository.java`, `record/repository/PitchResultRepository.java`
- Test: `record/entity/BatterRecordTest.java`, `record/entity/PitcherRecordTest.java` (신규)

**Interfaces:**
- Produces: `batter_records`/`pitcher_records` 집계 컬럼 구조 — Task 4 DDL과 Task 7 수집기 upsert가 컬럼명에 의존.
- 컬럼 세트의 원천: py-collector `game_records.py`의 `BattingRow`/`PitchingRow` dataclass (네이버 record API battersBoxscore/pitchersBoxscore와 1:1).

- [ ] **Step 1: BatterRecord 재작성** — 경기×선수 1행 집계. `batResult` FK 제거, 스탯 컬럼 추가. 수집기 멱등 upsert 대상이므로 "기록성(created_at만)"이 아니라 **갱신되는 엔티티(created_at+updated_at)** 로 재분류:

```java
/**
 * 타자 경기 기록(경기×선수 1행 집계). 원천은 네이버 record API 박스스코어(battersBoxscore)이며
 * py-collector 가 멱등 upsert 한다(백필 재실행으로 갱신될 수 있어 updated_at 보유).
 * 스탯 컬럼은 API 결측 대비 전부 nullable. 타순·포지션·선발 여부는 game_lineups 소관(중복 저장 금지).
 */
@Entity
@Table(name = "batter_records", uniqueConstraints = @UniqueConstraint(
        name = "uk_batter_records_game_player", columnNames = {"game_id", "player_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class BatterRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "player_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Player player;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "game_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Game game;

    @Column(name = "at_bats", nullable = true)     private Integer atBats;
    @Column(name = "runs", nullable = true)        private Integer runs;
    @Column(name = "hits", nullable = true)        private Integer hits;
    @Column(name = "home_runs", nullable = true)   private Integer homeRuns;
    @Column(name = "rbi", nullable = true)         private Integer rbi;
    @Column(name = "walks", nullable = true)       private Integer walks;
    @Column(name = "strikeouts", nullable = true)  private Integer strikeouts;
    @Column(name = "stolen_bases", nullable = true) private Integer stolenBases;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Builder
    private BatterRecord(Player player, Game game, Integer atBats, Integer runs, Integer hits,
            Integer homeRuns, Integer rbi, Integer walks, Integer strikeouts, Integer stolenBases) {
        // 필드 대입 (타임스탬프 제외)
    }
}
```

- [ ] **Step 2: PitcherRecord 재작성** — 동일 패턴. `pitchResult` FK 제거. decision(W/L/S/H)은 game_lineups 소관이므로 두지 않는다:

```java
// @Table(name = "pitcher_records", uniqueConstraints = @UniqueConstraint(
//         name = "uk_pitcher_records_game_player", columnNames = {"game_id", "player_id"}))
// player/game FK는 BatterRecord와 동일. 스탯 컬럼:
    @Column(name = "seq", nullable = false)            private int seq;        // 등판 순서(0=선발)
    @Column(name = "ip_display", length = 8, nullable = true) private String ipDisplay; // "6 ⅓"
    @Column(name = "ip_outs", nullable = true)         private Integer ipOuts; // 아웃 수(6⅓=19)
    @Column(name = "batters_faced", nullable = true)   private Integer battersFaced;
    @Column(name = "at_bats", nullable = true)         private Integer atBats;
    @Column(name = "hits", nullable = true)            private Integer hits;
    @Column(name = "runs", nullable = true)            private Integer runs;
    @Column(name = "earned_runs", nullable = true)     private Integer earnedRuns;
    @Column(name = "home_runs", nullable = true)       private Integer homeRuns;
    @Column(name = "walks_hbp", nullable = true)       private Integer walksHbp;
    @Column(name = "strikeouts", nullable = true)      private Integer strikeouts;
```

- [ ] **Step 3: BatResult/PitchResult 4개 파일 삭제** — 소비처 없음은 domain.md에 이미 기록돼 있음(엔티티·리포지토리 뼈대뿐). `git rm`으로 제거.

- [ ] **Step 4: 테스트 신설** — `BatterRecordTest`/`PitcherRecordTest`: 빌더 필드 배선(전 컬럼), null 스탯 허용 각 1케이스 (순수 단위 테스트, DB 없음 — `GameTest` 스타일).

- [ ] **Step 5: 테스트 실행** — `./gradlew :domain:test` PASS.

- [ ] **Step 6: Commit** — `feat(domain): batter/pitcher_records 집계형 재설계 — bat/pitch_results 코드테이블 폐기`

### Task 3: [BE] Player.naverPcode 폐기 — kbo_player_id 단일 자연키

**Files:**
- Modify: `VictoryFairy_BE/domain/src/main/java/com/skhynix/domain/player/entity/Player.java`
- Modify: `VictoryFairy_BE/user/src/main/java/com/skhynix/user/player/dto/PlayerResponse.java` (javadoc만)
- Modify: `VictoryFairy_BE/user/src/test/java/com/skhynix/user/player/service/PlayerServiceTest.java`
- Modify: `VictoryFairy_BE/user/src/test/java/com/skhynix/user/player/controller/PlayerControllerTest.java`

**Interfaces:**
- Produces: `players` 테이블에서 `naver_pcode` 컬럼 제거 — Task 4 마이그레이션 SQL과 Task 5 수집기 해소 로직이 이 결정에 의존.
- 근거: 네이버 pcode == KBO playerId 실측 동치(Global Constraints) + naverPcode 소비처 없음(DTO 미노출, 서비스 로직 미사용 — 엔티티 필드·테스트 픽스처가 전부).

- [ ] **Step 1: Player.java 수정** — `naverPcode` 필드와 빌더 파라미터를 삭제하고, `kboPlayerId` javadoc을 교체 (기존 naverPcode javadoc의 "KBO 공식 playerId 와는 다른 체계" 서술은 실측으로 반증된 낡은 문구 — 함께 제거):

```java
    /**
     * KBO 공식 사이트 playerId. py-collector 로스터·박스스코어 적재 공통의 소스 자연키(UNIQUE).
     * 네이버 record API 의 pcode 도 실측상 같은 값(2026-07 교집합 228명 전수 일치)이라
     * 단일 컬럼으로 통합했다(구 naver_pcode 컬럼 폐기 — infra/sql/migrate-position-records.sql).
     */
    @Column(name = "kbo_player_id", length = 16, unique = true)
    private String kboPlayerId;
```

빌더 시그니처: `private Player(Team team, String name, double average, String kboPlayerId)`.

- [ ] **Step 2: PlayerResponse.java javadoc 정리** — "`Player.naverPcode`/`kboPlayerId` 는 py-collector 가 upsert 키로 소유하는 소스 자연키라" → "`Player.kboPlayerId` 는 py-collector 가 upsert 키로 소유하는 소스 자연키라". 나머지 서술 불변.

- [ ] **Step 3: 테스트 픽스처 정리** — `PlayerServiceTest`의 `.naverPcode("6" + id)` 라인 삭제. `PlayerControllerTest`의 `@DisplayName` 문구 "average·naverPcode·kboPlayerId·team" → "average·kboPlayerId·team", `jsonPath("$.data[0].naverPcode").doesNotExist()` 라인 삭제.

- [ ] **Step 4: 잔존 참조 확인** — `grep -rn naverPcode VictoryFairy_BE/` 결과 0건.

- [ ] **Step 5: 테스트 실행** — `JAVA_HOME=$(brew --prefix openjdk@21) ./gradlew :domain:test :user:test` PASS.

- [ ] **Step 6: Commit** — `feat(domain): players.naver_pcode 폐기 — kbo_player_id 단일 자연키로 통합`

### Task 4: [BE] 마이그레이션 SQL + domain.md 갱신

**Files:**
- Create: `VictoryFairy_BE/infra/sql/migrate-position-records.sql`
- Modify: `VictoryFairy_BE/.claude/modules/domain.md`

**Interfaces:**
- Produces: prod/로컬 DB에 1회 실행할 마이그레이션 (Task 12에서 실행). 실행 전제: batter/pitcher_records·bat/pitch_results **행 0건** 확인(소비처 없음) + players pcode 단일화 검증 2건 0건.

- [ ] **Step 1: 마이그레이션 SQL 작성** (`chat-init.sql` 선례처럼 주석에 실행 조건 명시):

```sql
-- game_lineups.position 텍스트 → positions FK 전환 + record 계열 집계형 재구축
-- + players.naver_pcode 단일화(kbo_player_id 로 통합). MySQL 8.0.
-- 실행 전 확인: SELECT COUNT(*) FROM batter_records; 가 0이어야 한다 (0이 아니면 중단하고 상의).
-- 실행 후: user 앱 ddl-auto=update 는 이미 맞는 스키마를 발견하므로 추가 DDL 없음.

CREATE TABLE IF NOT EXISTS positions (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  name VARCHAR(100) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL
);

INSERT INTO positions (name, created_at, updated_at)
SELECT DISTINCT gl.position, NOW(6), NOW(6)
FROM game_lineups gl
WHERE gl.position IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM positions p WHERE p.name = gl.position);

ALTER TABLE game_lineups
  ADD COLUMN position_id BIGINT NULL,
  ADD CONSTRAINT fk_game_lineups_position FOREIGN KEY (position_id) REFERENCES positions(id);

UPDATE game_lineups gl JOIN positions p ON p.name = gl.position
SET gl.position_id = p.id;

ALTER TABLE game_lineups DROP COLUMN position;

-- record 계열: 빈 테이블 전제 재구축 (FK 역순 제거)
DROP TABLE IF EXISTS batter_records;
DROP TABLE IF EXISTS pitcher_records;
DROP TABLE IF EXISTS bat_results;
DROP TABLE IF EXISTS pitch_results;

CREATE TABLE batter_records (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  player_id BIGINT NOT NULL,
  game_id BIGINT NOT NULL,
  at_bats INT NULL, runs INT NULL, hits INT NULL, home_runs INT NULL,
  rbi INT NULL, walks INT NULL, strikeouts INT NULL, stolen_bases INT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  UNIQUE KEY uk_batter_records_game_player (game_id, player_id),
  CONSTRAINT fk_batter_records_player FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE,
  CONSTRAINT fk_batter_records_game   FOREIGN KEY (game_id)   REFERENCES games(id)   ON DELETE CASCADE
);

CREATE TABLE pitcher_records (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  player_id BIGINT NOT NULL,
  game_id BIGINT NOT NULL,
  seq INT NOT NULL,
  ip_display VARCHAR(8) NULL, ip_outs INT NULL,
  batters_faced INT NULL, at_bats INT NULL, hits INT NULL, runs INT NULL,
  earned_runs INT NULL, home_runs INT NULL, walks_hbp INT NULL, strikeouts INT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  UNIQUE KEY uk_pitcher_records_game_player (game_id, player_id),
  CONSTRAINT fk_pitcher_records_player FOREIGN KEY (player_id) REFERENCES players(id) ON DELETE CASCADE,
  CONSTRAINT fk_pitcher_records_game   FOREIGN KEY (game_id)   REFERENCES games(id)   ON DELETE CASCADE
);

-- players.naver_pcode 단일화: 실측 동치(2026-07 교집합 228명 전수 일치) 근거로 kbo_player_id 통합.
-- 실행 전 확인: 아래 두 검증 쿼리가 모두 0 이어야 한다 (0이 아니면 중단하고 상의).
--   SELECT COUNT(*) FROM players
--     WHERE naver_pcode IS NOT NULL AND kbo_player_id IS NOT NULL
--       AND naver_pcode != kbo_player_id;                        -- 값 충돌
--   SELECT COUNT(*) FROM players p1 JOIN players p2
--     ON p1.naver_pcode = p2.kbo_player_id AND p1.id != p2.id;   -- 같은 인물 중복 행
UPDATE players SET kbo_player_id = naver_pcode
WHERE kbo_player_id IS NULL AND naver_pcode IS NOT NULL;

ALTER TABLE players DROP COLUMN naver_pcode;
```

- [ ] **Step 2: domain.md 갱신** — ① 모듈 목록: `record` 설명을 집계형으로, `BatResult`/`PitchResult` 제거, `game`에 `Position` 추가 ② 엔티티→테이블 표 갱신 ③ FK 관계: `GameLineup → Position`(@OnDelete 없음, 마스터), `BatterRecord/PitcherRecord → Player/Game`(CASCADE 유지) ④ 타임스탬프 정책 문단: BatterRecord/PitcherRecord를 "갱신되는 엔티티"로 이동(사유: 수집기 멱등 upsert) ⑤ "py-collector와 테이블 스키마 충돌 (미해결)" 항목을 해소로 갱신(집계형 재설계로 수렴) ⑥ game_statuses 시드 항목에 "수집기 games_sync가 lookup-or-insert로 자동 생성" 추가 ⑦ Player 관련 서술: naver_pcode 폐기·kbo_player_id 단일 자연키(실측 동치 228명 전수 일치 근거) 반영.

- [ ] **Step 3: 테스트 재실행 + Commit** — `./gradlew :domain:test` PASS 후 `feat(infra): position·record 재구축 마이그레이션 SQL + domain.md 정합`

### Task 5: [AI] 선수 해소 kbo_player_id 단일키 전환

**Files:**
- Modify: `VictoryFairy_AI/py-collector/kbo_collector/db.py`
- Test: `VictoryFairy_AI/py-collector/tests/test_db_sink.py`

**Interfaces:**
- Consumes: Task 3/4의 players 단일키 구조 (`naver_pcode` 컬럼 없음).
- Produces: `resolve_players(refs, team_ids) -> {pcode: players.id}` — **시그니처·반환 형태 불변** (records 잡과 Task 7이 그대로 사용). 내부에서 pcode 값을 kbo_player_id 로 취급.

- [ ] **Step 1: 실패 테스트 작성** — `test_db_sink.py`의 resolve 테스트 2개를 아래로 교체 (기존 4단계 백필 테스트 `test_resolve_players_pcode_hit_name_backfill_and_insert`는 삭제). 임포트 라인의 `PLAYER_SET_PCODE, PLAYER_INSERT_PCODE` → `PLAYER_INSERT`:

```python
def test_resolve_players_kbo_id_hit_and_insert(caplog):
    refs = [PlayerRef("P1", "기존선수", "LG"), PlayerRef("P2", "개명선수", "LG"),
            PlayerRef("P3", "신규선수", "OB")]
    conn = FakeConn(fetch_results=[
        [("P1", 11, "기존선수"), ("P2", 22, "옛이름")],  # 일괄 조회 (P3 미존재)
    ])
    with caplog.at_level("WARNING", logger="db"):
        out = DbSink(None, connection=conn).resolve_players(refs, {"LG": 2, "OB": 1})
    # P3 는 유일한 INSERT — FakeCursor last_id 100 -> 101
    assert out == {"P1": 11, "P2": 22, "P3": 101}
    sqls = [s for _, s, _ in conn.log]
    assert PLAYER_INSERT in sqls
    # 이름 불일치는 pcode==kbo_player_id 동치 전제 훼손 신호 — warning 1건
    assert "name mismatch" in caplog.text and "P2" in caplog.text


def test_resolve_players_skips_unknown_team():
    refs = [PlayerRef("P9", "올스타", "DR")]
    conn = FakeConn(fetch_results=[[]])
    out = DbSink(None, connection=conn).resolve_players(refs, {"LG": 2})
    assert out == {}
```

- [ ] **Step 2: 구현** — `db.py`에서 SQL 상수 5개(`PLAYER_BY_PCODE`, `PLAYER_BY_KBO_ID_EQ`, `PLAYER_BY_NAME_TEAM`, `PLAYER_SET_PCODE`, `PLAYER_INSERT_PCODE`)를 삭제하고 아래로 교체:

```python
# 실측상 네이버 pcode == KBO playerId (2026-07 박스스코어·로스터 교집합 228명 전수
# 일치) — 이 동치를 스키마 전제로 승격해 kbo_player_id 단일 자연키로 해소한다.
# 전제가 깨지면 이름 불일치 warning 이 급증하므로 그때 재설계한다.
PLAYER_BY_KBO_ID = (
    "SELECT kbo_player_id, id, name FROM players WHERE kbo_player_id IN ({ph})"
)
PLAYER_INSERT = (
    "INSERT INTO players (kbo_player_id, name, team_id, average, created_at, updated_at) "
    "VALUES (%s, %s, %s, 0, NOW(6), NOW(6))"
)
```

`resolve_players` 재작성 (시그니처 불변):

```python
    def resolve_players(self, refs, team_ids) -> dict:
        """PlayerRef 목록 -> {pcode: players.id}. pcode == kbo_player_id 전제.

        1) kbo_player_id 로 일괄 조회 — DB 이름과 API 이름이 다르면 동치 전제
           훼손 신호라 warning. 2) 없으면 신규 행 INSERT (이후 로스터 잡이 같은
           키로 upsert 하므로 자연 병합). 팀 코드가 미지(비표준 팀)면 스킵.
        """
        uniq = {r.pcode: r for r in refs}
        if not uniq:
            return {}
        codes = list(uniq)
        ph = ",".join(["%s"] * len(codes))
        out = {}
        for kbo_id, pk, db_name in self.fetch_all(PLAYER_BY_KBO_ID.format(ph=ph), codes):
            out[kbo_id] = pk
            if db_name != uniq[kbo_id].name:
                log.warning("resolve_players: name mismatch pcode=%s db=%s api=%s",
                            kbo_id, db_name, uniq[kbo_id].name)
        with self._conn.cursor() as cur:
            for pcode, ref in uniq.items():
                if pcode in out:
                    continue
                team_id = team_ids.get(ref.team_code)
                if team_id is None:
                    log.warning("resolve_players: unknown team %s (pcode=%s %s)",
                                ref.team_code, pcode, ref.name)
                    continue
                cur.execute(PLAYER_INSERT, (pcode, ref.name, team_id))
                out[pcode] = cur.lastrowid
        self._conn.commit()
        return out
```

모듈 docstring의 자연키 열거 `players.kbo_player_id/naver_pcode` → `players.kbo_player_id`, "박스스코어 선수 해소" 주석 블록(4단계 서술)도 새 로직에 맞게 축약.

- [ ] **Step 3: 테스트** — `pytest -q` 전체 PASS.
- [ ] **Step 4: Commit** — `feat(py-collector): 선수 해소를 kbo_player_id 단일키로 단순화 — naver_pcode 폐기`

### Task 6: [AI] 수집기 position FK 적재 전환

**Files:**
- Modify: `VictoryFairy_AI/py-collector/kbo_collector/db.py` (LINEUP_UPSERT, position lookup)
- Test: `VictoryFairy_AI/py-collector/tests/test_db_sink.py` (기존 라인업 테스트 갱신)

**Interfaces:**
- Consumes: Task 1의 `positions`(name)·`game_lineups.position_id` 구조.
- Produces: `DbSink.upsert_lineups()` 시그니처 불변 (내부에서 position명→id 해소) — run.py 수정 불필요.

- [ ] **Step 1: 실패 테스트 작성** — 가짜 커서로 `upsert_lineups` 호출 시 `position_id`가 lookup-or-insert 결과로 바인딩되는지, `position=None`이면 NULL인지 검증 (기존 test_db_sink.py 패턴 준수).
- [ ] **Step 2: 구현** — `db.py`에 추가:

```python
POSITION_SELECT = "SELECT id FROM positions WHERE name=%s"
POSITION_INSERT = (
    "INSERT INTO positions (name, created_at, updated_at) VALUES (%s, NOW(6), NOW(6))"
)
```

`LINEUP_UPSERT`의 `position` → `position_id` (INSERT 컬럼·ON DUP 양쪽). `DbSink`에 status/stadium과 같은 패턴으로:

```python
    def position_id(self, name):
        if not name:
            return None
        return self._lookup_or_insert(POSITION_SELECT, POSITION_INSERT, name)
```

`upsert_lineups` 내부에서 행별 `r.position` → `self.position_id(r.position)` (호출당 memo dict로 중복 lookup 방지 — 포지션은 ~10종).

- [ ] **Step 3: 테스트** — `pytest -q` 전체 PASS.
- [ ] **Step 4: Commit** — `feat(py-collector): 라인업 포지션을 positions FK로 적재`

### Task 7: [AI] batter/pitcher 경기 기록 적재

**Files:**
- Modify: `VictoryFairy_AI/py-collector/kbo_collector/db.py` (BATTER_UPSERT/PITCHER_UPSERT + 메서드 2개)
- Modify: `VictoryFairy_AI/py-collector/kbo_collector/run.py` (records 잡에서 호출 — `upsert_lineups` 다음 줄)
- Test: `tests/test_db_sink.py`, `tests/test_run_jobs.py`(records 잡 테스트가 있는 파일)

**Interfaces:**
- Consumes: `GameRow.batting: list[BattingRow]`, `GameRow.pitching: list[PitchingRow]` (이미 파싱됨 — game_records.py 수정 불필요), Task 2/4의 테이블 구조.
- Produces: `DbSink.upsert_batting(game_pk, rows, player_map)`, `DbSink.upsert_pitching(game_pk, rows, player_map)`.

- [ ] **Step 1: 실패 테스트** — records 잡 실행 시 batter/pitcher upsert가 호출되고, player_map에 없는 pcode 행은 스킵되는지.
- [ ] **Step 2: 구현** —

```python
BATTER_UPSERT = (
    "INSERT INTO batter_records (game_id, player_id, at_bats, runs, hits, home_runs, "
    " rbi, walks, strikeouts, stolen_bases, created_at, updated_at) "
    "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,NOW(6),NOW(6)) "
    "ON DUPLICATE KEY UPDATE at_bats=VALUES(at_bats), runs=VALUES(runs), "
    "  hits=VALUES(hits), home_runs=VALUES(home_runs), rbi=VALUES(rbi), "
    "  walks=VALUES(walks), strikeouts=VALUES(strikeouts), "
    "  stolen_bases=VALUES(stolen_bases), updated_at=NOW(6)"
)
PITCHER_UPSERT = (
    "INSERT INTO pitcher_records (game_id, player_id, seq, ip_display, ip_outs, "
    " batters_faced, at_bats, hits, runs, earned_runs, home_runs, walks_hbp, "
    " strikeouts, created_at, updated_at) "
    "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,NOW(6),NOW(6)) "
    "ON DUPLICATE KEY UPDATE seq=VALUES(seq), ip_display=VALUES(ip_display), "
    "  ip_outs=VALUES(ip_outs), batters_faced=VALUES(batters_faced), "
    "  at_bats=VALUES(at_bats), hits=VALUES(hits), runs=VALUES(runs), "
    "  earned_runs=VALUES(earned_runs), home_runs=VALUES(home_runs), "
    "  walks_hbp=VALUES(walks_hbp), strikeouts=VALUES(strikeouts), updated_at=NOW(6)"
)
```

메서드는 `upsert_lineups`와 같은 골격 (`player_map`에 없는 pcode·미지 팀 스킵). run.py records 잡에서 `db.upsert_lineups(...)` 바로 다음에 `db.upsert_batting(game_pk, game.batting, player_map)` / `db.upsert_pitching(game_pk, game.pitching, player_map)` 호출.

- [ ] **Step 3: 테스트** — `pytest -q` PASS.
- [ ] **Step 4: Commit** — `feat(py-collector): records 잡에 batter/pitcher 경기 기록 집계 적재 복원`

### Task 8: [AI] 상태 매핑 + games_sync 잡

**Files:**
- Modify: `VictoryFairy_AI/py-collector/kbo_collector/game_records.py` (`list_kbo_games`, `map_status` 추가)
- Modify: `VictoryFairy_AI/py-collector/kbo_collector/db.py` (`GAME_SYNC_UPSERT` + `sync_game`)
- Modify: `VictoryFairy_AI/py-collector/kbo_collector/run.py` (`job_games_sync` + CLI `games_sync` 서브커맨드)
- Test: `tests/test_game_records.py`, `tests/test_db_sink.py`

**Interfaces:**
- Produces: `map_status(g: dict) -> str | None` (5종 상태명 또는 None=skip), `job_games_sync(settings, db, date)` — Task 9 핸들러가 호출.

- [ ] **Step 1: 실패 테스트 (map_status)** — 실측 케이스 고정:

```python
def test_map_status_cancelled_game_wins_over_winner_field():
    # 2026-07-08 NCHH 실측: 취소 경기는 BEFORE + cancel:true + winner:"DRAW"로 온다.
    g = {"statusCode": "BEFORE", "cancel": True, "winner": "DRAW",
         "homeTeamScore": 0, "awayTeamScore": 0}
    assert game_records.map_status(g) == "CANCELED"

def test_map_status_result_draw_by_scores():
    g = {"statusCode": "RESULT", "cancel": False,
         "homeTeamScore": 5, "awayTeamScore": 5}
    assert game_records.map_status(g) == "DRAW"

def test_map_status_unknown_returns_none():
    assert game_records.map_status({"statusCode": "READY", "cancel": False}) is None
```

추가: BEFORE→SCHEDULED, LIVE→IN_PROGRESS, RESULT 비동점→FINISHED 각 1케이스.

- [ ] **Step 2: 구현 (game_records.py)** —

```python
def list_kbo_games(schedule_json: dict) -> list[dict]:
    """일자별 스케줄 JSON -> 상태 무관 KBO 정규 팀 경기 전부 (games_sync용).

    list_finished_games 와 달리 취소·예정·진행 경기를 포함한다.
    """
    games = (schedule_json.get("result") or {}).get("games") or []
    return [g for g in games
            if g.get("categoryId") == "kbo"
            and g.get("awayTeamCode") in TEAM_CODES
            and g.get("homeTeamCode") in TEAM_CODES]


def map_status(g: dict) -> str | None:
    """schedule 경기 1건 -> game_statuses.name (미지 상태는 None).

    취소는 cancel 플래그 최우선 — 취소 경기는 statusCode "BEFORE" + winner
    "DRAW" 껍데기로 오므로(2026-07-08 NCHH 실측) 다른 필드로 판정하면 오답.
    """
    if g.get("cancel"):
        return "CANCELED"
    sc = g.get("statusCode")
    if sc == "BEFORE":
        return "SCHEDULED"
    if sc == "LIVE":
        return "IN_PROGRESS"
    if sc == "RESULT":
        draw = g.get("homeTeamScore") == g.get("awayTeamScore")
        return "DRAW" if draw else "FINISHED"
    return None
```

- [ ] **Step 3: 구현 (db.py)** — 기존 데이터 저하 금지 계약을 SQL로:

```python
# games_sync 전용: 점수는 제공될 때만 갱신(COALESCE), stadium_id 는 records 잡
# 소유라 건드리지 않는다(INSERT 시 NULL, UPDATE 목록에서 제외).
GAME_SYNC_UPSERT = (
    "INSERT INTO games (naver_game_id, game_date, home_team_id, away_team_id, "
    " stadium_id, home_score, away_score, game_status_id, created_at, updated_at) "
    "VALUES (%s, %s, %s, %s, NULL, %s, %s, %s, NOW(6), NOW(6)) "
    "ON DUPLICATE KEY UPDATE game_date=VALUES(game_date), "
    "  home_score=COALESCE(VALUES(home_score), home_score), "
    "  away_score=COALESCE(VALUES(away_score), away_score), "
    "  game_status_id=VALUES(game_status_id), updated_at=NOW(6)"
)
```

`sync_game(self, *, naver_game_id, game_dt, home_team_id, away_team_id, home_score, away_score, status_id)` 메서드 추가 (커밋 포함, `upsert_game` 골격).

- [ ] **Step 4: 구현 (run.py)** —

```python
def job_games_sync(settings, db, date):
    """당일 KBO 경기 전부의 상태를 games 테이블에 동기화 (취소·예정 포함).

    점수는 LIVE/RESULT 에서만 채운다 — SCHEDULED/CANCELED 의 0-0 은 껍데기라
    NULL 로 적재해야 미시작 경기가 0:0 무승부처럼 보이지 않는다.
    """
    log = logging.getLogger("games_sync")
    with fetch.make_client(settings) as client:
        resp = fetch.fetch(client, game_records.schedule_url(settings, date),
                           settings=settings, referer=settings.naver_referer)
    games = game_records.list_kbo_games(resp.json())
    team_ids = db.upsert_teams(dimensions.TEAMS)
    synced, skipped = 0, 0
    for g in games:
        status = game_records.map_status(g)
        if status is None:
            log.warning("unknown status %s: %s", g.get("gameId"), g.get("statusCode"))
            skipped += 1
            continue
        live_or_done = status in ("IN_PROGRESS", "FINISHED", "DRAW")
        dt = (g.get("gameDateTime") or "").replace("T", " ") or f"{date} 00:00:00"
        db.sync_game(
            naver_game_id=g["gameId"], game_dt=dt,
            home_team_id=team_ids[g["homeTeamCode"]],
            away_team_id=team_ids[g["awayTeamCode"]],
            home_score=g.get("homeTeamScore") if live_or_done else None,
            away_score=g.get("awayTeamScore") if live_or_done else None,
            status_id=db.status_id(status))
        synced += 1
    log.info("%s: synced=%d skipped=%d", date, synced, skipped)
    return synced
```

CLI: argparse 잡 목록에 `games_sync` 추가 (`--date` 기본값은 기존 records 잡과 동일 규약), DB 필요 잡으로 배선 (records와 같은 경로 — 이 브랜치의 DbSink 지연 생성 구조 유지).

- [ ] **Step 5: 테스트** — `pytest -q` PASS (sync_game의 COALESCE 계약은 SQL 문자열 단언 + FakeDb 호출 인자 검증).
- [ ] **Step 6: Commit** — `feat(py-collector): games_sync 잡 — 취소·예정·진행 경기 상태 동기화`

### Task 9: [AI] Lambda 핸들러 + 스케줄 문서

**Files:**
- Modify: `VictoryFairy_AI/py-collector/deploy/lambda/handler.py` (`{"job": "games_sync"}` 추가 — DB 잡이므로 -db 함수 소관)
- Modify: `VictoryFairy_AI/py-collector/deploy/lambda/README.md` (또는 기존 잡 표가 있는 문서) — 스케줄 제안 기록
- Test: `tests/test_lambda_handler.py`

**Interfaces:**
- Consumes: Task 8의 `job_games_sync`. 날짜 앵커는 이 브랜치의 game_schedule 잡과 동일하게 **KST** (`TZ=Asia/Seoul` 기준 오늘).

- [ ] **Step 1: 실패 테스트** — `{"job": "games_sync"}` 이벤트가 `job_games_sync`를 KST 오늘 날짜로 호출하는지 (기존 game_schedule 잡 테스트 패턴).
- [ ] **Step 2: 구현** — handler 잡 분기 추가. 문서에 EventBridge 스케줄 제안 기록 (테라폼 적용은 infra 보류 상태이므로 **문서만**):
  - 아침 동기화: `cron(0 23 * * ? *)` = 08:00 KST — 당일 SCHEDULED 선반영
  - 경기 시간대: `cron(0/10 8-14 * * ? *)` = 17:00~23:50 KST 10분 간격 — LIVE/종료/취소 반영
- [ ] **Step 3: 테스트 + Commit** — `feat(py-collector): games_sync Lambda 잡 + 스케줄 제안 문서`

### Task 10: [AI] exporter 상태 필터 (통계 오염 방지)

**Files:**
- Modify: `VictoryFairy_AI/py-collector/kbo_collector/exports/exporter.py` (game_result SQL)
- Test: `VictoryFairy_AI/py-collector/tests/test_exporter.py`

**Interfaces:**
- 배경: games_sync 도입으로 games 테이블에 SCHEDULED/IN_PROGRESS/CANCELED 행이 생긴다. 현재 game_result 쿼리는 상태 무관이라 미완료 경기가 envelope로 나가 시즌 통계(aggregate_stats)를 오염시킨다.

- [ ] **Step 1: 실패 테스트** —

```python
def test_game_results_sql_filters_finished_and_draw_only():
    # games_sync 가 SCHEDULED/CANCELED 행을 넣기 시작하므로, 완료 경기만
    # envelope 로 나가야 시즌 통계가 오염되지 않는다.
    assert "gs.name IN ('FINISHED','DRAW')" in exporter.GAME_RESULTS_SQL
```

(SQL 상수명은 실제 파일의 이름을 따른다 — 상수가 아니면 상수로 추출.)

- [ ] **Step 2: 구현** — WHERE 절에 `AND gs.name IN ('FINISHED','DRAW')` 추가.
- [ ] **Step 3: 테스트 + Commit** — `fix(py-collector): game_result export를 완료 경기(FINISHED/DRAW)로 한정`

### Task 11: [AI] 네이버 API 문서 보강

**Files:**
- Modify: `VictoryFairy_AI/py-collector/docs/data-formats.md`
- Modify: `VictoryFairy_AI/py-collector/CLAUDE.md` (DB 잡 문단에 games_sync 한 줄 + 자연키 열거에서 naver_pcode 제거)
- Modify: `VictoryFairy_AI/py-collector/docs/current-crawl-overview.md` (스키마 표: positions·batter/pitcher_records 반영, players 표기 갱신)
- Modify: `VictoryFairy_AI/py-collector/docs/crawl-flow.md`, `VictoryFairy_AI/py-collector/docs/data-pipeline-requirements.md` (자연키 열거에서 naver_pcode 제거)

- [ ] **Step 1: data-formats.md "경기 상태 판정" 절 보강** — 기존 표를 DB 상태값까지 확장:

| 상태 | 판정 (우선순위 순) | DB game_statuses.name |
|---|---|---|
| 취소 | `cancel == true` — **최우선.** `statusCode`는 `"BEFORE"`, `winner`는 `"DRAW"` 껍데기로 옴 (2026-07-08 `20260708NCHH02026` 실측) | `CANCELED` |
| 예정 | `statusCode == "BEFORE"` | `SCHEDULED` |
| 진행중 | `statusCode == "LIVE"` | `IN_PROGRESS` |
| 완료 | `statusCode == "RESULT"`, 양팀 동점이면 무승부 | `FINISHED` / `DRAW` |

추가 문구: ① **`winner` 필드로 무승부·취소를 판정하지 말 것** (취소 경기도 `winner:"DRAW"`) ② **schedule API 광범위 조회 금지** — `fromDate~toDate` 2개월 조회 실측 시 경기 4건만 반환, 하루 단위 조회가 정석 ③ `suspended`(서스펜디드)는 아직 미반영 — 관측되면 `map_status`가 skip하고 warning 로그 ④ 취소·예정 경기의 0-0 점수는 껍데기이므로 DB에는 NULL로 적재(games_sync).

- [ ] **Step 2: 선수 자연키 단일화 반영** — data-formats.md "선수코드 주의" 문구 교체: 기존 "이 `pcode`는 KBO 공식 `playerId`와 **다른 체계**라 … `naver_pcode`/`kbo_player_id` 두 컬럼으로 각각 매핑" → "이 `pcode`는 실측상 KBO 공식 `playerId`와 **같은 값**(2026-07 교집합 228명 전수 일치)이라 `players.kbo_player_id` 단일 컬럼으로 통합(해소: kbo_player_id 일괄 조회 → 없으면 신규 행. DB 이름과 API 이름이 다르면 동치 전제 훼손 신호로 warning)". CLAUDE.md·crawl-flow.md·data-pipeline-requirements.md의 자연키 열거에서 `naver_pcode` 제거.

- [ ] **Step 3: CLAUDE.md·current-crawl-overview.md 갱신 + Commit** — `docs(py-collector): 경기 상태 판정·API 함정 실측 보강 + 신규 테이블·단일 자연키 반영`

### Task 12: [수동 게이트] prod 마이그레이션 + 2026 백필 실행

> **이 태스크는 코드가 아니라 운영 실행이다. 반드시 사용자 승인 후, 사용자와 함께 진행한다** (prod DDL + SSH 터널 필요). 구현 태스크(1~11) 완료·리뷰 통과 후 별도로 실행.

- [ ] **Step 1: 사전 확인** — SSH 터널 열림(`127.0.0.1:3306` = 원격 DB), `SELECT COUNT(*) FROM batter_records;` == 0, `SHOW CREATE TABLE game_lineups;`로 구 스키마(position 텍스트) 확인, **players pcode 단일화 검증 2건 == 0** (마이그레이션 SQL 주석의 값 충돌·중복 인물 쿼리).
- [ ] **Step 2: 마이그레이션 실행** — `migrate-position-records.sql` 실행 → positions 행 수·game_lineups.position_id NULL 비율 검증 (`position IS NOT NULL이던 행 == position_id IS NOT NULL 행`), `SHOW COLUMNS FROM players LIKE 'naver_pcode';` 0행 확인.
- [ ] **Step 3: 2026 백필** — AI worktree에서 `python -m kbo_collector.run records --from 2026-03-28 --to <오늘>` → batter/pitcher_records 건수 검증 (경기 수 × 평균 출전 인원 규모, 예: `SELECT COUNT(DISTINCT game_id) FROM batter_records;`가 games의 FINISHED/DRAW 수와 일치).
- [ ] **Step 4: games_sync 1회 수동 실행** — `python -m kbo_collector.run games_sync --date <오늘>` → 당일 경기 SCHEDULED 행 + (있다면) 취소 경기 CANCELED 행 확인.
- [ ] **Step 5: Lambda 배포 + EventBridge 등록** — infra 보류 상태 해소 시점에 별도 진행 (Task 9 문서의 cron 2줄).
