output "alarm_names" {
  description = "생성된 알람 이름 목록. FE 알람만 자동 롤백을 유발하고 나머지(BE·stalled)는 알림만 보낸다."
  value = concat(
    [aws_cloudwatch_metric_alarm.fe.alarm_name],
    [for a in aws_cloudwatch_metric_alarm.api : a.alarm_name],
    [aws_cloudwatch_metric_alarm.stalled.alarm_name],
  )
}

output "healthcheck_function_name" {
  description = "점검 함수 이름. 수동 실행으로 지표를 즉시 채울 때: aws lambda invoke --function-name <이름> /dev/null"
  value       = aws_lambda_function.healthcheck.function_name
}

output "metric_namespace" {
  description = "커스텀 지표 네임스페이스. 대시보드·추가 알람을 붙일 때 참조한다."
  value       = local.metric_namespace
}

output "responder_function_name" {
  description = "알람 대응 함수 이름 (롤백·Slack·티켓)."
  value       = aws_lambda_function.responder.function_name
}

output "sns_topic_arn" {
  description = "알람 SNS 토픽 ARN. 추가 구독(이메일 등)을 붙일 때 참조한다."
  value       = aws_sns_topic.alerts.arn
}
