locals {
  # 모든 리소스 이름·태그의 접두사 (mysql-ec2 와 같은 environment 기반 규약).
  name = "victoryfairy-kafka-${var.environment}"

  # 브로커 클라이언트 리스너 포트. advertised listener·SG 인입·출력이 모두 이 값을 쓴다.
  client_port = 9092
}
