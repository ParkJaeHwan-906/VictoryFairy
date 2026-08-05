output "repository_arns" {
  description = "리포지토리 이름 → ARN 맵 (IAM 정책에서 push 권한 스코프에 사용)"
  value       = { for k, r in aws_ecr_repository.this : k => r.arn }
}

output "repository_urls" {
  description = "리포지토리 이름 → URL 맵 (docker push/pull 및 k8s 매니페스트 image 필드에 사용)"
  value       = { for k, r in aws_ecr_repository.this : k => r.repository_url }
}
