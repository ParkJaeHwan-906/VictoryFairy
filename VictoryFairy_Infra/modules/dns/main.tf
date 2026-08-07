# dns 모듈: 퍼블릭 DNS(Route53) + TLS 인증서(ACM) + ExternalDNS 용 IRSA.
#
# 흐름:
#   1) Route53 호스팅영역 생성 → 출력 name_servers 를 도메인 레지스트라(구입처)의
#      네임서버로 등록해야 이 존이 인터넷에서 권한을 가진다(runbook 참고).
#   2) ACM 인증서를 DNS 검증으로 발급 — 검증 CNAME 을 같은 존에 자동 생성하므로,
#      NS 전파가 끝나면(1) 검증이 자동 완료된다. 이 ARN 을 ALB(Ingress)가 TLS 종료에 쓴다.
#   3) ExternalDNS IRSA — 클러스터의 ExternalDNS 가 Ingress host 를 보고 이 존에
#      A(ALIAS)/TXT 레코드를 자동 관리하도록 파드 단위 최소 권한을 부여한다.
# 경계: ACM/ALB 모두 ap-northeast-2(서울) 리전. (us-east-1 은 CloudFront 용으로 무관)

# ---------------------------------------------------------------------------
# 1) Route53 퍼블릭 호스팅영역
# ---------------------------------------------------------------------------
resource "aws_route53_zone" "this" {
  name = var.domain_name

  tags = merge(var.tags, {
    Name = var.domain_name
  })
}

# ---------------------------------------------------------------------------
# 2) ACM 인증서 (DNS 검증) + 검증 레코드 + 검증 완료 대기
# ---------------------------------------------------------------------------
resource "aws_acm_certificate" "this" {
  domain_name               = var.domain_name
  subject_alternative_names = var.subject_alternative_names
  validation_method         = "DNS"

  # 갱신·SAN 변경 시 새 인증서를 먼저 만들고 교체(무중단).
  lifecycle {
    create_before_destroy = true
  }

  tags = merge(var.tags, {
    Name = var.domain_name
  })
}

# 각 도메인(루트+SAN)의 검증 CNAME 을 자기 존에 생성. domain_name 으로 중복 제거.
resource "aws_route53_record" "cert_validation" {
  for_each = {
    for dvo in aws_acm_certificate.this.domain_validation_options : dvo.domain_name => {
      name   = dvo.resource_record_name
      record = dvo.resource_record_value
      type   = dvo.resource_record_type
    }
  }

  zone_id         = aws_route53_zone.this.zone_id
  name            = each.value.name
  type            = each.value.type
  records         = [each.value.record]
  ttl             = 60
  allow_overwrite = true # 재발급 시 동일 이름 레코드 덮어쓰기 허용
}

# NS 전파 후 검증이 완료될 때까지 apply 를 대기시킨다(ALB 가 유효 인증서를 참조하도록).
resource "aws_acm_certificate_validation" "this" {
  certificate_arn         = aws_acm_certificate.this.arn
  validation_record_fqdns = [for r in aws_route53_record.cert_validation : r.fqdn]
}

# ---------------------------------------------------------------------------
# 2-1) CloudFront 오리진(ALB) 전용 인증서 — origin.<domain> (서울)
#
# CloudFront 는 오리진에 HTTPS 로 붙을 때 오리진 도메인 이름과 오리진이 제시한 인증서가
# 일치하는지 검증한다. ALB 의 실제 주소(k8s-*.elb.amazonaws.com)는 apex 인증서와 이름이 어긋나
# 거부되므로, 그 이름을 커버하는 인증서가 ALB 리스너에 붙어 있어야 한다.
#
# ⚠ 이 이름을 위 apex 인증서의 SAN 으로 넣지 않는 이유가 중요하다. SAN 변경은 인증서를 '교체'하고,
#   그 인증서는 지금 운영 ALB 리스너가 물고 있다. create_before_destroy 로 새 인증서를 먼저
#   만들어도 옛 인증서 삭제 시 ACM 이 ResourceInUseException 을 낸다(리스너가 아직 참조 중).
#   게다가 두 인증서가 모두 apex 를 커버하는 동안 LBC 의 인증서 자동 탐색이 무엇을 고를지
#   불확실하다. 별도 인증서로 두면 apex 인증서를 건드리지 않아 운영 TLS 가 무사하다.
#   ALB 리스너는 SNI 로 여러 인증서를 붙일 수 있고, LBC 는 victoryfairy-dns-origin Ingress 의
#   host 를 보고 이 인증서를 자동 탐색한다.
# ---------------------------------------------------------------------------

# ---------------------------------------------------------------------------
# 2-2) CloudFront 용 ACM 인증서 (us-east-1)
#
# CloudFront 는 us-east-1 인증서만 받는다 — 위 서울 인증서를 그대로 쓸 수 없어 같은 도메인으로
# 한 장 더 발급한다. 서울 인증서는 ALB(Ingress) TLS 종료용으로 그대로 남는다.
#
# 검증 레코드는 위 cert_validation 을 재사용하지 않고 이 인증서용으로 따로 만든다.
#
# ACM 은 보통 같은 계정·같은 도메인에 같은 검증 CNAME 을 돌려주므로 재사용도 대체로 동작하지만,
# 그 가정이 깨지면 apply 가 검증 대기에서 45분간 멈춘 뒤 실패한다. 반면 따로 만들면 양쪽 다 안전하다 —
# 검증 CNAME 의 '이름'에 토큰 해시가 들어가기 때문이다:
#   토큰이 같으면  → 이름·값이 모두 같아 두 리소스의 목표 상태가 동일(덮어써도 무해, allow_overwrite)
#   토큰이 다르면  → 이름 자체가 달라 서로 다른 레코드가 되어 충돌하지 않는다
# ⚠ 토큰이 같은 경우 같은 레코드를 두 리소스가 관리하게 된다. 평시엔 무해하지만 destroy 시
#   나중 것이 '이미 없는 레코드' 삭제로 실패할 수 있다.
# ---------------------------------------------------------------------------
resource "aws_acm_certificate" "origin" {
  count = var.origin_host != "" ? 1 : 0

  domain_name       = var.origin_host
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }

  tags = merge(var.tags, {
    Name = var.origin_host
  })
}

resource "aws_route53_record" "origin_cert_validation" {
  for_each = var.origin_host != "" ? {
    for dvo in aws_acm_certificate.origin[0].domain_validation_options : dvo.domain_name => {
      name   = dvo.resource_record_name
      record = dvo.resource_record_value
      type   = dvo.resource_record_type
    }
  } : {}

  zone_id         = aws_route53_zone.this.zone_id
  name            = each.value.name
  type            = each.value.type
  records         = [each.value.record]
  ttl             = 60
  allow_overwrite = true
}

resource "aws_acm_certificate_validation" "origin" {
  count = var.origin_host != "" ? 1 : 0

  certificate_arn         = aws_acm_certificate.origin[0].arn
  validation_record_fqdns = [for r in aws_route53_record.origin_cert_validation : r.fqdn]
}

resource "aws_acm_certificate" "cloudfront" {
  provider = aws.us_east_1

  domain_name       = var.domain_name
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }

  tags = merge(var.tags, {
    Name = "${var.domain_name}-cloudfront"
  })
}

resource "aws_route53_record" "cloudfront_cert_validation" {
  for_each = {
    for dvo in aws_acm_certificate.cloudfront.domain_validation_options : dvo.domain_name => {
      name   = dvo.resource_record_name
      record = dvo.resource_record_value
      type   = dvo.resource_record_type
    }
  }

  zone_id         = aws_route53_zone.this.zone_id
  name            = each.value.name
  type            = each.value.type
  records         = [each.value.record]
  ttl             = 60
  allow_overwrite = true
}

resource "aws_acm_certificate_validation" "cloudfront" {
  provider = aws.us_east_1

  certificate_arn         = aws_acm_certificate.cloudfront.arn
  validation_record_fqdns = [for r in aws_route53_record.cloudfront_cert_validation : r.fqdn]
}

# ---------------------------------------------------------------------------
# 3) ExternalDNS 용 IRSA — 이 존의 레코드만 조작 가능(최소 권한, SKILL §7)
# ---------------------------------------------------------------------------
data "aws_iam_policy_document" "external_dns_assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [var.oidc_provider_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "${var.oidc_provider_url}:sub"
      values   = ["system:serviceaccount:${var.external_dns_service_account_namespace}:${var.external_dns_service_account_name}"]
    }

    condition {
      test     = "StringEquals"
      variable = "${var.oidc_provider_url}:aud"
      values   = ["sts.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "external_dns" {
  name               = "${var.cluster_name}-external-dns"
  assume_role_policy = data.aws_iam_policy_document.external_dns_assume.json

  tags = merge(var.tags, {
    Name = "${var.cluster_name}-external-dns"
  })
}

data "aws_iam_policy_document" "external_dns" {
  # 레코드 변경(쓰기): 이 호스팅영역으로만 제한.
  statement {
    sid       = "ChangeRecords"
    actions   = ["route53:ChangeResourceRecordSets"]
    resources = [aws_route53_zone.this.arn]
  }

  # 존/레코드 발견(읽기): 리소스 단위 제한 미지원 → "*".
  statement {
    sid = "ListZones"
    actions = [
      "route53:ListHostedZones",
      "route53:ListResourceRecordSets",
      "route53:ListTagsForResource",
    ]
    resources = ["*"]
  }
}

resource "aws_iam_role_policy" "external_dns" {
  name   = "${var.cluster_name}-external-dns"
  role   = aws_iam_role.external_dns.id
  policy = data.aws_iam_policy_document.external_dns.json
}

# ---------------------------------------------------------------------------
# 4) Mailjet 이메일 발신 도메인 인증 레코드 (값이 주어질 때만 생성 — count 가드)
#    DKIM·검증은 ExternalDNS/apex A레코드와 겹치지 않는 이름이라 안전하게 추가 가능.
#    ⚠ SPF 는 루트(apex) TXT 라 ExternalDNS 소유권 TXT 와 충돌 → txt-prefix 조정 전엔
#      mailjet_spf_value 를 비워둬(count=0) 생성하지 않는다.
# ---------------------------------------------------------------------------
locals {
  # Route53 TXT 문자열은 조각당 255자 제한 → 초과 시 두 조각("...""...")으로 분할한다.
  _dkim               = var.mailjet_dkim_value
  mailjet_dkim_record = length(local._dkim) > 255 ? "${substr(local._dkim, 0, 255)}\"\"${substr(local._dkim, 255, length(local._dkim) - 255)}" : local._dkim
}

resource "aws_route53_record" "mailjet_dkim" {
  count   = var.mailjet_dkim_value != "" ? 1 : 0
  zone_id = aws_route53_zone.this.zone_id
  name    = "mailjet._domainkey.${var.domain_name}"
  type    = "TXT"
  ttl     = 300
  records = [local.mailjet_dkim_record]
}

resource "aws_route53_record" "mailjet_verification" {
  count   = var.mailjet_verification_value != "" ? 1 : 0
  zone_id = aws_route53_zone.this.zone_id
  name    = "${var.mailjet_verification_name}.${var.domain_name}"
  type    = "TXT"
  ttl     = 300
  records = [var.mailjet_verification_value]
}

resource "aws_route53_record" "mailjet_spf" {
  count   = var.mailjet_spf_value != "" ? 1 : 0
  zone_id = aws_route53_zone.this.zone_id
  name    = var.domain_name
  type    = "TXT"
  ttl     = 300
  records = [var.mailjet_spf_value]
}
