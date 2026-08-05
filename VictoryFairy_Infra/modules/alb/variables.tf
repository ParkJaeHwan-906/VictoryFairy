# 변수는 알파벳 순 (SKILL §1 파일 분리 규약)

variable "name_prefix" {
  description = "IAM 리소스 이름 접두사 (예: victoryfairy-dev)"
  type        = string
}

variable "oidc_provider_arn" {
  description = "EKS IRSA OIDC 프로바이더 ARN (eks 모듈 출력). 컨트롤러 SA 토큰의 AssumeRole 신뢰 앵커."
  type        = string
}

variable "oidc_provider_url" {
  description = "EKS OIDC 프로바이더 URL(https:// 제거, eks 모듈 출력). 신뢰정책 sub/aud 조건 변수 접두사."
  type        = string
}

variable "service_account_name" {
  description = "AWS Load Balancer Controller ServiceAccount 이름. Helm 설치 시 SA 이름과 일치해야 한다."
  type        = string
  default     = "aws-load-balancer-controller"
}

variable "service_account_namespace" {
  description = "AWS Load Balancer Controller ServiceAccount 네임스페이스."
  type        = string
  default     = "kube-system"
}

variable "tags" {
  description = "리소스에 병합할 추가 태그 (프로바이더 default_tags 위에 merge)."
  type        = map(string)
  default     = {}
}
