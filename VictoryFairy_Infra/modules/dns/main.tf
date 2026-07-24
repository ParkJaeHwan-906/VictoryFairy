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
