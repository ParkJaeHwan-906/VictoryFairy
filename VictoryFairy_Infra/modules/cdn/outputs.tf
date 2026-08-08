output "bucket_arn" {
  description = "FE 정적 자산 버킷 ARN. CI 배포 역할의 s3:PutObject 정책 대상(modules/security)."
  value       = aws_s3_bucket.fe.arn
}

output "bucket_name" {
  description = "FE 정적 자산 버킷 이름. 배포 워크플로의 aws s3 sync 대상(.github/workflows/deploy-fe.yml)."
  value       = aws_s3_bucket.fe.id
}

output "distribution_arn" {
  description = "CloudFront 배포 ARN. CI 배포 역할의 cloudfront:CreateInvalidation 정책 대상(modules/security)."
  value       = aws_cloudfront_distribution.this.arn
}

output "distribution_domain_name" {
  description = "CloudFront 배포 도메인(d*.cloudfront.net). 전환 1단계에서 apex 를 옮기기 전에 이 주소로 직접 접속해 검증한다."
  value       = aws_cloudfront_distribution.this.domain_name
}

output "distribution_id" {
  description = "CloudFront 배포 ID. 배포 워크플로의 무효화(create-invalidation) 대상."
  value       = aws_cloudfront_distribution.this.id
}
