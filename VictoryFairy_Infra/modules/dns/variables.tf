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

variable "origin_host" {
  description = "CloudFront 가 ALB 오리진에 붙을 때 쓰는 호스트명 (예: origin.victoryfairy.com). 이 이름 전용 인증서를 서울에 별도 발급한다 — apex 인증서에 SAN 으로 넣으면 인증서가 교체되고 운영 ALB 리스너가 그것을 물고 있어 삭제가 거부된다(main.tf §2-1). 비우면 만들지 않는다."
  type        = string
  default     = ""
}

variable "subject_alternative_names" {
  description = "ACM 인증서에 추가할 대체 도메인(SAN) 목록 (예: [\"www.victoryfairy.com\"]). 기본은 루트 도메인만."
  type        = list(string)
  default     = []
}

variable "mailjet_dkim_value" {
  description = "Mailjet DKIM TXT 값(k=rsa; p=...). mailjet._domainkey.<domain> 에 등록. 빈 문자열이면 미생성. 255자 초과 시 자동 분할."
  type        = string
  default     = ""
}

variable "mailjet_spf_value" {
  description = "Mailjet SPF TXT 값(루트 도메인). ⚠ apex TXT는 ExternalDNS 소유권 레코드와 충돌하므로, ExternalDNS txt-prefix 조정 전에는 비워둘 것."
  type        = string
  default     = ""
}

variable "mailjet_verification_name" {
  description = "Mailjet 도메인 검증 TXT의 호스트명(도메인 접미사 제외, 예: mailjet._bc2f75b5). 빈 문자열이면 미생성."
  type        = string
  default     = ""
}

variable "mailjet_verification_value" {
  description = "Mailjet 도메인 검증 TXT 값."
  type        = string
  default     = ""
}

variable "tags" {
  description = "리소스에 병합할 추가 태그 (프로바이더 default_tags 위에 merge)."
  type        = map(string)
  default     = {}
}
