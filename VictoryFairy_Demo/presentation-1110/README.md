# 발표 데모 운영 가이드 (2026-11-10 ~ 11-15)

> `demo` 브랜치 전용(main 에 머지하지 않는다). 스크립트·SQL·이미지는 모두 이 폴더에 있다.
> 지난 회차 문서(Desktop `경기채팅-더미봇-48시간테스트-1029.md`)의 함정은 그대로 유효하다. 이 문서는 이번 회차 차이와 실행 명령만 담는다.
> 경기 SQL 을 바꾸려면 `gen_games_sql.py` 의 `DAYS` 를 고치고 `python gen_games_sql.py` 로 다시 만든다.

## 0. 한눈에

| 항목 | 내용 |
|---|---|
| 기간 | 11/10(화) ~ 11/15(일) 23:59 KST. 봇은 **08~24시에만** 활동(`ACTIVE_HOURS`) |
| 경기 | 하루 5경기 × 6일 = 30경기, 접두사 `DEMO`, **12:00 시작 → 17:00 종료**. 하루 1~2경기는 16:05~16:40에 먼저 끝남(9회까지 다 흐른 뒤 종료) |
| 계정 | 더미 1,039명은 11/06에 삭제됐다 → **페르소나 계정(`@persona.victoryfairy.internal`, 약 44명)** 으로 전부 돈다 |
| 파드 4개 | `livegame`(점수·이닝·승률) · `chatbot`(경기 채팅·응원·입퇴장) · `communitybot`(글·사진·댓글·좋아요) · `quizbot`(퀴즈방 생성·풀이·입퇴장·좋아요) |

### 경기표 (원정:홈 최종, ★ = 17시 전 종료)
| 날짜 | 경기 |
|---|---|
| 11/10 | 두산@LG 4:6 · ★KT@한화 9:2(16:15) · NC@SSG 3:4 · 키움@롯데 5:7 · KIA@삼성 6:5 |
| 11/11 | LG@두산 2:1 · ★한화@KT 3:8(16:20) · SSG@NC 7:6 · ★롯데@키움 1:4(16:40) · 삼성@KIA 5:3 |
| 11/12 | 삼성@LG 3:5 · ★두산@KIA 10:4(16:10) · 롯데@KT 2:3 · 한화@NC 6:4 · SSG@키움 8:7 |
| 11/13 | LG@삼성 1:2 · KIA@두산 4:7 · ★KT@롯데 6:3(16:30) · NC@한화 5:9 · ★키움@SSG 2:0(16:05) |
| 11/14 | KT@LG 7:5 · NC@두산 3:2 · ★삼성@롯데 11:3(16:15) · KIA@한화 4:6 · SSG@키움 5:8 |
| 11/15 | LG@KT 6:4 · ★NC@두산 2:5(16:35) · 롯데@삼성 4:3 · ★한화@KIA 1:7(16:10) · 키움@SSG 9:8 |

득점 시점은 경기별 시드로 고정이라 경기마다 흐름이 다르다(빅이닝·역전·1점 차 접전·대승이 섞인다). 30경기 모두 로컬 시뮬레이션으로 "9회까지 진행 후 최종 스코어 도달"을 확인했다.

**오늘(11/10)은 이미 정오가 지났다.** 시작 이벤트가 적용 1분 뒤에 돌고, livegame 이 12시 기준 타임라인으로 계산하므로 지금 시각에 맞는 이닝(15시대면 6~7회)부터 바로 보인다. **오늘 17:00 전에 1단계를 넣어야** 오늘 종료 이벤트가 살아 있다.

## 1. 실행 (사람이 `!` 로 — 자동 모드는 운영 쓰기·조회를 막는다)

```bash
cd /d/dev/VictoryFairy-wt/demo/VictoryFairy_Demo/presentation-1110   # demo 브랜치 worktree
```

### 1-1. (선택) 사전 점검 — 읽기 전용
```bash
kubectl -n victoryfairy create configmap demo1110-sql --from-file=games-1110-1115.sql --from-file=inspect.sql --dry-run=client -o yaml | kubectl apply -f -
MSYS_NO_PATHCONV=1 kubectl -n victoryfairy run tmp-demo-inspect --rm -i --restart=Never --image=mysql:8.0 --overrides="$(cat inspect-ov.json)"
```
볼 것: 페르소나 수(40명대), 응원 구단 분포, 오늘 날짜에 실경기 행이 없는지.

### 1-2. 데모 경기 30건 + 이벤트 36개
```bash
kubectl -n victoryfairy create configmap demo1110-sql --from-file=games-1110-1115.sql --from-file=inspect.sql --dry-run=client -o yaml | kubectl apply -f -
MSYS_NO_PATHCONV=1 kubectl -n victoryfairy run tmp-demo-apply --rm -i --restart=Never --image=mysql:8.0 --overrides="$(cat apply-ov.json)"
```
확인(마지막 SELECT): DEMO 30행, **`stadium_id` NULL 없음**, `removed = 0`, events 36개, `@@event_scheduler = ON`.

### 1-3. 스크립트·이미지 ConfigMap
```bash
kubectl -n victoryfairy create configmap demo1110-scripts \
  --from-file=livegame.py --from-file=chatbot.py --from-file=communitybot.py --from-file=quizbot.py --from-file=quizzes.json \
  --dry-run=client -o yaml | kubectl apply -f -
# 이미지는 apply 가 아니라 create — apply 는 last-applied 주석에 데이터를 한 벌 더 넣어 주석 한도(256KB)를 넘긴다
for x in a b c d; do
  kubectl -n victoryfairy delete configmap demo1110-img-$x --ignore-not-found
  kubectl -n victoryfairy create configmap demo1110-img-$x --from-file=baseball-img/$x/
done
```

### 1-4. 파드 4개
지난 회차 `chatbot`·`livegame` 파드가 `Completed` 로 남아 있어 이름이 겹친다 — 먼저 지운다.
```bash
kubectl -n victoryfairy delete pod chatbot livegame communitybot quizbot --ignore-not-found --wait=true
kubectl apply -f pod-livegame.yaml -f pod-chatbot.yaml -f pod-communitybot.yaml -f pod-quizbot.yaml
kubectl -n victoryfairy get pod -l app=demo1110
```

시작 로그에서 확인할 것:
| 파드 | 첫 줄 근처 |
|---|---|
| livegame | `경기 계획 DEMO1110…: 최종 4:6 … 9회 종료 16:54` 5줄, 이어서 `📡` |
| chatbot | `페르소나 N명` → 방 5개에 봇 배정 → `▶ 입장` / `◀ 퇴장` 반복 |
| communitybot | `야구 사진 32장` → `✍ #… (이미지 n)` 시드 40건 → 📊 |
| quizbot | `🧩 새 방 #… '2026 한국시리즈 총정리 퀴즈'` 등 3개 → `❤`·`✅` |

```bash
kubectl -n victoryfairy logs -f livegame
kubectl -n victoryfairy logs -f chatbot --tail=30
kubectl -n victoryfairy logs -f communitybot --tail=30
kubectl -n victoryfairy logs -f quizbot --tail=30
```

## 2. 각 기능이 화면에서 어떻게 보이나

### 경기 + 경기별 채팅
- 경기 목록: 12시 이후 `진행 중`, 이닝·점수·승률이 15~30초마다 움직인다. 17시 전에 1~2경기가 `종료`로 바뀐다.
- 채팅방: 방마다 페르소나 7~11명이 배정되고, **25~150초 머물다 나가 15~110초 뒤 다시 들어온다.** 30초마다 방별로 "몰려 들어오기/빠지기" 물결이 일어 접속 인원 숫자가 눈에 띄게 오르내린다.
- 대사는 경기 상황을 따른다: 시작 전("곧 시작이네요"), 6회 이후 1점 차("3:4 지키자!!"), 5점 차 이상("편하게 보는 중"), 종료 후 승패("이겼다!!!!" / "내일 이기면 돼").
- 응원(좋아요): 접속 중인 봇이 1~5회/초로 몇 초 연타 → 쉼. 경기 중엔 더 잦다.

### 커뮤니티
- 시작하자마자 글 40건(60%에 야구 사진 1~3장) → 이후 3~10분마다 새 글.
- **사진은 위키미디어 공용의 야구 사진 32장만 쓴다**(KBO 구장·관중석 22장, 야구공 10장, 모두 CC/퍼블릭 도메인, 출처 `baseball-img/CREDITS.tsv`). 사람·마스코트가 주인공인 사진과 야구와 무관한 사진은 직접 보고 걸렀다. 사진을 못 읽으면 이미지 없이 올리고, 엉뚱한 그림으로 대체하지 않는다.
- 실시간 반응: 열람 슬롯 10개가 목록→상세를 돌며 좋아요·댓글·답글을 달고, **'버즈'가 가장 최근 글 5개에 8~35초마다 댓글이나 좋아요를 붙인다.** 발표 때는 최신 글 상세를 열어 두면 숫자가 늘어나는 게 보인다.
- 글감은 11월 기준(삼성 12년 만의 우승, 구자욱 KS MVP, 강민호 첫 우승, 김도영 45홈런, 레이예스 211안타, 역대 최다 관중, 스토브리그, WBC·월드시리즈).

### 사용자 정의 퀴즈
| 공개 | 방 | 문항 |
|---|---|---|
| 11/10 즉시 | 2026 한국시리즈 총정리 퀴즈 | 10 |
| 11/10 즉시 | 2026 KBO 정규시즌 기록왕 퀴즈 | 10 |
| 11/10 즉시 | 야구 규칙 O/X 상식 | 10 |
| 11/11 10시 | 2026 WBC 돌아보기 | 9 |
| 11/12 10시 | 2026 MLB 월드시리즈 퀴즈 | 7 |
| 11/13 10시 | 야구 용어 마스터 퀴즈 | 8 |
| 11/14 10시 | KBO 10개 구단 연고지 퀴즈 | 8 |

- 방마다 만든 사람(페르소나)이 다르고, 시사 문항은 **복수 보도로 확인한 사실만** 넣었다. 출처가 하나뿐이거나 엇갈린 것(KS 5차전 스코어, 타격왕 타율, 곽빈 최종 기록, 정규시즌 MVP)은 뺐다.
- 풀이 슬롯 40개가 페르소나를 바꿔 가며 방에 들어와(SSE 구독 = 풀이 중 인원 +1) 90~420초 머물다 나가고 3~20초 뒤 다시 들어온다. 접속 인원은 고유 계정 수이고 계정당 퀴즈방 하나라, 페르소나 수(44)가 전체 방 합계의 상한이다(11/10 16:20 16→40 슬롯으로 증설 — 3개 방 기준 방당 10명 이상). 방마다 인기 가중치가 달라 인원이 고르지 않고, 새로 연 방에 한동안 사람이 몰린다.
- **실제 풀이는 계정당 방 하나에 한 번(409)** 이라, 미풀이 계정을 기간 끝까지 고르게 나눠 쓴다(방당 대략 1~2시간에 한 명). 발표 중 투표 분포(votes)가 더 자주 움직이길 원하면 `SOLVE_PACE` 를 0.2~0.3으로 낮춰 파드를 다시 띄운다(대신 미풀이 계정이 일찍 바닥난다).
- 좋아요: 방문 한 번에 12% 확률(계정당 한 번). 작성자 본인은 자기 방을 풀지도 누르지도 않는다.
- ⚠ **인기 탭(`/popular`)은 매주 월요일 00:10 확정분**이다. 이번 주에 만든 방은 11/16 확정 전까지 인기 탭에 안 뜬다. 발표 때는 **전체 목록·상세의 좋아요 수와 풀이 중 인원**으로 보여 줘야 한다. (11/16 확정 때 상위 방 작성자 페르소나에게 500포인트가 지급된다 — 무해.)

## 3. 조절 손잡이 (pod-*.yaml 의 env → 파드 지우고 다시 apply)
| 바꾸고 싶은 것 | 파드 | env |
|---|---|---|
| 밤에도 돌리기 | 전부 | `ACTIVE_HOURS` "0-24" |
| 채팅 인원 변동 더 크게 | chatbot | `STAY_SEC` 줄이기(예 "20,90") |
| 채팅 더 많이 | chatbot | `MSG_INTERVAL` 줄이기(예 "2.5") |
| 커뮤니티 반응 더 빨리 | communitybot | `BUZZ_SEC` "5,20" |
| 퀴즈방 인원 더 많이 | quizbot | `SOLVERS` 늘리기(페르소나 수가 상한) |

스크립트만 고쳤으면 1-3 의 `demo1110-scripts` 를 다시 만들고 해당 파드만 지웠다 다시 apply 한다. 모든 봇은 재시작해도 이어서 돈다(시드 글을 또 쌓지 않고, 이미 있는 퀴즈방을 제목으로 찾아 쓰고, 이미 푼 계정을 DB 에서 읽는다).

## 4. 관찰
```bash
kubectl -n victoryfairy logs chatbot | grep '📊' | tail          # 응원·채팅 202, 실패 코드
kubectl -n victoryfairy logs quizbot | grep '📊' | tail          # 방별 인원/풀이/좋아요
kubectl -n victoryfairy logs communitybot | grep '📊' | tail
kubectl -n victoryfairy logs livegame | grep -E '상태 전이|오류' | tail
```
Grafana(https://grafana.victoryfairy.com)에서 chat-app·quiz-app·user-app 파드 수·CPU. 봇 규모는 지난 회차(70명)보다 작다(44명).

## 5. 종료·정리 (11/15 23:59 이후)
봇은 END_AT 에 스스로 끝나 `Completed` 로 남는다.
```bash
kubectl -n victoryfairy delete pod -l app=demo1110
kubectl -n victoryfairy delete configmap demo1110-scripts demo1110-sql demo1110-img-a demo1110-img-b demo1110-img-c demo1110-img-d
```
경기 행·글·퀴즈방은 남겨 둔다(지난 회차와 같은 방침). 지워야 하면:
- 경기: `games-1110-1115.sql` 상단의 `DELETE … IN (...)` — **`LIKE 'DEMO%'` 로 지우지 말 것**(10월 데모 행까지 지워진다)
- 커뮤니티 글: `pod-communitybot.yaml` 의 `MODE` 를 `cleanup` 으로 바꿔 한 번 실행(11/10 이후 페르소나 글만 서버 경로로 삭제)
