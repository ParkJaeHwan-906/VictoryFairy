output "lambda_function_name" {
  value = aws_lambda_function.this.function_name
}

output "ecr_repository_url" {
  value = aws_ecr_repository.this.repository_url
}

output "community_schedule" {
  value = aws_cloudwatch_event_rule.community.schedule_expression
}

output "game_schedule" {
  value = aws_cloudwatch_event_rule.game.schedule_expression
}

output "invoke_community_now" {
  description = "Trigger one community crawl manually"
  value       = "aws lambda invoke --function-name ${aws_lambda_function.this.function_name} --payload '{\"job\":\"community\"}' --cli-binary-format raw-in-base64-out /dev/stdout"
}

output "invoke_game_now" {
  description = "Trigger one game crawl manually (optionally add \\\"date\\\":\\\"YYYY-MM-DD\\\" to backfill)"
  value       = "aws lambda invoke --function-name ${aws_lambda_function.this.function_name} --payload '{\"job\":\"game\"}' --cli-binary-format raw-in-base64-out /dev/stdout"
}
