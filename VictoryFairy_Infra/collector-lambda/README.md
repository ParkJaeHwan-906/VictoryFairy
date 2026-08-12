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

## 퀴즈 원천 잡 컷오버

`kbo_records`·`game_schedule`·`export`(docType 2종 = `game_result`·`player_profile`)
네 룰은 `quiz_source_jobs_enabled`(기본 `false`) 뒤에 있다.
**기본값이 false 인 이유**: 핸들러가 모르는 `job` 값은 예외를 내지 않고 빈 summary 만
남기고 끝난다. 코드가 배포되기 전에 켜면 "매일 성공하는데 산출물은 없는" 룰이 되고,
그건 알람에도 안 걸린다.

네 번째 docType 인 `player_meme` 은 일부러 크론이 없다 — 원본이 손으로 쓰는 시드
파일이라 이미지 재배포 말고는 바뀔 계기가 없다. 이유와 수동 invoke 명령은
`lambda_db.tf` 의 "export(player_meme) 는 일부러 크론이 없다" 주석 참고.

실제로 2026-08-07 에 배포 이미지(8/6 다이제스트)를 직접 찔러 확인했다 — 두 잡 모두
입력만 되돌아오고 결과 키가 없다. 이미지가 코드보다 오래됐다는 증거다:

```
kbo-collector    {"job":"game_schedule","date":"2026-08-11"}
  -> {"job": "game_schedule", "date": "2026-08-11"}   # gameSchedule 키 없음
kbo-collector-db {"job":"export","target":"game_result"}
  -> {"job": "export", "date": "2026-08-06"}          # exported 키 없음
```

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

### ⚠ 버킷이 갈려 있었다 — 이게 컷오버의 진짜 함정이었다 (2026-08-07 해소)

그림자 스택은 **다른 버킷**에 쓴다:

| | 버킷 | 무엇이 최신이었나 (8/6 확인 시점) |
|---|---|---|
| 정식 함수 (`kbo-collector`, 이 스택) | `victoryfairy-crawl-dev` | community·raw-json 은 매일 갱신, **kbo-records 는 7/30 에 멈춤** |
| 그림자 (`kbo-collector-local-test`) | `victoryfairy-crawl-local` | **kbo-records·question-source/game_schedule 이 여기서만 갱신** |

퀴즈 루틴(`vf-quiz-daily`)의 `S3_BUCKET` 도 `victoryfairy-crawl-local` 이었다 —
산출물 `quiz-candidates/` 가 그 버킷에만 있었다.

그래서 게이트만 켜면 새 데이터는 `-dev` 로 가는데 퀴즈 루틴은 계속 `-local` 을 읽어,
**아무 에러 없이 퀴즈 파이프라인이 눈을 감는** 상태였다.

**2026-08-07 에 아래 3번(버킷 일원화)을 먼저 처리했다** — 절차는 기록으로 남긴다:

- 3-a 과거분 이관 완료. `kbo-records` 8/1~8/5(40건)와 `question-source`
  `game_result/2026-08-01`·`game_schedule/2026-08-04`~`08-05`(25건)를 `-dev` 로 복사했다.
  `kbo-records/*/2026-08-06.json` 만 제외했다 — 양쪽에 다 있는데 `-dev` 쪽이 현재
  코드로 만든 스냅샷이라 그림자(8/3 고정 이미지)의 것으로 덮지 않았다.
- 3-b 루틴 `S3_BUCKET` → `victoryfairy-crawl-dev` 변경 완료. 같은 날 실행부터
  `quiz-candidates/2026-08-07/` 이 `-dev` 에 생성됐다(20건 — 직전 `-local` 실행은 2건).
  위키 빌더 쪽은 `wiki-builder/ROUTINE.md` 의 버킷 가드로 막았다(`-local` 이면 강제 전환).
- `-dev` 의 `question-source` 도 함께 메웠다 — `game_result` 499건(3/28~8/4),
  `player_profile` 558건. 둘 다 export 크론이 없어 수동 적재분에 멈춰 있었다.

**남은 것은 1·2번(이미지)과 4번(게이트)뿐이다.** 순서를 지켜야 한다 — 켜기 전에
코드가 이미지에 들어가 있어야 한다.

1. 세 잡의 핸들러 분기(`handler.py`)와 소스(`kbo_collector/sources/kbo_records.py` 등)를
   main 에 머지 → `deploy-collector.yml` 이 이미지를 다시 굽는다.
2. 실제로 들어갔는지 확인 — **응답에 결과 키가 있어야 한다.** 없으면 아직이다:
   ```bash
   aws lambda invoke --function-name kbo-collector \
     --payload '{"job":"kbo_records"}' --cli-binary-format raw-in-base64-out /dev/stdout
   #  아직    -> {"job": "kbo_records", "date": "..."}
   #  배포됨  -> {"job": "kbo_records", "date": "...", "kboRecords": {"loaded": 8, ...}}

   aws lambda invoke --function-name kbo-collector-db \
     --payload '{"job":"export","target":"game_result"}' \
     --cli-binary-format raw-in-base64-out /dev/stdout
   #  아직    -> {"job": "export", "date": "..."}
   #  배포됨  -> {"job": "export", "date": "...", "exported": 499}
   ```
   StatusCode 는 어느 쪽이든 200 이다 — **모르는 job 은 예외를 내지 않는다.**
   2026-08-07 에 실제로 두 함수 다 결과 키 없이 돌아오는 것을 확인했다.
3. ~~**버킷 일원화**~~ — **2026-08-07 완료** (위 절 참고). 다시 할 필요 없다.
   a. ~~과거분 이관~~ — `-local` 에만 있던 퀴즈 원천을 `-dev` 로 옮겼다:
      ```bash
      for p in kbo-records question-source; do
        aws s3 sync s3://victoryfairy-crawl-local/$p/ s3://victoryfairy-crawl-dev/$p/
      done
      ```
   b. ~~퀴즈 루틴의 `S3_BUCKET` 을 `victoryfairy-crawl-dev` 로 변경~~
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

`games_sync` 3 개는 게이트 없이 바로 생성된다 — 핸들러가 이미 지원하므로 apply 즉시 돈다.
`days` 를 모르는 이미지로 롤백해도 안전하다: 모르는 이벤트 키는 무시되어 세 룰 모두
당일치 동기화로 조용히 내려앉을 뿐, 실패하지 않는다.

### 취소 사유 잡 컷오버 (`cancel_reasons_enabled`)

`quiz_source_jobs_enabled` 와 같은 절차인데, 선행 조건이 **둘**이라 하나만 봐서는 안 된다.

1. **`games.cancel_reason` 컬럼** (dev_be) — 없으면 잡의 UPDATE 가 `Unknown column` 으로
   매일 실패한다. 컬럼은 `user` 앱의 `ddl-auto=update` 가 기동 시 만든다(1회성 DDL 불필요).
2. **잡을 아는 이미지** (dev_ai) — 모르는 `job` 값은 예외 없이 빈 summary 만 내고 끝나
   `StatusCode 200` 이 나오므로, 이걸 확인하지 않으면 "매일 성공하는데 아무것도 안 하는"
   룰이 된다.

둘 다 끝났는지는 수동 호출 한 번으로 같이 확인된다 — 응답에 `cancelReasons` 키가 있고
컬럼이 없으면 여기서 에러가 난다:

```bash
aws lambda invoke --function-name kbo-collector-db \
  --cli-binary-format raw-in-base64-out \
  --payload '{"job":"cancel_reasons","months":["2026-08"]}' out.json && cat out.json
# {"job": "cancel_reasons", "date": "...", "cancelReasons": 30}
```

확인 뒤 `config.auto.tfvars` 의 `cancel_reasons_enabled = true` 로 올린다.

## 다른 스택과의 접점

- **environments/dev 소유 값 입력**: 프라이빗 서브넷 id, VPC id, 데이터 EC2(MySQL) SG id·
  프라이빗 IP 를 `config.auto.tfvars` 로 받는다. 데이터 EC2 재생성 시 `db_host` 와
  k8s Endpoints(`k8s/30-external-data.yaml`) 둘 다 갱신.
- **MySQL SG 에 규칙 추가**: 이 스택이 데이터 EC2 SG 에 "collector Lambda SG → 3306"
  인바운드 규칙 하나를 단다(`lambda_db.tf` 의 `mysql_from_db_lambda`). environments/dev
  쪽에서 같은 규칙을 중복 선언하지 말 것.
- **버킷은 안 만든다**: `data_bucket_name` S3 버킷은 선행 존재해야 한다.

> CI 실행 이력은 GitHub Actions 의 `collector-terraform` 워크플로에서 확인한다.
