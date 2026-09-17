# 크롤링 데이터 포맷 명세

> py-collector가 S3 브론즈 랜딩존에 적재하는 JSON 데이터의 생김새를 정리한 문서입니다.
> 모든 예시는 실제 적재된 데이터(`victoryfairy-crawl-local`, 2026-07-08 경기 기준)에서 추출했습니다.

## 한눈에 보기

| 구분 | 데이터 | 출처 | 포맷 | 성격 |
|------|--------|------|------|------|
| 정형 | **schedule** (일정) | 네이버 스포츠 API | 원본 JSON 그대로 | 소스가 준 바이트 그대로 |
| 정형 | **result** (경기결과) | 네이버 스포츠 API | 원본 JSON 그대로 | 소스가 준 바이트 그대로 |
| 정형 | **relay** (문자중계) | 네이버 스포츠 API | 원본 JSON 그대로 | 소스가 준 바이트 그대로 |
| 정형 | **record** (박스스코어) | 네이버 스포츠 API | 파싱 → **MySQL** | 네이버 응답을 정규화 적재 |
| 비정형 | **RawPost** (커뮤니티 글) | FMKorea / DCInside HTML | 우리가 만든 JSON 스키마 | HTML을 파싱해 구성 |

> **record만 예외**: schedule/result/relay/RawPost는 S3에 저장하지만, **record는 파싱해서 운영 MySQL**(`games`/`game_lineups`, 선수는 `players`에 해소)에 넣습니다. 아래 5절은 "네이버가 record 엔드포인트에서 내려주는 원본 구조"를 정리한 것이고, 테이블 구조는 domain 모듈 JPA 엔티티 참고.

**핵심 원칙 2가지**
- **정형(네이버)**: 응답을 **가공 없이 byte-for-byte** 저장합니다. 아래 구조는 "우리가 정한 것"이 아니라 **네이버가 내려주는 모양**입니다.
- **비정형(커뮤니티)**: HTML엔 정해진 스키마가 없으므로 우리가 `RawPost` 스키마(`schemaVersion=2`)로 구성합니다. 본문(`body`)은 **검열 없이 원문 그대로**, 댓글 작성자는 **마스킹(salt 해시)** 합니다.

### 저장 위치(S3 키)

| 데이터 | S3 키 |
|--------|-------|
| schedule | `raw-json/schedule/{yyyy-MM-dd}/schedule.json` |
| result | `raw-json/result/{yyyy-MM-dd}/{gameId}.json` |
| relay | `raw-json/relay/{gameId}/{inning}.json` |
| community | `community/{source}/{yyyy-MM-dd}/{postExternalId}.json` |

> 파일명은 콘텐츠 정체성에서 결정론적으로 도출됩니다(멱등). 실행 추적은 각 오브젝트의 **S3 메타데이터**(`run-id`, `job`)와 **매니페스트**(`manifests/{job}/{date}/{run_id}.json`)로 분리 보관합니다.

---

## 1. schedule — 일정

특정 날짜의 KBO 경기 목록. `land_schedule`이 여기서 `gameId`를 뽑아 result/relay를 구동합니다.

- **S3 키**: `raw-json/schedule/2026-07-08/schedule.json`
- **요청 필드**: `fields=basic,statusNum,statusInfo` (경량 버전 — 라인업/기록 없음)

### 봉투(envelope) 구조

```json
{
  "code": 200,
  "success": true,
  "result": {
    "games": [ /* 그날의 모든 경기 (배열) */ ],
    "gameTotalCount": 8
  }
}
```

> ⚠️ `result.games`는 **배열**입니다(그날 여러 경기). 뒤의 result는 `result.game` **단일 객체**라 헷갈리기 쉽습니다.

### `games[]` 한 경기 필드

| 필드 | 타입 | 예시 | 설명 |
|------|------|------|------|
| `gameId` | string | `"20260708LGSS02026"` | 경기 고유 ID (아래 포맷 참고) |
| `categoryId` | string | `"kbo"` | `kbo`=1군 정규전 / `kbaseballetc`=시범·특별경기 |
| `gameDate` | string | `"2026-07-08"` | 경기 날짜 |
| `gameDateTime` | string | `"2026-07-08T18:30:00"` | 경기 시각 |
| `homeTeamCode` / `homeTeamName` | string | `"SS"` / `"삼성"` | 홈팀 |
| `awayTeamCode` / `awayTeamName` | string | `"LG"` / `"LG"` | 원정팀 |
| `homeTeamScore` / `awayTeamScore` | int | `2` / `8` | 점수 (경기 전이면 0) |
| `winner` | string | `"AWAY"` | `HOME` / `AWAY` / `DRAW` |
| `statusCode` | string | `"RESULT"` | 경기 상태 (아래 표) |
| `statusNum` | int | `4` | 0=예정, 4=완료 (숫자 코드) |
| `statusInfo` | string | `"9회말"` / `"경기전"` / `"경기취소"` | 상태 한글 텍스트. **진행 이닝의 원천**([진행 이닝 판정](#진행-이닝-판정)) |
| `cancel` | bool | `false` | 취소 여부 |
| `suspended` | bool | `false` | 서스펜디드(정지) 여부 |
| `reversedHomeAway` | bool | `true` | 홈/원정 표기 뒤집힘 플래그 |
| `homeTeamEmblemUrl` / `awayTeamEmblemUrl` | string\|null | `null` | 엠블럼 이미지 URL |

### 경기 상태 판정

| 상태 | 판정 (우선순위 순) | DB `game_statuses.name` |
|---|---|---|
| 취소 | `cancel == true` — **최우선.** `statusCode`는 `"BEFORE"`, `winner`는 `"DRAW"` 껍데기로 옴(2026-07-08 `20260708NCHH02026` 실측) | `CANCELED` |
| 예정 | `statusCode in ("BEFORE", "READY")` — **코드가 둘이다.** 먼 날짜는 `BEFORE`(statusNum=0), **경기 당일은 첫 투구 전까지 `READY`**(statusNum=1, statusInfo=`"경기전"`). 2026-08-12 18:25 KST, 19:00 시작 5경기 전수 실측 | `SCHEDULED` |
| 진행중 | `statusCode in ("STARTED", "LIVE")` — 실측값은 `STARTED`(2026-08-04 실황 3경기). `LIVE`는 초기 가정값이나 반례가 없어 호환 유지 | `IN_PROGRESS` |
| 완료 | `statusCode == "RESULT"`, 양팀 동점이면 무승부 | `FINISHED` / `DRAW` |

> ⚠️ **판정 시 함정 4가지** (`game_records.py`의 `map_status`, `run.py`의 `job_games_sync` 실측 근거)
> ① **`winner` 필드로 무승부·취소를 판정하지 말 것** — 취소 경기도 `winner:"DRAW"` 껍데기로 옴.
> ② **schedule API 광범위 조회 금지** — `fromDate`~`toDate`를 2개월로 걸어 조회한 실측에서 경기 **4건만** 반환됨. 하루 단위(`fromDate=toDate=date`) 조회가 정석.
> ③ `suspended`(서스펜디드)는 아직 `map_status`에 미반영 — 관측되면 상태 판정 실패로 **skip + warning 로그**.
> ⑤ **미지 상태는 조용히 실패한다** — `map_status`가 `None`을 내면 `_sync_games_for_date`가 그 경기를 통째로 `continue` 한다. 상태 동기화도, 선발 라인업 적재도 일어나지 않고 warning 로그만 남는다. 실제로 `READY`가 미반영이던 동안 **경기 당일 낮~경기 직전 구간의 경기가 전부 스킵**되고 있었다(2026-08-12 발견). 새 `statusCode`를 관측하면 표에 추가할 것.
> ④ 취소·예정 경기의 점수는 0-0 **껍데기**이므로 `games_sync` 적재 시 DB에는 `NULL`로 넣는다(점수는 진행중/완료일 때만 채움).

일정 없음(`result.games == []`, 그날 경기 없음)은 위 표와 별개로 스케줄 자체가 비는 경우다.

### 진행 이닝 판정

`games.current_inning`/`inning_half`/`last_inning`의 원천은 **schedule API의 `statusInfo`** 하나다(`game_records.parse_inning`). 진행 중에는 `"3회말"` 한 가지 형태로만 오고 아웃카운트·주자 같은 접미사가 붙지 않는다 — 2026-08-12 19:00 시작 5경기를 15초 간격으로 관측한 실측이다.

**같은 값이 정반대 정책의 두 곳으로 갈라져 들어간다.**

| 컬럼 | 언제 채우나 | upsert 정책 | 뜻 |
|---|---|---|---|
| `current_inning` · `inning_half` | `IN_PROGRESS`일 때만 | `VALUES()` 덮어쓰기 | **지금** 몇 회인가 |
| `last_inning` | 상태 무관(파싱되면 언제나) | `COALESCE` 보존 | 몇 회에 **끝났나** |

| `statusInfo` | `current_inning` | `inning_half` | `last_inning` |
|---|---|---|---|
| `"1회초"` (IN_PROGRESS) | 1 | 0 (`TOP`) | 1 |
| `"3회말"` (IN_PROGRESS) | 3 | 1 (`BOTTOM`) | 3 |
| `"9회말"` (FINISHED) | `NULL` | `NULL` | 9 |
| `"경기전"` · `"경기취소"` · `""` · 미지 포맷 | `NULL` | `NULL` | 기존 값 유지 |
| `"12회초"` 이상 | `NULL` (+ warning) | `NULL` | **12** (상한 없음) |

**상한이 두 컬럼에 다르게 걸린다.** `current_inning`은 CHECK `ck_games_current_inning`(1~11) 때문에 `INNING_MAX`로 막지만, `last_inning`에는 **CHECK가 없어**(prod 제약 조회 결과 `ck_games_current_inning`·`ck_games_inning_half` 둘뿐) `parse_inning(..., max_inning=None)`으로 읽은 그대로 넣는다. 같이 막으면 12회 경기에서 파싱이 `None`이 되고 `COALESCE`가 직전 폴링의 11을 보존해 **"12회에 끝난 경기"가 `last_inning=11`로 틀리게 기록된다** — `NULL`은 "모른다"지만 11은 "틀린 답"이라 더 나쁘다.

종료 경기의 `statusInfo`가 마지막 이닝을 그대로 들고 있다는 성질(아래 함정 ①)이 여기서는 **버그가 아니라 정답의 출처**가 된다. 덕분에 라이브 구간을 놓친 경기도 `last_inning`이 회수된다 — 단 **`games_sync`가 그 날짜를 다시 훑을 때만**이다(live 룰은 당일, morning/nightly는 오늘~+N일). 그보다 과거 날짜는 `--from`/`--to` 수동 백필이 필요하다.
>
> 진행 중 취소(노게임)면 취소 직전 이닝이 `last_inning`에 남는다 — `COALESCE`라 지우는 경로가 없다. 점수·구장과 같은 성질이며, "몇 회까지 하고 취소됐나"로 읽으면 오히려 정보다.

`inning_half`는 domain `InningHalf`의 **ORDINAL**(`TOP=0`/`BOTTOM=1`)이다. 네이버 relay의 `homeOrAway`(`"0"`=원정 공격=초, `"1"`=홈 공격=말)와도 값이 같다.

> ⚠️ **이닝 함정 4가지**
> ① **`IN_PROGRESS`가 아니면 무조건 `NULL`이다**(BE 계약: `GET /api/games`의 `inning`/`inningHalf`는 진행 중에만 값). `statusInfo`는 **경기가 끝나도 마지막 이닝을 그대로 들고 있어서**(2026-08-11 전 경기 `"9회초"`/`"9회말"`, 강우콜드는 `"6회말"`) 상태로 거르지 않으면 종료 경기에 이닝이 박힌다.
> ② **`GAME_SYNC_UPSERT`에서 이닝 세 컬럼의 정책이 서로 다르다.** `current_inning`/`inning_half`는 점수·구장과 반대로 `COALESCE` 없이 `VALUES()` 직접 대입이고(종료 시 `NULL`로 덮여야 하므로 — 붙이면 ①이 깨진다), `last_inning`은 다시 반대로 `COALESCE`다(종료 후에도 남아야 하므로 — 빼면 다음 폴링에 지워진다). **셋 다 다른 이유로 지금 모양이니 일관성 명목으로 어느 한쪽에 맞추지 말 것.**
> ③ **detail API(`/schedule/games/{gameId}`)의 `currentInning`을 원천으로 쓰지 말 것.** 경기 시작 **20분 전**(`statusCode="READY"`)부터 이미 `"1회초"`로 앞서 나간다(2026-08-12 18:41 실측). 같은 시각 `statusInfo`는 정확히 `"경기전"`이었다. 더 이른 `BEFORE` 구간에서는 `currentInning`이 빈 문자열 `""`이다.
> ④ **`INNING_MAX`(11)를 넘으면 값을 버리고 warning을 남긴다.** 11은 KBO 현행 규정의 이닝 한도(정규 9 + 연장 2)이고, `games`의 CHECK `ck_games_current_inning`(1~11)과 같은 값이다 — **진짜 상한은 우리 코드가 아니라 DB**이며 prod에 실제로 걸려 있다(실측). 2026 정규시즌 전수 스캔(3/22~8/11)도 11회 18경기·12회 0경기로 일치한다.
>
> 규정 한도와 같은 값이므로 **이 가드가 실제로 발동하면 그건 연장이 아니라 파싱 이상**이다(네이버 포맷 변경 등). 규정이 바뀌어 올려야 한다면 순서가 고정돼 있다 — ① BE 엔티티 `@CheckConstraint` → ② 관리자 계정으로 prod·devdb에 `ALTER TABLE games DROP CHECK ...` + `ADD CONSTRAINT ...` → ③ 수집기 `INNING_MAX`. `ddl-auto=update`는 기존 CHECK를 바꾸지 않아 ②를 건너뛸 수 없고, ③만 먼저 하면 DB가 거부한다.

### 경기 단위 격리

`_sync_games_for_date`는 경기 하나의 적재 실패를 **그 경기에 가둔다**(warning + `failed` 집계, 나머지 경기는 계속). 격리가 없으면 예외가 `job_games_sync_range`의 **날짜 단위** 핸들러까지 올라가, 실패한 경기 뒤에 오는 경기가 통째로 빠진다.

발동 조건은 DB 제약 위반(위 ④), 커넥션 끊김, 예상 못 한 응답 형태 등이다. 이 잡은 1분마다 도는 멱등 upsert라, 격리된 경기는 원인이 해소되면 다음 폴링에서 저절로 회수된다.

이닝별 득점(스코어보드)이 필요하면 원천이 다르다 — schedule에는 없고 detail의 `homeTeamScoreByInning`/`awayTeamScoreByInning`(경기당 1콜, ~2.4KB)이나 relay의 `inningScore`를 봐야 한다. relay는 한 경기 9이닝 합계가 **1.46MB**(이닝당 118~358KB)라 라이브 폴링에는 쓰지 않는다.

> **gameId 포맷**: `YYYYMMDD(8) + away(2) + home(2) + dh(1) + year(4)`
> 예) `20260708 LG SS 0 2026` → `20260708LGSS02026`. 더블헤더 순번은 13번째 문자(index 12).
> `categoryId != "kbo"`인 특별경기는 `gameId`가 `20260708KBO1`처럼 다르고 팀명이 비어 있어, `land_schedule`이 자동 제외합니다.

---

## 2. result — 경기결과

한 경기의 상세 결과. schedule의 경량 정보보다 훨씬 풍부합니다(이닝별 점수, 선발/승패투수, 날씨 등).

- **S3 키**: `raw-json/result/2026-07-08/20260708LGSS02026.json`

### 봉투 구조

```json
{
  "code": 200,
  "success": true,
  "result": {
    "game": { /* 단일 경기 객체 */ }
  }
}
```

> ⚠️ 여기는 `result.game` **단일 객체**입니다. schedule의 `result.games[]` 배열과 다릅니다.

### `result.game` 주요 필드

schedule의 상태 필드(`gameId`, `statusCode`, `cancel`, `suspended`, 점수 등)를 모두 포함하고, 아래가 추가됩니다.

| 필드 | 타입 | 예시 | 설명 |
|------|------|------|------|
| `stadium` | string | `"대구"` | 구장 |
| `seasonYear` | int | `2026` | 시즌 연도 |
| `currentInning` | string | `"9회말"` | 현재/최종 이닝 |
| `homeTeamScoreByInning` | string[] | `["2","0",...]` | 이닝별 점수 (9개) |
| `awayTeamScoreByInning` | string[] | `["0","0","2",...]` | 이닝별 점수 (9개) |
| `homeTeamRheb` / `awayTeamRheb` | int[] | `[2,7,0,2]` | **R**un·**H**it·**E**rror·**B**B(볼넷) 합계 (4개) |
| `homeStarterName` / `awayStarterName` | string | `"오러클린"` / `"임찬규"` | 선발투수 |
| `winPitcherName` / `losePitcherName` | string | `"임찬규"` / `"오러클린"` | 승/패 투수 |
| `homeTeamFullName` / `awayTeamFullName` | string | `"삼성 라이온즈"` | 팀 정식명 |
| `weatherInfo` | object | `{"weather":"30.3° 맑음", ...}` | 날씨 |
| `commentInfo` | object | `{"title":"LG vs 삼성 …", ...}` | 댓글창 메타 |
| `gameCenterUrl` | object | `{"baseUrl":"https://m.sports.naver.com/…"}` | 게임센터 링크 |
| `hasVideo` | bool | `true` | 영상 유무 |

> 💡 **취소된 경기도 result는 존재**합니다(스코어 0, `cancel:true`). 다만 아래 relay는 문자중계 데이터가 없어 비어 있습니다.

---

## 3. relay — 문자중계

경기의 **이닝별** 문자중계(play-by-play)와 라인업·기록. 4종 중 가장 크고(평균 ≈ 168KB) 정보가 조밀합니다.

- **S3 키**: `raw-json/relay/20260708LGSS02026/1.json` (이닝마다 파일 1개, `1.json`~`9.json`…)

### 봉투 구조

```json
{
  "code": 200,
  "success": true,
  "result": {
    "textRelayData": { /* 이 이닝의 중계 데이터 */ }
  }
}
```

### `result.textRelayData` 주요 필드

| 필드 | 타입 | 설명 |
|------|------|------|
| `category` / `gameId` / `inn` | string/int | `"kbo"` / 경기ID / 이닝 번호 |
| `inningScore` | object | `{ "home": {"1":"2","2":"0",...}, "away": {...} }` 이닝별 점수 |
| `homeEntry` / `awayEntry` | object | 출전 명단 (`batter[]`, `pitcher[]` — 이름·포지션·pcode) |
| `homeLineup` / `awayLineup` | object | 선수별 **상세 스탯** (`batter[]`: 타율·안타·타점…, `pitcher[]`: 이닝·자책·삼진…) |
| `currentGameState` | object | 현재 스코어·안타·볼넷·주자 상황(`base1/2/3`)·볼카운트 |
| **`textRelays`** | array | **문자중계 핵심** — 타석/이벤트 단위 서술 (아래) |
| `lastValidMetricOption` | object | `{ "homeTeamWinRate": 67.7, "awayTeamWinRate": 32.3, ... }` 승률 |

### `textRelays[]` 한 항목 (play-by-play의 알맹이)

```json
{
  "title": "5번타자 디아즈",
  "no": 10,
  "inn": 1,
  "homeOrAway": "1",
  "statusCode": 0,
  "textOptions": [
    {
      "text": "5번타자 디아즈",
      "type": 8,
      "seqno": 58,
      "currentGameState": { "homeScore": "2", "awayScore": "0", "out": "2", "ball": "0", ... },
      "batterRecord": { "name": "디아즈", "ab": 1, "hit": 0, "seasonHra": 0.297, ... }
    }
  ]
}
```

- `textOptions[].text` — 실제 중계 문구(타자 등장·투구 결과 등)
- `textOptions[].currentGameState` — 그 시점의 스코어/주자/볼카운트 스냅샷
- `textOptions[].batterRecord` / `currentPlayersInfo` — 해당 타자·투수의 기록

### 빈 이닝 / 수집 종료 규칙

- `result.textRelayData.textRelays`가 **없거나 빈 배열**이면 → 그 이닝은 경기 범위 밖 → `land_relays`가 해당 경기 수집을 종료(다음 경기로).
- **취소 경기**(예: `20260708NCHH02026`)는 `textRelays`가 아예 없어 relay 파일이 0개입니다(result는 존재).

> 💡 relay JSON은 반복 텍스트가 많아 gzip 시 **약 95% 압축**됩니다. 분석 계층에선 압축/컬럼형(Parquet) 변환을 고려할 수 있습니다(현재 브론즈는 무압축 원본 유지).

---

## 4. record — 경기 박스스코어 (박스스코어)

한 경기의 **최종 기록** — 누가 던지고(투수별 이닝·자책·삼진), 누가 치고(타자별 안타·홈런·타점), 승/패/세/홀 투수, 이닝별 스코어. 경기 종료 후에도 영구 제공되므로 **과거 시즌 백필**에 사용합니다.

- **엔드포인트**: `{base}/schedule/games/{gameId}/record`  (예: `/schedule/games/20260328HTSK02026/record`)
- **저장**: S3 아님. `game_records.parse_record()`로 파싱해 MySQL에 정규화 적재.
- **주의**: 스케줄 조회는 날짜에 **대시 필수**(`fromDate=2026-03-28`). 컴팩트(`20260328`)로 넣으면 `400`.

### 봉투 구조

```json
{ "code": 200, "success": true, "result": { "recordData": { /* 아래 */ } } }
```

### `recordData` 최상위 키 (15개)

| 키 | 타입 | 우리가 쓰나 | 내용 |
|----|------|:--:|------|
| `gameInfo` | object | ✅ | 경기 메타: `gdate`,`round`,`gameFlag`(0=정규/1=시범),`stadium`,`gtime`,`aCode`/`hCode`,**`aPCode`/`hPCode`(선발투수 pcode)**,`statusCode` |
| `scoreBoard` | object | ✅ | `rheb`(away/home 각 `{r,h,e,b}`) + `inn`(away/home 이닝별 득점 배열) |
| `pitchersBoxscore` | object | ✅ | `{away:[], home:[]}` 투수별 기록(아래) |
| `battersBoxscore` | object | ✅ | `{away:[], home:[], awayTotal, homeTotal}` 타자별 기록(아래) |
| `pitchingResult` | array | ✅ | 승패세홀: `[{pCode, name, wls(W/L/S/H), w, l, s}]` |
| `teamPitchingBoxscore` | object | – | 팀 투수 합계 `{away,home}`: `inn,bf,pa,ab,hit,r,er,hr,bbhp,kk` |
| `todayKeyStats` | object | – | 팀 요약 `{away,home}`: `hit,err,hr,kk,sb,gd,tb` |
| `etcRecords` | array | – | `[{how, result}]` — 결승타·2루타·홈런·실책·도루·폭투·포일 등 서술 |
| `awayStandings`/`homeStandings` | object | – | 순위·전적: `rank,w,l,d,era,hra,hr,wra,seriesOutcome` |
| `awayTeamNextGames`/`homeTeamNextGames` | array | – | 다음 경기 3개 |
| `games` | array | – | 그날 전체 경기 목록(5개) — score·inn·상태 |
| `recentVsGames` | array | – | 최근 상대전적(비어있을 수 있음) |
| `currentInning` | string | – | 현재/최종 이닝 |

### `pitchersBoxscore.away/home[]` — 투수 한 명

| 필드 | 예시 | 설명 |
|------|------|------|
| `pcode` / `name` | `"54640"` / `"네일"` | 네이버 선수코드 / 이름 |
| `tb` | `"T"`/`"B"` | 원정(Top)/홈(Bottom) |
| `inn` | `"6"`, `"6 ⅓"` | 투구 이닝(유니코드 분수) → 우리는 `ip_outs`(아웃 수)로 변환 |
| `bf`,`pa`,`ab` | `84`,`21`,`20` | 상대 타자·타석·타수 |
| `hit`,`r`,`er` | `2`,`0`,`0` | 피안타·실점·자책 |
| `hr`,`bbhp`,`kk` | `0`,`1`,`5` | 피홈런·볼넷+사구·탈삼진 |
| `wls`,`w`,`l`,`s` | `""`/`승`/`패` … | 승패 표기(한글) + 시즌 승/패/세 |

### `battersBoxscore.away/home[]` — 타자 한 명

| 필드 | 예시 | 설명 |
|------|------|------|
| `playerCode` / `name` | `"65653"` / `"김호령"` | 선수코드 / 이름 |
| `batOrder`,`pos` | `1`, `"중"` | 타순, 포지션(중=중견수, 지=지명, 포 …) — DB(`positions.name`)에는 원문이 아니라 정식 명칭으로 적재된다(아래 매핑표). `positions.name`이 API `positionName`으로 그대로 나가므로 화면에서 읽히는 값이어야 한다. 매핑에 없는 미지 표기는 warning 로그 후 원문 그대로 적재(수집이 깨지지 않게) |

| `ab`,`run`,`hit` | `4`,`1`,`0` | 타수·득점·안타 |
| `hr`,`rbi`,`bb` | `0`,`0`,`1` | 홈런·타점·볼넷 |
| `sb`,`kk`,`hra` | `0`,`1`,`0.200` | 도루·삼진·시즌타율 |
| `inn1`~`inn25` | `"삼진"`,`"1땅"`,`"좌비"` … | 타석별 결과 서술(연장 대비 25칸) |

`pos` 표기 → `positions.name` 적재값 매핑:

| 네이버 원문 | 적재값 | 네이버 원문 | 적재값 |
|---|---|---|---|
| 투 | `투수` | 좌 | `좌익수` |
| 포 | `포수` | 중 | `중견수` |
| 一 | `1루수` | 우 | `우익수` |
| 二 | `2루수` | 지 | `지명타자` |
| 三 | `3루수` | 타 | `대타`(출전 형태) |
| 유 | `유격수` | 주 | `대주자`(출전 형태) |

> **2글자 조합은 첫 글자로 접는다.** 경기 중 수비 위치를 바꾼 선수는 표기가 이어붙어
> 온다 — `중좌`(중견수→좌익수), `타二`(대타→2루수), `三유`(3루수→유격수). 라인업은
> "이 선수가 어디로 나왔나"를 보여주는 화면이라 **첫 글자 = 그 경기 시작 위치**를 적재한다
> (`db.py`의 `position_name`). 접지 않으면 조합이 12×12까지 늘어 `positions`가 계속
> 불어난다(2026-08-04 dev DB 기준 30종 관측).

> **선수코드 주의**: 노드마다 키 이름이 다릅니다 — `pcode`(투수) / `playerCode`(타자) / `pCode`(pitchingResult) / `aPCode`·`hPCode`(gameInfo 선발). 파서에서 모두 정규화합니다. 이 `pcode`는 실측상 KBO 공식 `playerId`와 **같은 값**(2026-07 교집합 228명 전수 일치)이라 `players.kbo_player_id` 단일 컬럼으로 통합(해소: kbo_player_id 일괄 조회 → 없으면 신규 행. DB 이름과 API 이름이 다르면 동치 전제 훼손 신호로 warning).

---

## 5. RawPost — 커뮤니티 글 (우리 스키마)

FMKorea·DCInside 글을 파싱해 만든 통일 스키마. **정형 3종과 달리, 이 구조는 우리가 정의한 것**입니다.

- **S3 키**: `community/dcinside/2026-07-10/11158020.json`
- **버전**: `schemaVersion=2`, `crawlerVersion="community-v3"`

### 전체 예시

```json
{
  "schemaVersion": 2,
  "source": "DCINSIDE",
  "postExternalId": "11158020",
  "sourceUrl": "https://gall.dcinside.com/board/view/?id=doosanbears_new1&no=11158020",
  "title": "구멍게이 병살ㅋㅋㅋㅋ",
  "body": "…(원문, 필터링 없음)…",
  "engagement": { "viewCount": 10, "likeCount": 0, "dislikeCount": 0, "commentCount": 0 },
  "topComments": [
    { "author": "a1b2c3d4e5f6", "body": "…", "likeCount": 5 }
  ],
  "team": "DOOSAN",
  "crawledAt": "2026-07-10T10:30:34+00:00",
  "crawlerVersion": "community-v3"
}
```

### 필드

| 필드 | 타입 | 설명 |
|------|------|------|
| `schemaVersion` | int | 스키마 버전 (`2`) |
| `source` | string | `"FMKOREA"` 또는 `"DCINSIDE"` |
| `postExternalId` | string | 원본 글 번호(FMKorea postId / DC `no`) |
| `sourceUrl` | string | 원본 글 URL |
| `title` | string | 제목 (목록 페이지에서 추출) |
| `body` | string | **본문 원문 — 검열/필터 없음** (광고·스크립트 노드만 제거) |
| `engagement.viewCount` | int\|null | 조회수 |
| `engagement.likeCount` | int\|null | 추천수 |
| `engagement.dislikeCount` | int\|null | 비추천수 (FMKorea는 `null`) |
| `engagement.commentCount` | int\|null | 댓글수 |
| `topComments[]` | array | 상위 댓글 (추천순, 최대 `COLLECTOR_TOP_COMMENTS`개) |
| `topComments[].author` | string | **마스킹된** 작성자 (salt SHA-256, 12자) — 원문 핸들 미저장 |
| `topComments[].body` | string | 댓글 본문 |
| `topComments[].likeCount` | int | 댓글 추천수 |
| `team` | string\|null | DCInside는 구단(`"DOOSAN"` 등), FMKorea는 `null` |
| `crawledAt` | string | 수집 시각 (UTC ISO-8601) |
| `crawlerVersion` | string | `"community-v3"` |

### 소스별 주의사항

| 항목 | FMKorea | DCInside |
|------|---------|----------|
| `team` | `null` | 갤러리별 구단명 |
| `topComments` | 있음(추천순 상위 N) | **항상 `[]`** — 댓글이 AJAX 로드라 정적 HTML에 없음 |
| `engagement.dislikeCount` | `null` | 값 존재 |
| 현재 상태 | Cloudflare 차단으로 수집 0건일 수 있음 | 정상 수집 |

> 🔒 **PII 위생**: `topComments[].author`는 검열이 아니라 위생 목적의 마스킹입니다. `salt`(`COLLECTOR_PII_SALT`)로 해시하여 원문 핸들을 남기지 않되, 같은 작성자는 같은 토큰으로 매핑됩니다.
> 📝 **본문 무필터**: `body`는 의도적으로 검열 없이 저장합니다(나중에 걸러지지 못한 원문을 역추적하기 위함).

---

## 부록: 5종 요약 비교

| | schedule | result | relay | record | RawPost |
|---|---|---|---|---|---|
| 봉투 최상위 | `result.games[]` (배열) | `result.game` (객체) | `result.textRelayData` | `result.recordData` | (봉투 없음, 평면) |
| 단위 | 날짜 1 파일 | 경기 1 파일 | 경기×이닝 1 파일 | 경기 1 응답 | 글 1 파일 |
| 저장 | S3 | S3 | S3 | **MySQL(정규화)** | S3 |
| 스키마 소유 | 네이버 | 네이버 | 네이버 | 네이버(원본)→우리(테이블) | **우리(v2)** |
| 가공 | 없음(원본) | 없음(원본) | 없음(원본) | 파싱→정규화 적재 | HTML 파싱 + 작성자 마스킹 |

---

## 관련 문서

- 여기(원본·RawPost) 다음 단계인 질문생성 인계 산출물: [`envelope-format.md`](./envelope-format.md)
- 파일 배치·MySQL 테이블 스키마: [`directory-structure.md`](./directory-structure.md) · [`current-crawl-overview.md`](./current-crawl-overview.md)
