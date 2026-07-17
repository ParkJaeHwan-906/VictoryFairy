output "vpc_id" {
  description = "생성된 VPC ID"
  value       = null # TODO: aws_vpc.this.id
}

output "public_subnet_ids" {
  description = "퍼블릭 서브넷 ID 목록 (ALB 배치용)"
  value       = [] # TODO: values(aws_subnet.public)[*].id
}

output "private_subnet_ids" {
  description = "프라이빗 서브넷 ID 목록 (EKS 노드 / EC2 MySQL 배치용)"
  value       = [] # TODO: values(aws_subnet.private)[*].id
}
