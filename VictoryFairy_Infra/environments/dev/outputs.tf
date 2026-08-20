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

output "fe_bucket_name" {
  description = "FE 정적 자산 S3 버킷 이름. .github/workflows/deploy-fe.yml 의 aws s3 sync 대상."
  value       = module.cdn.bucket_name
}

output "fe_cloudfront_distribution_id" {
  description = "FE CloudFront 배포 ID. deploy-fe.yml 의 무효화(create-invalidation) 대상."
  value       = module.cdn.distribution_id
}

output "asset_bucket_name" {
  description = "사용자 업로드(프로필 이미지) S3 버킷 이름. BE 의 업로드 대상 버킷 설정 값."
  value       = module.asset.bucket_name
}

output "user_app_role_arn" {
  description = "user-app 파드 IRSA 역할 ARN. k8s/20-user-app.yaml 에 ServiceAccount(victoryfairy/user-app)를 만들어 eks.amazonaws.com/role-arn 어노테이션 값으로 넣고, Deployment 에 serviceAccountName: user-app 을 건다."
  value       = module.user_irsa.user_app_role_arn
}

output "watchdog_alarm_names" {
  description = "상시 감시 알람 이름 목록. FE 알람만 자동 롤백을 유발한다."
  value       = module.fe_watchdog.alarm_names
}

output "watchdog_healthcheck_function_name" {
  description = "점검 함수 이름. 지표를 즉시 채우려면: aws lambda invoke --function-name <이름> /dev/null"
  value       = module.fe_watchdog.healthcheck_function_name
}

output "watchdog_sns_topic_arn" {
  description = "감시 알람 SNS 토픽 ARN. 이메일 등 추가 구독을 붙일 때 참조."
  value       = module.fe_watchdog.sns_topic_arn
}

output "fe_cloudfront_domain_name" {
  description = "FE CloudFront 배포 도메인(d*.cloudfront.net). apex 를 옮기기 전에 이 주소로 직접 접속해 검증한다(docs/fe-cdn-migration.md §4 1단계)."
  value       = module.cdn.distribution_domain_name
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

output "mysql_public_ip" {
  description = "운영 MySQL EC2 개발자 직접 접속용 고정 퍼블릭 IP(EIP). 퍼블릭 접속 미사용 시 null."
  value       = module.mysql_ec2.public_ip
}

output "mysql_data_volume_id" {
  description = "MySQL 데이터 EBS 볼륨 ID (prevent_destroy — 스냅샷/복원 참조용)"
  value       = module.mysql_ec2.data_volume_id
}

output "dev_db_public_ip" {
  description = "dev DB 퍼블릭 IP(EIP 사용 시 EIP). dev_db 미생성 시 null."
  value       = length(module.dev_db) > 0 ? module.dev_db[0].public_ip : null
}

output "dev_db_elastic_ip" {
  description = "dev DB 고정 Elastic IP(use_eip=true 일 때). 아니면 null."
  value       = length(module.dev_db) > 0 ? module.dev_db[0].elastic_ip : null
}

output "dev_db_instance_id" {
  description = "dev DB EC2 인스턴스 ID. dev_db 미생성 시 null."
  value       = length(module.dev_db) > 0 ? module.dev_db[0].instance_id : null
}

output "refine_bedrock_queue_url" {
  description = "패턴 통과분이 들어가는 SQS 큐 URL (패턴 Lambda 가 SendMessage)"
  value       = module.refine_pipeline.bedrock_queue_url
}

output "refine_bedrock_dlq_url" {
  description = "3회 실패한 정제 메시지가 쌓이는 DLQ URL — 쌓이면 사람이 봐야 한다"
  value       = module.refine_pipeline.bedrock_dlq_url
}

output "refine_budget_table_name" {
  description = "일별 Bedrock 소비액 카운터 DynamoDB 테이블 이름"
  value       = module.refine_pipeline.budget_table_name
}

output "quiz_app_role_arn" {
  description = "quiz-app 파드 IRSA 역할 ARN. k8s/21-quiz-app.yaml 의 SA 어노테이션(eks.amazonaws.com/role-arn) 값."
  value       = module.quiz_irsa.quiz_app_role_arn
}
