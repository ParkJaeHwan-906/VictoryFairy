output "nat_gateway_id" {
  description = "단일 NAT Gateway ID (운영 AZ = 2a). 프라이빗 서브넷 아웃바운드 경로."
  value       = aws_nat_gateway.this.id
}

output "private_subnet_ids" {
  description = "프라이빗 서브넷 ID 목록 (AZ 이름 오름차순 → index 0 이 운영 AZ 2a). EKS 노드 / MySQL EC2 배치용."
  value       = [for az in sort(keys(aws_subnet.private)) : aws_subnet.private[az].id]
}

output "private_subnet_ids_by_az" {
  description = "AZ 이름 → 프라이빗 서브넷 ID 맵. 특정 AZ(예: 2a) 서브넷을 이름으로 명확히 참조할 때 사용."
  value       = { for az, s in aws_subnet.private : az => s.id }
}

output "public_subnet_ids" {
  description = "퍼블릭 서브넷 ID 목록 (AZ 이름 오름차순 → index 0 이 운영 AZ 2a). ALB 배치용."
  value       = [for az in sort(keys(aws_subnet.public)) : aws_subnet.public[az].id]
}

output "public_subnet_ids_by_az" {
  description = "AZ 이름 → 퍼블릭 서브넷 ID 맵."
  value       = { for az, s in aws_subnet.public : az => s.id }
}

output "vpc_cidr_block" {
  description = "VPC CIDR 블록 (보안그룹 규칙 등에서 참조)."
  value       = aws_vpc.this.cidr_block
}

output "vpc_id" {
  description = "생성된 VPC ID."
  value       = aws_vpc.this.id
}
