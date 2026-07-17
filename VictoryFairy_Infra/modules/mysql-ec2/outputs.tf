output "instance_id" {
  description = "MySQL 호스트 EC2 인스턴스 ID (SSM 포트포워딩 대상)"
  value       = null # TODO: aws_instance.this.id
}

output "private_ip" {
  description = "MySQL 호스트의 프라이빗 IP (클러스터 내부 접속용)"
  value       = null # TODO: aws_instance.this.private_ip
}

output "security_group_id" {
  description = "MySQL 보안그룹 ID"
  value       = null # TODO: aws_security_group.this.id
}
