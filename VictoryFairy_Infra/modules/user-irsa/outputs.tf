output "user_app_role_arn" {
  description = "user-app 파드 IRSA 역할 ARN. k8s/20-user-app.yaml 의 SA 어노테이션(eks.amazonaws.com/role-arn) 값."
  value       = aws_iam_role.user_app.arn
}
