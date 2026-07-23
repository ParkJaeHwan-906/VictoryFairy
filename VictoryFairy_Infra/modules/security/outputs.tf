output "github_actions_role_arn" {
  description = "GitHub Actions 배포 역할 ARN (워크플로 aws-actions/configure-aws-credentials 의 role-to-assume 값)"
  value       = aws_iam_role.github_actions.arn
}
