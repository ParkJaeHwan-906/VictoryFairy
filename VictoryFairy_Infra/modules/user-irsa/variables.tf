# 변수는 알파벳 순 (SKILL §1 파일 분리 규약)

variable "asset_bucket_name" {
  description = "프로필 이미지 자산 버킷 이름(modules/asset 출력). 정책 ARN 조립에 쓴다."
  type        = string
}

variable "name_prefix" {
  description = "역할·정책 이름 접두사 (예: victoryfairy-dev → victoryfairy-dev-user-app)"
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

variable "profile_prefix" {
  description = "확정 프로필 이미지 키 접두사(슬래시로 끝난다). modules/asset·BE 업로드 키와 같은 값이어야 한다."
  type        = string
  default     = "user-profile-img/"

  validation {
    condition     = endswith(var.profile_prefix, "/") && !startswith(var.profile_prefix, "/")
    error_message = "profile_prefix 는 슬래시로 끝나고 슬래시로 시작하지 않아야 합니다(예: user-profile-img/)."
  }
}

variable "service_account_name" {
  description = "user-app ServiceAccount 이름. 매니페스트(k8s/20-user-app.yaml)의 SA 이름과 일치해야 한다"
  type        = string
  default     = "user-app"
}

variable "service_account_namespace" {
  description = "user-app ServiceAccount 네임스페이스"
  type        = string
  default     = "victoryfairy"
}

variable "tags" {
  description = "리소스에 병합할 추가 태그 (프로바이더 default_tags 위에 merge)"
  type        = map(string)
  default     = {}
}

variable "temp_prefix" {
  description = "가입 전 임시 업로드 키 접두사(슬래시로 끝난다). ListBucket 의 s3:prefix 조건 값이기도 하다 — modules/asset·BE 와 같은 값이어야 한다."
  type        = string
  default     = "temp/"

  validation {
    condition     = endswith(var.temp_prefix, "/") && !startswith(var.temp_prefix, "/")
    error_message = "temp_prefix 는 슬래시로 끝나고 슬래시로 시작하지 않아야 합니다(예: temp/)."
  }
}
