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

output "eks_cluster_autoscaler_role_arn" {
  description = "Cluster Autoscaler IRSA 역할 ARN (k8s/50-cluster-autoscaler.yaml SA 어노테이션 값)"
  value       = module.eks.cluster_autoscaler_role_arn
}

output "github_actions_role_arn" {
  description = "GitHub Actions 배포 역할 ARN (.github/workflows/deploy-eks.yml 의 ROLE_ARN)"
  value       = module.security.github_actions_role_arn
}

output "ecr_repository_urls" {
  description = "ECR 리포지토리 URL 맵 (docker push 및 k8s image 필드용)"
  value       = module.ecr.repository_urls
}

output "route53_name_servers" {
  description = "도메인 레지스트라(구입처)에 등록할 네임서버 4개. 등록해야 Route53 존 활성 + ACM DNS 검증 완료(runbook 1단계)."
  value       = module.dns.name_servers
}

output "acm_certificate_arn" {
  description = "검증 완료된 ACM 인증서 ARN. ALB(Ingress) HTTPS 종료용."
  value       = module.dns.certificate_arn
}

output "aws_lbc_role_arn" {
  description = "AWS Load Balancer Controller IRSA 역할 ARN. LBC Helm 설치 시 serviceAccount.annotations[eks.amazonaws.com/role-arn] 값."
  value       = module.alb.controller_role_arn
}

output "external_dns_role_arn" {
  description = "ExternalDNS IRSA 역할 ARN. k8s/23-external-dns.yaml 의 SA 어노테이션(eks.amazonaws.com/role-arn) 값."
  value       = module.dns.external_dns_role_arn
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
