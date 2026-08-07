# 변수는 알파벳 순 (SKILL §1 파일 분리 규약)

variable "api_path_patterns" {
  description = "ALB 오리진으로 보낼 경로 패턴 목록 (예: [\"/api/*\", \"/rt/*\"]). 나머지는 전부 S3 정적 자산으로 간다."
  type        = list(string)
  validation {
    condition     = length(var.api_path_patterns) > 0
    error_message = "api_path_patterns 는 최소 1개가 필요합니다(비우면 API 가 S3 로 흘러 404 가 됩니다)."
  }
}

variable "attach_apex_alias" {
  description = <<-EOT
    apex 도메인 A/AAAA(ALIAS) 레코드를 CloudFront 로 붙일지 여부. 이것이 곧 실서비스 전환 스위치다.

    false(기본) = 1단계. CloudFront·S3 는 만들되 트래픽은 그대로 ALB 로 간다. 배포 도메인
    (d*.cloudfront.net)으로 직접 접속해 검증하는 단계.
    true = 2단계. apex 를 CloudFront 로 교체해 실제 트래픽을 옮긴다.

    ⚠ 이 레코드는 ExternalDNS 가 만들어 둔 것을 allow_overwrite 로 덮어써 소유권을 가져온다.
      되돌리려면 false 로 바꾸는 것만으로는 부족하다 — Terraform 이 레코드를 지우면 도메인이
      어디도 가리키지 않는다. 롤백 절차는 docs/fe-cdn-migration.md §4 를 따를 것.
  EOT
  type        = bool
  default     = false
}

variable "certificate_arn" {
  description = "CloudFront 용 ACM 인증서 ARN. ⚠ 반드시 us-east-1 리전 인증서여야 한다(CloudFront 는 다른 리전 인증서를 거부한다). 서울 인증서는 ALB 용이라 여기 쓸 수 없다."
  type        = string
}

variable "domain_name" {
  description = "CloudFront 에 붙일 사용자 대면 도메인 (예: victoryfairy.com). certificate_arn 의 인증서가 이 이름을 커버해야 한다."
  type        = string
}

variable "name_prefix" {
  description = "리소스 이름 접두사 (예: victoryfairy-dev → victoryfairy-dev-fe 버킷)"
  type        = string
}

variable "noncurrent_version_expiration_days" {
  description = "버저닝된 옛 오브젝트 버전을 보관할 일수. 롤백 여지를 남기되 무한 누적은 막는다."
  type        = number
  default     = 30
  validation {
    condition     = var.noncurrent_version_expiration_days >= 1
    error_message = "noncurrent_version_expiration_days 는 1 이상이어야 합니다."
  }
}

variable "origin_domain_name" {
  description = <<-EOT
    API 오리진(ALB)의 도메인 이름 (예: origin.victoryfairy.com).

    ALB 의 실제 주소(k8s-*.elb.amazonaws.com)를 쓸 수 없다 — CloudFront 는 오리진 HTTPS 연결에서
    오리진 도메인 이름과 오리진이 제시한 인증서가 일치하는지 검증하는데, ALB 인증서는
    victoryfairy.com 용이라 이름이 어긋나 연결이 거부된다. 그래서 ALB 인증서(서울)가 커버하는
    별도 이름을 두고, 그 레코드는 Ingress host 를 통해 ExternalDNS 가 만든다.
    이 이름은 CloudFront 가 뒤에서만 쓰므로 브라우저에 노출되지 않는다.
  EOT
  type        = string
}

variable "origin_read_timeout" {
  description = <<-EOT
    CloudFront 가 오리진 응답을 기다리는 최대 초. 패킷 사이 유휴 시간에도 적용된다.

    ⚠ 채팅 SSE 의 생명선이다. quiz 앱이 15초마다 :ping 주석 프레임을 보내(SseEmitterRegistry.heartbeat)
      이 값에 걸리지 않는데, 하트비트 주기를 늘리면 이 값도 함께 올려야 스트림이 끊기지 않는다.
      쿼터 문서상 조정 가능 범위는 1~120 초이고, 그 이상은 증설 요청이 필요하다.
  EOT
  type        = number
  default     = 30
  validation {
    condition     = var.origin_read_timeout >= 1 && var.origin_read_timeout <= 120
    error_message = "origin_read_timeout 은 1~120 이어야 합니다(초과는 AWS 쿼터 증설 후 가능)."
  }
}

variable "price_class" {
  description = "CloudFront 엣지 배포 범위. ⚠ PriceClass_100 은 미국·유럽만이라 서울 엣지가 빠진다 — 국내 서비스에는 200 이상이어야 한다."
  type        = string
  default     = "PriceClass_200"
  validation {
    condition     = contains(["PriceClass_200", "PriceClass_All"], var.price_class)
    error_message = "price_class 는 PriceClass_200 또는 PriceClass_All 이어야 합니다(100 은 서울 엣지 제외)."
  }
}

variable "route53_zone_id" {
  description = "apex ALIAS 레코드를 만들 Route53 호스팅영역 ID (modules/dns 의 zone_id)."
  type        = string
}

variable "tags" {
  description = "리소스에 병합할 추가 태그"
  type        = map(string)
  default     = {}
}
