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
      # player_meme export 의 시드 파일. 크론은 없지만(아래 "export(player_meme)" 주석)
      # 수동 invoke 로 도는 경로라 필요하다. Settings 기본값이 상대경로
      # ("config/memes.yaml")라 Lambda 의 cwd 에 의존하게 되므로 절대경로로 못 박는다.
      COLLECTOR_MEMES_FILE = "/var/task/config/memes.yaml"
      JOURNAL_DIR          = "/tmp/journal" # Lambda's only writable path
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

# --- games_sync: 경기 일정·상태 -> games ---
#
# records(완료 경기 박스스코어)와 달리 취소·예정·진행 상태를 따라간다. 같은 잡을
# 세 주기로 부르되 훑는 구간이 다르다 — days 가 붙은 룰은 "일정 선적재"(오늘~+N일),
# 붙지 않은 룰은 "상태 추적"(당일). 룰/타깃/권한 3종을 여러 벌 복붙하면 한쪽만
# 고치는 사고가 나므로 for_each 로 묶는다.
#
# ⚠ days 를 모르는 이미지(핸들러에 games_sync 구간 분기가 없는 버전)로 롤백해도
#   안전하다 — 모르는 이벤트 키는 무시되어 당일치 동기화로 조용히 내려앉는다.
locals {
  games_sync_rules = local.db_enabled ? {
    # 아침에 앞으로의 일정을 미리 깔아둔다(구장 포함).
    morning = { schedule = var.games_sync_morning_schedule, days = var.games_sync_lookahead_days }
    # 경기 시간대에 LIVE/종료/취소를 따라간다 — 당일만.
    live = { schedule = var.games_sync_live_schedule, days = 0 }
    # 라이브 윈도가 닫힌 직후 일정을 다시 받는다(순연·편성 변경 반영).
    nightly = { schedule = var.games_sync_nightly_schedule, days = var.games_sync_lookahead_days }
  } : {}
}

resource "aws_cloudwatch_event_rule" "games_sync" {
  for_each            = local.games_sync_rules
  name                = "${var.name}-games-sync-${each.key}"
  description         = "KBO 경기 일정·상태 -> prod MySQL games (${each.key})"
  schedule_expression = each.value.schedule
  tags                = var.tags
}

resource "aws_cloudwatch_event_target" "games_sync" {
  for_each = local.games_sync_rules
  rule     = aws_cloudwatch_event_rule.games_sync[each.key].name
  arn      = aws_lambda_function.db[0].arn
  # days 를 아예 빼야 핸들러의 당일치 기본 경로를 탄다 (days=0 을 실어 보내도 결과는
  # 같지만, 라이브 룰의 이벤트에 선적재용 키가 보이면 읽는 사람이 오해한다).
  input = jsonencode(merge(
    { job = "games_sync" },
    each.value.days > 0 ? { days = each.value.days } : {},
  ))
}

resource "aws_lambda_permission" "games_sync" {
  for_each      = aws_cloudwatch_event_rule.games_sync
  statement_id  = "AllowEventBridgeGamesSync${title(each.key)}"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.db[0].function_name
  principal     = "events.amazonaws.com"
  source_arn    = each.value.arn
}

# --- cancel_reasons: KBO 일정표 취소 사유 -> games.cancel_reason (매일 01:00 KST) ---
#
# games_sync 가 상태(CANCELED)까지만 알려주는 걸 보완한다 — 네이버는 취소를
# "경기취소" 로만 주고 사유가 없어서, 이 잡만 KBO 공식 일정표를 긁는다.
#
# 시각이 01:00 KST 인 이유: 00:30 의 games_sync(일정 선적재)가 그날 경기 행을
# 만들어 둔 뒤라야 UPDATE 가 걸릴 행이 있다. 그 전에 돌면 갱신 0건으로 헛돈다.
#
# ⚠ cancel_reasons_enabled 기본값이 false 인 이유는 선행 조건이 둘이기 때문이다:
#   1. games.cancel_reason 컬럼 (dev_be) — 없으면 UPDATE 가 Unknown column 으로
#      실패한다. 컬럼은 user 앱의 ddl-auto=update 가 기동 시 만든다.
#   2. 배포 이미지의 handler.py 가 cancel_reasons 잡을 알아야 한다 (dev_ai).
#      모르는 job 은 예외 없이 빈 summary 만 내고 끝나 실패로도 안 드러난다.
# 둘 다 확인한 뒤 tfvars 에서 true 로 올린다 — quiz_source_jobs_enabled 와 같은 절차.
locals {
  cancel_reasons_enabled = local.db_enabled && var.cancel_reasons_enabled
}

resource "aws_cloudwatch_event_rule" "cancel_reasons" {
  count               = local.cancel_reasons_enabled ? 1 : 0
  name                = "${var.name}-cancel-reasons"
  description         = "KBO 일정표 취소 사유 -> prod MySQL games.cancel_reason (01:00 KST)"
  schedule_expression = var.cancel_reasons_schedule
  tags                = var.tags
}

resource "aws_cloudwatch_event_target" "cancel_reasons" {
  count = local.cancel_reasons_enabled ? 1 : 0
  rule  = aws_cloudwatch_event_rule.cancel_reasons[0].name
  arn   = aws_lambda_function.db[0].arn
  # months 를 주지 않으면 잡이 KST 오늘 기준으로 고른다(그 달 + 사흘 전의 달).
  input = jsonencode({ job = "cancel_reasons" })
}

resource "aws_lambda_permission" "cancel_reasons" {
  count         = local.cancel_reasons_enabled ? 1 : 0
  statement_id  = "AllowEventBridgeCancelReasons"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.db[0].function_name
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.cancel_reasons[0].arn
}

# --- export(game_result): games/game_lineups -> S3 question-source/ (매일 04:00 KST) ---
#
# records(03:30) 가 그날 경기를 적재한 뒤라야 의미가 있어 그 다음에 둔다. S3 에 쓰는
# 잡이지만 원본이 MySQL 이라 -db 함수 소관이다(위 environment 주석의 IAM 근거 참고).
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

# --- export(player_profile): players -> S3 envelope (매일 11:30 KST, registrations 11:00 이후) ---
# 퀴즈 루틴은 question-source/player_profile/ 의 "가장 최신 파티션 하나"만 읽는다
# (question-gen/ROUTINE.md).
resource "aws_cloudwatch_event_rule" "export_player_profile" {
  count               = local.db_enabled && var.quiz_source_jobs_enabled ? 1 : 0
  name                = "${var.name}-export-player-profile"
  description         = "player_profile envelope export -> S3 (11:30 KST)"
  schedule_expression = var.export_player_profile_schedule
  tags                = var.tags
}

resource "aws_cloudwatch_event_target" "export_player_profile" {
  count = local.db_enabled && var.quiz_source_jobs_enabled ? 1 : 0
  rule  = aws_cloudwatch_event_rule.export_player_profile[0].name
  arn   = aws_lambda_function.db[0].arn
  input = jsonencode({ job = "export", target = "player_profile" })
}

resource "aws_lambda_permission" "export_player_profile" {
  count         = local.db_enabled && var.quiz_source_jobs_enabled ? 1 : 0
  statement_id  = "AllowEventBridgeExportPlayerProfile"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.db[0].function_name
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.export_player_profile[0].arn
}

# --- export(player_meme) 는 일부러 크론이 없다 ---
#
# 원본이 사람이 손으로 쓰는 시드 파일(config/memes.yaml)이고, 그 파일은 이미지에
# 구워진다(Dockerfile 의 COPY config/). 즉 내용이 바뀌는 계기는 "시드를 고쳐
# main 에 머지 -> 이미지 재배포" 뿐이고, 크론이 매일 돈다고 새 밈이 생기지 않는다.
# 2026-08-07 기준 시드는 선수 2명·밈 2개짜리 스텁이고 이관 커밋 외 편집 이력이 없다.
#
# 소비자도 매일 도는 퀴즈가 아니라 주 2회 도는 위키 빌더 하나뿐이다
# (wiki-builder/ROUTINE.md 2단계 — 선수 문서의 밈 시드로 읽는다).
#
# 시드를 고쳤을 때만 -db 함수를 1회 직접 부른다:
#   aws lambda invoke --function-name kbo-collector-db \
#     --cli-binary-format raw-in-base64-out \
#     --payload '{"job":"export","target":"player_meme"}' /dev/stdout
# 시드가 커져 사람이 자주 채워 넣게 되면 그때 크론을 다시 검토한다.
