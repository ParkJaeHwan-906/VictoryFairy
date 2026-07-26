output "budget_table_name" {
  description = "일별 Bedrock 소비액 카운터 DynamoDB 테이블 이름 (앱이 UpdateItem ADD 로 누적)"
  value       = aws_dynamodb_table.budget.name
}

output "bedrock_dlq_url" {
  description = "3회 실패한 메시지가 쌓이는 DLQ URL. 여기에 쌓이면 사람이 봐야 한다"
  value       = aws_sqs_queue.bedrock_dlq.url
}

output "bedrock_function_name" {
  description = "Bedrock 2차 검열 Lambda 함수 이름 (로그 조회·수동 호출용)"
  value       = aws_lambda_function.bedrock.function_name
}

output "bedrock_queue_url" {
  description = "패턴 통과분이 들어가는 SQS 큐 URL. 패턴 Lambda 가 여기에 SendMessage 한다"
  value       = aws_sqs_queue.bedrock.url
}

output "pattern_function_name" {
  description = "패턴 검열 Lambda 함수 이름 (S3 이벤트로 호출됨)"
  value       = aws_lambda_function.pattern.function_name
}
