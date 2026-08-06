# 스케줄 인벤토리

정기 실행되는 것 전부. **새 스케줄을 만들면 여기 한 줄을 추가한다** — 어디서
무엇이 도는지 한곳에서 보이지 않으면, 산출물이 비었을 때 어느 잡을 봐야
하는지부터 헤매게 된다.

시각은 전부 KST 기준이고 괄호 안이 실제 등록값(UTC)이다.

## 하루 흐름

```
03:00  game            크롤 → 오늘/어제 경기 일정·결과·중계
03:30  records         완료 경기 → games / game_lineups (RDB)
06:00  vf-wiki-builder 위키 갱신분 → S3 wiki-outbox/   (화·금)
07:00  kbo-records     KBO 기록실 스냅샷 → S3
07:30  wiki-sync       outbox → WIKI dev 커밋 → S3 캐시 (화·금)
08:30  game-schedule   당일 예정경기 → S3
08:50  vf-quiz-daily   퀴즈 후보 생성 → S3 quiz-candidates/
11:00  registrations   KBO 1군 등록명단 → players (RDB)
  ―    community       커뮤니티 증분 크롤
```

순서에 의미가 있다. `vf-quiz-daily`(08:50)는 `game-schedule`(08:30)이 오늘
경기를 올려둔 뒤라야 예측 문항을 만들 수 있고, `wiki-sync`(07:30)는
`vf-wiki-builder`(06:00)가 outbox에 반출한 뒤라야 커밋할 게 있다.

## 등록 위치

| 스케줄 | 무엇 | 실행체 | 정의 위치 |
|---|---|---|---|
| `community` | 커뮤니티 증분 크롤 | Lambda | `VictoryFairy_Infra/collector-lambda/schedules.tf` |
| `game` | 일정·결과·중계 크롤 | Lambda | 〃 |
| `kbo-records` | KBO 기록실 스냅샷 | Lambda | 〃 |
| `game-schedule` | 당일 예정경기 export | Lambda | 〃 |
| `records` | 완료 경기 → RDB 적재 | Lambda | 〃 |
| `registrations` | 1군 등록명단 → RDB | Lambda | 〃 |
| `vf-quiz-daily` | 퀴즈 후보 생성 | Claude Code 루틴 | `VictoryFairy_AI/deploy/routines/vf-quiz-daily.prompt.md` |
| `vf-wiki-builder` | 위키 문서 갱신 | Claude Code 루틴 | `VictoryFairy_AI/deploy/routines/vf-wiki-builder.prompt.md` |
| `wiki-sync` | outbox → 위키 커밋·캐시 | GitHub Actions | `.github/workflows/wiki-sync.yml` (**`main` 브랜치**) |

전부 이 리포에서 관리한다 — 위키 리포에는 위키 문서만 둔다(2026-08-06 이관,
PR #153).

`wiki-sync`만 `main` 브랜치에 있는 이유는 **GitHub Actions `schedule`이 기본
브랜치의 워크플로만 발화**하기 때문이다. `dev_ai`나 feature 브랜치에 두면
크론이 돌지 않는다. 이 워크플로를 고칠 때는 `main` 대상 PR을 따로 올려야 한다.

남의 리포(`VictoryFairy_WIKI`)에 커밋해야 하므로 인증이 두 겹이다:

| 대상 | 인증 |
|---|---|
| AWS S3 | OIDC — `vf-wiki-mirror-gha` 역할, 신뢰 정책이 이 리포 `main` sub 허용 |
| 위키 리포 쓰기 | 배포 키 `secrets.WIKI_DEPLOY_KEY` (기본 `GITHUB_TOKEN`은 자기 리포 전용) |

배포 키를 쓴 이유는 PAT와 달리 리포 하나에만 묶이고, 만료 관리가 없고,
개인 계정에 종속되지 않기 때문이다. 키를 교체할 때는 `VictoryFairy_WIKI`
Settings → Deploy keys(write 허용)와 이 리포의 시크릿을 함께 바꾼다.

## 루틴 프롬프트를 고칠 때

Claude Code 루틴 2개는 프롬프트가 곧 코드다. **정본은 리포의 `.prompt.md`
파일이고 claude.ai 콘솔은 복사본이다** — 콘솔에서 직접 고치면 이력도 리뷰도
남지 않는다. 실제로 2026-08-04에 콘솔 프롬프트가 문서의 안전 경고를 무시하라고
지시하는 상태였고, 루틴 세션이 그 지시를 수상하게 여겨 보고해서야 발견됐다.

## 여기 없는 것

- **EKS CronJob** — 2026-08-03 Bedrock 러너 스펙 시절의 `deploy/runner/
  cronjob-quiz.yaml`이 있었으나 실행체가 클라우드 루틴으로 확정되며 폐기했다
  (스펙 §9). 퀴즈는 EKS가 아니라 루틴에서 돈다.
- **로컬 크론** — `py-collector/deploy/local/`의 launchd·crontab 스크립트는
  fmkorea 크롤 전용 수동 도구다(fmkorea가 AWS IP를 차단해 주거용 IP에서만
  돌기 때문). 상시 운영 스케줄이 아니라 사람이 필요할 때 돌린다.
