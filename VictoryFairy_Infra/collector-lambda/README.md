# collector-lambda — KBO 수집기(Lambda) 스택

py-collector(dev_ai, `VictoryFairy_AI/py-collector`)를 운영하는 서버리스 수집 스택.
`VictoryFairy_AI/py-collector/deploy/lambda/terraform` 에서 이관 — **크롤러 코드는
dev_ai, 인프라 정의는 dev_infra** 로 소유를 나눴다.

ECR 리포 하나의 이미지를 Lambda 함수 두 개가 공유한다:

| 함수 | 위치 | 잡 (EventBridge) |
|---|---|---|
| `kbo-collector` | VPC 밖 | community `rate(10 minutes)` · game 03:00 · kbo_records 07:00 · game_schedule 08:30 → S3 적재 |
| `kbo-collector-db` | VPC 안(프라이빗 서브넷) | records 03:30 · export game_result 04:00 · games_sync 00:30+08:00+경기시간대 · registrations 11:00 · export player_profile 11:30 → 운영 MySQL / S3 |

시각은 전부 KST. 하루 순서:

```
00:30  games_sync      일정 선적재 오늘~+7일             (RDB)
01:00  cancel_reasons  KBO 일정표 취소 사유 -> games     (RDB)
03:00  game            일정·결과·중계 크롤              -> S3 raw-json/
03:30  records         완료 경기 -> games/game_lineups   (RDB)
04:00  export          games/game_lineups -> envelope    -> S3 question-source/
07:00  kbo_records     KBO 기록실 스냅샷                 -> S3 kbo-records/
08:00  games_sync      일정 선적재 오늘~+7일             (RDB)
08:30  game_schedule   당일 예정경기                     -> S3 question-source/
11:00  registrations   KBO 1군 등록명단 -> players       (RDB)
17:00~23:59  games_sync   1분 간격, 당일 LIVE/종료/취소 + 선발 라인업  (RDB)
  ―    community       커뮤니티 증분 크롤               -> S3 community/
```

`games_sync` 는 룰이 셋인데 **같은 잡을 구간만 달리해 부른다.** 00:30·08:00 은
`{"days": 7}` 을 실어 오늘~+7일 일정을 미리 깔고(구장 포함), 경기 시간대 폴링은
`days` 없이 당일만 훑는다 — 확정된 과거 경기를 매분 다시 긁지 않기 위해서다.
경기 시간대 폴링이 1분인 건 상태뿐 아니라 **경기 전 선발 라인업**(네이버 preview)도
따라가기 때문이다. 공시는 경기 직전 한 번 뜨므로 주기가 곧 화면 반영 지연이 된다.
윈도 크기는 `games_sync_lookahead_days`(기본 7, 상한 14 = 핸들러 `MAX_SYNC_DAYS`).

00:30 룰이 따로 있는 이유는 순연·편성 변경이 대체로 그날 경기가 끝날 무렵
확정되기 때문이다. 아침 룰만 두면 그 변경이 다음 날 08:00 까지 반영되지 않는다.

`game`(03:00)과 `game_schedule`(08:30)은 이름이 닮았지만 방향이 반대다 — 앞은 끝난
경기(UTC 앵커), 뒤는 그날 아직 시작 전인 경기(KST 앵커)를 본다.

`cancel_reasons`(01:00)는 **취소 "사유"만** 담당한다. 상태(`CANCELED`)는 `games_sync`가
네이버에서 받아오지만 네이버는 취소를 `"경기취소"`로만 알려줘 사유가 없다 — 사유가
적힌 곳은 KBO 공식 일정표뿐이라 이 잡만 KBO를 긁는다. 01:00 인 이유는 그 직전
`games_sync`가 경기 행을 만들어 둔 뒤라야 갱신할 행이 있기 때문이다 — **사유는 경기 행이
없으면 붙지 않는다.** 2026-08-10 실측: 8/07 이전 취소 경기는 행 자체가 없어 30건 중 15건만
붙었고, `games_sync` 백필로 행을 만든 뒤에야 30/30 이 됐다. 룰 순서가 이 선후를 지킨다.
`cancel_reasons_enabled` 는 2026-08-10 에 켰다(근거는 `config.auto.tfvars` 주석).

함수를 나눈 이유, 잡별 상세·수동 실행·백필은
[`VictoryFairy_AI/py-collector/deploy/lambda/README.md`](../../VictoryFairy_AI/py-collector/deploy/lambda/README.md)(dev_ai) 참고.

## 배포 = 머지 (CI)

파이프라인 두 개가 브랜치별로 분리돼 있고, **ECR 이미지가 유일한 접점**이다:

```
main 머지 (py-collector 소스 변경)
  └→ deploy-collector.yml    : docker build(arm64) → ECR :latest+SHA → 두 함수 코드 갱신
dev_infra 머지 (collector-lambda/** 변경)
  └→ collector-terraform.yml : terraform plan → apply   (PR 단계는 fmt/validate 만)
```

- 이 스택은 이미지를 **빌드하지 않는다.** `data.aws_ecr_image` 로 그 시점 `:latest`
  다이제스트를 읽어 함수에 핀할 뿐이라, py-collector 소스 없이(dev_infra 단독
  체크아웃·CI 러너) plan/apply 가 된다.
- CI 의 AWS 로그인은 OIDC 롤 — 장기 액세스키 없음. terraform 쪽은 `ci.tf` 가 만드는
  `kbo-collector-terraform-ci`(dev_infra 신뢰), 이미지 쪽은 `deploy-collector.yml` 이
  쓰는 공용 `victoryfairy-dev-github-actions`(main 신뢰)다. `ci.tf` 의
  `kbo-collector-image-ci` 는 이관 당시 dev_ai 트리거용으로 만든 것으로, 지금 이미지
  파이프라인은 이 롤을 쓰지 않는다 — 정리 대상.
- ⚠ 이미지 갱신 순서: `deploy-collector.yml` 은 SHA 태그와 `:latest` 를 **함께** 민다.
  `:latest` 가 옛 이미지를 가리킨 채 남으면 다음 apply 가 그 다이제스트로 함수를
  되감는다.

## 설정·비밀 관리

| 무엇 | 어디 | 비고 |
|---|---|---|
| state | S3 `victoryfairy-tfstate/collector-lambda/` + DynamoDB 락 | environments/dev 와 같은 버킷 관례 |
| 비밀 아닌 설정 | `config.auto.tfvars` (커밋됨) | 서브넷/SG/db_host 등 — CI·로컬 공용 |
| 비밀 2개 | `db_password`, `pii_salt` | CI: GitHub Secrets `TF_VAR_db_password`·`TF_VAR_pii_salt` / 로컬: gitignore 된 `terraform.tfvars` |

⚠ `pii_salt` 는 커뮤니티 작성자 마스킹의 연속성을 좌우하는 **복구 불가능한 비밀** —
비밀번호 관리자에 백업해 둘 것. state 에도 평문으로 들어가므로 state 버킷 접근 권한이
곧 비밀 접근 권한이다.

## 수동 실행 (부트스트랩·비상용)

평상시엔 필요 없다. 로컬에서 돌릴 일이 있으면:

```bash
cd VictoryFairy_Infra/collector-lambda
cat > terraform.tfvars <<'EOF'   # 비밀 2개만 (나머지는 config.auto.tfvars)
db_password = "..."
pii_salt    = "..."
EOF
terraform init     # 원격 state 자동 연결 (로컬 state 에서 전환 시 -migrate-state)
terraform plan -out=tfplan && terraform apply tfplan
```

새 환경 부트스트랩 순서(이미지가 없으면 `data.aws_ecr_image` 조회가 실패하므로):
ECR 리포만 targeted apply → 이미지 CI 1회(workflow_dispatch) → 전체 apply.

## 다른 스택과의 접점

- **environments/dev 소유 값 입력**: 프라이빗 서브넷 id, VPC id, 데이터 EC2(MySQL) SG id·
  프라이빗 IP 를 `config.auto.tfvars` 로 받는다. 데이터 EC2 재생성 시 `db_host` 와
  k8s Endpoints(`k8s/30-external-data.yaml`) 둘 다 갱신.
- **MySQL SG 에 규칙 추가**: 이 스택이 데이터 EC2 SG 에 "collector Lambda SG → 3306"
  인바운드 규칙 하나를 단다(`lambda_db.tf` 의 `mysql_from_db_lambda`). environments/dev
  쪽에서 같은 규칙을 중복 선언하지 말 것.
- **버킷은 안 만든다**: `data_bucket_name` S3 버킷은 선행 존재해야 한다.

> CI 실행 이력은 GitHub Actions 의 `collector-terraform` 워크플로에서 확인한다.
