output "cluster_certificate_authority" {
  description = "클러스터 CA 인증서(base64). kubeconfig 구성에 사용."
  value       = aws_eks_cluster.this.certificate_authority[0].data
}

output "cluster_endpoint" {
  description = "EKS API 서버 엔드포인트."
  value       = aws_eks_cluster.this.endpoint
}

output "cluster_name" {
  description = "EKS 클러스터 이름 (kubectl/kubeconfig 설정용)."
  value       = aws_eks_cluster.this.name
}

output "node_security_group_id" {
  description = "EKS가 관리하는 클러스터 보안그룹 ID. 관리형 노드그룹 노드에 자동 부착되는 공용 SG로, mysql-ec2 모듈이 3306/6379 인입 소스로 참조한다."
  value       = aws_eks_cluster.this.vpc_config[0].cluster_security_group_id
}

output "oidc_provider_arn" {
  description = "IRSA용 OIDC 프로바이더 ARN. 파드 단위 IAM 역할의 신뢰 정책에서 참조."
  value       = aws_iam_openid_connect_provider.this.arn
}
