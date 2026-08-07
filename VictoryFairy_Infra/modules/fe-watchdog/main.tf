# fe-watchdog 모듈: 상시 감시 + FE 자동 롤백 + Slack 알림 + 티켓 발행
#
#   EventBridge(주기) → healthcheck Lambda → CloudWatch 커스텀 지표(FE / BE 모듈별)
#                                              ↓
#                                     알람 (연속 N회 실패)
#                                              ↓
#                                          SNS 토픽
#                                              ↓
#                                     responder Lambda
#                                        ├ FE 알람 → S3 롤백 + GitHub Issue
#                                        └ 항상    → Slack 알림
#
# 배포 워크플로의 스모크 테스트는 배포 직후 수십 초만 본다. 이 모듈은 그 창이 닫힌 뒤를 맡는다.
#
# ⚠ 왜 알람을 거치는가: healthcheck 가 직접 롤백하지 않는다. '연속 N회 실패' 디바운스가 필요하고
#   (단발 네트워크 흔들림에 정상 버전을 되돌리면 자동화가 장애 원인이 된다), 그 판정은 알람의
#   datapoints_to_alarm 이 해준다. 알람이 '상태 전이' 에만 통보한다는 성질도 연속 되감기를 막는
#   1차 방어가 된다.
#
# ⚠ 왜 CloudFront 에러율 알람이 아닌가: FE 번들이 깨져도 S3·CloudFront 는 200 을 준다. JS 오류는
#   브라우저에서 터지므로 서버 측 4xx/5xx 신호가 발생하지 않는다. 그래서 '응답이 왔는지' 가 아니라
#   '올바른 것이 왔는지' 를 단정하는 능동 점검이어야 한다.

data "aws_caller_identity" "current" {}
data "aws_region" "current" {}

locals {
  metric_namespace = "VictoryFairy/Watchdog"
  fe_alarm_name    = "${var.name_prefix}-fe-unhealthy"

  # SSM 파라미터 이름 → ARN. 이름이 "/" 로 시작하든 아니든 동작하도록 접두 슬래시를 정규화한다.
  # 비어 있는 이름은 제외한다 — 미설정 시 그 기능만 생략되고 감시는 계속 돈다.
  secret_param_arns = [
    for name in compact([var.slack_webhook_param, var.github_token_param]) :
    "arn:aws:ssm:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:parameter/${trimprefix(name, "/")}"
  ]
}

# ---------------------------------------------------------------------------
# 1) 알림 관문 — 알람은 스스로 통보하지 못하고 SNS 발행만 할 수 있다
# ---------------------------------------------------------------------------
resource "aws_sns_topic" "alerts" {
  name = "${var.name_prefix}-watchdog"

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-watchdog"
  })
}

# ---------------------------------------------------------------------------
# 2) Lambda 패키징 — zip (컨테이너를 쓰지 않는 이유는 terraform.tf 참고)
# ---------------------------------------------------------------------------
data "archive_file" "healthcheck" {
  type        = "zip"
  source_file = "${path.module}/functions/healthcheck.py"
  output_path = "${path.module}/.build/healthcheck.zip"
}

data "archive_file" "responder" {
  type        = "zip"
  source_file = "${path.module}/functions/responder.py"
  output_path = "${path.module}/.build/responder.zip"
}

# ---------------------------------------------------------------------------
# 3) IAM — 최소 권한 (SKILL §7)
# ---------------------------------------------------------------------------
data "aws_iam_policy_document" "lambda_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "healthcheck" {
  name               = "${var.name_prefix}-watchdog-healthcheck"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume.json
  tags               = var.tags
}

# 지표 발행은 리소스 단위 제한을 지원하지 않아 "*" 가 불가피하다.
# 대신 조건으로 이 함수가 쓰는 네임스페이스로 좁힌다.
data "aws_iam_policy_document" "healthcheck" {
  statement {
    sid       = "PutWatchdogMetrics"
    actions   = ["cloudwatch:PutMetricData"]
    resources = ["*"]

    condition {
      test     = "StringEquals"
      variable = "cloudwatch:namespace"
      values   = [local.metric_namespace]
    }
  }
}

resource "aws_iam_role_policy" "healthcheck" {
  name   = "${var.name_prefix}-watchdog-healthcheck"
  role   = aws_iam_role.healthcheck.id
  policy = data.aws_iam_policy_document.healthcheck.json
}

resource "aws_iam_role_policy_attachment" "healthcheck_logs" {
  role       = aws_iam_role.healthcheck.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

resource "aws_iam_role" "responder" {
  name               = "${var.name_prefix}-watchdog-responder"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume.json
  tags               = var.tags
}

data "aws_iam_policy_document" "responder" {
  # 롤백: 아카이브를 읽어 루트 index.html 과 CURRENT 마커를 쓴다.
  # ⚠ DeleteObject 는 주지 않는다. 롤백은 덮어쓰기만 하면 되고, 권한이 없으면 이 함수만으로는
  #   사이트를 지울 수 없다.
  statement {
    sid       = "ReadReleases"
    actions   = ["s3:GetObject"]
    resources = ["${var.fe_bucket_arn}/*"]
  }

  statement {
    sid       = "ListReleases"
    actions   = ["s3:ListBucket"]
    resources = [var.fe_bucket_arn]
  }

  # 되돌릴 대상은 진입점과 마커뿐이다 — 자산은 루트에 누적돼 있어 손댈 필요가 없다.
  statement {
    sid     = "WriteEntrypointAndMarker"
    actions = ["s3:PutObject"]
    resources = [
      "${var.fe_bucket_arn}/index.html",
      "${var.fe_bucket_arn}/${var.releases_prefix}CURRENT",
    ]
  }

  # Slack 웹훅·GitHub 토큰. 목록이 비면 statement 자체를 만들지 않는다(빈 resources 는 거부됨).
  dynamic "statement" {
    for_each = length(local.secret_param_arns) > 0 ? [1] : []

    content {
      sid       = "ReadSecrets"
      actions   = ["ssm:GetParameter"]
      resources = local.secret_param_arns
    }
  }
}

resource "aws_iam_role_policy" "responder" {
  name   = "${var.name_prefix}-watchdog-responder"
  role   = aws_iam_role.responder.id
  policy = data.aws_iam_policy_document.responder.json
}

resource "aws_iam_role_policy_attachment" "responder_logs" {
  role       = aws_iam_role.responder.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

# ---------------------------------------------------------------------------
# 4) 함수
# ---------------------------------------------------------------------------
resource "aws_cloudwatch_log_group" "healthcheck" {
  name              = "/aws/lambda/${var.name_prefix}-watchdog-healthcheck"
  retention_in_days = var.log_retention_days
  tags              = var.tags
}

resource "aws_lambda_function" "healthcheck" {
  function_name    = "${var.name_prefix}-watchdog-healthcheck"
  role             = aws_iam_role.healthcheck.arn
  runtime          = "python3.13"
  handler          = "healthcheck.handler"
  filename         = data.archive_file.healthcheck.output_path
  source_code_hash = data.archive_file.healthcheck.output_base64sha256

  # 순차로 4~6회 HTTP 를 때린다. 타임아웃 10초 × 여유.
  timeout     = 60
  memory_size = 128

  environment {
    variables = {
      SITE_URL             = var.site_url
      METRIC_NAMESPACE     = local.metric_namespace
      API_TARGETS          = jsonencode(var.api_targets)
      HTTP_TIMEOUT_SECONDS = "10"
    }
  }

  tags = var.tags

  depends_on = [aws_cloudwatch_log_group.healthcheck]
}

resource "aws_cloudwatch_log_group" "responder" {
  name              = "/aws/lambda/${var.name_prefix}-watchdog-responder"
  retention_in_days = var.log_retention_days
  tags              = var.tags
}

resource "aws_lambda_function" "responder" {
  function_name    = "${var.name_prefix}-watchdog-responder"
  role             = aws_iam_role.responder.arn
  runtime          = "python3.13"
  handler          = "responder.handler"
  filename         = data.archive_file.responder.output_path
  source_code_hash = data.archive_file.responder.output_base64sha256

  timeout     = 60
  memory_size = 128

  environment {
    variables = {
      FE_BUCKET                 = var.fe_bucket_name
      RELEASES_PREFIX           = var.releases_prefix
      FE_ALARM_NAME             = local.fe_alarm_name
      SITE_URL                  = var.site_url
      ROLLBACK_COOLDOWN_SECONDS = tostring(var.rollback_cooldown_seconds)
      SLACK_WEBHOOK_PARAM       = var.slack_webhook_param
      GITHUB_TOKEN_PARAM        = var.github_token_param
      GITHUB_REPO               = var.github_repo
      MENTION_USER_IDS          = join(",", var.mention_user_ids)

      # 알람 시점에 직접 때려 실제 증상을 알림에 담기 위해 healthcheck 와 같은 대상을 받는다.
      # CloudWatch 의 NewStateReason 은 "Threshold Crossed: ..." 라 원인을 알려주지 않는다.
      API_TARGETS = jsonencode(var.api_targets)
    }
  }

  tags = var.tags

  depends_on = [aws_cloudwatch_log_group.responder]
}

# ---------------------------------------------------------------------------
# 5) 주기 실행
# ---------------------------------------------------------------------------
resource "aws_cloudwatch_event_rule" "schedule" {
  name                = "${var.name_prefix}-watchdog"
  description         = "FE·BE 상시 점검"
  schedule_expression = var.schedule_expression
  tags                = var.tags
}

resource "aws_cloudwatch_event_target" "schedule" {
  rule = aws_cloudwatch_event_rule.schedule.name
  arn  = aws_lambda_function.healthcheck.arn
}

resource "aws_lambda_permission" "schedule" {
  statement_id  = "AllowEventBridge"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.healthcheck.function_name
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.schedule.arn
}

# ---------------------------------------------------------------------------
# 6) 알람 — 이름이 responder 의 분기 기준이다(FE 만 롤백)
# ---------------------------------------------------------------------------
resource "aws_cloudwatch_metric_alarm" "fe" {
  alarm_name        = local.fe_alarm_name
  alarm_description = "FE 가 연속 ${var.datapoints_to_alarm}회 점검 실패. responder 가 이전 릴리스로 자동 롤백한다."

  namespace   = local.metric_namespace
  metric_name = "FeHealthy"
  statistic   = "Minimum"
  period      = var.alarm_period_seconds

  comparison_operator = "LessThanThreshold"
  threshold           = 1
  evaluation_periods  = var.datapoints_to_alarm
  datapoints_to_alarm = var.datapoints_to_alarm

  # ⚠ missing 이어야 한다. breaching 으로 두면 '지표가 없는 것' 이 곧 롤백 트리거가 된다 —
  #   2026-08-07 최초 apply 직후 실제로 그랬다. 알람이 INSUFFICIENT_DATA → ALARM 으로 튀어
  #   (아직 첫 점검이 돌기 전) 감시가 스스로 롤백을 실행했다. 마침 두 릴리스의 번들이 같아
  #   사용자 영향은 없었지만, 구조적으로는 '감시 자신의 공백' 을 '서비스 장애' 로 오인한 것이다.
  #
  #   감시가 멈춘 것도 물론 알아야 한다. 다만 그 대응은 롤백이 아니라 알림이어야 하므로
  #   아래 stalled 알람으로 분리했다(알람 이름이 다르므로 responder 가 롤백하지 않는다).
  treat_missing_data = "missing"

  alarm_actions = [aws_sns_topic.alerts.arn]
  ok_actions    = [aws_sns_topic.alerts.arn] # 복구도 알린다

  tags = var.tags
}

# 감시 자신이 멈춘 것을 잡는다 — '서비스 장애' 와 다른 사건이므로 알람을 따로 둔다.
# 지표가 아예 안 들어오면(점검 Lambda 실패·EventBridge 중단·IAM 문제) SampleCount 가 없어
# breaching 으로 떨어진다. FE 알람 이름이 아니므로 responder 는 알림만 보낸다.
resource "aws_cloudwatch_metric_alarm" "stalled" {
  alarm_name        = "${var.name_prefix}-watchdog-stalled"
  alarm_description = "점검 지표가 들어오지 않는다 = 감시가 멈췄다. 서비스 장애와 별개이며 롤백하지 않는다. 점검 Lambda 로그를 확인할 것."

  namespace   = local.metric_namespace
  metric_name = "FeHealthy"
  statistic   = "SampleCount"

  # 점검 주기의 3배 창을 본다 — 한 번 거른 것으로 울리지 않게 한다.
  period              = var.alarm_period_seconds * 3
  comparison_operator = "LessThanThreshold"
  threshold           = 1
  evaluation_periods  = 1
  treat_missing_data  = "breaching"

  alarm_actions = [aws_sns_topic.alerts.arn]
  ok_actions    = [aws_sns_topic.alerts.arn]

  tags = var.tags
}

resource "aws_cloudwatch_metric_alarm" "api" {
  for_each = var.api_targets

  alarm_name        = "${var.name_prefix}-api-${each.key}-unhealthy"
  alarm_description = "BE ${each.key} 모듈(${each.value})이 연속 ${var.datapoints_to_alarm}회 점검 실패. FE 롤백은 하지 않고 알림만 보낸다."

  namespace   = local.metric_namespace
  metric_name = "ApiHealthy"
  dimensions  = { Target = each.key }
  statistic   = "Minimum"
  period      = var.alarm_period_seconds

  comparison_operator = "LessThanThreshold"
  threshold           = 1
  evaluation_periods  = var.datapoints_to_alarm
  datapoints_to_alarm = var.datapoints_to_alarm

  # FE 알람과 같은 이유로 missing 이다 — 지표 공백은 stalled 알람이 따로 잡는다.
  treat_missing_data = "missing"

  alarm_actions = [aws_sns_topic.alerts.arn]
  ok_actions    = [aws_sns_topic.alerts.arn]

  tags = var.tags
}

# ---------------------------------------------------------------------------
# 7) SNS → responder
# ---------------------------------------------------------------------------
resource "aws_sns_topic_subscription" "responder" {
  topic_arn = aws_sns_topic.alerts.arn
  protocol  = "lambda"
  endpoint  = aws_lambda_function.responder.arn
}

resource "aws_lambda_permission" "sns" {
  statement_id  = "AllowSNS"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.responder.function_name
  principal     = "sns.amazonaws.com"
  source_arn    = aws_sns_topic.alerts.arn
}
