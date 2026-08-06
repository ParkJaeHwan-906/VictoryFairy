# DB 적재 잡(records/registrations) 전용 Lambda — S3 잡 함수와 같은 이미지, 별도 함수.
#
# 왜 함수를 분리하나:
# - 운영 MySQL(데이터 EC2)은 VPC 안에서만 접근 가능 → 이 함수만 vpc_config 로 프라이빗
#   서브넷에 배치. 외부 API(네이버/KBO)는 프라이빗 라우트의 NAT 로 나간다(이미 존재).
# - S3 잡 함수까지 VPC 에 넣으면 커뮤니티 잡(10분 주기) 트래픽 전부가 NAT 처리 요금을
#   물고, DB 자격증명이 필요 없는 함수에도 퍼진다.
#
# db_subnet_ids 가 비어 있으면(기본) 여기 리소스는 전부 count=0 — 기존 스택 무변경.

locals {
  db_enabled = length(var.db_subnet_ids) > 0
}

# Lambda ENI 에 붙는 SG. 아웃바운드만 필요(DB 3306 + NAT 경유 HTTPS).
resource "aws_security_group" "db_lambda" {
  count       = local.db_enabled ? 1 : 0
  name        = "${var.name}-db-lambda-sg"
  description = "kbo-collector DB-job Lambda ENIs (egress only)"
  vpc_id      = var.db_vpc_id

  tags = merge(var.tags, { Name = "${var.name}-db-lambda-sg" })
}

# aws_security_group 은 생성 시 AWS 기본 allow-all egress 를 제거하므로 명시적으로 연다.
resource "aws_vpc_security_group_egress_rule" "db_lambda_all" {
  count             = local.db_enabled ? 1 : 0
  security_group_id = aws_security_group.db_lambda[0].id
  ip_protocol       = "-1"
  cidr_ipv4         = "0.0.0.0/0"
  description       = "DB 3306 + external APIs via NAT"

  tags = var.tags
}

# 데이터 EC2(MySQL) SG 에 "이 Lambda SG 로부터 3306 허용" 인바운드를 단다.
# 참조 SG 방식이라 IP 가 아니라 역할 단위 — infra 의 mysql_ingress_sg_ids 와 같은 패턴.
# ⚠ infra 소유 SG 에 이 스택이 규칙 하나를 추가하는 것 — infra 쪽 terraform 은 규칙을
#   별도 리소스로 관리하므로 충돌하지 않지만, dev_infra 팀과 공유할 것.
resource "aws_vpc_security_group_ingress_rule" "mysql_from_db_lambda" {
  count                        = local.db_enabled ? 1 : 0
  security_group_id            = var.db_ingress_sg_id
  referenced_security_group_id = aws_security_group.db_lambda[0].id
  from_port                    = 3306
  to_port                      = 3306
  ip_protocol                  = "tcp"
  description                  = "MySQL 3306 from kbo-collector DB-job Lambda"

  tags = merge(var.tags, { Name = "${var.name}-mysql-from-db-lambda" })
}

# vpc_config 로 ENI 를 만들려면 실행 롤에 ENI 관리 권한이 필요하다.
resource "aws_iam_role_policy_attachment" "vpc" {
  count      = local.db_enabled ? 1 : 0
  role       = aws_iam_role.lambda.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaVPCAccessExecutionRole"
}

resource "aws_lambda_function" "db" {
  count         = local.db_enabled ? 1 : 0
  function_name = "${var.name}-db"
  role          = aws_iam_role.lambda.arn
  package_type  = "Image"
  image_uri     = "${aws_ecr_repository.this.repository_url}@${data.aws_ecr_image.deployed.image_digest}"
  architectures = [var.architecture]
  memory_size   = var.memory_mb
  timeout       = var.timeout_s
  tags          = var.tags

  vpc_config {
    subnet_ids         = var.db_subnet_ids
    security_group_ids = [aws_security_group.db_lambda[0].id]
  }

  environment {
    variables = {
      COLLECTOR_DB_HOST      = var.db_host
      COLLECTOR_DB_PORT      = tostring(var.db_port)
      COLLECTOR_DB_NAME      = var.db_name
      COLLECTOR_DB_USER      = var.db_user
      COLLECTOR_DB_PASSWORD  = var.db_password
      COLLECTOR_TARGETS_FILE = "/var/task/config/targets.yaml"
      JOURNAL_DIR            = "/tmp/journal" # Lambda's only writable path
      # records/registrations 는 S3/마스킹을 안 쓰지만 Settings 가 필수값으로 요구한다
      # (설정 로딩용). export 잡은 실제로 이 값으로 S3 에 쓴다 — 이 함수는 S3 잡 함수와
      # 같은 aws_iam_role.lambda 를 쓰고, 그 롤엔 iam.tf 의 s3-landing 인라인 정책
      # (PutObject/GetObject/ListBucket, 버킷 전체)이 붙어 있어 추가 권한이 필요 없다.
      COLLECTOR_S3_BUCKET = var.data_bucket_name
      COLLECTOR_S3_REGION = var.region
      COLLECTOR_PII_SALT  = local.pii_salt
    }
  }

  lifecycle {
    precondition {
      condition     = var.db_vpc_id != "" && var.db_ingress_sg_id != "" && var.db_host != "" && var.db_password != ""
      error_message = "db_subnet_ids 를 설정했으면 db_vpc_id / db_ingress_sg_id / db_host / db_password 도 채워야 합니다 (tfvars)."
    }
  }

  depends_on = [
    aws_iam_role_policy_attachment.vpc,
  ]
}

# DB 적재는 자연키 upsert 라 재실행이 무해 — 실패 시 1회만 재시도.
resource "aws_lambda_function_event_invoke_config" "db" {
  count                  = local.db_enabled ? 1 : 0
  function_name          = aws_lambda_function.db[0].function_name
  maximum_retry_attempts = 1
}

# --- records: 완료 경기 -> games/game_lineups (매일 03:30 KST) ---
resource "aws_cloudwatch_event_rule" "records" {
  count               = local.db_enabled ? 1 : 0
  name                = "${var.name}-records"
  description         = "Finished-game records -> prod MySQL (03:30 KST)"
  schedule_expression = var.records_schedule
  tags                = var.tags
}

resource "aws_cloudwatch_event_target" "records" {
  count = local.db_enabled ? 1 : 0
  rule  = aws_cloudwatch_event_rule.records[0].name
  arn   = aws_lambda_function.db[0].arn
  input = jsonencode({ job = "records" })
}

resource "aws_lambda_permission" "records" {
  count         = local.db_enabled ? 1 : 0
  statement_id  = "AllowEventBridgeRecords"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.db[0].function_name
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.records[0].arn
}

# --- registrations: KBO 1군 등록명단 -> players (매일 11:00 KST) ---
resource "aws_cloudwatch_event_rule" "registrations" {
  count               = local.db_enabled ? 1 : 0
  name                = "${var.name}-registrations"
  description         = "KBO 1-gun roster -> prod MySQL players (11:00 KST)"
  schedule_expression = var.registrations_schedule
  tags                = var.tags
}

resource "aws_cloudwatch_event_target" "registrations" {
  count = local.db_enabled ? 1 : 0
  rule  = aws_cloudwatch_event_rule.registrations[0].name
  arn   = aws_lambda_function.db[0].arn
  input = jsonencode({ job = "registrations" })
}

resource "aws_lambda_permission" "registrations" {
  count         = local.db_enabled ? 1 : 0
  statement_id  = "AllowEventBridgeRegistrations"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.db[0].function_name
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.registrations[0].arn
}

# --- games_sync: 당일 경기 상태 -> games (아침 1회 + 경기 시간대 폴링) ---
#
# records(완료 경기 박스스코어)와 달리 취소·예정·진행 상태를 따라간다. 한 잡에
# 스케줄이 둘이라 for_each 로 묶었다 — 룰/타깃/권한 3종을 두 벌 복붙하면 한쪽만
# 고치는 사고가 난다.
locals {
  games_sync_schedules = local.db_enabled ? {
    # 당일 SCHEDULED 를 미리 깔아둔다.
    morning = var.games_sync_morning_schedule
    # 경기 시간대에 LIVE/종료/취소를 따라간다.
    live = var.games_sync_live_schedule
  } : {}
}

resource "aws_cloudwatch_event_rule" "games_sync" {
  for_each            = local.games_sync_schedules
  name                = "${var.name}-games-sync-${each.key}"
  description         = "당일(KST) 경기 상태 -> prod MySQL games (${each.key})"
  schedule_expression = each.value
  tags                = var.tags
}

resource "aws_cloudwatch_event_target" "games_sync" {
  for_each = aws_cloudwatch_event_rule.games_sync
  rule     = each.value.name
  arn      = aws_lambda_function.db[0].arn
  input    = jsonencode({ job = "games_sync" })
}

resource "aws_lambda_permission" "games_sync" {
  for_each      = aws_cloudwatch_event_rule.games_sync
  statement_id  = "AllowEventBridgeGamesSync${title(each.key)}"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.db[0].function_name
  principal     = "events.amazonaws.com"
  source_arn    = each.value.arn
}

# --- export(game_result): games/game_lineups -> S3 question-source/ (매일 04:00 KST) ---
#
# records(03:30) 가 그날 경기를 적재한 뒤라야 의미가 있어 그 다음에 둔다. S3 에 쓰는
# 잡이지만 원본이 MySQL 이라 -db 함수 소관이다(위 environment 주석의 IAM 근거 참고).
# quiz_source_jobs_enabled 게이트 이유는 schedules.tf 의 같은 이름 변수 주석 참고.
resource "aws_cloudwatch_event_rule" "export_game_result" {
  count               = local.db_enabled && var.quiz_source_jobs_enabled ? 1 : 0
  name                = "${var.name}-export-game-result"
  description         = "game_result envelope -> S3 question-source/ (04:00 KST)"
  schedule_expression = var.export_game_result_schedule
  tags                = var.tags
}

resource "aws_cloudwatch_event_target" "export_game_result" {
  count = local.db_enabled && var.quiz_source_jobs_enabled ? 1 : 0
  rule  = aws_cloudwatch_event_rule.export_game_result[0].name
  arn   = aws_lambda_function.db[0].arn
  input = jsonencode({ job = "export", target = "game_result" })
}

resource "aws_lambda_permission" "export_game_result" {
  count         = local.db_enabled && var.quiz_source_jobs_enabled ? 1 : 0
  statement_id  = "AllowEventBridgeExportGameResult"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.db[0].function_name
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.export_game_result[0].arn
}
