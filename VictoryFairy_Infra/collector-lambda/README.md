# collector-lambda — KBO 수집기(Lambda) 스택

py-collector(dev_ai, `VictoryFairy_AI/py-collector`)를 운영하는 서버리스 수집 스택.
`VictoryFairy_AI/py-collector/deploy/lambda/terraform` 에서 이관 — **크롤러 코드는
dev_ai, 인프라 정의는 dev_infra** 로 소유를 나눴다.

ECR 리포 하나의 이미지를 Lambda 함수 두 개가 공유한다:

| 함수 | 위치 | 잡 (EventBridge) |
|---|---|---|
| `kbo-collector` | VPC 밖 | community `rate(10 minutes)` · game 03:00 · kbo_records 07:00 · game_schedule 08:30 → S3 적재 |
| `kbo-collector-db` | VPC 안(프라이빗 서브넷) | export 04:00 · games_sync 08:00+경기시간대 · records 03:30 · registrations 11:00 → 운영 MySQL / S3 |

시각은 전부 KST. 하루 순서:

```
03:00  game            일정·결과·중계 크롤              -> S3 raw-json/
03:30  records         완료 경기 -> games/game_lineups   (RDB)
04:00  export          games/game_lineups -> envelope    -> S3 question-source/
07:00  kbo_records     KBO 기록실 스냅샷                 -> S3 kbo-records/
08:00  games_sync      당일 SCHEDULED 선반영             (RDB)
08:30  game_schedule   당일 예정경기                     -> S3 question-source/
11:00  registrations   KBO 1군 등록명단 -> players       (RDB)
17:00~23:50  games_sync   10분 간격, LIVE/종료/취소 반영  (RDB)
  ―    community       커뮤니티 증분 크롤               -> S3 community/
```

`game`(03:00)과 `game_schedule`(08:30)은 이름이 닮았지만 방향이 반대다 — 앞은 끝난
경기(UTC 앵커), 뒤는 그날 아직 시작 전인 경기(KST 앵커)를 본다.

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

## 퀴즈 원천 잡 컷오버

`kbo_records`·`game_schedule`·`export` 세 잡의 룰은 `quiz_source_jobs_enabled`(기본
`false`) 뒤에 있다. **기본값이 false 인 이유**: 핸들러가 모르는 `job` 값은 예외를 내지
않고 빈 summary 만 남기고 끝난다. 코드가 배포되기 전에 켜면 "매일 성공하는데 산출물은
없는" 룰이 되고, 그건 알람에도 안 걸린다.

또 하나 — 2026-08-06 확인 시점에 이 잡들은 **이미 돌고 있었다.** 다만 terraform 밖이었다:

```
vf-local-test-kbo-records     cron(0 22 * * ? *)   -> kbo-collector-local-test
vf-local-test-game-schedule   cron(30 23 * * ? *)  -> kbo-collector-local-test
```

`var.name` 을 `vf-local-test` 로 준 로컬 apply 의 잔재로 보인다. 장난감이 아니라 퀴즈
파이프라인 원천 데이터를 실제로 생산 중이었다(`kbo-records/*` 가 매일 07:00 에 갱신).
문제는 타깃인 `kbo-collector-local-test` 가 이미지 CI 의 갱신 대상이 아니라 이미지가
2026-08-03 다이제스트에 고정돼 있다는 점 — 코드를 고쳐도 영영 반영되지 않는다.
컷오버는 이 그림자 스택을 걷어내는 작업이기도 하다.

### ⚠ 버킷이 갈려 있다 — 이게 컷오버의 진짜 함정

그림자 스택은 **다른 버킷**에 쓴다:

| | 버킷 | 무엇이 최신인가 |
|---|---|---|
| 정식 함수 (`kbo-collector`, 이 스택) | `victoryfairy-crawl-dev` | community·raw-json 은 매일 갱신, **kbo-records 는 7/30 에 멈춤** |
| 그림자 (`kbo-collector-local-test`) | `victoryfairy-crawl-local` | **kbo-records·question-source/game_schedule 이 여기서만 갱신** |

퀴즈 루틴(`vf-quiz-daily`)의 `S3_BUCKET` 도 `victoryfairy-crawl-local` 이다 —
산출물 `quiz-candidates/` 가 그 버킷에만 있다.

그래서 게이트만 켜면 새 데이터는 `-dev` 로 가는데 퀴즈 루틴은 계속 `-local` 을 읽는다.
**아무 에러 없이 퀴즈 파이프라인이 눈을 감는다.** 아래 3-b 를 건너뛰지 말 것.

**순서를 지켜야 한다** — 켜기 전에 코드가 이미지에 들어가 있어야 한다.

1. 세 잡의 핸들러 분기(`handler.py`)와 소스(`kbo_collector/sources/kbo_records.py` 등)를
   main 에 머지 → `deploy-collector.yml` 이 이미지를 다시 굽는다.
2. 실제로 들어갔는지 확인 — 여기서 no-op 이면 아직이다:
   ```bash
   aws lambda invoke --function-name kbo-collector \
     --payload '{"job":"kbo_records"}' --cli-binary-format raw-in-base64-out /dev/stdout
   ```
3. **버킷 일원화** (게이트 켜기 전에):
   a. 과거분 이관 — `-local` 에만 있는 퀴즈 원천을 `-dev` 로 옮긴다:
      ```bash
      for p in kbo-records question-source; do
        aws s3 sync s3://victoryfairy-crawl-local/$p/ s3://victoryfairy-crawl-dev/$p/
      done
      ```
   b. 퀴즈 루틴의 `S3_BUCKET` 을 `victoryfairy-crawl-dev` 로 변경
      (claude.ai 루틴 설정 — 정본은 `VictoryFairy_AI/deploy/routines/`).
4. `config.auto.tfvars` 에 `quiz_source_jobs_enabled = true` → dev_infra 머지(= apply).
5. 하루 돌려보고 산출물 확인 — **`-dev` 쪽이 갱신돼야 한다**:
   ```bash
   aws s3 ls s3://victoryfairy-crawl-dev/kbo-records/hitter-basic/ | tail -2
   aws s3 ls s3://victoryfairy-crawl-dev/question-source/game_schedule/ | tail -2
   ```
6. **그다음에** 그림자 스택을 지운다. 순서를 바꾸면 그날 데이터에 구멍이 난다:
   ```bash
   for r in vf-local-test-kbo-records vf-local-test-game-schedule; do
     aws events remove-targets --rule "$r" --ids "$(aws events list-targets-by-rule \
       --rule "$r" --query 'Targets[0].Id' --output text)"
     aws events delete-rule --name "$r"
   done
   aws lambda delete-function --function-name kbo-collector-local-test
   ```
   함수를 지우면 전용 IAM 롤(`kbo-collector-local-test`)도 함께 정리한다.

`games_sync` 2 개는 게이트 없이 바로 생성된다 — 핸들러가 이미 지원하므로 apply 즉시 돈다.

## 다른 스택과의 접점

- **environments/dev 소유 값 입력**: 프라이빗 서브넷 id, VPC id, 데이터 EC2(MySQL) SG id·
  프라이빗 IP 를 `config.auto.tfvars` 로 받는다. 데이터 EC2 재생성 시 `db_host` 와
  k8s Endpoints(`k8s/30-external-data.yaml`) 둘 다 갱신.
- **MySQL SG 에 규칙 추가**: 이 스택이 데이터 EC2 SG 에 "collector Lambda SG → 3306"
  인바운드 규칙 하나를 단다(`lambda_db.tf` 의 `mysql_from_db_lambda`). environments/dev
  쪽에서 같은 규칙을 중복 선언하지 말 것.
- **버킷은 안 만든다**: `data_bucket_name` S3 버킷은 선행 존재해야 한다.

> CI 실행 이력은 GitHub Actions 의 `collector-terraform` 워크플로에서 확인한다.
