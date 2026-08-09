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

output "db_lambda_function_name" {
  description = "DB-job Lambda name (empty when db_subnet_ids is unset)"
  value       = local.db_enabled ? aws_lambda_function.db[0].function_name : ""
}

output "invoke_records_now" {
  description = "Load today's finished games into prod MySQL (add \\\"from\\\"/\\\"to\\\" for a backfill range)"
  value       = local.db_enabled ? "aws lambda invoke --function-name ${aws_lambda_function.db[0].function_name} --payload '{\"job\":\"records\"}' --cli-binary-format raw-in-base64-out /dev/stdout" : ""
}

output "invoke_registrations_now" {
  description = "Load the current KBO 1-gun roster into prod MySQL"
  value       = local.db_enabled ? "aws lambda invoke --function-name ${aws_lambda_function.db[0].function_name} --payload '{\"job\":\"registrations\"}' --cli-binary-format raw-in-base64-out /dev/stdout" : ""
}
