# 클로드 루틴 직접 실행 설계 — 퀴즈 생성기·위키 빌더 실행체 확정 (A안)

> 상태: 확정 (2026-08-04). 이 문서는
> [2026-08-03 Bedrock 러너 설계](2026-08-03-bedrock-quiz-runner-design.md)를 **대체**한다.
> 선행 스펙 [2026-07-28 LLM 위키+퀴즈 설계](2026-07-28-llm-wiki-quiz-generation-design.md)의
> 데이터 계약(S3 경로·envelope·quiz-candidates JSON)·결정적 스크립트·프롬프트
> 문서·카탈로그는 그대로 유지되며, §5에서 난이도·점수 필드가 추가된다.

## 1. 배경: 2026-08-04 실측으로 확정된 사실

실행체 논의는 두 번 뒤집혔다: 클라우드 루틴 → (AWS 접근 실패 판정) → Bedrock
러너 구현 → (아래 재검증) → **클라우드 루틴으로 회귀·확정**.

| # | 실험 | 결과 |
|---|---|---|
| 1 | 루틴 프롬프트에 AWS 키 평문 주입 (3회: 45분·19분 관찰) | 완전 무산출. **"AWS 이그레스 차단" 결론은 오진이었다** — 기본 Trusted 네트워크 레벨은 `*.amazonaws.com`을 이미 허용 (공식 문서 code.claude.com/docs/en/cloud-environments) |
| 2 | 자격을 **클라우드 환경 환경변수**로 주입 후 동일 테스트 | **발사 55초 만에 성공** — sts get-caller-identity, s3 ls, s3 cp 전부 정상. aws CLI는 세션 VM에 기본 설치돼 있음 (1.45.x) |
| 3 | 루틴 세션에서 git push (기존 브랜치 커밋·신규 브랜치 생성, 2개 환경에서 반복) | 무산출. 원인 규명: 세션 프록시는 푸시를 세션 작업 브랜치(`claude/*`)로만 허용하는데, 리포 룰셋이 `claude/*` 생성을 거부(로컬 재현 확인) → **이 리포에서 세션 git push는 전면 불가** |
| 4 | 루틴 세션에서 GitHub Contents API 쓰기 (gh, 프록시 토큰 주입 경로) | 무산출 — **GitHub 쓰기는 API 경로로도 불가** |

파생 교훈:
- IAM 키 last-used는 집계 지연 때문에 실시간 검증 신호로 쓸 수 없다 (성공한
  테스트에서도 N/A). 판정은 S3 마커 객체로 한다.
- 루틴 세션 트랜스크립트는 API로 조회 불가 — claude.ai/code 브라우저 전용.

**결론**: 실행체 = Claude Code 클라우드 루틴 (구독 포함이라 LLM 한계비용 0,
Bedrock 종량제 회피). 루틴이 산출물을 남길 수 있는 곳은 S3뿐이므로 **위키도
S3 `wiki/` 유지** (git 이전안 폐기). 자격증명은 환경 환경변수로만 주입한다.

## 2. 아키텍처

```
EventBridge (07:00/08:30 KST)
   └─▶ 수집 Lambda ──▶ S3 victoryfairy-crawl-local (기존 그대로)

claude.ai 루틴 ①: vf-quiz-daily (매일 08:50 KST)
   └─▶ 클라우드 세션: 리포 clone(읽기) + S3 재료 읽기
         → question-gen/ROUTINE.md 절차 수행 (결정적 단계는 runner 모듈 실행)
         → S3 quiz-candidates/{KST날짜}/*.json 업로드
              └─▶ BE 임포터가 RDB 적재 (BE 소유)

claude.ai 루틴 ②: vf-wiki-builder (화·금 06:00 KST)
   └─▶ 클라우드 세션: S3 정제글·기록 읽기 → wiki-builder/ROUTINE.md 절차 수행
         → S3 wiki/players/*.md, graph.json, trending.md 갱신
```

- **클라우드 환경**: `vf-question-creation` (개인 환경). 환경변수 4개 —
  `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, `AWS_DEFAULT_REGION=ap-northeast-2`,
  `S3_BUCKET=victoryfairy-crawl-local`. 네트워크 레벨은 기본(Trusted).
- **IAM**: 사용자 `vf-quiz-routine`, 인라인 정책 `vf-quiz-routine-s3` —
  읽기(List/Get)는 crawl 버킷 전체, 쓰기(Put)는 `quiz-candidates/*`·`wiki/*`만.
- 두 루틴이 같은 환경을 공유한다.

## 3. 보안 규칙

1. **프롬프트에 자격증명 평문 금지** — 실측상 동작하지도 않고(§1-1), 루틴
   설정에 영구 노출된다. 주입은 환경 환경변수로만.
2. 환경변수는 공식적으로 시크릿 저장소가 아니다(문서 명시) — 이 트레이드오프는
   2026-08-04 사용자가 승인했다. 완화: 최소권한 정책(유출 시 피해 = 크롤 데이터
   열람 + 문항/위키 접두사 쓰기), 분기 1회 키 로테이션, CloudTrail 감사.
3. 크롤 데이터(커뮤니티 글)는 프롬프트 인젝션 벡터다 — ROUTINE.md의 기존 검열
   단계(validation 통과분만 사용) 유지, 세션 프롬프트에 "환경변수 값 출력 금지"
   상시 포함.

## 4. AI 호출 — 4콜 유지, 실행 방식만 회귀

콜 구성은 Bedrock 스펙과 동일(C1 작문 Sonnet급 / C2 심사 Haiku급 / C3 위키
병합 Sonnet급 / C4 트렌딩). 차이는 실행 방식: 루틴 세션(에이전트)이 ROUTINE.md
지침에 따라 직접 수행한다. 세션 토큰 낭비를 줄이기 위해 **결정적 단계는
Bedrock 러너에서 구현·테스트 완료된 모듈을 CLI로 재사용**한다:

| 단계 | 실행 |
|---|---|
| 템플릿×엔티티 선택 | `runner/catalog.py` `select_combos` (enabled→needs 가용→편중 회피→라운드로빈) |
| 데이터 바인딩 | `runner/binding.py` (yesterday/recent7d 창, sources 임베드) |
| C1 작문·C2 심사 | 세션이 직접 수행 (rules+casebook 준수) |
| evidence 원문 대조 | `runner/finalize.py` `check_evidence` — C2 판정과 무관하게 항상 실행 |
| 난이도 비율 선별·quizId 부여 | `runner/finalize.py` `select_final`(3/4/2/1)·`assign_and_write` |

기존 금지 유지: LLM은 수치를 계산하지 않고, evidence를 창작하지 않고,
카탈로그를 수정하지 않는다.

## 5. 난이도 산정·획득점수 (신규)

### 5.1 난이도 3층 산정

| 층 | 담당 | 내용 |
|---|---|---|
| 객관 피처 | 결정적 코드 | 대상 유명도(기록실 상위권·주전 여부), 시점(어제 vs 과거), 추론 단계 수, 보기 간 간격 → 기본 등급 |
| 체감 판정 | C2 | 등급별 앵커 예시(verification-pass.md에 추가)에 대조해 재분류. 코드 피처와 2등급 이상 어긋나면 어려운 쪽 채택 또는 탈락 |
| 실측 보정 | RDB 정답률 | BE 적재 후 쌓이는 정답률로 템플릿·유형별 예측-실측 오차를 주기 점검, 피처 가중치·앵커 예시 갱신 (사람 주도) |

### 5.2 획득점수 — 정책 파일 + 결정적 주입

점수는 AI가 정하지 않는다. `question-gen/scoring.yaml`(사람 소유)을 신설:

```yaml
KNOWLEDGE:
  EASY: 10
  NORMAL: 20
  HARD: 40
  VERY_HARD: 70
PREDICTION:
  base: 30   # 적중 배율 등 세부는 BE와 협의 후 확정
```

quizId 부여 단계에서 확정 난이도를 이 테이블에 대조해 주입한다.

### 5.3 계약 변경 — quiz-candidates JSON 추가 필드

```json
{
  "difficulty": "HARD",
  "difficultyBasis": {"feature": "HARD", "judge": "HARD"},
  "points": 40
}
```

BE 퀴즈 테이블 신설 시 `difficulty`·`points` 칼럼과 정답/오답 집계(보정
루프용)를 포함하도록 계약 문서에 명시한다.

## 6. 저장·소비 계약

- **문항**: S3 `quiz-candidates/{KST날짜}/*.json` (기존 경로·envelope 유지 +
  §5.3 필드). 소비: BE 임포터가 RDB 적재 — 적재 방식·주기는 BE 소유.
- **위키**: S3 `wiki/` (기존 계약 그대로 — players/*.md, graph.json,
  trending.md, _meta/).
- **위키 원본 = git (확정·구축 완료 2026-08-04)**: 위키의 진실의 원천은 전용
  리포 [VictoryFairy_WIKI](https://github.com/ParkJaeHwan-906/VictoryFairy_WIKI)의
  **dev 브랜치**다 (main은 워크플로 전용 — schedule은 기본 브랜치에서만 발화).
  루틴(무인) 세션의 GitHub 쓰기는 리포 종류와 무관하게 차단됨이 3회 실측(메인
  리포 push·Contents API·전용 리포 push 전부 무산출)으로 확정됐으므로, 흐름을
  다음처럼 구성한다 (E2E 검증 완료):

  | 단계 | 주체 | 내용 |
  |---|---|---|
  | 읽기 | 위키 빌더 루틴 | `git clone -b dev` 로 기존 위키 읽음 (public 리포 전제) |
  | 쓰기 | 위키 빌더 루틴 | 갱신분을 S3 `wiki-outbox/` 에 상대경로 그대로 업로드 (유일한 반출 통로) |
  | 반영 | Actions `wiki-sync` (화·금 07:30 KST) | outbox → dev 커밋 → dev `wiki/` → S3 `wiki/` 역동기화(퀴즈 루틴 읽기 캐시) → outbox 비움 |
  | 사람 편집 | dev 직접 커밋/PR | 덮어써지지 않음 — 빌더가 다음 실행 때 그 위에서 작업 |

  IAM 롤 `vf-wiki-mirror-gha`(OIDC 무키): `wiki/*`·`wiki-outbox/*` 한정
  읽기/쓰기. S3 `wiki/`는 원본이 아니라 **파생 캐시**로 지위가 바뀐다. 주의:
  리포가 private로 전환되면 루틴의 public clone이 깨지므로, 전환 시 읽기 전용
  PAT를 환경변수로 주입하는 방식으로 교체해야 한다. 기존 dev_wiki 브랜치는
  폐기 — 삭제는 룰셋 제한으로 관리자 소관.

## 7. 스케줄

| 루틴 | KST | cron (UTC) |
|---|---|---|
| vf-quiz-daily | 매일 08:50 | `50 23 * * *` |
| vf-wiki-builder | 화·금 06:00 | `0 21 * * 1,4` |

수집 Lambda(07:00/08:30 KST)보다 퀴즈 루틴(08:50)이 뒤에 오는 순서는 기존과
동일하다.

## 8. 실패 처리 — 기존 규칙 계승

어느 단계든 실패 → 그날 업로드 생략(fail-closed) + 세션 트랜스크립트가 로그.
업로드는 quizId·문서 단위 멱등이라 재실행 안전. 폴백 퀴즈는 BE/어드민 소관.

## 9. Bedrock 러너 산출물 처리

| 처분 | 대상 |
|---|---|
| **유지 (재사용)** | `runner/{config,catalog,binding,finalize}.py` + 해당 tests — §4의 결정적 단계 실행체 |
| **폐기** | `runner/{bedrock_client,generate,judge,main}.py`, `entrypoint-quiz.sh`, `Dockerfile`, `deploy/runner/` (cronjob yaml·IRSA 정책·README) |
| **원복** | `question-gen/ROUTINE.md` 머리의 "Bedrock 러너의 스펙 문서" 안내 블록 → 루틴 실행 지침으로 되돌림 |

폐기 커밋은 구현계획에서 별도 태스크로 다룬다 (2026-08-03 스펙 문서 자체는
결정 기록으로 보존).

## 10. 마이그레이션 순서

1. ~~IAM `vf-quiz-routine` 발급~~ (완료), `vf-question-creation` 환경에
   환경변수 4개 설정 (사용자)
2. 루틴 2개 등록 + 환경 연결 (환경 연결은 claude.ai 루틴 편집 UI에서만 가능)
3. 퀴즈 루틴 1회 스모크: `quiz-candidates/{오늘}/` 생성 확인
4. ROUTINE.md 원복·scoring.yaml 신설·Bedrock 잔재 폐기 커밋
5. (dev 전환 시) 환경변수 `S3_BUCKET=victoryfairy-crawl-dev` 교체 + 키를 dev
   버킷 정책으로 재발급

## 11. 확장: 경기 문항·예측 퀴즈 (2026-08-04 추가, 2026-08-05 용어 확정)

목표: "시청 중 재미". 유저는 응원팀 경기를 보는 동안 그 경기와 관련된 문제를
풀어야 하므로, 생성 단위를 하루 풀(pool)에서 확장해 문항을 두 갈래로 만든다.

### 11.1 경기 문항 / 공통 문항

- **경기 문항** — `gameId`·`teamCodes`가 붙는다. 그 경기를 보는 사람에게만
  노출된다.
- **공통 문항** — `gameId`가 없다(`null`). 누구에게나 노출된다.

묶음을 가리키는 별도 용어는 쓰지 않는다 — 구분은 `gameId` 유무 하나뿐이고,
코드에서도 `d["gameId"] is None`으로 판별한다. 사용자 화면 문구는 "오늘 삼성
경기 퀴즈"(경기 문항) / "오늘의 퀴즈"(공통 문항)로 별도 관리한다.

- 아침 루틴이 오늘 스케줄의 각 경기마다 경기 문항을 생성: 양팀 소속 엔티티만
  바인딩(선수 밈·기록·상대전적·순위), 편중 회피는 팀 내부에서만 적용.
- 물량: `question-gen/config/scoring.yaml`의 `volume`이 정본 — 현재
  `perGame` 12문항 × 5경기 + `common` 20문항 = **일 80문항**(종전 10문항).
  값을 바꾸려면 그 파일만 고치면 되고, 코드·문서에는 숫자를 두지 않는다.
  LLM 한계비용 0(구독)이므로 비용 제약 없음. 세션 소요 상한은 30분 → 60분으로
  완화하고, 초과 시 **경기 단위로** fail-closed(한 경기가 실패해도 나머지 경기
  문항과 공통 문항은 올린다). 그래서 경기 문항을 먼저, 공통 문항을 마지막에
  만든다.
- 선별도 경기 단위다 — `finalize.select_final`이 후보를 `gameId`로 묶어 경기마다
  `perGame` 슬롯을 따로 채운다. 한 경기 재료가 부족해도 다른 경기 몫이 줄지
  않고, 남는 슬롯이 다른 경기로 넘어가지도 않는다.
- **태깅 계약**: 경기 문항은 `gameId`(기존 필드)와 `teamCodes: ["SS","LT"]`
  (신규 필드)를 채운다. BE는 유저 응원팀/시청 경기로 필터해 노출.
- 중복 회피 창(최근 7일)은 팀 단위로 적용한다.

> 2026-08-05 실측: 이 구조로 하루치 159문항(경기 문항 106 + 공통 문항 53)을
> 생성해 근거 원문 대조 게이트를 100% 통과시켰다. 경기당 19~25문항으로,
> 위 초기값(6문항)보다 재료가 훨씬 넉넉하다는 것이 확인됐다.

### 11.2 예측 퀴즈 활성화

원칙: **재료가 아니라 채점지** — 예측 문항은 아침에 생성하고(마감 = 시작
−2h, 기존 규칙 유지), 정답 확정은 이후 수집 데이터로 한다. 라인업 발표 전에
마감되므로 정보 우위 문제도 없다.

| 유형 | 생성 재료 (아침) | 정산 근거 |
|---|---|---|
| 매치업 승부 예측 | game_schedule (기확보) | game_result (익일) |
| 선발 맞대결 예측 | game_schedule의 `awayStarter`/`homeStarter` (파서 보강 필요 — 필드는 기존재) | game_result |
| 라인업 예측형 ("오늘 4번 타자는?") | 최근 라인업 이력 | 당일 17:00 수집 라인업 |

- **정산 잡**: 아침 루틴 시작부에서 어제 예측분을 game_result·라인업과
  대조해 `settlement`를 채워 멱등 재업로드한다. BE는 채점만 담당.
- 라인업 확정 후 초단기 문항(마감 규칙 변경 필요)은 채택하지 않는다 — 마감
  −2h 정책과 충돌.

### 11.3 수집 확장 (PR #106 머지 후 착수 — 파일 충돌 회피)

1. game_result 야간 수집 잡 + EventBridge (예: 23:30 KST) — 정산·어제경기
   템플릿의 선결 조건. #106의 games_sync 재작업 위에 구현한다.
2. 선발투수 파싱: schedule 크롤에서 `awayStarter`/`homeStarter` 채우기
3. 라인업 수집 모드 신규 (17:00 KST, 정산 전용)

### 11.4 quizId 다회 실행 규칙 (예비)

현재는 일 1회 실행이라 불필요. 하루 다회 실행이 생기면 같은 날 파티션의
기존 최대 번호를 이어서 부여한다(충돌 방지).

## 12. 오픈 퀘스천

1. scoring.yaml 수치·PREDICTION 배율 — BE와 협의 (기존 필드명 `pointReward`·
   난이도 라벨 EASY/MEDIUM/HARD/EXPERT 체계에 맞춘다)
2. `teamCodes` 필드 추가 — BE 합의 필요 (§11.1)
3. 경기당 문항 수 초기값 6 → 증량 시점 판단 기준 (C2 통과율·casebook 리듬).
   2026-08-05 수동 생성에서 경기당 19~25문항까지 재료가 나왔으므로, 상한은
   재료가 아니라 사람 검수 처리량이 정한다
4. ~~dev_wiki 미러 채택 여부~~ → 해결: 전용 리포 VictoryFairy_WIKI 미러로 확정
   (§6). 리포 public 상태의 private 전환 여부만 남음
5. 루틴 실패 알림 채널 (Slack 연동 여부)
6. casebook 리포 반영 주기, all-time-records.yaml v0 검수 — 기존 갭 승계
