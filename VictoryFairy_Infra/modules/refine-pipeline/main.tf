# refine-pipeline 모듈: 서버리스 정제 파이프라인 (ARCHITECTURE §4)
#
#   크롤(Lambda, 이 모듈 밖) → S3 community/ → [S3 이벤트] → pattern Lambda
#     → SQS → [이벤트 소스 매핑 batch_size=N] → bedrock Lambda → S3 validation/bedrock/

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
# SQS — 이닝 종료 이벤트. BE quiz-app(이 모듈 밖)이 소비해 퀴즈 정산을 트리거한다
# ─────────────────────────────────────────────────────────────────────────────
# py-collector(이 모듈 밖)가 이닝 종료 시 crawl 버킷에
#   inning-events/{date}/{gameId}/{inning}-{half}.json 을 쓰면
# 아래 aws_s3_bucket_notification.crawl 의 queue 알림이 이 큐로 SendMessage 한다.
#
# 이 모듈은 큐와 S3→SQS 배선만 소유한다. 소비자는 EKS 의 BE quiz-app 이라
# Lambda 이벤트 소스 매핑이 없다 — 수신 권한(ReceiveMessage 등)은 modules/quiz-irsa 가
# IRSA 역할에 부여한다(이 모듈 출력 inning_events_queue_arn 을 그 모듈에 넘긴다).
resource "aws_sqs_queue" "inning_events_dlq" {
  name                      = "${var.name_prefix}-refine-inning-events-dlq"
  message_retention_seconds = 1209600 # 14일 — 실패분을 사람이 확인할 시간

  tags = merge(var.tags, { Name = "${var.name_prefix}-refine-inning-events-dlq" })
}

resource "aws_sqs_queue" "inning_events" {
  name = "${var.name_prefix}-refine-inning-events"

  # 소비자가 BE quiz-app(Spring, 폴링)이라 Lambda 타임아웃처럼 참조할 처리 시간이 없다.
  # 정산 처리가 길어질 가능성을 감안해 SQS 기본값(30초)보다 넉넉히 잡는다. 가시성 타임아웃
  # 안에 DeleteMessage 가 안 되면 같은 이닝이 재배달되므로, 소비 쪽 로직은 반드시
  # 멱등(이미 정산된 이닝은 skip)이어야 한다 — 이 큐만으로는 중복 수신을 막지 못한다.
  visibility_timeout_seconds = 60

  message_retention_seconds = 345600 # 4일
  receive_wait_time_seconds = 20     # 롱 폴링 — 빈 수신 요청 비용 절감

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.inning_events_dlq.arn
    maxReceiveCount     = 3 # 3회 실패하면 DLQ 로. 무한 재배달로 중복 정산을 유발하지 않는다.
  })

  tags = merge(var.tags, { Name = "${var.name_prefix}-refine-inning-events" })
}

# S3 → SQS 알림은 Lambda 와 달리 전용 Permission 리소스가 없다 — 큐 정책에서
# s3.amazonaws.com 의 SendMessage 를 직접 허용해야 한다. SourceArn/SourceAccount 조건으로
# "이 버킷에서 온 알림만" 으로 좁혀 다른 계정·버킷이 이 큐에 메시지를 넣지 못하게 막는다.
data "aws_iam_policy_document" "inning_events_queue_policy" {
  statement {
    sid     = "AllowCrawlBucketSendMessage"
    effect  = "Allow"
    actions = ["sqs:SendMessage"]

    principals {
      type        = "Service"
      identifiers = ["s3.amazonaws.com"]
    }

    resources = [aws_sqs_queue.inning_events.arn]

    condition {
      test     = "ArnEquals"
      variable = "aws:SourceArn"
      values   = [local.crawl_bucket_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }
  }
}

resource "aws_sqs_queue_policy" "inning_events" {
  queue_url = aws_sqs_queue.inning_events.id
  policy    = data.aws_iam_policy_document.inning_events_queue_policy.json
}

# ─────────────────────────────────────────────────────────────────────────────
# SQS — 경기 상태 변화 이벤트. BE user-app(이 모듈 밖)이 소비해 경기 SSE 로 푸시한다
# ─────────────────────────────────────────────────────────────────────────────
# py-collector 의 games_sync 라이브 폴링(1분)이 경기의 이닝·점수·상태 중 하나라도 직전
# 폴링과 다르면 crawl 버킷에
#   game-state-events/{date}/{gameId}/{observedAt}.json
# 을 쓰고, 아래 aws_s3_bucket_notification.crawl 의 queue 알림이 이 큐로 SendMessage 한다.
#
# 위 inning_events 큐와 **별개 큐**인 이유: SQS 는 pub/sub 이 아니다 — 소비자가 둘이면
# 메시지를 나눠 갖는다. quiz-app 이 정산용으로 inning_events 를 소비·삭제하고 있으므로
# user-app 이 같은 큐를 읽으면 정산 메시지를 뺏는다. 또 S3 알림은 같은 prefix 에 대상
# 둘을 허용하지 않아(Configurations overlap) prefix 도 갈랐다. 페이로드도 다르다 —
# 저쪽은 "막 끝난 이닝의 안타"(정산 사실), 이쪽은 "지금 상태"(점수·이닝·상태 스냅샷).
#
# 이 모듈은 큐와 S3→SQS 배선만 소유한다. 소비자는 EKS 의 BE user-app 이라 Lambda 이벤트
# 소스 매핑이 없다 — 수신 권한은 modules/user-irsa 가 IRSA 역할에 부여한다.
resource "aws_sqs_queue" "game_state_events_dlq" {
  name                      = "${var.name_prefix}-refine-game-state-events-dlq"
  message_retention_seconds = 1209600 # 14일 — 실패분을 사람이 확인할 시간

  tags = merge(var.tags, { Name = "${var.name_prefix}-refine-game-state-events-dlq" })
}

resource "aws_sqs_queue" "game_state_events" {
  name = "${var.name_prefix}-refine-game-state-events"

  # 소비자는 user-app(Spring, 폴링). 처리는 games 행 1건 재조회 + Redis 발행이라 짧다.
  # 가시성 안에 DeleteMessage 가 안 되면 같은 스냅샷이 재배달된다 — 소비 쪽은 멱등이어야
  # 한다(같은 상태를 두 번 푸시해도 클라이언트 표시는 같다).
  visibility_timeout_seconds = 30

  # 보존이 짧은 이유: "지금 상태" 알림이라 경기가 끝난 뒤 남은 메시지는 가치가 없다.
  # 소비자가 한참 죽어 있다가 살아나도 스냅샷 순서대로 밀어내면 최종 상태는 맞는다.
  message_retention_seconds = 21600 # 6시간
  receive_wait_time_seconds = 20    # 롱 폴링 — 빈 수신 요청 비용 절감

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.game_state_events_dlq.arn
    maxReceiveCount     = 3
  })

  tags = merge(var.tags, { Name = "${var.name_prefix}-refine-game-state-events" })
}

data "aws_iam_policy_document" "game_state_events_queue_policy" {
  statement {
    sid     = "AllowCrawlBucketSendMessage"
    effect  = "Allow"
    actions = ["sqs:SendMessage"]

    principals {
      type        = "Service"
      identifiers = ["s3.amazonaws.com"]
    }

    resources = [aws_sqs_queue.game_state_events.arn]

    condition {
      test     = "ArnEquals"
      variable = "aws:SourceArn"
      values   = [local.crawl_bucket_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }
  }
}

resource "aws_sqs_queue_policy" "game_state_events" {
  queue_url = aws_sqs_queue.game_state_events.id
  policy    = data.aws_iam_policy_document.game_state_events_queue_policy.json
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
# ⚠ image_uri 가 가리키는 태그가 ECR 에 push 되기 전에는 **최초 생성** apply 가
#   실패한다(plan 은 통과). 이미지는 VictoryFairy_AI 의 pipeline/Dockerfile 로 빌드한다.
#   생성 이후의 이미지 갱신은 CI 가 맡는다(각 함수의 lifecycle 주석 참고).
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
    command = ["pipeline.lambda_pattern.handler"]
  }

  environment {
    variables = {
      S3_BUCKET         = var.crawl_bucket_name
      BEDROCK_QUEUE_URL = aws_sqs_queue.bedrock.url
      LOG_LEVEL         = "INFO"
    }
  }

  # ⚠ 최초 생성 이후 **이미지 소유권은 CI 로 넘어간다.**
  #   .github/workflows/deploy-ai.yml 이 커밋 SHA 태그를 push 하고
  #   update-function-code 로 교체한다. 이 블록이 없으면 다음 apply 가
  #   var.image_tag 기준으로 image_uri 를 되돌려 배포를 되감는다.
  #   따라서 var.image_tag 는 **부트스트랩(최초 생성) 값**으로만 의미가 있다.
  lifecycle {
    ignore_changes = [image_uri]
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
    command = ["pipeline.lambda_bedrock.handler"]
  }

  environment {
    variables = {
      S3_BUCKET               = var.crawl_bucket_name
      BEDROCK_MODEL_ID        = var.bedrock_model_id
      BEDROCK_REGION          = data.aws_region.current.name
      BEDROCK_BATCH_POST_SIZE = tostring(var.bedrock_batch_post_size)
      # 앱의 BedrockSettings 는 pydantic BaseSettings 이고 env_prefix 가 없어서
      # 필드명 그대로가 환경변수명이 된다(BEDROCK_MODEL_ID 와 같은 경로).
      # ⚠ 한 호출의 판정 결과 전부가 이 상한을 나눠 쓴다 — 넘치면 응답이 잘려
      #   항목 수 불일치가 되고, 2회 재시도 후 배치 전건이 폴백 통과한다(BRK-LLM-15).
      BEDROCK_MAX_TOKENS      = tostring(var.bedrock_max_output_tokens)
      BEDROCK_SPEND_LIMIT_USD = tostring(var.bedrock_spend_limit_usd)
      BUDGET_TABLE_NAME       = aws_dynamodb_table.budget.name
      # 현행 모델은 프롬프트 캐싱 미지원 — 켜면 전 호출이 AccessDeniedException 으로 죽는다.
      BEDROCK_PROMPT_CACHE = "false"
      LOG_LEVEL            = "INFO"
    }
  }

  # 패턴 함수와 동일 — 생성 후 image_uri 는 CI 소관이다(위 주석 참고).
  lifecycle {
    ignore_changes = [image_uri]
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
#   ⚠️ 새 prefix 에 대한 알림이 필요하면 **새 aws_s3_bucket_notification 을 만들지 말고**
#   이 리소스에 블록을 추가할 것 — 두 개를 선언하면 나중에 apply 된 쪽이 앞선 쪽을 덮어쓴다.
resource "aws_s3_bucket_notification" "crawl" {
  bucket = var.crawl_bucket_name

  lambda_function {
    lambda_function_arn = aws_lambda_function.pattern.arn
    events              = ["s3:ObjectCreated:*"]
    filter_prefix       = "community/"
    filter_suffix       = ".json"
  }

  # 이닝 종료 이벤트 → SQS(inning_events). py-collector 가 inning-events/{date}/{gameId}/
  # {inning}-{half}.json 을 쓰면 BE quiz-app(이 모듈 밖)이 이 큐를 폴링해 정산한다.
  queue {
    queue_arn     = aws_sqs_queue.inning_events.arn
    events        = ["s3:ObjectCreated:*"]
    filter_prefix = "inning-events/"
    filter_suffix = ".json"
  }

  # 경기 상태 변화 → SQS(game_state_events). py-collector 가 game-state-events/{date}/{gameId}/
  # {observedAt}.json 을 쓰면 BE user-app 이 이 큐를 폴링해 경기 SSE 구독자에게 푸시한다.
  # ⚠ prefix 가 위 inning-events/ 와 겹치면 S3 가 알림 설정 전체를 거부한다.
  queue {
    queue_arn     = aws_sqs_queue.game_state_events.arn
    events        = ["s3:ObjectCreated:*"]
    filter_prefix = "game-state-events/"
    filter_suffix = ".json"
  }

  depends_on = [
    aws_lambda_permission.s3_invoke_pattern,
    aws_sqs_queue_policy.inning_events,
    aws_sqs_queue_policy.game_state_events,
  ]
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

  # ⚠️ `scaling_config { maximum_concurrency }` 를 두지 않는다.
  #
  # 처음에는 2로 넣었다가 apply 에서 막혔다:
  #     InvalidParameterValueException: MaximumConcurrency: 2 is greater than
  #     Function Reserved Concurrency: 1
  # AWS 는 `maximum_concurrency <= reserved_concurrency` 를 요구하는데, 이 설정의
  # **최소값이 2** 라 예약 동시성 1과는 애초에 공존할 수 없다.
  #
  # 그리고 필요하지도 않다 — 함수의 `reserved_concurrent_executions = 1` 이 이미
  # 직렬화를 강제한다. 폴러 쪽 제한은 **불필요한 이중 장치**였다.
  #
  # ⚠️ 이 제약은 두 리소스에 걸쳐 있어 **plan 에서 잡히지 않는다.** API 호출에서야 드러난다.
}
