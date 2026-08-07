output "certificate_arn" {
  description = "검증 완료된 ACM 인증서 ARN (서울). ALB(Ingress) TLS 종료용. LBC 가 host 로 자동 탐색하지만 명시 참조도 가능."
  value       = aws_acm_certificate_validation.this.certificate_arn
}

output "cloudfront_certificate_arn" {
  description = "검증 완료된 ACM 인증서 ARN (us-east-1). CloudFront 전용 — 서울 인증서는 CloudFront 가 거부한다."
  value       = aws_acm_certificate_validation.cloudfront.certificate_arn
}

output "origin_certificate_arn" {
  description = "CloudFront 오리진(ALB) 전용 ACM 인증서 ARN (서울). LBC 가 host 로 자동 탐색하므로 명시 참조는 필요 없다. origin_host 가 비면 null."
  value       = var.origin_host != "" ? aws_acm_certificate_validation.origin[0].certificate_arn : null
}

output "external_dns_role_arn" {
  description = "ExternalDNS IRSA 역할 ARN. k8s ServiceAccount 어노테이션(eks.amazonaws.com/role-arn) 값(매니페스트: k8s/23-external-dns.yaml)."
  value       = aws_iam_role.external_dns.arn
}

output "name_servers" {
  description = "이 호스팅영역의 NS 레코드 4개. 도메인 레지스트라(구입처)의 네임서버로 등록해야 존이 활성화된다(runbook 1단계)."
  value       = aws_route53_zone.this.name_servers
}

output "zone_id" {
  description = "Route53 호스팅영역 ID (레코드 추가·ExternalDNS 존 필터 참조용)."
  value       = aws_route53_zone.this.zone_id
}
