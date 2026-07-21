# 출력은 알파벳 순 (SKILL §1)

output "availability_zone" {
  description = "MySQL 호스트/데이터 볼륨이 위치한 AZ (운영 AZ = 2a)"
  value       = data.aws_subnet.this.availability_zone
}

output "data_volume_id" {
  description = "MySQL 데이터 EBS 볼륨 ID (prevent_destroy 대상 — 스냅샷/복원 참조용)"
  value       = aws_ebs_volume.data.id
}

output "iam_role_name" {
  description = "MySQL 호스트 EC2 인스턴스 역할 이름 (SSM 세션 권한 부여 대상)"
  value       = aws_iam_role.this.name
}

output "instance_id" {
  description = "MySQL 호스트 EC2 인스턴스 ID (SSM 포트포워딩 대상)"
  value       = aws_instance.this.id
}

output "private_ip" {
  description = "MySQL 호스트의 프라이빗 IP (클러스터 내부 접속용). 3306=MySQL, 6379=서비스 Redis."
  value       = aws_instance.this.private_ip
}

output "security_group_id" {
  description = "MySQL/Redis 보안그룹 ID (인입은 EKS 노드 SG로부터만)"
  value       = aws_security_group.this.id
}
