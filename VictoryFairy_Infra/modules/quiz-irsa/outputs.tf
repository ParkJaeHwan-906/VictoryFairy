output "quiz_app_role_arn" {
  description = "quiz-app 파드 IRSA 역할 ARN. k8s/21-quiz-app.yaml 의 SA 어노테이션(eks.amazonaws.com/role-arn) 값."
  value       = aws_iam_role.quiz_app.arn
}
