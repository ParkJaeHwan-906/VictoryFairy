locals {
  data_arn = "arn:aws:s3:::${var.data_bucket_name}"
}

resource "aws_iam_role" "lambda" {
  name = "${var.name}-lambda"
  tags = var.tags

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "lambda.amazonaws.com" }
      Action    = "sts:AssumeRole"
    }]
  })
}

# CloudWatch Logs (create log group/stream, put events)
resource "aws_iam_role_policy_attachment" "basic" {
  role       = aws_iam_role.lambda.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

# Write raw content to the bronze landing bucket
resource "aws_iam_role_policy" "s3" {
  name = "${var.name}-s3-landing"
  role = aws_iam_role.lambda.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["s3:PutObject", "s3:GetObject", "s3:ListBucket"]
      Resource = [local.data_arn, "${local.data_arn}/*"]
    }]
  })
}
