# 테스트 컨테이너 + 선수/구단 DB 적재 설계

> 작성일 2026-07-14 · 대상 `VitoryFairy_BE/py-collector` (+ Spring `domain` 모듈 일부)

두 가지 작업을 한 스펙으로 다룬다.

- **Part A** — 커뮤니티 / 경기 일정 / 경기 내용 크롤을 **테스트 컨테이너에서 따로따로 즉시 실행**.
- **Part B** — **구단·선수(1군 로스터) 정보를 MySQL에 적재**하고, 날짜별 1군 등록현황을 조회 가능하게 함. 로컬 MySQL에 실제 적재 후 `mysqldump` 산출.

---

## 배경 / 현재 상태

- `py-collector`는 현재 **원본 → S3 브론즈 적재**만 한다(schedule/result/relay/community). 실행은 `python -m kbo_collector.run <job>` CLI + prod용 컨테이너-이미지 Lambda.
- Spring BE는 MySQL 8.0, JPA `ddl-auto: validate`(Hibernate가 스키마 자동생성 안 함), 현재 **User 계열 엔티티만** 존재. Team/Player 없음.
- 팀 코드 체계는 네이버 schedule과 KBO 공식이 동일(`LG/OB/HT/SS/SK/WO/HH/NC/LT/KT`).

## 확정된 결정 (브레인스토밍)

| 항목 | 결정 |
|---|---|
| 선수/구단 → DB 경로 | **collector가 MySQL 직접 쓰기** (신규 DB sink) |
| 스키마 소유 | collector가 DDL(`schema.sql`) 소유 + Spring domain에 읽기용 `@Entity` 추가 |
| 동기화 주기 | 수동 + **매일 1회** |
| 테스트 컨테이너 적재 대상 | **실제 AWS S3** (기존 `victoryfairy-crawl-local`) |
| 크롤 관심사 분리 | community / schedule / **game(신규)** 3갈래 |
| 선수 데이터 범위 | **1군 전용** (2군/퓨처스 제외) |
| 방출/은퇴 사유 추적 | **하지 않음** (1군 단일 소스로는 원리적 판별 불가) |
| 선수 소스 | KBO 공식 `Player/Register.aspx` (팀+날짜 → 1군 등록명단) |

---

## Part A — 테스트 컨테이너 (crawl → S3)

### 목표
`docker compose run` 한 번으로 세 크롤을 **독립 실행**.

```bash
docker compose run --rm collector community                 # 커뮤니티만
docker compose run --rm collector schedule --date 2026-07-14 # 경기 일정만
docker compose run --rm collector game     --date 2026-07-14 # 경기 내용만(결과+중계)
```

### 신규 job `game`
현재 CLI job은 `schedule / result / relay / community / all`. "경기 내용"(결과+중계, 커뮤니티 제외)만 도는 단일 job이 없다. **`game` = schedule→result→relay** 를 추가한다(gameId는 메모리 전달, 커뮤니티 미포함). 기존 job은 유지.

| 관심사 | job | 하는 일 | 적재 |
|---|---|---|---|
| 커뮤니티 | `community` | FMKorea·DCInside 새 글 증분 | S3 |
| 경기 일정 | `schedule` | 일정 JSON + gameId 추출 | S3 |
| 경기 내용 | `game`(신규) | 일정→결과→문자중계 | S3 |

### 컨테이너 구성
- `py-collector/docker-compose.yml` **신규** (기존 제약대로 Spring compose와 분리).
- 서비스 `collector`: **런타임용 Dockerfile**(Lambda 핸들러 이미지 아님) — `ENTRYPOINT ["python","-m","kbo_collector.run"]`. `.env` 마운트로 AWS 자격증명 + `COLLECTOR_*` 주입 → 실제 `victoryfairy-crawl-local` 버킷 적재.
- 서비스 `mysql`(profile `db`): Part B 로컬 검증용. 기본 `up`에는 안 뜨고 `--profile db`일 때만.
- 기존 Lambda 배포(`deploy/lambda/`)는 그대로 둔다.

---

## Part B — 선수/구단 → MySQL (1군 전용)

### 소스
- **구단(teams)**: 10팀 고정 시드(코드·약칭·정식명). 외부 소스 의존 없음(정적 상수).
- **선수(players) + 일자별 1군 등록(player_registrations)**: KBO `Player/Register.aspx`.
  - ASP.NET WebForms: GET으로 `__VIEWSTATE`/`__VIEWSTATEGENERATOR`/`__EVENTVALIDATION` 획득 → 팀 전환(`__doPostBack`, `hfSearchTeam`) + 날짜(`hfSearchDate`) POST. 요청/응답 인코딩 **EUC-KR**.
  - 응답의 `{팀} 선수등록명단` = 그 날짜의 **1군 등록 선수**. 포지션은 **섹션 헤더**(투수/포수/내야수/외야수)로 구분. **감독·코치 섹션은 제외**.
  - 검증됨(2026-07-14 라이브): 행당 `playerId, 등번호, 이름, 투타유형, 생년월일, 체격` 추출. 출신교(school)는 이 페이지에 없음 → 스키마에서 제외.

### 스키마 (`deploy/sql/schema.sql`, collector 소유)

```sql
CREATE TABLE teams (
  team_code  VARCHAR(4)  PRIMARY KEY,      -- LG, OB, HT...
  name       VARCHAR(20) NOT NULL,         -- LG, 두산, KIA
  full_name  VARCHAR(40),                  -- LG 트윈스
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);

-- 선수 마스터(신원/신체/포지션). Register.aspx 최신 등장 기준으로 upsert.
CREATE TABLE players (
  player_id         VARCHAR(16) PRIMARY KEY,  -- KBO playerId
  name              VARCHAR(30) NOT NULL,
  team_code         VARCHAR(4),               -- 현재 소속(FK teams). 이적 시 갱신.
  back_number       VARCHAR(4),               -- "00","49"
  position          VARCHAR(10),              -- 투수/포수/내야수/외야수
  throw_bat         VARCHAR(10),              -- 우투우타
  birth_date        DATE,
  height_cm         SMALLINT,
  weight_kg         SMALLINT,
  is_first_team     BOOLEAN NOT NULL DEFAULT TRUE, -- 최신 동기화 기준 '현재 1군 등록 여부'
  last_registered_on DATE,                    -- 마지막으로 1군 등록 확인된 날
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_player_team FOREIGN KEY (team_code) REFERENCES teams(team_code)
);

-- 일자별 1군 등록 스냅샷(시점 진실). 날짜 필터 조회의 근거.
CREATE TABLE player_registrations (
  snapshot_date DATE        NOT NULL,
  player_id     VARCHAR(16) NOT NULL,
  team_code     VARCHAR(4)  NOT NULL,          -- 그날의 소속(이적 이력 보존)
  PRIMARY KEY (snapshot_date, player_id),
  CONSTRAINT fk_reg_player FOREIGN KEY (player_id) REFERENCES players(player_id),
  CONSTRAINT fk_reg_team   FOREIGN KEY (team_code) REFERENCES teams(team_code)
);
```

> `is_first_team`의 의미는 **"현재 1군 등록 여부"** 일 뿐이다. 1군만 크롤하므로 방출·은퇴·2군 강등·부상 말소가 데이터상 동일하게 "1군에서 사라짐"으로 나타난다. 이 컬럼을 "방출/은퇴"로 해석하지 말 것.

### 조회 예시
```sql
-- 2026-07-14 LG 1군 등록명단
SELECT p.name, p.back_number, p.position
FROM player_registrations r JOIN players p USING (player_id)
WHERE r.snapshot_date = '2026-07-14' AND r.team_code = 'LG';
```

### 크롤 흐름 — 한 번의 크롤로 두 테이블 갱신
`registrations` job은 날짜 D에 대해 팀별 Register.aspx를 한 번 긁고, 그 결과로 **두 테이블을 함께** 쓴다.

1. **`player_registrations`**: D의 1군 명단을 `(snapshot_date=D, player_id, team_code)`로 삽입(멱등). 과거·오늘 무관 항상.
2. **`players` 마스터**:
   - **신원 필드**(name, birth_date)는 항상 upsert(안정적).
   - **현재 상태 필드**(team_code, back_number, position, throw_bat, height_cm, weight_kg, is_first_team, last_registered_on)는 **`D >= players.last_registered_on` 일 때만** 갱신 → backfill로 과거를 긁어도 "현재 소속/등번호"를 옛 값으로 덮지 않음. (`last_registered_on = GREATEST(기존, D)`)
   - 마스터 행이 없으면(신규 선수) D 기준으로 INSERT(FK 충족).
3. **오늘 동기화 완료 후**: 오늘 명단에 없는 (그날 크롤 **성공한** 팀의) 선수 → `is_first_team=false`.

### 변경 케이스 처리 (검토 결과)
- **이적/트레이드**: `playerId` 불변 → `players.team_code`는 새 팀으로 upsert, `player_registrations`는 **그날의 team_code를 보존** → 과거·현재 소속 모두 정확. ✅
- **방출/은퇴**: 1군 Register에서 사라짐 → 과거 스냅샷 보존, `is_first_team=false`. **사유는 판별 불가**(설계상 수용). ⚠️
- **복귀/재계약**: `playerId` 동일 → upsert로 `is_first_team=true`·team_code 갱신. ✅

### 신규 컴포넌트 (`kbo_collector/`)
- `kbo_register.py` — Register.aspx 클라이언트(viewstate/팀 postback/날짜/EUC-KR) + 표 파서(`parse_register(html) -> list[PlayerRow]`, 감독·코치 제외, 체격 분해).
- `dimensions.py` — 10팀 시드 상수, `TeamRow`/`PlayerRow` 데이터클래스, `"178cm, 81kg" → (178,81)` 파싱.
- `db.py` — PyMySQL 싱크: `upsert_teams()`, `upsert_players()`(`INSERT … ON DUPLICATE KEY UPDATE`), `insert_registrations()`(멱등, PK 충돌 무시/덮어씀), `mark_not_first_team(team_code, as_of)`.
- `run.py` — job **`teams`**(10팀 정적 시드 upsert) / **`registrations`**(팀별 Register → players 마스터 upsert + 그날 스냅샷). 기본 오늘, `--date`로 backfill.
- `config.py` — `COLLECTOR_DB_HOST/PORT/NAME/USER/PASSWORD`, `COLLECTOR_KBO_BASE_URL`.
- `pyproject.toml` — `PyMySQL` 의존성 추가.
- `deploy/sql/schema.sql` — 위 DDL.
- **Spring `domain` 모듈** — `com.skhynix.domain.team.Team`, `com.skhynix.domain.player.Player` `@Entity` + repository(스키마와 정확히 일치, `ddl-auto: validate` 통과).
- `tests/` — Register 파서(고정 fixture HTML), db upsert, run job 라우팅.

### 구현 가드 (반드시)
1. **적재 순서**: `upsert_players` → `insert_registrations` (FK 위반 방지).
2. **is_first_team 오탐 방지**: 어떤 팀 크롤이 실패한 날 그 팀 선수를 통째로 false로 만들지 말 것 → **팀별 크롤 성공 시에만** 그 팀의 미등장 선수를 `is_first_team=false`, 성공 팀 등장 선수는 `last_registered_on=오늘`.
3. **멱등**: `players` upsert, `player_registrations` PK(date, player) → 재실행 안전.

### 운영
- **teams/registrations(DB 쓰기)는 앱 DB 옆 collector 컨테이너에서** 수동 + 매일 1회 실행. Lambda 제외(스케줄 Lambda는 docker-MySQL 도달 불가).
- community/game 원본→S3는 기존 Lambda 유지.

---

## 이번 작업 산출물 (로컬 검증 + 덤프)

1. `py-collector/docker-compose.yml`의 `mysql`(profile `db`) 기동.
2. `deploy/sql/schema.sql` 적용.
3. `teams` + `registrations` job을 **실제 KBO 데이터**로 실행(오늘자; 필요 시 며칠 backfill) → 로컬 MySQL 적재(10팀 + 각 팀 1군 로스터 + 오늘 스냅샷).
4. `mysqldump` → **`deploy/sql/seed-dump.sql`** 로 추출·커밋.
5. 위 조회 예시로 날짜/팀 필터 동작 확인.

---

## 테스트 전략
- **단위**: Register 파서(고정 HTML fixture로 playerId/포지션 섹션/체격 분해/감독·코치 제외 검증), 체격 파서, 10팀 시드, `game` job 라우팅.
- **DB**: `db.py` upsert/insert를 로컬 MySQL(profile `db`) 통합 테스트로 검증(멱등 2회 실행 → 중복 없음, 미등장 선수 is_first_team 전이).
- **회귀**: 기존 50개 테스트 유지(파이프라인 코어 불변).

## 범위 밖 (YAGNI)
- 2군/퓨처스, 방출/은퇴 사유, 등/말소 트랜잭션 로그, 선수 상세 스탯, 출신교, 네이버 로스터 API.
- DB 쓰기 Lambda 자동화(도달성 문제 — 별도 결정).
