# 스케줄 인벤토리

정기 실행되는 것 전부. **새 스케줄을 만들면 여기 한 줄을 추가한다** — 어디서
무엇이 도는지 한곳에서 보이지 않으면, 산출물이 비었을 때 어느 잡을 봐야
하는지부터 헤매게 된다.

시각은 전부 KST 기준이고 괄호 안이 실제 등록값(UTC)이다.

> **2026-08-07 현재, 아래 넷은 아직 안 돈다** — `kbo-records`, `game-schedule`,
> `export-game-result`, `export-player-profile`. terraform
> 정의는 PR #171에 있지만 `quiz_source_jobs_enabled` 기본값이 `false`이고, 배포
> 이미지의 `handler.py`가 아직 이 `job` 값들을 모른다(모르는 job은 예외 없이 빈
> summary만 내므로 지금 켜면 "매일 성공하는데 산출물은 없는" 룰이 된다). 컷오버
> 순서는 이미지 재배포 → `quiz_source_jobs_enabled = true` → `vf-local-test-*`
> 그림자 룰 2개 폐기. 상세는 `VictoryFairy_Infra/collector-lambda/README.md`
> "퀴즈 원천 잡 컷오버".

## 하루 흐름

```
03:00  game                   크롤 → 오늘/어제 경기 일정·결과·중계
03:30  records                완료 경기 → games / game_lineups (RDB)
04:00  export-game-result     games → S3 question-source/game_result/
06:00  vf-wiki-builder        위키 갱신 → WIKI 리포 dev 커밋   (화·금)
07:00  kbo-records            KBO 기록실 스냅샷 → S3
08:00  games-sync-morning     당일 예정경기 상태 선반영 → games (RDB)
08:30  game-schedule          당일 예정경기 → S3 question-source/game_schedule/
08:50  vf-quiz-daily          퀴즈 후보 → S3 quiz-candidates/ + 통계·casebook → WIKI dev
11:00  registrations          KBO 1군 등록명단 → players (RDB)
11:30  export-player-profile  players → S3 question-source/player_profile/
17:00~23:50  games-sync-live  경기 상태 폴링(10분) → games (RDB)
  ―    community              커뮤니티 증분 크롤
```

순서에 의미가 있다. `vf-quiz-daily`(08:50)는 `game-schedule`(08:30)이 오늘
경기를 올려둔 뒤라야 예측 문항을 만들 수 있고, `vf-wiki-builder`(06:00)보다
뒤에 있어야 그날 갱신된 위키를 읽는다. `export-game-result`(04:00)는
`records`(03:30)가 RDB에 넣은 완료 경기를 읽으므로 그 뒤여야 한다.

`export-player-profile`만 퀴즈보다 **뒤**에 있다. `registrations`(11:00)가 갱신한
`players` 를 읽어야 하는데 등록 변동은 오전 늦게 확정되기 때문이다. 퀴즈 루틴은
이 docType을 "가장 최신 파티션 하나"로 읽으므로 전날 것을 쓰게 된다 — 스냅샷
성격이라 하루 지연은 무해하지만, **당일 신규 등록 선수는 다음 날 퀴즈부터
반영된다**는 뜻이다.

두 루틴 모두 `VictoryFairy_WIKI`의 `dev`에 커밋한다. 건드리는 파일이 다르고
(빌더는 `players/`·`graph.json`, 퀴즈는 `stats/`·`_meta/`) 시각도 겹치지 않아
충돌은 사실상 없지만, 양쪽 절차 모두 푸시 거부 시 `pull --rebase` 후 1회
재시도한다.

## 등록 위치

| 스케줄 | 무엇 | 실행체 | 정의 위치 |
|---|---|---|---|
| `community` | 커뮤니티 증분 크롤 | Lambda | `VictoryFairy_Infra/collector-lambda/schedules.tf` |
| `game` | 일정·결과·중계 크롤 | Lambda | 〃 |
| `kbo-records` | KBO 기록실 스냅샷 | Lambda | 〃 |
| `game-schedule` | 당일 예정경기 export | Lambda | 〃 |
| `records` | 완료 경기 → RDB 적재 | Lambda | `VictoryFairy_Infra/collector-lambda/lambda_db.tf` |
| `registrations` | 1군 등록명단 → RDB | Lambda | 〃 |
| `games-sync-*` | 당일 경기 상태 → RDB | Lambda | 〃 |
| `export-game-result` | 경기결과 export | Lambda | 〃 |
| `export-player-profile` | 선수 프로필 export | Lambda | 〃 |
| `vf-quiz-daily` | 퀴즈 후보 생성 | Claude Code 루틴 | `VictoryFairy_AI/deploy/routines/vf-quiz-daily.prompt.md` |
| `vf-wiki-builder` | 위키 문서 갱신 | Claude Code 루틴 | `VictoryFairy_AI/deploy/routines/vf-wiki-builder.prompt.md` |

전부 이 리포에서 관리한다 — 위키 리포에는 위키 문서만 둔다.

**GitHub Actions 스케줄은 없다.** 루틴 세션이 GitHub에 직접 푸시할 수 있으므로
(2026-08-06 `/web-setup`으로 계정 GitHub 자격증명에 write 확보) 위키 갱신은
루틴이 곧바로 커밋한다. 그 전까지 있던 `wiki-sync` 워크플로 — S3 `wiki-outbox/`를
`dev`에 대신 커밋하고 S3 `wiki/` 읽기 캐시를 역동기화하던 다리 — 는 함께 폐기했다.
S3에 위키 사본을 두지 않으므로 `vf-wiki-mirror-gha` IAM 역할과 배포 키도 필요 없다.

## 루틴 프롬프트를 고칠 때

Claude Code 루틴 2개는 프롬프트가 곧 코드다. **정본은 리포의 `.prompt.md`
파일이고 claude.ai 콘솔은 복사본이다** — 콘솔에서 직접 고치면 이력도 리뷰도
남지 않는다. 실제로 2026-08-04에 콘솔 프롬프트가 문서의 안전 경고를 무시하라고
지시하는 상태였고, 루틴 세션이 그 지시를 수상하게 여겨 보고해서야 발견됐다.

## 여기 없는 것

- **`export-player-meme`** — 퀴즈 원천 4종 중 이것만 크론이 없다. 원본이 사람이
  손으로 쓰는 `py-collector/config/memes.yaml` 이고 그 파일은 이미지에 구워지므로
  (Dockerfile 의 `COPY config/`), 내용이 바뀌는 계기는 "시드 수정 → `main` 머지 →
  이미지 재배포" 뿐이다. 소비자도 매일 도는 퀴즈가 아니라 주 2회 도는 위키
  빌더 하나다. 시드를 고쳤을 때만 `kbo-collector-db` 를 1회 직접 부른다 —
  명령은 `VictoryFairy_Infra/collector-lambda/lambda_db.tf` 주석 참고.

- **EKS CronJob** — 2026-08-03 Bedrock 러너 스펙 시절의 `deploy/runner/
  cronjob-quiz.yaml`이 있었으나 실행체가 클라우드 루틴으로 확정되며 폐기했다
  (스펙 §9). 퀴즈는 EKS가 아니라 루틴에서 돈다.
- **로컬 크론** — `py-collector/deploy/local/`의 launchd·crontab 스크립트는
  fmkorea 크롤 전용 수동 도구다(fmkorea가 AWS IP를 차단해 주거용 IP에서만
  돌기 때문). 상시 운영 스케줄이 아니라 사람이 필요할 때 돌린다.
