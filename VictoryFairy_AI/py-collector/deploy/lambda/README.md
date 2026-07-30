# Lambda 배포 (EventBridge 스케줄 크롤링 + DB 적재)

호출한 만큼만 과금되는 서버리스 자동 수집. EventBridge 스케줄이 **같은 이미지를 쓰는
두 개의 Lambda 함수**를 호출합니다.

`kbo-collector` (S3 잡, VPC 밖):
- **community** — `rate(10 minutes)` → `{"job":"community"}` : 새 글만 증분 수집
- **game** — `cron(0 18 * * ? *)`(03:00 KST) → `{"job":"game"}` : schedule→result→relay
  - 03:00 KST에 돌면 전날 저녁(KST) 끝난 경기가 대상 — 그 시각 UTC 날짜가 곧 경기 KST 날짜라 자동 정합.

`kbo-collector-db` (DB 잡, VPC 안 — `db_subnet_ids` 설정 시에만 생성):
- **records** — `cron(30 18 * * ? *)`(03:30 KST) → `{"job":"records"}` : 완료 경기 → games/game_lineups
- **registrations** — `cron(0 2 * * ? *)`(11:00 KST) → `{"job":"registrations"}` : KBO 1군 등록명단 → players

함수를 나눈 이유: 운영 MySQL(데이터 EC2)은 VPC 안에서만 접근되므로 DB 잡 함수만
프라이빗 서브넷에 배치하고(외부 API는 기존 NAT로 아웃바운드), S3 잡 함수는 VPC 밖에
그대로 둬서 10분 주기 커뮤니티 트래픽이 NAT 요금을 물지 않게 + DB 자격증명을 격리.

핸들러(`handler.py`)는 코어(`kbo_collector.run.land_*`)를 그대로 호출하는 얇은 어댑터입니다.
`lxml` 네이티브 의존성 때문에 **컨테이너 이미지**(ECR)로 배포합니다.

## 배포 = 머지 (CI 자동)

- **크롤러 코드 배포**: py-collector 변경이 **dev_ai 에 머지되면**
  `.github/workflows/collector-image.yml` 이 이미지 빌드(arm64) → ECR `:latest` 푸시 →
  두 함수 코드 갱신까지 자동으로 합니다. 손댈 것 없음. (docs/·*.md 변경은 빌드 안 함)
- **인프라 변경**(스케줄·환경변수·메모리·SG 등): dev_infra 브랜치의
  [`VictoryFairy_Infra/collector-lambda/`](../../../VictoryFairy_Infra/collector-lambda/)
  스택에서 — 그쪽도 dev_infra 머지 시 CI 가 terraform apply 합니다. ECR 이미지가 두
  파이프라인의 유일한 접점이라 서로를 몰라도 됩니다.
- `handler.py`/`Dockerfile`/`requirements.txt`/`build_and_push.sh` 는 여기(dev_ai) 소유 —
  이미지 내용을 바꾸는 수정은 여기서 합니다.

## 수동 실행 / 백필

배포된 함수는 어디서든 AWS CLI 로 직접 호출할 수 있습니다(테라폼 불필요):

```bash
# S3 잡
aws lambda invoke --function-name kbo-collector \
  --payload '{"job":"community"}' --cli-binary-format raw-in-base64-out /dev/stdout
aws lambda invoke --function-name kbo-collector \
  --payload '{"job":"game"}' --cli-binary-format raw-in-base64-out /dev/stdout   # 백필은 "date" 추가

# DB 잡
aws lambda invoke --function-name kbo-collector-db \
  --payload '{"job":"registrations"}' --cli-binary-format raw-in-base64-out /dev/stdout
# 시즌 백필 (구간이 길면 타임아웃 840s — 보름~한 달 단위로 나눠 호출)
aws lambda invoke --function-name kbo-collector-db \
  --payload '{"job":"records","from":"2026-03-28","to":"2026-04-15"}' \
  --cli-binary-format raw-in-base64-out --cli-read-timeout 900 /dev/stdout

# 적재 확인
aws s3 ls s3://victoryfairy-crawl-local/community/ --recursive | tail
aws logs tail /aws/lambda/kbo-collector-db --follow
```

> DB 적재는 자연키 upsert 라 재실행·중복 호출이 무해합니다.

## DB 잡 참고사항

- 서비스 테이블(teams/players/games/game_lineups 등) 생성은 **dev_be 소관**(domain JPA
  엔티티가 원천) — py-collector 는 DDL 사본을 갖지 않습니다.
- registrations 는 KBO 공식 사이트를 긁습니다. AWS IP 차단 없음은 실측 확인됨(2026-07) —
  응답의 `registrations` 가 `[]` 이 아니면 정상.
- 구 수집기 스키마가 남아 있는 DB 라면 `../sql/migrate-legacy-collector.sql` 1회 실행
  (teams 가 team_code PK 면 구 스키마).

## 참고
- 크롤링 플로우: [`../../docs/crawl-flow.md`](../../docs/crawl-flow.md)
- Lambda/EventBridge는 **호출한 만큼만** 과금이라 상시 운영에 가장 저렴합니다.
