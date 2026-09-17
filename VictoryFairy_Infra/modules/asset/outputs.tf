output "bucket_arn" {
  description = "자산 버킷 ARN. user-app IRSA 정책(modules/user-irsa)의 리소스 대상."
  value       = aws_s3_bucket.this.arn
}

output "bucket_name" {
  description = "자산 버킷 이름. BE 의 S3 클라이언트 설정(업로드 대상 버킷) 값."
  value       = aws_s3_bucket.this.id
}

output "bucket_regional_domain_name" {
  description = "버킷의 리전 도메인. modules/cdn 이 두 번째 S3 오리진의 domain_name 으로 쓴다."
  value       = aws_s3_bucket.this.bucket_regional_domain_name
}
