output "controller_role_arn" {
  description = "AWS Load Balancer Controller IRSA 역할 ARN. Helm 설치 시 serviceAccount.annotations['eks.amazonaws.com/role-arn'] 값으로 지정."
  value       = aws_iam_role.this.arn
}
