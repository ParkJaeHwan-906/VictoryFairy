# 디렉토리 구조 — 어디를 고치면 무엇이 바뀌나

> `py-collector`의 파일 배치를 **역할 중심**으로 정리한 문서입니다. "이 동작을 바꾸려면 어느 파일을
> 여는가"를 빠르게 찾도록, 각 항목에 한 줄 설명 + 근거 파일을 답니다. 모든 설명은 실제 코드 기준.

수집 코어는 **오케스트레이션 비의존 순수 파이썬**(`kbo_collector/`)이고, 그 코어를 CLI·Lambda·로컬
스크립트가 얇게 호출합니다. 수집물은 성격에 따라 **S3**(경기 원본·커뮤니티 글·question-source
envelope) 또는 **MySQL**(로스터·박스스코어)로 흐릅니다.

```
py-collector/
├── kbo_collector/          # ① 수집 코어 (순수 파이썬)
│   ├── run.py              #    CLI 오케스트레이터 (job 분기 + land_* 함수)
│   ├── config.py           #    Settings (COLLECTOR_* 환경변수 로드)
│   ├── fetch.py            #    httpx + tenacity 재시도 + UA + 429/430 쿨다운
│   ├── sink.py             #    S3RawSink (S3 멱등 PUT / exists / dead-letter)
│   ├── db.py               #    DbSink (MySQL upsert / fetch_all)
│   ├── journal.py          #    JSONL 저널 + 구조화 로깅
│   ├── keys.py             #    S3 키 생성 규칙 (한곳에서 관리)
│   ├── masking.py          #    댓글 작성자 salt 해시 마스킹
│   ├── naver.py            #    네이버 URL 빌더 + gameId 추출 + 빈 relay 판정
│   ├── community.py        #    FMKorea/DCInside HTML 파서 → RawPost
│   ├── game_records.py     #    네이버 record → GameRow/Pitching/Batting 파싱
│   ├── kbo_register.py     #    KBO 공식 Register.aspx (ASP.NET) fetch·파싱
│   ├── dimensions.py       #    10구단 시드 + PlayerRow + 신체 파싱
│   ├── targets.py          #    커뮤니티 대상 YAML 로더
│   ├── stats.py            #    수집 결과 리포트 CLI (성공률·HTTP 코드)
│   ├── sources/            # ② 소스 플러그인 (질문생성 파이프라인 입구)
│   │   ├── base.py         #    REGISTRY + @register + CollectContext/Result
│   │   ├── __init__.py     #    소스 모듈 import = @register 실행 (등록 목록)
│   │   ├── naver_games.py  #    game_result  ← land_game_records_range 위임
│   │   ├── kbo_roster.py   #    player_profile ← land_registrations 위임
│   │   ├── community_posts.py #  community_post ← land_community 위임
│   │   └── meme_dict.py    #    player_meme  ← memes.yaml (collect가 곧 export)
│   └── exports/            # ③ question-source envelope 산출
│       ├── envelope.py     #    Envelope dataclass (12키) + s3_key/safe_id
│       └── exporter.py     #    docType별 reader 레지스트리 + export()
├── config/                 # 설정 데이터 (코드 아님)
│   ├── targets.yaml        #    Lambda 커뮤니티 대상 (DCInside 10갤러리)
│   ├── targets.fmkorea.yaml #   로컬 FMKorea 일일 대상 (popular 정렬)
│   ├── targets.fmkorea.backfill.yaml # 로컬 FMKorea 백필 대상 (date 정렬)
│   └── memes.yaml          #    선수-밈 사전 (사람이 직접 관리)
├── deploy/
│   ├── lambda/             #    서버리스 배포 (handler + Dockerfile + Terraform)
│   ├── local/              #    주거 IP 로컬 실행 스크립트 (FMKorea)
│   └── sql/                #    MySQL 스키마 + 시드 덤프
├── tests/                  # pytest 단위·통합 테스트
├── docs/                   # 문서 (이 파일 포함)
├── notebooks/              # run_crawler.ipynb (수동 실행/스모크)
├── README.md               # 사람용 소개
├── CLAUDE.md               # Claude Code 작업 가이드 (docs 링크 맵)
├── pyproject.toml          # 패키지/의존성/CLI 엔트리포인트
├── Dockerfile.run · docker-compose.yml · .env.example
```

---

## ① `kbo_collector/` — 수집 코어

오케스트레이션(누가 언제 부르는가)과 무관하게 동작하는 순수 로직. CLI(`run.py`)·Lambda
(`deploy/lambda/handler.py`)가 이 함수들을 그대로 호출합니다.

| 파일 | 역할 | 여길 고치면 |
|---|---|---|
| **run.py** | CLI 진입점(`python -m kbo_collector.run <job>`). `argparse`로 job 분기, `land_schedule`/`land_results`/`land_relays`/`land_community`/`land_community_range`/`land_registrations`/`land_game_records[_range]` 함수 보유 | 잡의 실행 순서·옵션(`--date`·`--cap`·`--concurrency`·`--from/--to` 등)·잡 간 배선 |
| **config.py** | `pydantic-settings` 기반 `Settings`. 모든 `COLLECTOR_*`/재시도/경로 환경변수를 한곳에서 정의, `get_settings()`(lru_cache) | 새 설정값 추가, 기본값·환경변수 이름 |
| **fetch.py** | `httpx.Client` 빌드 + `tenacity` 지수백오프. 브라우저 UA(현행 Windows Chrome), 429/430은 긴 쿨다운, 최종 실패 시 `FetchError` | HTTP 동작·UA·재시도/쿨다운 정책. **FMKorea가 다시 430이면 여기 UA 버전을 올림** |
| **sink.py** | `S3RawSink` — `exists()` 체크포인트, `put`/`put_json`(멱등), `dead_letter`, `iter_keys`/`get_json`(export reader용). boto3 | S3 적재 방식, dead-letter/메타데이터 형식 |
| **db.py** | `DbSink` — PyMySQL. `upsert_teams`/`upsert_players`/`insert_registrations`/`upsert_game*`/`link_kbo_player_ids`/`fetch_all`. upsert SQL 문자열 상수 소유 | MySQL 적재 SQL·멱등 규칙(현재상태 전진 조건 등) |
| **keys.py** | S3 키 문자열을 **한곳에서** 생성(`schedule_key`/`result_key`/`relay_key`/`community_key`/`dead_letter_key`/`manifest_key`) | S3 키 레이아웃(디렉토리 구조) |
| **masking.py** | `mask_author(author, salt)` = `sha256(f"{salt}:{author}")[:12]` | 댓글 작성자 마스킹 방식(PII 위생) |
| **naver.py** | 네이버 URL 빌더 3종 + `extract_game_ids`(categoryId=="kbo"만) + `relay_is_empty`(빈 이닝 판정) | 네이버 엔드포인트, gameId 필터, relay 종료 규칙 |
| **community.py** | FMKorea/DCInside 목록·상세 파서, `PostRef`, `raw_post`(RawPost 조립, `schemaVersion=2`) | 커뮤니티 CSS 셀렉터·본문/댓글 추출·RawPost 필드 |
| **game_records.py** | 네이버 record 응답 → `GameRow`(+`PitchingRow`/`BattingRow`/`PlayerRef`). `innings_to_outs`, `list_finished_games`(완료·표준10구단만) | 박스스코어 파싱·정규화 규칙 |
| **kbo_register.py** | KBO `Player/Register.aspx`(ASP.NET WebForms) 히든값 POST·`parse_register`·`current_date` | 로스터 크롤 파싱 |
| **dimensions.py** | `TEAMS`(10구단 시드), `TeamRow`/`PlayerRow` dataclass, `PLAYER_SECTIONS`, `parse_physique` | 구단 마스터 데이터, 선수 행 스키마 |
| **targets.py** | `load_targets(path)` — 커뮤니티 대상 YAML → dict 리스트 | 대상 파일 파싱 |
| **stats.py** | 별도 CLI(`python -m kbo_collector.stats <job> --date`). 저널+S3 dead-letter를 읽어 성공률·HTTP 코드 분포 출력 | 수집 결과 리포트 |

## ② `kbo_collector/sources/` — 소스 플러그인 레지스트리

질문생성 파이프라인의 **입구**. 각 소스는 `source_id`·`doc_types`·`collect(ctx)` 계약만 지키면
`@register`로 전역 `REGISTRY`에 등록되고, `run.py collect`/`export`가 이름으로만 조회합니다
(**소스가 늘어도 run.py 불변**).

| 파일 | 역할 |
|---|---|
| **base.py** | `REGISTRY` dict + `@register` 데코레이터(계약 강제) + `CollectContext`(settings·client·db·sink·date 주입) + `CollectResult`. `get_source`/`sources_for` |
| **__init__.py** | 네 소스 모듈을 import → import 시점에 `@register` 실행 = 등록. **새 소스 추가 시 여기 import 1줄** |
| **naver_games.py** | `game_result` 방출. `run.land_game_records_range` 위임(얇은 래퍼) |
| **kbo_roster.py** | `player_profile` 방출. `run.land_registrations` 위임 |
| **community_posts.py** | `community_post` 방출. `run.land_community` 위임 |
| **meme_dict.py** | `player_meme` 방출. `memes.yaml` 로드→엔티티 해소→envelope→S3(**중간 저장소 없어 collect가 곧 export**). `resolve_player_uid`(이름+팀 유일매칭) 보유 |

## ③ `kbo_collector/exports/` — question-source envelope

수집물을 **소스 무관 통일 스키마(Envelope v1)** 로 재포장해 S3 `question-source/`에 적재. 질문생성기가
읽는 **최종 산출물** 계층입니다(상세: [`envelope-format.md`](./envelope-format.md)).

| 파일 | 역할 |
|---|---|
| **envelope.py** | `Envelope` dataclass(직렬화 시 JSON 12키 고정) + `validate()` + `s3_key`/`safe_id`(키 안전화). `ENVELOPE_VERSION=1` |
| **exporter.py** | docType별 `reader` 레지스트리(`game_result`·`player_profile`·`community_post`는 MySQL/S3에서 읽어 envelope 생성). `export()`가 reader 없으면 해당 docType을 방출하는 소스의 `collect`로 위임 |

> `sources/`(수집·적재)와 `exports/`(재포장)의 차이: `game_result`·`player_profile`는 소스가
> **MySQL에 적재**하고, exporter가 그 MySQL을 **읽어** envelope를 만듭니다. `player_meme`는 reader가
> 없어 소스 `collect`가 곧 export입니다.

---

## `config/` — 설정 데이터

코드가 아니라 **런타임에 로드되는 데이터**. 크롤 대상/사전을 바꾸려면 코드 대신 여기를 고칩니다.

| 파일 | 쓰는 곳 | 내용 |
|---|---|---|
| **targets.yaml** | Lambda(기본 `COLLECTOR_TARGETS_FILE`) | DCInside 구단 갤러리 10개. FMKorea는 여기 없음(AWS IP 430) |
| **targets.fmkorea.yaml** | 로컬 일일 크롤 | FMKorea KBO 인기글(`order: popular`) 1개 대상 |
| **targets.fmkorea.backfill.yaml** | 로컬 백필 | FMKorea `order: date`(최신→과거 walk)용 별도 대상 |
| **memes.yaml** | `meme_dict` 소스 | 선수-밈 사전(사람이 직접 편집) |

## `deploy/` — 배포·실행 어댑터 (코어를 얇게 호출)

| 경로 | 역할 |
|---|---|
| **lambda/** | 서버리스 크롤(호출당 과금). `handler.py`(코어 호출 어댑터), `Dockerfile`+`requirements.txt`(lxml 네이티브 → 컨테이너 이미지), `terraform/`(ECR·Lambda·EventBridge 스케줄·IAM), `README.md`. **community 10분 + game 매일 03:00 KST**. MySQL엔 안 씀 |
| **local/** | 주거 IP에서 도는 스크립트. `crawl_fmkorea.sh`(일일 인기글), `backfill_fmkorea.sh`(구간 백필), `README.md` |
| **sql/** | `schema.sql`(MySQL 테이블 DDL — teams/players/player_registrations/games/game_*), `seed-dump.sql`(시드) |

## `tests/` · `docs/` · `notebooks/` · 루트

- **tests/** — `pytest`. 모듈별 단위(`test_naver`·`test_community_*`·`test_game_records`·`test_kbo_register` …) + 레지스트리/래퍼 계약(`test_sources_base`·`test_registry_contract`·`test_sources_wrappers`) + envelope/exporter(`test_envelope`·`test_exporter`) + 잡(`test_run_jobs`)·Lambda(`test_lambda_handler`).
- **docs/** — 이 문서 세트(아래 "관련 문서").
- **notebooks/** — `run_crawler.ipynb`(수동 실행/스모크).
- **루트** — `README.md`(사람용 소개) · `CLAUDE.md`(Claude Code 작업 가이드·docs 링크 맵) · `pyproject.toml`(의존성·`kbo-collector` CLI 엔트리) · `Dockerfile.run`·`docker-compose.yml`(로컬 실행/LocalStack) · `.env.example`.

---

## 자주 하는 변경 → 여는 파일

| 하고 싶은 것 | 여는 곳 |
|---|---|
| 새 데이터 소스 추가 | `sources/`에 모듈 1개(`@register`) + `sources/__init__.py` import 1줄 (+ 필요 시 `config.py` 설정) |
| 커뮤니티 크롤 대상(갤러리) 추가/변경 | `config/targets*.yaml` (코드 수정 없음) |
| S3 키 레이아웃 변경 | `keys.py`(원본 3종·커뮤니티·dead-letter·manifest) / `exports/envelope.py`(question-source) |
| 재시도·UA·rate-limit 정책 | `fetch.py` (+ `config.py`의 `retry_*`·`rate_limit_cooldown_s`) |
| MySQL 적재 규칙(upsert) | `db.py` (+ 스키마는 `deploy/sql/schema.sql`) |
| 커뮤니티 파싱 셀렉터 | `community.py` |
| envelope 필드/템플릿 문구 | `exports/envelope.py`(스키마) / `exports/exporter.py`(reader 문장) / `sources/meme_dict.py`(밈 문장) |
| Lambda 스케줄/환경변수 | `deploy/lambda/terraform/` |

> **백필**: 경기/커뮤니티 모두 구간 백필을 지원합니다 — `records --from/--to`(`land_game_records_range`),
> `community --from/--to`(`land_community_range`, date-ordered 대상 필요). FMKorea 구간 백필의 로컬
> 실행 세부는 `deploy/local/backfill_fmkorea.sh`·`config/targets.fmkorea.backfill.yaml` 참고(별도 스크립트).

## 관련 문서

- 크롤링 플로우(잡→코어→싱크): [`crawl-flow.md`](./crawl-flow.md)
- 적재 JSON 필드 명세: [`data-formats.md`](./data-formats.md)
- question-source envelope 스키마: [`envelope-format.md`](./envelope-format.md)
- 전체 개요(미팅용): [`current-crawl-overview.md`](./current-crawl-overview.md)
- 요구사항·아키텍처 검토: [`data-pipeline-requirements.md`](./data-pipeline-requirements.md)
</content>
</invoke>
