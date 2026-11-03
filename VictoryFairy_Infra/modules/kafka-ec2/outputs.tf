# 출력은 알파벳 순 (SKILL §1)

output "availability_zone" {
  description = "Kafka 호스트/데이터 볼륨이 위치한 AZ (운영 AZ = 2a)"
  value       = data.aws_subnet.this.availability_zone
}

output "bootstrap_servers" {
  description = "Kafka bootstrap 주소(<private_ip>:9092). chat-app 의 KAFKA_BOOTSTRAP_SERVERS 에 그대로 넣는다. advertised listener 와 같은 값이다."
  value       = "${aws_instance.this.private_ip}:${local.client_port}"
}

output "data_volume_id" {
  description = "Kafka 데이터 EBS 볼륨 ID (prevent_destroy 대상)"
  value       = aws_ebs_volume.data.id
}

output "iam_role_name" {
  description = "Kafka 호스트 EC2 인스턴스 역할 이름 (SSM 세션 권한 부여 대상)"
  value       = aws_iam_role.this.name
}

output "instance_id" {
  description = "Kafka 호스트 EC2 인스턴스 ID (SSM 세션·send-command 대상)"
  value       = aws_instance.this.id
}

output "private_ip" {
  description = "Kafka 호스트의 프라이빗 IP"
  value       = aws_instance.this.private_ip
}

output "security_group_id" {
  description = "Kafka 보안그룹 ID (9092 인입은 EKS 노드 SG 로부터만)"
  value       = aws_security_group.this.id
}
