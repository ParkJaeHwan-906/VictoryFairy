# dev 환경 출력. 모듈 연결 후 주석 해제.

output "eks_cluster_name" {
  description = "EKS 클러스터 이름 (kubectl 설정용)"
  value       = module.eks.cluster_name
}

output "eks_cluster_endpoint" {
  description = "EKS API 서버 엔드포인트 (kubeconfig 구성용)"
  value       = module.eks.cluster_endpoint
}

output "eks_node_security_group_id" {
  description = "EKS 노드 공용 보안그룹 ID (mysql-ec2 3306/6379 인입 소스)"
  value       = module.eks.node_security_group_id
}

output "eks_oidc_provider_arn" {
  description = "IRSA용 OIDC 프로바이더 ARN"
  value       = module.eks.oidc_provider_arn
}

output "mysql_instance_id" {
  description = "MySQL EC2 인스턴스 ID (SSM 포트포워딩 대상)"
  value       = module.mysql_ec2.instance_id
}

output "mysql_private_ip" {
  description = "MySQL 호스트 프라이빗 IP (클러스터 내부 접속용: 3306=MySQL, 6379=서비스 Redis)"
  value       = module.mysql_ec2.private_ip
}

output "mysql_security_group_id" {
  description = "MySQL/Redis 보안그룹 ID (인입은 EKS 노드 SG로부터만)"
  value       = module.mysql_ec2.security_group_id
}

output "mysql_data_volume_id" {
  description = "MySQL 데이터 EBS 볼륨 ID (prevent_destroy — 스냅샷/복원 참조용)"
  value       = module.mysql_ec2.data_volume_id
}
