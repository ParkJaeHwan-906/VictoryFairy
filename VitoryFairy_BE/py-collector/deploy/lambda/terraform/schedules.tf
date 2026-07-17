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
