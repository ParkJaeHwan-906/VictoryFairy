output "budget_table_name" {
  description = "일별 Bedrock 소비액 카운터 DynamoDB 테이블 이름 (앱이 UpdateItem ADD 로 누적)"
  value       = aws_dynamodb_table.budget.name
}

output "bedrock_dlq_url" {
  description = "3회 실패한 메시지가 쌓이는 DLQ URL. 여기에 쌓이면 사람이 봐야 한다"
  value       = aws_sqs_queue.bedrock_dlq.url
}

output "bedrock_function_arn" {
  description = "Bedrock 2차 검열 Lambda 함수 ARN (CI 배포 역할의 UpdateFunctionCode 스코프)"
  value       = aws_lambda_function.bedrock.arn
}

output "bedrock_function_name" {
  description = "Bedrock 2차 검열 Lambda 함수 이름 (로그 조회·수동 호출용)"
  value       = aws_lambda_function.bedrock.function_name
}

output "bedrock_queue_url" {
  description = "패턴 통과분이 들어가는 SQS 큐 URL. 패턴 Lambda 가 여기에 SendMessage 한다"
  value       = aws_sqs_queue.bedrock.url
}

output "game_state_events_dlq_url" {
  description = "3회 수신 실패한 경기 상태 변화 이벤트가 쌓이는 DLQ URL. 쌓이면 사람이 봐야 한다"
  value       = aws_sqs_queue.game_state_events_dlq.url
}

output "game_state_events_queue_arn" {
  description = "경기 상태 변화 이벤트 SQS 큐 ARN. BE user-app IRSA 역할(modules/user-irsa)에 소비 권한을 주는 데 쓴다"
  value       = aws_sqs_queue.game_state_events.arn
}

output "game_state_events_queue_url" {
  description = "경기 상태 변화 이벤트 SQS 큐 URL. BE user-app 컨테이너 환경변수(USER_GAME_EVENTS_SQS_QUEUE_URL)로 주입해 폴링 대상으로 쓴다"
  value       = aws_sqs_queue.game_state_events.url
}

output "inning_events_dlq_url" {
  description = "3회 수신 실패한 이닝 이벤트가 쌓이는 DLQ URL. 여기에 쌓이면 사람이 봐야 한다"
  value       = aws_sqs_queue.inning_events_dlq.url
}

output "inning_events_queue_arn" {
  description = "이닝 종료 이벤트 SQS 큐 ARN. BE quiz-app IRSA 역할(modules/quiz-irsa)에 소비 권한을 주는 데 쓴다"
  value       = aws_sqs_queue.inning_events.arn
}

output "inning_events_queue_url" {
  description = "이닝 종료 이벤트 SQS 큐 URL. BE quiz-app 컨테이너 환경변수로 주입해 폴링 대상으로 쓴다"
  value       = aws_sqs_queue.inning_events.url
}

output "pattern_function_arn" {
  description = "패턴 검열 Lambda 함수 ARN (CI 배포 역할의 UpdateFunctionCode 스코프)"
  value       = aws_lambda_function.pattern.arn
}

output "pattern_function_name" {
  description = "패턴 검열 Lambda 함수 이름 (S3 이벤트로 호출됨)"
  value       = aws_lambda_function.pattern.function_name
}
