# 테스트 컨테이너 + 선수/구단 DB 적재 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 커뮤니티/경기일정/경기내용 크롤을 테스트 컨테이너에서 개별 실행하고, KBO 1군 로스터를 MySQL에 적재(선수 마스터 + 날짜별 등록 스냅샷)한 뒤 로컬 MySQL에 실제 적재 후 `mysqldump`를 산출한다.

**Architecture:** 순수 파이썬 코어(`kbo_collector`)에 (1) `game` job(schedule→result→relay), (2) KBO `Register.aspx` 로스터 크롤러 + PyMySQL DB 싱크 + `teams`/`registrations` job을 추가한다. 실행은 `py-collector/docker-compose.yml`의 `collector` 서비스(실 S3용) + `mysql` 서비스(profile `db`). Spring `domain` 모듈에 읽기용 `Team`/`Player`/`PlayerRegistration` 엔티티를 추가한다.

**Tech Stack:** Python 3.11+, httpx, BeautifulSoup4+lxml, PyMySQL, boto3, pytest, respx, moto. MySQL 8.0. Spring Boot JPA(`domain` 모듈).

## Global Constraints

- Python `requires-python = ">=3.11"`. 신규 런타임 의존성은 **PyMySQL만** 추가(`>=1.1`). 그 외 새 의존성 금지.
- KBO 소스(`www.koreabaseball.com`)는 **ASP.NET WebForms**: GET으로 `__VIEWSTATE`/`__VIEWSTATEGENERATOR`/`__EVENTVALIDATION` 획득 → POST. 요청/응답 인코딩 **EUC-KR**. 팀 전환은 `hfSearchTeam=<code>` + `__EVENTTARGET=ctl00$ctl00$ctl00$cphContents$cphContents$cphContents$btnCalendarSelect`. 날짜는 `hfSearchDate=YYYYMMDD`(대시 없음).
- **1군 전용**. 2군/퓨처스, 방출·은퇴 사유 판별은 범위 밖.
- `players.is_first_team`의 의미는 **"현재 1군 등록 여부"** 일 뿐 — 방출/은퇴를 뜻하지 않는다.
- 크롤 흐름: 날짜 D 한 번 크롤 → `player_registrations`엔 항상 삽입, `players` 마스터의 **현재상태 필드는 `D >= last_registered_on`일 때만** 갱신.
- `is_first_team=false` 전이는 **그날 크롤에 성공한 팀**에 대해, 그리고 **사이트 현재 날짜를 동기화할 때만** 수행(backfill 시 금지).
- Spring은 `ddl-auto: validate` — 엔티티 컬럼/타입이 `schema.sql`과 **정확히 일치**해야 한다.
- 팀 코드 10개: `OB LG SS KT WO HT HH NC LT SK`.

---

## 파일 구조

**신규 (py-collector)**
- `kbo_collector/dimensions.py` — 10팀 시드 상수, `TeamRow`/`PlayerRow` 데이터클래스, 체격/포지션 파싱 헬퍼, `PLAYER_SECTIONS`.
- `kbo_collector/kbo_register.py` — KBO `Register.aspx` 클라이언트(viewstate/팀/날짜/EUC-KR) + `parse_register(html)`.
- `kbo_collector/db.py` — `DbSink`(PyMySQL): `upsert_teams`/`upsert_players`/`insert_registrations`/`mark_not_first_team`.
- `deploy/sql/schema.sql` — teams/players/player_registrations DDL.
- `Dockerfile.run` — 런타임 컨테이너(엔트리 = `python -m kbo_collector.run`).
- `docker-compose.yml` — `collector` + `mysql`(profile `db`) 서비스.
- `tests/test_dimensions.py`, `tests/test_kbo_register.py`, `tests/test_db_sink.py`, `tests/fixtures/kbo/register_lg.html`.

**수정 (py-collector)**
- `kbo_collector/config.py` — `COLLECTOR_DB_*`, `COLLECTOR_KBO_BASE_URL` 추가.
- `kbo_collector/run.py` — `game` job, `land_registrations`, CLI 분기 추가.
- `pyproject.toml` — `PyMySQL>=1.1` 추가.
- `tests/test_run_jobs.py` — `game`/`registrations` job 테스트 추가.

**신규 (Spring domain)**
- `domain/src/main/java/com/skhynix/domain/team/entity/Team.java` + `repository/TeamRepository.java`
- `domain/src/main/java/com/skhynix/domain/player/entity/Player.java` + `repository/PlayerRepository.java`
- `domain/src/main/java/com/skhynix/domain/player/entity/PlayerRegistration.java` + `PlayerRegistrationId.java` + `repository/PlayerRegistrationRepository.java`

---

## Task A1: `game` job (schedule→result→relay, 커뮤니티 제외)

**Files:**
- Modify: `kbo_collector/run.py:160` (CLI choices), `kbo_collector/run.py:172-187` (dispatch)
- Test: `tests/test_run_jobs.py`

**Interfaces:**
- Consumes: 기존 `land_schedule`, `land_results`, `land_relays`.
- Produces: CLI job `game` — schedule→result→relay를 한 실행에서 순차 수행, 커뮤니티 미포함.

- [ ] **Step 1: 실패 테스트 작성** — `tests/test_run_jobs.py` 끝에 추가

```python
def test_main_game_job_runs_schedule_result_relay_not_community(monkeypatch):
    calls = []
    monkeypatch.setattr(run, "land_schedule", lambda *a, **k: (calls.append("schedule") or ["g1"]))
    monkeypatch.setattr(run, "land_results", lambda *a, **k: calls.append("result"))
    monkeypatch.setattr(run, "land_relays", lambda *a, **k: calls.append("relay"))
    monkeypatch.setattr(run, "land_community", lambda *a, **k: calls.append("community"))
    monkeypatch.setattr(run, "S3RawSink", lambda settings: object())
    import kbo_collector.fetch as fetch_mod
    monkeypatch.setattr(fetch_mod, "build_client",
                        lambda settings: __import__("contextlib").nullcontext(object()))
    rc = run.main(["game", "--date", "2026-07-10"])
    assert rc == 0
    assert calls == ["schedule", "result", "relay"]  # no community
```

- [ ] **Step 2: 실패 확인**

Run: `cd VitoryFairy_BE/py-collector && python -m pytest tests/test_run_jobs.py::test_main_game_job_runs_schedule_result_relay_not_community -v`
Expected: FAIL — `argument job: invalid choice: 'game'`

- [ ] **Step 3: 구현** — `run.py`의 CLI choices와 분기 수정

`kbo_collector/run.py:160` 를:
```python
    parser.add_argument("job", choices=["schedule", "result", "relay", "game", "community", "all"])
```
`kbo_collector/run.py:172` 의 조건을:
```python
        if args.job in ("schedule", "result", "relay", "game", "all"):
            j_sched = Journal("schedule", date, run_id, settings.journal_dir)
            game_ids = land_schedule(date, settings=settings, sink=sink, client=client,
                                     journal=j_sched, force=args.force)
            if args.job in ("result", "game", "all"):
                land_results(date, game_ids, settings=settings, sink=sink, client=client,
                             journal=Journal("result", date, run_id, settings.journal_dir),
                             force=args.force)
            if args.job in ("relay", "game", "all"):
                land_relays(date, game_ids, settings=settings, sink=sink, client=client,
                            journal=Journal("relay", date, run_id, settings.journal_dir),
                            force=args.force)
```

- [ ] **Step 4: 통과 확인**

Run: `python -m pytest tests/test_run_jobs.py -v`
Expected: PASS (기존 + 신규 테스트 모두)

- [ ] **Step 5: 커밋**

```bash
git add kbo_collector/run.py tests/test_run_jobs.py
git commit -m "feat(py-collector): add 'game' job (schedule->result->relay, no community)"
```

---

## Task A2: 런타임 컨테이너 + docker-compose

**Files:**
- Create: `Dockerfile.run`, `docker-compose.yml` (둘 다 `py-collector/` 루트)
- Modify: `README.md` (실행 예시 섹션)

**Interfaces:**
- Produces: `docker compose run --rm collector <job> [--date ...]` (실 S3), `docker compose --profile db up -d mysql` (로컬 MySQL).

- [ ] **Step 1: 런타임 Dockerfile 작성** — `py-collector/Dockerfile.run`

```dockerfile
# 로컬/테스트 실행용 런타임 컨테이너 (Lambda 핸들러 이미지와 별개).
# build context = py-collector/
FROM python:3.12-slim

WORKDIR /app
COPY pyproject.toml ./
COPY kbo_collector/ ./kbo_collector/
COPY config/ ./config/
COPY deploy/sql/ ./deploy/sql/
RUN pip install --no-cache-dir .

ENTRYPOINT ["python", "-m", "kbo_collector.run"]
```

- [ ] **Step 2: docker-compose 작성** — `py-collector/docker-compose.yml`

```yaml
# py-collector 전용 (Spring compose와 분리).
#   크롤(실 S3):  docker compose run --rm collector <job> [--date YYYY-MM-DD]
#   로컬 MySQL:   docker compose --profile db up -d mysql
services:
  collector:
    build:
      context: .
      dockerfile: Dockerfile.run
    env_file:
      - .env
    depends_on:
      mysql:
        condition: service_healthy
        required: false
    network_mode: host

  mysql:
    profiles: ["db"]
    image: mysql:8.0
    container_name: collector-mysql
    environment:
      MYSQL_DATABASE: ${COLLECTOR_DB_NAME:-victoryfairy}
      MYSQL_USER: ${COLLECTOR_DB_USER:-vf}
      MYSQL_PASSWORD: ${COLLECTOR_DB_PASSWORD:-vfpass}
      MYSQL_ROOT_PASSWORD: ${COLLECTOR_DB_PASSWORD:-vfpass}
    ports:
      - "3306:3306"
    volumes:
      - collector-mysql-data:/var/lib/mysql
    healthcheck:
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost", "-u", "root", "-p${COLLECTOR_DB_PASSWORD:-vfpass}"]
      interval: 5s
      timeout: 5s
      retries: 20

volumes:
  collector-mysql-data:
```

- [ ] **Step 3: 빌드 검증**

Run: `cd VitoryFairy_BE/py-collector && docker compose build collector`
Expected: 이미지 빌드 성공(`Successfully built` / `naming to ...`).

- [ ] **Step 4: 스모크 실행 (help)**

Run: `docker compose run --rm --no-deps collector --help`
Expected: argparse usage에 `{schedule,result,relay,game,community,all,teams,registrations}` 노출(teams/registrations는 Task B6 이후). 지금은 `game`까지 보이면 OK.

- [ ] **Step 5: README 실행 예시 추가** — `README.md`의 "직접 실행 (CLI)" 아래에 추가

```markdown
## 테스트 컨테이너로 개별 실행
```bash
docker compose run --rm collector community                  # 커뮤니티만
docker compose run --rm collector schedule --date 2026-07-14 # 경기 일정만
docker compose run --rm collector game     --date 2026-07-14 # 경기 내용만(결과+중계)
# 선수/구단 DB 적재(로컬 MySQL):
docker compose --profile db up -d mysql
docker compose run --rm collector teams
docker compose run --rm collector registrations
```
```

- [ ] **Step 6: 커밋**

```bash
git add Dockerfile.run docker-compose.yml README.md
git commit -m "feat(py-collector): runtime container + docker-compose (collector + mysql profile)"
```

---

## Task B1: DB/KBO 설정 + PyMySQL 의존성

**Files:**
- Modify: `kbo_collector/config.py:41-48`, `pyproject.toml:9-17`
- Test: `tests/test_config.py`

**Interfaces:**
- Produces: `Settings.db_host/db_port/db_name/db_user/db_password`, `Settings.kbo_base_url`.

- [ ] **Step 1: 실패 테스트 작성** — `tests/test_config.py` 끝에 추가

```python
def test_settings_have_db_and_kbo_defaults(monkeypatch):
    monkeypatch.setenv("COLLECTOR_S3_BUCKET", "b")
    monkeypatch.setenv("COLLECTOR_PII_SALT", "s")
    from kbo_collector.config import Settings
    st = Settings(_env_file=None)
    assert st.db_host == "127.0.0.1"
    assert st.db_port == 3306
    assert st.db_name == "victoryfairy"
    assert st.kbo_base_url == "https://www.koreabaseball.com"
```

- [ ] **Step 2: 실패 확인**

Run: `python -m pytest tests/test_config.py::test_settings_have_db_and_kbo_defaults -v`
Expected: FAIL — `AttributeError: 'Settings' object has no attribute 'db_host'`

- [ ] **Step 3: 구현** — `config.py`의 `# --- retry / local paths ---` 블록 위에 추가

```python
    # --- MySQL sink (선수/구단 DB 적재) ---
    db_host: str = Field(default="127.0.0.1", validation_alias="COLLECTOR_DB_HOST")
    db_port: int = Field(default=3306, validation_alias="COLLECTOR_DB_PORT")
    db_name: str = Field(default="victoryfairy", validation_alias="COLLECTOR_DB_NAME")
    db_user: str = Field(default="vf", validation_alias="COLLECTOR_DB_USER")
    db_password: str = Field(default="vfpass", validation_alias="COLLECTOR_DB_PASSWORD")

    # --- KBO source (선수 로스터) ---
    kbo_base_url: str = Field(
        default="https://www.koreabaseball.com", validation_alias="COLLECTOR_KBO_BASE_URL"
    )
```

`pyproject.toml`의 `dependencies` 리스트에 추가:
```toml
    "PyMySQL>=1.1",
```

- [ ] **Step 4: 통과 확인 + 설치**

Run: `pip install -e . && python -m pytest tests/test_config.py -v`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add kbo_collector/config.py pyproject.toml tests/test_config.py
git commit -m "feat(py-collector): DB/KBO settings + PyMySQL dependency"
```

---

## Task B2: 스키마 DDL (`schema.sql`)

**Files:**
- Create: `deploy/sql/schema.sql`

**Interfaces:**
- Produces: `teams`, `players`, `player_registrations` 테이블. 이후 Task B5/B7이 이 컬럼/타입에 의존.

- [ ] **Step 1: DDL 작성** — `deploy/sql/schema.sql`

```sql
-- KBO 1군 로스터 적재 스키마 (collector 소유). MySQL 8.0.
CREATE TABLE IF NOT EXISTS teams (
  team_code  VARCHAR(4)  PRIMARY KEY,
  name       VARCHAR(20) NOT NULL,
  full_name  VARCHAR(40),
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS players (
  player_id          VARCHAR(16) PRIMARY KEY,
  name               VARCHAR(30) NOT NULL,
  team_code          VARCHAR(4),
  back_number        VARCHAR(4),
  position           VARCHAR(10),
  throw_bat          VARCHAR(10),
  birth_date         DATE,
  height_cm          SMALLINT,
  weight_kg          SMALLINT,
  is_first_team      BOOLEAN NOT NULL DEFAULT TRUE,
  last_registered_on DATE,
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CONSTRAINT fk_player_team FOREIGN KEY (team_code) REFERENCES teams(team_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS player_registrations (
  snapshot_date DATE        NOT NULL,
  player_id     VARCHAR(16) NOT NULL,
  team_code     VARCHAR(4)  NOT NULL,
  PRIMARY KEY (snapshot_date, player_id),
  CONSTRAINT fk_reg_player FOREIGN KEY (player_id) REFERENCES players(player_id),
  CONSTRAINT fk_reg_team   FOREIGN KEY (team_code) REFERENCES teams(team_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

- [ ] **Step 2: 문법 검증(로컬 MySQL 컨테이너)**

Run:
```bash
cd VitoryFairy_BE/py-collector
docker compose --profile db up -d mysql
sleep 15
docker exec -i collector-mysql sh -c 'exec mysql -uroot -pvfpass victoryfairy' < deploy/sql/schema.sql
docker exec collector-mysql mysql -uroot -pvfpass victoryfairy -e "SHOW TABLES;"
```
Expected: `teams`, `players`, `player_registrations` 3개 테이블 출력, 오류 없음.

- [ ] **Step 3: 커밋**

```bash
git add deploy/sql/schema.sql
git commit -m "feat(py-collector): teams/players/player_registrations schema (1군 roster)"
```

---

## Task B3: `dimensions.py` (팀 시드 · 데이터클래스 · 파서 헬퍼)

**Files:**
- Create: `kbo_collector/dimensions.py`, `tests/test_dimensions.py`

**Interfaces:**
- Produces:
  - `TeamRow(team_code, name, full_name)`, `PlayerRow(player_id, name, back_number, position, throw_bat, birth_date, height_cm, weight_kg)` (dataclasses)
  - `TEAMS: list[TeamRow]` (10팀), `TEAM_CODES: list[str]`
  - `PLAYER_SECTIONS: set[str]` = {"투수","포수","내야수","외야수"}
  - `parse_physique(text) -> tuple[int|None, int|None]`

- [ ] **Step 1: 실패 테스트 작성** — `tests/test_dimensions.py`

```python
from kbo_collector import dimensions as d


def test_ten_teams_with_codes():
    assert len(d.TEAMS) == 10
    assert set(d.TEAM_CODES) == {"OB","LG","SS","KT","WO","HT","HH","NC","LT","SK"}
    lg = next(t for t in d.TEAMS if t.team_code == "LG")
    assert lg.name == "LG" and lg.full_name == "LG 트윈스"


def test_player_sections_excludes_staff():
    assert d.PLAYER_SECTIONS == {"투수", "포수", "내야수", "외야수"}
    assert "감독" not in d.PLAYER_SECTIONS and "코치" not in d.PLAYER_SECTIONS


def test_parse_physique():
    assert d.parse_physique("184cm, 88kg") == (184, 88)
    assert d.parse_physique("178cm, 65kg") == (178, 65)
    assert d.parse_physique("") == (None, None)
    assert d.parse_physique("-") == (None, None)
```

- [ ] **Step 2: 실패 확인**

Run: `python -m pytest tests/test_dimensions.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'kbo_collector.dimensions'`

- [ ] **Step 3: 구현** — `kbo_collector/dimensions.py`

```python
import re
from dataclasses import dataclass


@dataclass(frozen=True)
class TeamRow:
    team_code: str
    name: str
    full_name: str


@dataclass
class PlayerRow:
    player_id: str
    name: str
    back_number: str
    position: str
    throw_bat: str
    birth_date: str | None
    height_cm: int | None
    weight_kg: int | None


TEAMS: list[TeamRow] = [
    TeamRow("OB", "두산", "두산 베어스"),
    TeamRow("LG", "LG", "LG 트윈스"),
    TeamRow("SS", "삼성", "삼성 라이온즈"),
    TeamRow("KT", "KT", "KT 위즈"),
    TeamRow("WO", "키움", "키움 히어로즈"),
    TeamRow("HT", "KIA", "KIA 타이거즈"),
    TeamRow("HH", "한화", "한화 이글스"),
    TeamRow("NC", "NC", "NC 다이노스"),
    TeamRow("LT", "롯데", "롯데 자이언츠"),
    TeamRow("SK", "SSG", "SSG 랜더스"),
]
TEAM_CODES: list[str] = [t.team_code for t in TEAMS]

PLAYER_SECTIONS: set[str] = {"투수", "포수", "내야수", "외야수"}


def parse_physique(text: str) -> tuple[int | None, int | None]:
    """'184cm, 88kg' -> (184, 88). 값 없으면 None."""
    h = re.search(r"(\d+)\s*cm", text or "")
    w = re.search(r"(\d+)\s*kg", text or "")
    return (int(h.group(1)) if h else None, int(w.group(1)) if w else None)
```

- [ ] **Step 4: 통과 확인**

Run: `python -m pytest tests/test_dimensions.py -v`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add kbo_collector/dimensions.py tests/test_dimensions.py
git commit -m "feat(py-collector): team seed + player dataclasses + physique parser"
```

---

## Task B4: `kbo_register.py` (Register.aspx 크롤러 + 파서)

**Files:**
- Create: `kbo_collector/kbo_register.py`, `tests/test_kbo_register.py`, `tests/fixtures/kbo/register_lg.html`

**Interfaces:**
- Consumes: `dimensions.PlayerRow`, `dimensions.PLAYER_SECTIONS`, `dimensions.parse_physique`.
- Produces:
  - `parse_register(html: str) -> list[PlayerRow]` (감독/코치 제외)
  - `fetch_register_html(team_code: str, date_compact: str, *, settings, client) -> str` (date_compact=YYYYMMDD)
  - `current_date(settings, client) -> str` (사이트 기본 등록일, 'YYYY-MM-DD')

- [ ] **Step 1: 파서 fixture 작성** — `tests/fixtures/kbo/register_lg.html`

실제 구조(포지션별 별도 table, 헤더 2번째 `<th>`가 역할, 선수행 = 등번호/`<a playerId=N>`이름/투타/생년월일/체격)를 축약해 재현. 감독·코치 섹션은 제외돼야 함.
```html
<html><body>
<h4>LG 트윈스 선수등록명단</h4>
<table class="tEx"><tr><th>등번호</th><th>감독</th><th>투타유형</th><th>생년월일</th><th>체격</th></tr>
<tr><td>85</td><td><a href="/Record/Retire/Hitter.aspx?playerId=91350">염경엽</a></td><td>우투우타</td><td>1968-03-01</td><td>178cm, 65kg</td></tr>
</table>
<table class="tEx"><tr><th>등번호</th><th>코치</th><th>투타유형</th><th>생년월일</th><th>체격</th></tr>
<tr><td>72</td><td><a href="/Record/Retire/Pitcher.aspx?playerId=60001">김코치</a></td><td>우투우타</td><td>1975-05-05</td><td>180cm, 80kg</td></tr>
</table>
<table class="tEx"><tr><th>등번호</th><th>투수</th><th>투타유형</th><th>생년월일</th><th>체격</th></tr>
<tr><td>1</td><td><a href="/Record/Player/PitcherBasic/Basic.aspx?playerId=60123">손주영</a></td><td>좌투좌타</td><td>1998-05-12</td><td>184cm, 88kg</td></tr>
<tr><td>29</td><td><a href="/Record/Player/PitcherBasic/Basic.aspx?playerId=67890">임찬규</a></td><td>우투우타</td><td>1992-11-20</td><td>180cm, 83kg</td></tr>
</table>
<table class="tEx"><tr><th>등번호</th><th>내야수</th><th>투타유형</th><th>생년월일</th><th>체격</th></tr>
<tr><td>10</td><td><a href="/Record/Player/HitterBasic/Basic.aspx?playerId=79192">오지환</a></td><td>우투우타</td><td>1990-03-12</td><td>185cm, 83kg</td></tr>
</table>
</body></html>
```

- [ ] **Step 2: 파서 실패 테스트 작성** — `tests/test_kbo_register.py`

```python
from pathlib import Path

from kbo_collector import kbo_register

FIX = Path(__file__).parent / "fixtures" / "kbo"


def test_parse_register_extracts_players_excluding_staff():
    html = (FIX / "register_lg.html").read_text(encoding="utf-8")
    players = kbo_register.parse_register(html)
    ids = {p.player_id for p in players}
    # 감독(91350)·코치(60001) 제외, 선수 3명만
    assert ids == {"60123", "67890", "79192"}
    son = next(p for p in players if p.player_id == "60123")
    assert son.name == "손주영"
    assert son.back_number == "1"
    assert son.position == "투수"
    assert son.throw_bat == "좌투좌타"
    assert son.birth_date == "1998-05-12"
    assert son.height_cm == 184 and son.weight_kg == 88
    oh = next(p for p in players if p.player_id == "79192")
    assert oh.position == "내야수"
```

- [ ] **Step 3: 실패 확인**

Run: `python -m pytest tests/test_kbo_register.py -v`
Expected: FAIL — `AttributeError: module 'kbo_collector.kbo_register' has no attribute 'parse_register'` (또는 ModuleNotFoundError)

- [ ] **Step 4: 구현** — `kbo_collector/kbo_register.py`

```python
import re
import urllib.parse

import httpx
from bs4 import BeautifulSoup

from .dimensions import PLAYER_SECTIONS, PlayerRow, parse_physique

_CTL = "ctl00$ctl00$ctl00$cphContents$cphContents$cphContents$"
_UA = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
)


def parse_register(html: str) -> list[PlayerRow]:
    """Register.aspx 응답 → 1군 선수 목록. 감독/코치 섹션 제외."""
    soup = BeautifulSoup(html, "lxml")
    players: list[PlayerRow] = []
    for table in soup.select("table"):
        ths = table.select("tr th")
        if len(ths) < 2:
            continue
        section = ths[1].get_text(strip=True)  # 2번째 th = 역할/포지션
        if section not in PLAYER_SECTIONS:
            continue
        for tr in table.select("tr"):
            tds = tr.find_all("td")
            if len(tds) < 5:
                continue
            a = tds[1].find("a")
            if a is None:
                continue
            m = re.search(r"playerId=(\d+)", a.get("href", ""))
            if not m:
                continue
            h, w = parse_physique(tds[4].get_text(strip=True))
            birth = tds[3].get_text(strip=True) or None
            players.append(PlayerRow(
                player_id=m.group(1),
                name=a.get_text(strip=True),
                back_number=tds[0].get_text(strip=True),
                position=section,
                throw_bat=tds[2].get_text(strip=True),
                birth_date=birth,
                height_cm=h,
                weight_kg=w,
            ))
    return players


def _hidden(html: str, name: str) -> str:
    m = re.search(r'name="' + re.escape(name) + r'"[^>]*value="([^"]*)"', html)
    return m.group(1) if m else ""


def _headers(base: str) -> dict:
    return {"User-Agent": _UA, "Referer": f"{base}/Player/Register.aspx",
            "Content-Type": "application/x-www-form-urlencoded"}


def current_date(settings, client) -> str:
    """사이트 기본(=현재) 등록일. 'YYYY-MM-DD'."""
    url = f"{settings.kbo_base_url}/Player/Register.aspx"
    html = client.get(url, headers={"User-Agent": _UA}).text
    d = _hidden(html, _CTL + "hfSearchDate") or ""
    return f"{d[0:4]}-{d[4:6]}-{d[6:8]}" if len(d) == 8 else d


def fetch_register_html(team_code: str, date_compact: str, *, settings, client) -> str:
    """팀+날짜(YYYYMMDD)의 1군 등록명단 HTML. EUC-KR POST."""
    url = f"{settings.kbo_base_url}/Player/Register.aspx"
    seed = client.get(url, headers={"User-Agent": _UA}).text
    form = {
        "__EVENTTARGET": _CTL + "btnCalendarSelect",
        "__EVENTARGUMENT": "",
        "__VIEWSTATE": _hidden(seed, "__VIEWSTATE"),
        "__VIEWSTATEGENERATOR": _hidden(seed, "__VIEWSTATEGENERATOR"),
        "__EVENTVALIDATION": _hidden(seed, "__EVENTVALIDATION"),
        _CTL + "hfSearchTeam": team_code,
        _CTL + "hfSearchDate": date_compact,
    }
    body = urllib.parse.urlencode(form, encoding="euc-kr")
    resp = client.post(url, content=body.encode("euc-kr"), headers=_headers(settings.kbo_base_url))
    resp.encoding = "euc-kr"
    return resp.text
```

- [ ] **Step 5: 통과 확인**

Run: `python -m pytest tests/test_kbo_register.py -v`
Expected: PASS

- [ ] **Step 6: 라이브 스모크 (수동, 네트워크 필요)**

Run:
```bash
python -c "
import httpx
from kbo_collector.config import Settings
from kbo_collector import kbo_register as k
st = Settings(_env_file=None, COLLECTOR_S3_BUCKET='x', COLLECTOR_PII_SALT='x')
with httpx.Client(timeout=15, follow_redirects=True) as c:
    d = k.current_date(st, c); print('current_date', d)
    html = k.fetch_register_html('LG', d.replace('-',''), settings=st, client=c)
    ps = k.parse_register(html); print('LG players', len(ps), ps[0] if ps else None)
"
```
Expected: `current_date`가 `YYYY-MM-DD`, LG 선수 수 20+ 출력. (사이트 점검/네트워크 실패 시 이 스텝만 스킵)

- [ ] **Step 7: 커밋**

```bash
git add kbo_collector/kbo_register.py tests/test_kbo_register.py tests/fixtures/kbo/register_lg.html
git commit -m "feat(py-collector): KBO Register.aspx roster crawler + parser"
```

---

## Task B5: `db.py` (PyMySQL DbSink)

**Files:**
- Create: `kbo_collector/db.py`, `tests/test_db_sink.py`

**Interfaces:**
- Consumes: `dimensions.TeamRow`, `dimensions.PlayerRow`.
- Produces:
  - `DbSink(settings, connection=None)`
  - `.upsert_teams(teams: list[TeamRow])`
  - `.upsert_players(players: list[PlayerRow], team_code: str, snapshot_date: str)`
  - `.insert_registrations(snapshot_date: str, players: list[PlayerRow], team_code: str)`
  - `.mark_not_first_team(team_code: str, snapshot_date: str)`
  - `.close()`

- [ ] **Step 1: 실패 테스트 작성** — `tests/test_db_sink.py` (가짜 커넥션으로 SQL/파라미터 검증, DB 불필요)

```python
from kbo_collector.db import DbSink, PLAYERS_UPSERT, REG_UPSERT, MARK_NOT_FIRST
from kbo_collector.dimensions import PlayerRow, TeamRow


class FakeCursor:
    def __init__(self, log): self.log = log
    def __enter__(self): return self
    def __exit__(self, *a): return False
    def execute(self, sql, params=None): self.log.append(("execute", sql, params))
    def executemany(self, sql, rows): self.log.append(("executemany", sql, rows))


class FakeConn:
    def __init__(self): self.log = []; self.commits = 0
    def cursor(self): return FakeCursor(self.log)
    def commit(self): self.commits += 1
    def close(self): pass


def _player(pid="60123"):
    return PlayerRow(pid, "손주영", "1", "투수", "좌투좌타", "1998-05-12", 184, 88)


def test_upsert_players_builds_rows_with_snapshot_date():
    conn = FakeConn()
    DbSink(None, connection=conn).upsert_players([_player()], "LG", "2026-07-13")
    kind, sql, rows = conn.log[0]
    assert kind == "executemany" and sql == PLAYERS_UPSERT
    assert rows == [("60123", "손주영", "LG", "1", "투수", "좌투좌타",
                     "1998-05-12", 184, 88, "2026-07-13")]
    assert conn.commits == 1


def test_insert_registrations_rows():
    conn = FakeConn()
    DbSink(None, connection=conn).insert_registrations("2026-07-13", [_player()], "LG")
    kind, sql, rows = conn.log[0]
    assert kind == "executemany" and sql == REG_UPSERT
    assert rows == [("2026-07-13", "60123", "LG")]


def test_mark_not_first_team_params():
    conn = FakeConn()
    DbSink(None, connection=conn).mark_not_first_team("LG", "2026-07-13")
    kind, sql, params = conn.log[0]
    assert kind == "execute" and sql == MARK_NOT_FIRST
    assert params == ("LG", "2026-07-13")


def test_upsert_teams_rows():
    conn = FakeConn()
    DbSink(None, connection=conn).upsert_teams([TeamRow("LG", "LG", "LG 트윈스")])
    kind, sql, rows = conn.log[0]
    assert kind == "executemany"
    assert rows == [("LG", "LG", "LG 트윈스")]


def test_empty_rows_noop():
    conn = FakeConn()
    DbSink(None, connection=conn).upsert_players([], "LG", "2026-07-13")
    assert conn.log == [] and conn.commits == 0
```

- [ ] **Step 2: 실패 확인**

Run: `python -m pytest tests/test_db_sink.py -v`
Expected: FAIL — `ModuleNotFoundError: No module named 'kbo_collector.db'`

- [ ] **Step 3: 구현** — `kbo_collector/db.py`

```python
import pymysql

TEAMS_UPSERT = (
    "INSERT INTO teams (team_code, name, full_name) VALUES (%s, %s, %s) "
    "ON DUPLICATE KEY UPDATE name=VALUES(name), full_name=VALUES(full_name)"
)

# 현재상태 필드는 D >= last_registered_on 일 때만 전진. 신원(name/birth)은 항상.
PLAYERS_UPSERT = (
    "INSERT INTO players "
    "(player_id, name, team_code, back_number, position, throw_bat, "
    " birth_date, height_cm, weight_kg, is_first_team, last_registered_on) "
    "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, TRUE, %s) "
    "ON DUPLICATE KEY UPDATE "
    "  name=VALUES(name), birth_date=VALUES(birth_date), "
    "  team_code   = IF(VALUES(last_registered_on) >= COALESCE(last_registered_on,'0001-01-01'), VALUES(team_code), team_code), "
    "  back_number = IF(VALUES(last_registered_on) >= COALESCE(last_registered_on,'0001-01-01'), VALUES(back_number), back_number), "
    "  position    = IF(VALUES(last_registered_on) >= COALESCE(last_registered_on,'0001-01-01'), VALUES(position), position), "
    "  throw_bat   = IF(VALUES(last_registered_on) >= COALESCE(last_registered_on,'0001-01-01'), VALUES(throw_bat), throw_bat), "
    "  height_cm   = IF(VALUES(last_registered_on) >= COALESCE(last_registered_on,'0001-01-01'), VALUES(height_cm), height_cm), "
    "  weight_kg   = IF(VALUES(last_registered_on) >= COALESCE(last_registered_on,'0001-01-01'), VALUES(weight_kg), weight_kg), "
    "  is_first_team = IF(VALUES(last_registered_on) >= COALESCE(last_registered_on,'0001-01-01'), TRUE, is_first_team), "
    "  last_registered_on = GREATEST(COALESCE(last_registered_on,'0001-01-01'), VALUES(last_registered_on))"
)

REG_UPSERT = (
    "INSERT INTO player_registrations (snapshot_date, player_id, team_code) "
    "VALUES (%s, %s, %s) ON DUPLICATE KEY UPDATE team_code=VALUES(team_code)"
)

# 그날 명단에 없던(=last_registered_on이 D로 갱신되지 않은) 이 팀 선수를 1군에서 내림.
MARK_NOT_FIRST = (
    "UPDATE players SET is_first_team=FALSE "
    "WHERE team_code=%s AND is_first_team=TRUE "
    "AND (last_registered_on IS NULL OR last_registered_on < %s)"
)


class DbSink:
    def __init__(self, settings, connection=None):
        self._conn = connection or pymysql.connect(
            host=settings.db_host, port=settings.db_port, user=settings.db_user,
            password=settings.db_password, database=settings.db_name,
            charset="utf8mb4", autocommit=False,
        )

    def upsert_teams(self, teams) -> None:
        self._many(TEAMS_UPSERT, [(t.team_code, t.name, t.full_name) for t in teams])

    def upsert_players(self, players, team_code, snapshot_date) -> None:
        self._many(PLAYERS_UPSERT, [
            (p.player_id, p.name, team_code, p.back_number, p.position, p.throw_bat,
             p.birth_date, p.height_cm, p.weight_kg, snapshot_date) for p in players])

    def insert_registrations(self, snapshot_date, players, team_code) -> None:
        self._many(REG_UPSERT, [(snapshot_date, p.player_id, team_code) for p in players])

    def mark_not_first_team(self, team_code, snapshot_date) -> None:
        with self._conn.cursor() as cur:
            cur.execute(MARK_NOT_FIRST, (team_code, snapshot_date))
        self._conn.commit()

    def _many(self, sql, rows) -> None:
        if not rows:
            return
        with self._conn.cursor() as cur:
            cur.executemany(sql, rows)
        self._conn.commit()

    def close(self) -> None:
        self._conn.close()
```

- [ ] **Step 4: 통과 확인**

Run: `python -m pytest tests/test_db_sink.py -v`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add kbo_collector/db.py tests/test_db_sink.py
git commit -m "feat(py-collector): PyMySQL DbSink (upsert teams/players, registrations, mark inactive)"
```

---

## Task B6: `teams`/`registrations` job + CLI

**Files:**
- Modify: `kbo_collector/run.py` (import, `land_registrations`, CLI)
- Test: `tests/test_run_jobs.py`

**Interfaces:**
- Consumes: `dimensions`, `kbo_register`, `db.DbSink`.
- Produces:
  - `land_registrations(date, *, settings, db, client, teams=dimensions.TEAM_CODES, fetch_html=kbo_register.fetch_register_html, current=kbo_register.current_date) -> list[str]` (동기화 성공 팀 코드)
  - CLI job `teams`, `registrations`.

- [ ] **Step 1: 실패 테스트 작성** — `tests/test_run_jobs.py` 끝에 추가

```python
class _RecordingDb:
    def __init__(self): self.calls = []
    def upsert_teams(self, teams): self.calls.append(("teams", len(teams)))
    def upsert_players(self, players, code, date): self.calls.append(("players", code, len(players)))
    def insert_registrations(self, date, players, code): self.calls.append(("reg", code, date))
    def mark_not_first_team(self, code, date): self.calls.append(("mark", code, date))


def test_land_registrations_current_date_upserts_and_marks(monkeypatch, settings):
    from kbo_collector import dimensions, kbo_register
    monkeypatch.setattr(kbo_register, "current_date", lambda s, c: "2026-07-13")
    def fake_fetch(code, date_compact, *, settings, client):
        assert date_compact == "20260713"
        return f"<html>{code}</html>"
    monkeypatch.setattr(kbo_register, "fetch_register_html", fake_fetch)
    monkeypatch.setattr(kbo_register, "parse_register",
                        lambda html: [dimensions.PlayerRow("p1", "N", "1", "투수", "우투우타", None, None, None)])
    db = _RecordingDb()
    synced = run.land_registrations(None, settings=settings, db=db, client=object(),
                                    teams=["LG", "OB"])
    assert synced == ["LG", "OB"]
    assert ("players", "LG", 1) in db.calls and ("reg", "OB", "2026-07-13") in db.calls
    # 사이트 현재일이므로 mark 수행
    assert ("mark", "LG", "2026-07-13") in db.calls


def test_land_registrations_backfill_does_not_mark(monkeypatch, settings):
    from kbo_collector import dimensions, kbo_register
    monkeypatch.setattr(kbo_register, "current_date", lambda s, c: "2026-07-13")
    monkeypatch.setattr(kbo_register, "fetch_register_html",
                        lambda code, dc, *, settings, client: "<html></html>")
    monkeypatch.setattr(kbo_register, "parse_register",
                        lambda html: [dimensions.PlayerRow("p1", "N", "1", "투수", "우투우타", None, None, None)])
    db = _RecordingDb()
    run.land_registrations("2026-05-01", settings=settings, db=db, client=object(), teams=["LG"])
    assert not any(c[0] == "mark" for c in db.calls)  # backfill -> no mark


def test_land_registrations_failed_team_skipped_not_marked(monkeypatch, settings):
    from kbo_collector import dimensions, kbo_register
    monkeypatch.setattr(kbo_register, "current_date", lambda s, c: "2026-07-13")
    def fake_fetch(code, dc, *, settings, client):
        if code == "OB":
            raise RuntimeError("kbo down")
        return "<html></html>"
    monkeypatch.setattr(kbo_register, "fetch_register_html", fake_fetch)
    monkeypatch.setattr(kbo_register, "parse_register",
                        lambda html: [dimensions.PlayerRow("p1", "N", "1", "투수", "우투우타", None, None, None)])
    db = _RecordingDb()
    synced = run.land_registrations(None, settings=settings, db=db, client=object(),
                                    teams=["LG", "OB"])
    assert synced == ["LG"]  # OB 실패 -> 제외
    assert not any(c == ("mark", "OB", "2026-07-13") for c in db.calls)
```

- [ ] **Step 2: 실패 확인**

Run: `python -m pytest tests/test_run_jobs.py::test_land_registrations_current_date_upserts_and_marks -v`
Expected: FAIL — `AttributeError: module 'kbo_collector.run' has no attribute 'land_registrations'`

- [ ] **Step 3: 구현** — `run.py` 수정

`run.py` 상단 import에 추가(6번째 줄 `from . import community, fetch, keys, naver` 를 확장):
```python
from . import community, dimensions, fetch, keys, kbo_register, naver
```
`land_community` 아래(약 155번째 줄)에 함수 추가:
```python
def land_registrations(date=None, *, settings, db, client,
                       teams=dimensions.TEAM_CODES,
                       fetch_html=None, current=None) -> list[str]:
    fetch_html = fetch_html or kbo_register.fetch_register_html
    current = current or kbo_register.current_date
    site_current = current(settings, client)          # 'YYYY-MM-DD'
    snapshot_date = date or site_current
    is_current = snapshot_date == site_current
    date_compact = snapshot_date.replace("-", "")

    db.upsert_teams(dimensions.TEAMS)  # FK 충족 위해 팀 시드 먼저
    synced: list[str] = []
    for code in teams:
        try:
            html = fetch_html(code, date_compact, settings=settings, client=client)
            rows = kbo_register.parse_register(html)
        except Exception:
            continue  # 팀 실패: 스킵(inactive 처리 안 함)
        db.upsert_players(rows, code, snapshot_date)
        db.insert_registrations(snapshot_date, rows, code)
        synced.append(code)
    if is_current:
        for code in synced:
            db.mark_not_first_team(code, snapshot_date)
    return synced
```
CLI choices(160번째 줄)를:
```python
    parser.add_argument("job", choices=["schedule", "result", "relay", "game",
                                        "community", "all", "teams", "registrations"])
```
`main()`의 `with fetch.build_client(settings) as client:` 블록 **앞**에 DB 잡 분기를 추가:
```python
    if args.job in ("teams", "registrations"):
        from .db import DbSink
        db = DbSink(settings)
        try:
            with fetch.build_client(settings) as client:
                if args.job == "teams":
                    db.upsert_teams(dimensions.TEAMS)
                else:
                    land_registrations(args.date, settings=settings, db=db, client=client)
        finally:
            db.close()
        return 0
```

- [ ] **Step 4: 통과 확인**

Run: `python -m pytest tests/test_run_jobs.py -v`
Expected: PASS (기존 + 신규 3개)

- [ ] **Step 5: 전체 회귀**

Run: `python -m pytest -q`
Expected: 모든 테스트 PASS (기존 50 + 신규).

- [ ] **Step 6: 커밋**

```bash
git add kbo_collector/run.py tests/test_run_jobs.py
git commit -m "feat(py-collector): teams/registrations jobs (roster -> MySQL)"
```

---

## Task B7: Spring domain 엔티티 (Team/Player/PlayerRegistration)

**Files:**
- Create: `domain/src/main/java/com/skhynix/domain/team/entity/Team.java`, `.../team/repository/TeamRepository.java`
- Create: `.../player/entity/Player.java`, `.../player/entity/PlayerRegistration.java`, `.../player/entity/PlayerRegistrationId.java`, `.../player/repository/PlayerRepository.java`, `.../player/repository/PlayerRegistrationRepository.java`

**Interfaces:**
- Consumes: `schema.sql`의 컬럼/타입(정확히 일치해야 `ddl-auto: validate` 통과).
- Produces: 앱에서 팀/선수/일자별 등록을 읽는 JPA 엔티티·리포지토리.

- [ ] **Step 1: Team 엔티티** — `team/entity/Team.java`

```java
package com.skhynix.domain.team.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "teams")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Team {

    @Id
    @Column(name = "team_code", length = 4)
    private String teamCode;

    @Column(name = "name", length = 20, nullable = false)
    private String name;

    @Column(name = "full_name", length = 40)
    private String fullName;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 2: Player 엔티티** — `player/entity/Player.java`

```java
package com.skhynix.domain.player.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "players")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Player {

    @Id
    @Column(name = "player_id", length = 16)
    private String playerId;

    @Column(name = "name", length = 30, nullable = false)
    private String name;

    @Column(name = "team_code", length = 4)
    private String teamCode;

    @Column(name = "back_number", length = 4)
    private String backNumber;

    @Column(name = "position", length = 10)
    private String position;

    @Column(name = "throw_bat", length = 10)
    private String throwBat;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Column(name = "height_cm")
    private Short heightCm;

    @Column(name = "weight_kg")
    private Short weightKg;

    @Column(name = "is_first_team", nullable = false)
    private Boolean isFirstTeam;

    @Column(name = "last_registered_on")
    private LocalDate lastRegisteredOn;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", insertable = false, updatable = false)
    private LocalDateTime updatedAt;
}
```

- [ ] **Step 3: PlayerRegistration 복합키 + 엔티티**

`player/entity/PlayerRegistrationId.java`:
```java
package com.skhynix.domain.player.entity;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.Objects;

public class PlayerRegistrationId implements Serializable {

    private LocalDate snapshotDate;
    private String playerId;

    public PlayerRegistrationId() {
    }

    public PlayerRegistrationId(LocalDate snapshotDate, String playerId) {
        this.snapshotDate = snapshotDate;
        this.playerId = playerId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof PlayerRegistrationId that)) {
            return false;
        }
        return Objects.equals(snapshotDate, that.snapshotDate)
                && Objects.equals(playerId, that.playerId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(snapshotDate, playerId);
    }
}
```
`player/entity/PlayerRegistration.java`:
```java
package com.skhynix.domain.player.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.time.LocalDate;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "player_registrations")
@IdClass(PlayerRegistrationId.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PlayerRegistration {

    @Id
    @Column(name = "snapshot_date")
    private LocalDate snapshotDate;

    @Id
    @Column(name = "player_id", length = 16)
    private String playerId;

    @Column(name = "team_code", length = 4, nullable = false)
    private String teamCode;
}
```

- [ ] **Step 4: 리포지토리 3개**

`team/repository/TeamRepository.java`:
```java
package com.skhynix.domain.team.repository;

import com.skhynix.domain.team.entity.Team;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamRepository extends JpaRepository<Team, String> {
}
```
`player/repository/PlayerRepository.java`:
```java
package com.skhynix.domain.player.repository;

import com.skhynix.domain.player.entity.Player;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlayerRepository extends JpaRepository<Player, String> {

    List<Player> findByTeamCodeAndIsFirstTeamTrue(String teamCode);
}
```
`player/repository/PlayerRegistrationRepository.java`:
```java
package com.skhynix.domain.player.repository;

import com.skhynix.domain.player.entity.PlayerRegistration;
import com.skhynix.domain.player.entity.PlayerRegistrationId;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlayerRegistrationRepository
        extends JpaRepository<PlayerRegistration, PlayerRegistrationId> {

    List<PlayerRegistration> findBySnapshotDateAndTeamCode(LocalDate snapshotDate, String teamCode);
}
```

- [ ] **Step 5: 컴파일 검증**

Run: `cd VitoryFairy_BE && ./gradlew :domain:compileJava`
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: 커밋**

```bash
cd VitoryFairy_BE
git add domain/src/main/java/com/skhynix/domain/team domain/src/main/java/com/skhynix/domain/player
git commit -m "feat(domain): Team/Player/PlayerRegistration read entities (match roster schema)"
```

---

## Task B8: 로컬 MySQL 적재 + `mysqldump` 산출 (Deliverable)

**Files:**
- Create: `deploy/sql/seed-dump.sql` (산출물, 커밋)

**Interfaces:**
- Consumes: 모든 이전 태스크.
- Produces: 실제 KBO 데이터가 적재된 MySQL 덤프.

- [ ] **Step 1: MySQL 기동 + 스키마 적용**

Run:
```bash
cd VitoryFairy_BE/py-collector
docker compose --profile db up -d mysql
sleep 15
docker exec -i collector-mysql sh -c 'exec mysql -uroot -pvfpass victoryfairy' < deploy/sql/schema.sql
```
Expected: 오류 없음.

- [ ] **Step 2: `.env`에 DB 접속값 확인/추가**

`.env`에 아래가 없으면 추가(로컬 MySQL은 host 네트워크 3306):
```
COLLECTOR_DB_HOST=127.0.0.1
COLLECTOR_DB_PORT=3306
COLLECTOR_DB_NAME=victoryfairy
COLLECTOR_DB_USER=root
COLLECTOR_DB_PASSWORD=vfpass
```

- [ ] **Step 3: teams + registrations 실제 적재 (호스트에서 직접 실행)**

Run:
```bash
python -m kbo_collector.run teams
python -m kbo_collector.run registrations   # 사이트 현재일 기준
```
Expected: 예외 없이 종료.

- [ ] **Step 4: 적재 검증 (날짜 필터 조회 동작 확인)**

Run:
```bash
docker exec collector-mysql mysql -uroot -pvfpass victoryfairy -e "
SELECT COUNT(*) AS teams FROM teams;
SELECT COUNT(*) AS players FROM players;
SELECT COUNT(*) AS regs FROM player_registrations;
SELECT p.name, p.back_number, p.position
FROM player_registrations r JOIN players p USING(player_id)
WHERE r.team_code='LG' AND r.snapshot_date=(SELECT MAX(snapshot_date) FROM player_registrations)
ORDER BY p.position LIMIT 10;"
```
Expected: teams=10, players>200, regs>200, LG 선수 목록 출력.

- [ ] **Step 5: `mysqldump` 산출**

Run:
```bash
docker exec collector-mysql mysqldump -uroot -pvfpass --databases victoryfairy \
  --tables teams players player_registrations > deploy/sql/seed-dump.sql
head -5 deploy/sql/seed-dump.sql && wc -l deploy/sql/seed-dump.sql
```
Expected: `seed-dump.sql` 생성(INSERT문 포함, 수백 줄).

- [ ] **Step 6: 커밋**

```bash
git add deploy/sql/seed-dump.sql
git commit -m "chore(py-collector): seed dump of teams/players/registrations (real KBO data)"
```

---

## Self-Review 결과

**Spec 커버리지**
- Part A 3갈래 개별 실행 → Task A1(`game` job) + A2(compose). ✓
- Part B 스키마(teams/players/player_registrations, `is_first_team`) → B2, 엔티티 B7. ✓
- 소스: KBO Register.aspx(1군), 팀 시드 → B3/B4. ✓
- 크롤 흐름(두 테이블, D≥last_registered_on 규칙, 성공 팀만 mark, backfill 미mark) → B5(PLAYERS_UPSERT/MARK) + B6(land_registrations). ✓
- 로컬 MySQL 적재 + mysqldump → B8. ✓
- 구현 가드(적재 순서 upsert→registrations, 팀별 성공 시에만 mark) → B6. ✓
- 운영(DB잡은 컨테이너/호스트에서, Lambda 제외) → A2/B8 실행 경로. ✓

**Placeholder 스캔:** 없음(모든 코드/명령 구체화).

**타입 일관성:** `PlayerRow`(8필드) 정의(B3) ↔ 사용(B4 파서, B5 upsert 파라미터 순서, B6 테스트) 일치. `DbSink` 메서드 시그니처(B5) ↔ 호출(B6) 일치. `schema.sql` 컬럼(B2) ↔ SQL(B5) ↔ 엔티티(B7) 일치.
