output "cluster_name" {
  description = "EKS 클러스터 이름"
  value       = var.cluster_name
}

output "cluster_endpoint" {
  description = "EKS API 서버 엔드포인트"
  value       = null # TODO: aws_eks_cluster.this.endpoint
}

output "node_security_group_id" {
  description = "워커 노드 보안그룹 ID (MySQL SG 인입 허용에 사용)"
  value       = null # TODO: 노드 SG ID
}

output "oidc_provider_arn" {
  description = "IRSA용 OIDC 프로바이더 ARN"
  value       = null # TODO: aws_iam_openid_connect_provider.this.arn
}
