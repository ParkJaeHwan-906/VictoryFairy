# 변수는 알파벳 순 (SKILL §1 파일 분리 규약)

variable "cluster_name" {
  description = "EKS 클러스터 이름. ExternalDNS IRSA 역할 이름 접두사 및 TXT 소유자 식별에 사용."
  type        = string
}

variable "domain_name" {
  description = "루트 도메인 (예: victoryfairy.com). Route53 호스팅영역 + ACM 인증서의 기준 도메인."
  type        = string
  validation {
    condition     = can(regex("^[a-z0-9.-]+\\.[a-z]{2,}$", var.domain_name))
    error_message = "domain_name은 유효한 도메인 형식이어야 합니다 (예: victoryfairy.com)."
  }
}

variable "oidc_provider_arn" {
  description = "EKS IRSA OIDC 프로바이더 ARN (eks 모듈 출력). ExternalDNS SA 토큰의 AssumeRole 신뢰 앵커."
  type        = string
}

variable "oidc_provider_url" {
  description = "EKS OIDC 프로바이더 URL(https:// 제거, eks 모듈 출력). 신뢰정책 sub/aud 조건 변수 접두사."
  type        = string
}

variable "external_dns_service_account_name" {
  description = "ExternalDNS ServiceAccount 이름. 매니페스트(k8s/23-external-dns.yaml)의 SA 이름과 일치해야 한다."
  type        = string
  default     = "external-dns"
}

variable "external_dns_service_account_namespace" {
  description = "ExternalDNS ServiceAccount 네임스페이스."
  type        = string
  default     = "kube-system"
}

variable "subject_alternative_names" {
  description = "ACM 인증서에 추가할 대체 도메인(SAN) 목록 (예: [\"www.victoryfairy.com\"]). 기본은 루트 도메인만."
  type        = list(string)
  default     = []
}

variable "tags" {
  description = "리소스에 병합할 추가 태그 (프로바이더 default_tags 위에 merge)."
  type        = map(string)
  default     = {}
}
