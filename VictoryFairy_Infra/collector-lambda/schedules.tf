# --- Community crawl: every 10 minutes ---
resource "aws_cloudwatch_event_rule" "community" {
  name                = "${var.name}-community"
  description         = "KBO community crawl (incremental, new posts only)"
  schedule_expression = var.community_schedule
  tags                = var.tags
}

resource "aws_cloudwatch_event_target" "community" {
  rule  = aws_cloudwatch_event_rule.community.name
  arn   = aws_lambda_function.this.arn
  input = jsonencode({ job = "community" })
}

resource "aws_lambda_permission" "community" {
  statement_id  = "AllowEventBridgeCommunity"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.this.function_name
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.community.arn
}

# --- Game data (schedule -> results -> relays): daily at 03:00 KST ---
resource "aws_cloudwatch_event_rule" "game" {
  name                = "${var.name}-game"
  description         = "KBO schedule/result/relay for the day (03:00 KST)"
  schedule_expression = var.game_schedule
  tags                = var.tags
}

resource "aws_cloudwatch_event_target" "game" {
  rule  = aws_cloudwatch_event_rule.game.name
  arn   = aws_lambda_function.this.arn
  input = jsonencode({ job = "game" })
}

resource "aws_lambda_permission" "game" {
  statement_id  = "AllowEventBridgeGame"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.this.function_name
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.game.arn
}

# --- 퀴즈 원천 잡 (kbo_records / game_schedule) ---

resource "aws_cloudwatch_event_rule" "kbo_records" {
  count               = var.quiz_source_jobs_enabled ? 1 : 0
  name                = "${var.name}-kbo-records"
  description         = "KBO 기록실 스냅샷 -> S3 kbo-records/ (07:00 KST)"
  schedule_expression = var.kbo_records_schedule
  tags                = var.tags
}

resource "aws_cloudwatch_event_target" "kbo_records" {
  count = var.quiz_source_jobs_enabled ? 1 : 0
  rule  = aws_cloudwatch_event_rule.kbo_records[0].name
  arn   = aws_lambda_function.this.arn
  input = jsonencode({ job = "kbo_records" })
}

resource "aws_lambda_permission" "kbo_records" {
  count         = var.quiz_source_jobs_enabled ? 1 : 0
  statement_id  = "AllowEventBridgeKboRecords"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.this.function_name
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.kbo_records[0].arn
}

# 위 "game" 잡(schedule/result/relay, 끝난 경기 대상)과는 방향이 반대인 별개 잡 —
# 이건 그날 아직 시작 전인 경기를 내다본다. 그래서 날짜 앵커도 KST 다.
resource "aws_cloudwatch_event_rule" "game_schedule" {
  count               = var.quiz_source_jobs_enabled ? 1 : 0
  name                = "${var.name}-game-schedule"
  description         = "당일(KST) 예정경기 -> S3 question-source/ (08:30 KST)"
  schedule_expression = var.game_schedule_export_schedule
  tags                = var.tags
}

resource "aws_cloudwatch_event_target" "game_schedule" {
  count = var.quiz_source_jobs_enabled ? 1 : 0
  rule  = aws_cloudwatch_event_rule.game_schedule[0].name
  arn   = aws_lambda_function.this.arn
  input = jsonencode({ job = "game_schedule" })
}

resource "aws_lambda_permission" "game_schedule" {
  count         = var.quiz_source_jobs_enabled ? 1 : 0
  statement_id  = "AllowEventBridgeGameSchedule"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.this.function_name
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.game_schedule[0].arn
}
