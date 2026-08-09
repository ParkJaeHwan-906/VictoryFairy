variable "crawl_bucket_name" {
  description = "quiz-candidates/ 프리픽스가 있는 크롤 S3 버킷 이름. ⚠ Terraform 관리 밖 — ARN 조립에만 사용"
  type        = string
}

variable "name_prefix" {
  description = "역할·정책 이름 접두사 (예: victoryfairy-dev)"
  type        = string
}

variable "oidc_provider_arn" {
  description = "EKS IRSA용 OIDC 프로바이더 ARN (eks 모듈 출력)"
  type        = string
}

variable "oidc_provider_url" {
  description = "EKS OIDC 프로바이더 URL(https:// 제거, eks 모듈 출력). 신뢰정책 sub/aud 조건 변수 접두사"
  type        = string
}

variable "service_account_name" {
  description = "quiz-app ServiceAccount 이름. 매니페스트(k8s/21-quiz-app.yaml)의 SA 이름과 일치해야 한다"
  type        = string
  default     = "quiz-app"
}

variable "service_account_namespace" {
  description = "quiz-app ServiceAccount 네임스페이스"
  type        = string
  default     = "victoryfairy"
}

variable "tags" {
  description = "리소스에 병합할 추가 태그 (프로바이더 default_tags 위에 merge)"
  type        = map(string)
  default     = {}
}
