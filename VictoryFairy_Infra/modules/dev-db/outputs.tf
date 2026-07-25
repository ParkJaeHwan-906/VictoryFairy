# 출력은 알파벳 순 (SKILL §1)

output "instance_id" {
  description = "dev DB EC2 인스턴스 ID (SSM 세션·디버깅 대상)."
  value       = aws_instance.this.id
}

output "public_ip" {
  description = "dev DB 호스트의 퍼블릭 IP. allowed_cidr 에서만 22/3306/6379 접속 가능."
  value       = aws_instance.this.public_ip
}

output "security_group_id" {
  description = "dev DB 보안그룹 ID (인입은 allowed_cidr 로부터 22/3306/6379 만)."
  value       = aws_security_group.this.id
}
