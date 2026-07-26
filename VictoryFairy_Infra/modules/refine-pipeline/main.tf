# refine-pipeline 모듈: 서버리스 정제 파이프라인 (ARCHITECTURE §4)
#
#   크롤(Lambda, 이 모듈 밖) → S3 community/ → [S3 이벤트] → pattern Lambda
#     → SQS → [이벤트 소스 매핑 batch_size=N] → bedrock Lambda → S3 validation/bedrock/
#
# 설계 요지:
#   - **트리거가 인프라 부품이다.** 폴링 컨트롤러를 직접 짜지 않는다 — S3 이벤트 알림과
#     SQS 이벤트 소스 매핑이 단계 전이를 담당한다.
#   - **SQS 는 선택이 아니라 필수다.** 게시글 1건씩 Bedrock 을 부르면 시스템 프롬프트
#     2,470토큰이 호출마다 붙어 하루 $44.8 로 상한 $30 을 넘긴다. 묶어야 $8.97 이다.
#   - **예산 상한은 DynamoDB 원자적 카운터 + 예약 동시성 1** 두 겹이다. 카운터만으로는
#     동시에 뜬 Lambda 들이 각자 "아직 여유 있음"을 읽고 함께 넘긴다.
#   - **멱등은 S3 `_manifest` 마커**가 담당한다(앱 코드). Lambda 재시도·SQS 재전달에도 안전.

data "aws_caller_identity" "current" {}
data "aws_region" "current" {}

locals {
  pattern_function_name = "${var.name_prefix}-refine-pattern"
  bedrock_function_name = "${var.name_prefix}-refine-bedrock"

  crawl_bucket_arn = "arn:aws:s3:::${var.crawl_bucket_name}"

  # Bedrock Lambda 타임아웃. 한 배치 = 게시글 N개 = 모델 호출 1회(+재시도)라 여유롭다.
  bedrock_timeout_seconds = 300
}

# ─────────────────────────────────────────────────────────────────────────────
# 예산 카운터 — 날짜별 누적 소비액
# ─────────────────────────────────────────────────────────────────────────────
# 구 설계에서는 Redis INCRBYFLOAT 였다. Lambda 는 VPC 밖이라 ClusterIP Redis 에 닿지
# 못하므로 DynamoDB 로 옮겼다. UpdateItem 의 ADD 가 원자적 증분을 보장한다.
#
# 파티션 키를 batch_date 로 두면 **일 단위 리셋이 구조적으로 성립한다** — 날짜가 바뀌면
# 새 아이템이라 별도의 초기화 작업이 없다(구 emptyDir Redis 가 주던 성질과 같다).
resource "aws_dynamodb_table" "budget" {
  name         = "${var.name_prefix}-refine-budget"
  billing_mode = "PAY_PER_REQUEST" # 하루 수천 건 규모라 프로비저닝할 이유가 없다
  hash_key     = "batch_date"

  attribute {
    name = "batch_date"
    type = "S"
  }

  # 오래된 날짜 아이템 자동 정리. 앱이 expires_at(epoch seconds)을 넣는다.
  ttl {
    attribute_name = "expires_at"
    enabled        = true
  }

  point_in_time_recovery {
    # 소비액은 잃어도 재계산 가능(S3 산출물의 usage 로). 비용 대비 이득이 없어 끈다.
    enabled = false
  }

  tags = merge(var.tags, { Name = "${var.name_prefix}-refine-budget" })
}

# ─────────────────────────────────────────────────────────────────────────────
# SQS — 패턴 통과분을 모아 Bedrock 호출 단위로 묶는다
# ─────────────────────────────────────────────────────────────────────────────
resource "aws_sqs_queue" "bedrock_dlq" {
  name                      = "${var.name_prefix}-refine-bedrock-dlq"
  message_retention_seconds = 1209600 # 14일 — 실패분을 사람이 확인할 시간

  tags = merge(var.tags, { Name = "${var.name_prefix}-refine-bedrock-dlq" })
}

resource "aws_sqs_queue" "bedrock" {
  name = "${var.name_prefix}-refine-bedrock"

  # 가시성 타임아웃은 Lambda 타임아웃보다 커야 한다. 작으면 처리 중인 메시지가 다시
  # 배달돼 **같은 게시글을 두 번 판정하고 예산을 두 번 쓴다**(마커가 막아주지만 호출은 이미 나간 뒤다).
  visibility_timeout_seconds = local.bedrock_timeout_seconds * 6

  message_retention_seconds = 345600 # 4일
  receive_wait_time_seconds = 20     # 롱 폴링 — 빈 수신 요청 비용 절감

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.bedrock_dlq.arn
    maxReceiveCount     = 3 # 3회 실패하면 DLQ 로. 무한 재시도로 예산을 태우지 않는다.
  })

  tags = merge(var.tags, { Name = "${var.name_prefix}-refine-bedrock" })
}

# ─────────────────────────────────────────────────────────────────────────────
# IAM — 함수별 최소 권한 (SKILL §7)
# ─────────────────────────────────────────────────────────────────────────────
data "aws_iam_policy_document" "lambda_assume" {
  statement {
    effect  = "Allow"
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

# ── 패턴 Lambda: 크롤 원본을 읽고 패턴 산출물을 쓰고 통과분을 큐에 넣는다 ──
data "aws_iam_policy_document" "pattern" {
  statement {
    sid     = "ReadCrawlInput"
    effect  = "Allow"
    actions = ["s3:GetObject"]
    # 입력 prefix 는 **읽기 전용**이다. 원본을 이동하지 않는 것이 멱등 설계의 전제다.
    resources = ["${local.crawl_bucket_arn}/community/*"]
  }

  statement {
    sid       = "WritePatternOutput"
    effect    = "Allow"
    actions   = ["s3:PutObject", "s3:DeleteObject"]
    resources = ["${local.crawl_bucket_arn}/validation/pattern/*"]
  }

  statement {
    sid       = "CheckMarker"
    effect    = "Allow"
    actions   = ["s3:GetObject", "s3:ListBucket"]
    resources = [local.crawl_bucket_arn, "${local.crawl_bucket_arn}/validation/pattern/*"]
  }

  statement {
    sid       = "EnqueuePassedPosts"
    effect    = "Allow"
    actions   = ["sqs:SendMessage"]
    resources = [aws_sqs_queue.bedrock.arn]
  }
}

# ── Bedrock Lambda: 패턴 산출물을 읽고 모델을 호출하고 예산을 누적한다 ──
data "aws_iam_policy_document" "bedrock" {
  statement {
    sid       = "ReadPatternOutput"
    effect    = "Allow"
    actions   = ["s3:GetObject", "s3:ListBucket"]
    resources = [local.crawl_bucket_arn, "${local.crawl_bucket_arn}/validation/*"]
  }

  statement {
    sid       = "WriteBedrockOutput"
    effect    = "Allow"
    actions   = ["s3:PutObject", "s3:DeleteObject"]
    resources = ["${local.crawl_bucket_arn}/validation/bedrock/*"]
  }

  statement {
    sid       = "ConsumeQueue"
    effect    = "Allow"
    actions   = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes"]
    resources = [aws_sqs_queue.bedrock.arn]
  }

  statement {
    sid       = "TrackSpend"
    effect    = "Allow"
    actions   = ["dynamodb:GetItem", "dynamodb:UpdateItem"]
    resources = [aws_dynamodb_table.budget.arn]
  }

  statement {
    sid     = "InvokeModel"
    effect  = "Allow"
    actions = ["bedrock:InvokeModel"]
    # 모델 하나로 한정한다. 서울 리전 고정도 함께 강제된다 —
    # 조직 SCP 가 이미 서울 외를 거부하지만, 여기서도 좁혀 두면 의도가 코드에 남는다.
    resources = [
      "arn:aws:bedrock:${data.aws_region.current.name}::foundation-model/${var.bedrock_model_id}"
    ]
  }
}

resource "aws_iam_role" "pattern" {
  name               = "${local.pattern_function_name}-role"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume.json
  tags               = merge(var.tags, { Name = "${local.pattern_function_name}-role" })
}

resource "aws_iam_role" "bedrock" {
  name               = "${local.bedrock_function_name}-role"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume.json
  tags               = merge(var.tags, { Name = "${local.bedrock_function_name}-role" })
}

resource "aws_iam_role_policy" "pattern" {
  name   = "${local.pattern_function_name}-policy"
  role   = aws_iam_role.pattern.id
  policy = data.aws_iam_policy_document.pattern.json
}

resource "aws_iam_role_policy" "bedrock" {
  name   = "${local.bedrock_function_name}-policy"
  role   = aws_iam_role.bedrock.id
  policy = data.aws_iam_policy_document.bedrock.json
}

# 로그 쓰기 권한. VPC 밖에서 도는 함수라 VPCAccessExecutionRole 은 필요 없다.
resource "aws_iam_role_policy_attachment" "pattern_logs" {
  role       = aws_iam_role.pattern.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

resource "aws_iam_role_policy_attachment" "bedrock_logs" {
  role       = aws_iam_role.bedrock.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

# ─────────────────────────────────────────────────────────────────────────────
# 로그 그룹 — 함수가 암묵 생성하게 두면 보관 기간이 "무기한"이 된다
# ─────────────────────────────────────────────────────────────────────────────
resource "aws_cloudwatch_log_group" "pattern" {
  name              = "/aws/lambda/${local.pattern_function_name}"
  retention_in_days = var.log_retention_days
  tags              = merge(var.tags, { Name = local.pattern_function_name })
}

resource "aws_cloudwatch_log_group" "bedrock" {
  name              = "/aws/lambda/${local.bedrock_function_name}"
  retention_in_days = var.log_retention_days
  tags              = merge(var.tags, { Name = local.bedrock_function_name })
}

# ─────────────────────────────────────────────────────────────────────────────
# Lambda 함수 2개 — 같은 이미지, CMD 로 핸들러만 갈린다
# ─────────────────────────────────────────────────────────────────────────────
# ⚠ image_uri 가 가리키는 태그가 ECR 에 push 되기 전에는 apply 가 실패한다(plan 은 통과).
#   이미지는 VictoryFairy_AI 의 pipeline/Dockerfile 로 빌드한다(274MB).
resource "aws_lambda_function" "pattern" {
  function_name = local.pattern_function_name
  role          = aws_iam_role.pattern.arn
  package_type  = "Image"
  image_uri     = "${var.pipeline_repository_url}:${var.image_tag}"

  timeout     = 120 # 게시글 1건 · 정규식 판정. 콜드 스타트 여유 포함.
  memory_size = 1024

  # ⚠️ 이미지 아키텍처와 **반드시 일치**해야 한다. 다르면 함수 생성 자체가 실패한다.
  # pipeline/Dockerfile 이 `FROM --platform=linux/arm64` 로 고정돼 있다 —
  # 한쪽만 바꾸면 apply 가 깨진다. arm64(Graviton)는 x86 대비 약 20% 저렴하다.
  architectures = ["arm64"]

  image_config {
    # TODO(앱): 핸들러 경로 확정 후 맞출 것. 러너를 이벤트 핸들러 구조로 바꾸는 작업이 선행된다.
    command = ["pipeline.lambda_pattern.handler"]
  }

  environment {
    variables = {
      S3_BUCKET         = var.crawl_bucket_name
      BEDROCK_QUEUE_URL = aws_sqs_queue.bedrock.url
      LOG_LEVEL         = "INFO"
    }
  }

  depends_on = [aws_cloudwatch_log_group.pattern]

  tags = merge(var.tags, { Name = local.pattern_function_name })
}

resource "aws_lambda_function" "bedrock" {
  function_name = local.bedrock_function_name
  role          = aws_iam_role.bedrock.arn
  package_type  = "Image"
  image_uri     = "${var.pipeline_repository_url}:${var.image_tag}"

  timeout     = local.bedrock_timeout_seconds
  memory_size = 1024

  # 패턴 함수와 같은 이미지를 쓴다 — 아키텍처도 같아야 한다(위 주석 참고).
  architectures = ["arm64"]

  # ⚠ **예산 상한의 두 번째 겹.** DynamoDB 카운터만으로는 동시에 뜬 함수들이 각자
  #   "아직 여유 있음"을 읽고 함께 넘긴다. 1로 묶어 직렬화한다.
  #   처리량이 부족해지면 batch_size 를 키울 것 — 동시성을 올리지 말 것.
  reserved_concurrent_executions = 1

  image_config {
    # TODO(앱): 핸들러 경로 확정 후 맞출 것.
    command = ["pipeline.lambda_bedrock.handler"]
  }

  environment {
    variables = {
      S3_BUCKET               = var.crawl_bucket_name
      BEDROCK_MODEL_ID        = var.bedrock_model_id
      BEDROCK_REGION          = data.aws_region.current.name
      BEDROCK_BATCH_POST_SIZE = tostring(var.bedrock_batch_post_size)
      BEDROCK_SPEND_LIMIT_USD = tostring(var.bedrock_spend_limit_usd)
      BUDGET_TABLE_NAME       = aws_dynamodb_table.budget.name
      # 현행 모델은 프롬프트 캐싱 미지원 — 켜면 전 호출이 AccessDeniedException 으로 죽는다.
      BEDROCK_PROMPT_CACHE = "false"
      LOG_LEVEL            = "INFO"
    }
  }

  depends_on = [aws_cloudwatch_log_group.bedrock]

  tags = merge(var.tags, { Name = local.bedrock_function_name })
}

# ─────────────────────────────────────────────────────────────────────────────
# 트리거 배선
# ─────────────────────────────────────────────────────────────────────────────
resource "aws_lambda_permission" "s3_invoke_pattern" {
  statement_id   = "AllowExecutionFromS3"
  action         = "lambda:InvokeFunction"
  function_name  = aws_lambda_function.pattern.function_name
  principal      = "s3.amazonaws.com"
  source_arn     = local.crawl_bucket_arn
  source_account = data.aws_caller_identity.current.account_id
}

# ⚠ 이 리소스는 **버킷의 알림 설정 전체를 덮어쓴다**(authoritative).
#   버킷 자체는 Terraform 관리 밖이므로, 콘솔에서 다른 알림을 추가하면 다음 apply 때 사라진다.
#   현재 이 버킷에는 알림이 하나도 없음을 확인하고 도입했다.
resource "aws_s3_bucket_notification" "crawl" {
  bucket = var.crawl_bucket_name

  lambda_function {
    lambda_function_arn = aws_lambda_function.pattern.arn
    events              = ["s3:ObjectCreated:*"]
    filter_prefix       = "community/"
    filter_suffix       = ".json"
  }

  depends_on = [aws_lambda_permission.s3_invoke_pattern]
}

# SQS → Bedrock Lambda. batch_size 가 곧 "한 번의 모델 호출에 묶는 게시글 수"다.
resource "aws_lambda_event_source_mapping" "bedrock" {
  event_source_arn = aws_sqs_queue.bedrock.arn
  function_name    = aws_lambda_function.bedrock.arn

  batch_size = var.bedrock_batch_post_size

  # 큐가 한산해도 이 시간까지는 기다렸다가 묶어서 보낸다 — 1건짜리 호출을 줄인다.
  maximum_batching_window_in_seconds = 60

  # 배치 안에서 일부만 실패했을 때 성공분까지 재처리하지 않는다.
  # 재처리 자체는 마커가 막지만, 다시 모델을 부르는 낭비를 없앤다.
  function_response_types = ["ReportBatchItemFailures"]

  scaling_config {
    # 예약 동시성 1과 짝을 이룬다. 폴러가 동시에 여러 배치를 밀어 넣지 않게 한다.
    maximum_concurrency = 2
  }
}
