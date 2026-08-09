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
- **games_sync** — `{"job":"games_sync"}` : 당일 KBO 경기 전부의 상태(SCHEDULED/LIVE/종료/취소) →
  games 동기화. 스케줄 제안(**테라폼 미적용 — 아래 두 줄은 문서 기록용, infra 반영 보류**):
  - 아침 동기화: `cron(0 23 * * ? *)`(08:00 KST) — 당일 SCHEDULED 선반영
  - 경기 시간대: `cron(0/10 8-14 * * ? *)`(17:00~23:50 KST, 10분 간격) — LIVE/종료/취소 반영

함수를 나눈 이유: 운영 MySQL(데이터 EC2)은 VPC 안에서만 접근되므로 DB 잡 함수만
프라이빗 서브넷에 배치하고(외부 API는 기존 NAT로 아웃바운드), S3 잡 함수는 VPC 밖에
그대로 둬서 10분 주기 커뮤니티 트래픽이 NAT 요금을 물지 않게 + DB 자격증명을 격리.

핸들러(`handler.py`)는 코어(`kbo_collector.run.land_*`)를 그대로 호출하는 얇은 어댑터입니다.
`lxml` 네이티브 의존성 때문에 **컨테이너 이미지**(ECR)로 배포합니다.

## 인프라 정의는 여기 없습니다

ECR·Lambda·EventBridge 스케줄·IAM 은 **`VictoryFairy_Infra/collector-lambda`(dev_infra)**
소유입니다. 이 디렉토리는 **이미지에 구워지는 것**만 갖습니다 — `handler.py`,
`Dockerfile`, `requirements.txt`.

| 하고 싶은 것 | 어디 |
|---|---|
| 스케줄 추가·변경, 환경변수, VPC/SG | `VictoryFairy_Infra/collector-lambda/` (dev_infra) |
| 핸들러 잡 추가, 수집 로직 | 여기 + `kbo_collector/` (dev_ai) |
| 이미지 재배포 | 자동 — 아래 "코드 바꾼 뒤 재배포" |

스케줄을 하나 늘리려면 **양쪽이 다 필요합니다**: 핸들러에 `job` 분기(dev_ai) →
이미지 재빌드 → infra 스택에 EventBridge 룰(dev_infra). 핸들러는 모르는 `job` 값에
예외를 내지 않고 빈 summary 만 남기므로, 순서가 뒤집히면 조용히 아무 일도 안 합니다.

## 즉시 한 번 돌려보기 / 확인
```bash
# 수동 1회 실행
aws lambda invoke --function-name kbo-collector \
  --payload '{"job":"community"}' --cli-binary-format raw-in-base64-out /dev/stdout
aws lambda invoke --function-name kbo-collector \
  --payload '{"job":"game"}' --cli-binary-format raw-in-base64-out /dev/stdout
# ↑ 오늘(UTC) 경기; 백필은 payload에 "date" 추가

# DB 잡
aws lambda invoke --function-name kbo-collector-db \
  --payload '{"job":"registrations"}' --cli-binary-format raw-in-base64-out /dev/stdout
# 시즌 백필 (구간이 길면 타임아웃 840s — 보름~한 달 단위로 나눠 호출)
aws lambda invoke --function-name kbo-collector-db \
  --payload '{"job":"records","from":"2026-03-28","to":"2026-04-15"}' \
  --cli-binary-format raw-in-base64-out --cli-read-timeout 900 /dev/stdout
aws lambda invoke --function-name kbo-collector-db \
  --payload '{"job":"games_sync"}' --cli-binary-format raw-in-base64-out /dev/stdout  # 백필은 "date" 추가

# 적재 확인 (버킷은 함수의 COLLECTOR_S3_BUCKET — 현재 victoryfairy-crawl-dev)
aws s3 ls s3://victoryfairy-crawl-dev/community/ --recursive | tail
# 로그
aws logs tail /aws/lambda/kbo-collector --follow
```

## DB 잡 (records / registrations) 켜기

infra 스택의 `config.auto.tfvars` 에 DB 블록(`db_subnet_ids` 등)을 채우면
`kbo-collector-db` 함수 + 스케줄 + SG가 같이 생깁니다(비워두면 아무것도 안 만듦).
서브넷/VPC/데이터 EC2 SG·프라이빗 IP는 `environments/dev` 소유이고, 데이터 EC2 SG에
"Lambda SG → 3306" 인바운드 규칙 하나를 collector 스택이 추가합니다 —
자세한 건 [`VictoryFairy_Infra/collector-lambda/README.md`](../../../../VictoryFairy_Infra/collector-lambda/README.md).

**최초 1회 (배포 전):**

1. **레거시 스키마 마이그레이션** — 구 수집기 테이블(teams 가 team_code PK 면 구 스키마)이
   있으면 운영 MySQL 에 `../sql/migrate-legacy-collector.sql` 을 1회 실행.
   서비스 테이블(teams/players/games/game_lineups 등) 생성은 **dev_be 소관**
   (domain JPA 엔티티 + `VictoryFairy_BE/infra/sql/` 선행 SQL) — py-collector 는 DDL 사본을 갖지 않는다.
2. **KBO 사이트 접근 확인** — registrations 는 KBO 공식 사이트를 긁는다. AWS IP 차단 여부를
   먼저 확인하고, 차단이면 registrations 스케줄만 끄고 로컬에서 돌린다:
   ```bash
   # 응답의 registrations 가 [] 이 아니면 OK
   aws lambda invoke --function-name kbo-collector-db \
     --payload '{"job":"registrations"}' --cli-binary-format raw-in-base64-out /dev/stdout
   ```

**수동 실행 / 백필:**
```bash
aws lambda invoke --function-name kbo-collector-db \
  --payload '{"job":"records"}' --cli-binary-format raw-in-base64-out /dev/stdout
# 시즌 백필 (예: 개막일부터)
aws lambda invoke --function-name kbo-collector-db \
  --payload '{"job":"records","from":"2026-03-28","to":"2026-07-27"}' \
  --cli-binary-format raw-in-base64-out --cli-read-timeout 900 /dev/stdout
# 적재 확인
mysql ... -e 'SELECT COUNT(*) FROM games; SELECT COUNT(*) FROM game_lineups;'
```
> 백필 구간이 길면 Lambda 타임아웃(기본 840s)을 넘을 수 있다 — 한 달 단위 정도로 나눠 호출.
> 적재는 자연키 upsert 라 재실행·중복 호출이 무해하다.

## 코드 바꾼 뒤 재배포 — **CI가 합니다 (2026-08-05~)**

`dev_ai`가 main에 머지되면 `.github/workflows/deploy-collector.yml`이 이미지를 굽고
`kbo-collector`·`kbo-collector-db`를 함께 갱신합니다. **손으로 할 일은 없습니다.**

트리거 경로는 이미지에 실제로 구워지는 것만입니다 — `kbo_collector/**`, `config/**`,
`deploy/lambda/`의 `Dockerfile`·`handler.py`·`requirements.txt`. `tests/`·`docs/`만
고쳤다면 워크플로는 돌지 않습니다(맞는 동작입니다).

```bash
# 지금 도는 이미지가 어느 커밋인지
aws lambda get-function --function-name kbo-collector \
  --query 'Code.{tag:ImageUri,digest:ResolvedImageUri}'
```

> `image_uri`는 `:latest` 태그가 아니라 그 태그가 가리키는 **다이제스트**
> (`@sha256:...`, `data.aws_ecr_image`로 조회)에 고정돼 있습니다. `:latest` 문자열로
> 고정하면 값이 안 바뀌어 Terraform이 갱신을 건너뛰기 때문입니다. CI가 SHA 태그와 함께
> `:latest`도 갱신하는 이유가 이 핀을 맞춰 두기 위함입니다 — SHA만 밀면 다음
> `terraform apply`가 함수를 옛 이미지로 되감습니다.

## 참고
- 인프라 정의·배포·컷오버: [`VictoryFairy_Infra/collector-lambda/README.md`](../../../../VictoryFairy_Infra/collector-lambda/README.md) (dev_infra)
- 크롤링 플로우: [`../../docs/crawl-flow.md`](../../docs/crawl-flow.md)
- Lambda/EventBridge는 **호출한 만큼만** 과금이라 상시 운영에 가장 저렴합니다.
