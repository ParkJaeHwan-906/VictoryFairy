resource "random_password" "salt" {
  length  = 32
  special = false
}

locals {
  pii_salt = var.pii_salt != "" ? var.pii_salt : random_password.salt.result
}

resource "aws_lambda_function" "this" {
  function_name = var.name
  role          = aws_iam_role.lambda.arn
  package_type  = "Image"
  image_uri     = "${aws_ecr_repository.this.repository_url}@${data.aws_ecr_image.deployed.image_digest}"
  architectures = [var.architecture]
  memory_size   = var.memory_mb
  timeout       = var.timeout_s
  tags          = var.tags

  environment {
    variables = {
      COLLECTOR_S3_BUCKET     = var.data_bucket_name
      COLLECTOR_S3_REGION     = var.region
      COLLECTOR_S3_ENDPOINT   = ""
      COLLECTOR_S3_PATH_STYLE = "false"
      COLLECTOR_TARGETS_FILE  = "/var/task/config/targets.yaml"
      COLLECTOR_PII_SALT      = local.pii_salt
      JOURNAL_DIR             = "/tmp/journal" # Lambda's only writable path
      # Community crawl tuning: parallel but gentle, to stay under source rate-limits.
      COLLECTOR_COMMUNITY_CONCURRENCY = tostring(var.community_concurrency)
      COLLECTOR_FETCH_DELAY_MS        = tostring(var.community_delay_ms)
      # Bound the full-board date-walk per gallery (see variable docs).
      COLLECTOR_COMMUNITY_MAX_PAGES = tostring(var.community_max_pages)
    }
  }

  depends_on = [null_resource.image, aws_iam_role_policy.s3]
}

# EventBridge invokes async; cap retries (a failed run just retries next tick,
# and the S3-existence checkpoint makes any overlap/rerun harmless & idempotent).
resource "aws_lambda_function_event_invoke_config" "this" {
  function_name          = aws_lambda_function.this.function_name
  maximum_retry_attempts = 1
}
