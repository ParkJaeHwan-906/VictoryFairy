# cdn 모듈: FE 정적 자산 버킷(S3) + CloudFront 배포.
#
# 이 모듈이 대체한 것: nginx 파드(k8s/24-fe-app.yaml — 2026-08-07 제거)와 그 nginx.conf. 정적 서버를 직접
# 운영하지 않고 S3 가 파일을 갖고 CloudFront 가 내보낸다. nginx.conf 가 하던 캐시 정책·
# SPA fallback·압축은 아래 Response Headers Policy / CloudFront Function / compress 로 옮겨왔다.
#
# 오리진이 2개인 이유: 사용자 대면 도메인을 하나로 유지하기 위해서다. CloudFront 가 진입점이 되어
#   /api/*, /rt/*  → ALB (기존 EKS 앱)
#   그 외          → S3 (정적 자산)
# 로 갈라 보내므로 FE·API 가 계속 같은 오리진이고, CORS 도 FE 번들의 절대 URL 도 필요 없다.
#
# 전환 절차·롤백은 docs/fe-cdn-migration.md.

locals {
  s3_origin_id  = "s3-fe"
  alb_origin_id = "alb-api"
}

# ---------------------------------------------------------------------------
# 1) 정적 자산 버킷 — 퍼블릭 접근은 완전히 차단하고 CloudFront(OAC)만 읽는다
# ---------------------------------------------------------------------------
resource "aws_s3_bucket" "fe" {
  bucket = "${var.name_prefix}-fe"

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-fe"
  })
}

# 롤백의 1순위는 KVS 의 current 를 옛 릴리스로 바꾸는 것이다(§3). 버저닝은 그보다 아래,
# 오브젝트를 실수로 덮어썼을 때의 최후 수단으로 남긴다.
resource "aws_s3_bucket_versioning" "fe" {
  bucket = aws_s3_bucket.fe.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "fe" {
  bucket = aws_s3_bucket.fe.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# 버킷은 절대 퍼블릭이 아니다 — 유일한 독자는 OAC 를 든 CloudFront 다.
resource "aws_s3_bucket_public_access_block" "fe" {
  bucket = aws_s3_bucket.fe.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# ⚠ releases/ 에 만료 규칙을 걸지 않는다. 규칙으로는 "KVS 의 current 가 가리키는 것만 제외" 를
#   표현할 수 없어서, 배포가 한동안 없으면 '지금 서비스 중인 릴리스' 가 삭제된다 —
#   그 순간 함수가 없는 접두사를 가리켜 사이트가 통째로 403 이 된다.
#   누적량은 배포당 수백 KB 수준이라 필요할 때 수동 정리하는 편이 안전하다.
#   (루트 사본도 같은 이유로 건드리지 않는다. 그쪽은 KVS 장애 시의 폴백이다.)
resource "aws_s3_bucket_lifecycle_configuration" "fe" {
  bucket = aws_s3_bucket.fe.id

  rule {
    id     = "expire-noncurrent-versions"
    status = "Enabled"

    filter {}

    noncurrent_version_expiration {
      noncurrent_days = var.noncurrent_version_expiration_days
    }

    # 멈춘 멀티파트 업로드가 요금만 먹는 것을 막는다.
    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}

# ---------------------------------------------------------------------------
# 2) OAC — CloudFront 가 SigV4 로 버킷을 읽는다 (구식 OAI 대체)
# ---------------------------------------------------------------------------
resource "aws_cloudfront_origin_access_control" "fe" {
  name                              = "${var.name_prefix}-fe"
  description                       = "CloudFront → S3(${aws_s3_bucket.fe.id}) 전용 읽기"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

# 이 배포에서 온 요청만 허용한다. SourceArn 조건이 없으면 다른 계정의 CloudFront 도 이 버킷을 읽는다.
data "aws_iam_policy_document" "fe_bucket" {
  statement {
    sid       = "AllowCloudFrontRead"
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.fe.arn}/*"]

    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [aws_cloudfront_distribution.this.arn]
    }
  }
}

resource "aws_s3_bucket_policy" "fe" {
  bucket = aws_s3_bucket.fe.id
  policy = data.aws_iam_policy_document.fe_bucket.json

  # 퍼블릭 차단이 먼저 걸린 뒤 정책을 붙인다(BlockPublicPolicy 판정 순서 안정화).
  depends_on = [aws_s3_bucket_public_access_block.fe]
}

# ---------------------------------------------------------------------------
# 3) SPA fallback 함수 — 코드와 근거는 functions/spa-fallback.js
#
# 릴리스 롤백은 이 함수가 담당하지 않는다. 배포가 releases/<타임스탬프>-<SHA>/ 에 사본을 남기고,
# 되돌릴 때 그 안의 index.html 을 루트로 복사한다(docs/fe-release-rollback.md).
# ⚠ KVS 로 버전 접두사를 붙이는 방식을 구현했다가 되돌렸다 — 조직 SCP 가
#   cloudfront-keyvaluestore 네임스페이스를 명시적으로 거부해 키를 쓸 수 없다. 다시 시도하지 말 것.
# ---------------------------------------------------------------------------
resource "aws_cloudfront_function" "spa_fallback" {
  name    = "${var.name_prefix}-spa-fallback"
  runtime = "cloudfront-js-2.0"
  comment = "확장자 없는 경로를 /index.html 로 재작성(SPA 딥링크)"
  publish = true
  code    = file("${path.module}/functions/spa-fallback.js")
}

# ---------------------------------------------------------------------------
# 4) 캐시 정책 — 엣지가 얼마나 보관할지
#
# ⚠ Response Headers Policy(§5)와 다른 축이다. 여기는 '엣지 보관 기간', 저기는 '브라우저 보관 기간'.
#   index.html 에 브라우저용 no-cache 를 줘도 엣지 TTL 이 길면 배포 후에도 옛 버전이 나간다.
# ---------------------------------------------------------------------------
resource "aws_cloudfront_cache_policy" "html" {
  name        = "${var.name_prefix}-html"
  comment     = "index.html — 엣지에 담지 않는다(자산 해시가 적힌 곳이라 낡으면 배포가 먹히지 않는다)"
  min_ttl     = 0
  default_ttl = 0
  max_ttl     = 60

  parameters_in_cache_key_and_forwarded_to_origin {
    enable_accept_encoding_brotli = true
    enable_accept_encoding_gzip   = true

    cookies_config {
      cookie_behavior = "none"
    }
    headers_config {
      header_behavior = "none"
    }
    query_strings_config {
      query_string_behavior = "none"
    }
  }
}

resource "aws_cloudfront_cache_policy" "assets" {
  name        = "${var.name_prefix}-assets"
  comment     = "Vite 해시 자산 — 내용이 바뀌면 파일명이 바뀌므로 영구 캐시가 안전하다"
  min_ttl     = 1
  default_ttl = 31536000
  max_ttl     = 31536000

  parameters_in_cache_key_and_forwarded_to_origin {
    enable_accept_encoding_brotli = true
    enable_accept_encoding_gzip   = true

    cookies_config {
      cookie_behavior = "none"
    }
    headers_config {
      header_behavior = "none"
    }
    query_strings_config {
      query_string_behavior = "none"
    }
  }
}

# API 응답은 엣지에 남기지 않는다 — 남으면 한 사용자의 응답이 다른 사용자에게 샌다.
data "aws_cloudfront_cache_policy" "caching_disabled" {
  name = "Managed-CachingDisabled"
}

# Authorization 헤더·쿼리·쿠키·Host 를 전부 오리진에 전달한다.
#
# Host 를 그대로 넘기는 것이 핵심이다. 오리진 TLS 검증에 쓰이는 이름(SNI)은 오리진 도메인
# (origin.victoryfairy.com)이고 Host 헤더는 그와 별개라, ALB 는 브라우저가 보낸 Host
# (victoryfairy.com)를 받는다. 덕분에 **Ingress 의 host 를 바꾸지 않아도 된다** — 기존 규칙이
# 그대로 맞고, 브라우저 직접 접속(전환 롤백 경로)과 CloudFront 경유가 같은 규칙을 쓴다.
#
# ⚠ Managed-AllViewerExceptHostHeader 로 바꾸면 Host 가 origin.victoryfairy.com 으로 전달돼
#   기존 Ingress 규칙의 host 조건과 어긋나 파드까지 못 가고 404 가 된다. 그 정책을 쓰려면
#   Ingress host 를 함께 origin 으로 내려야 한다.
data "aws_cloudfront_origin_request_policy" "all_viewer" {
  name = "Managed-AllViewer"
}

# ---------------------------------------------------------------------------
# 5) 응답 헤더 정책 — 브라우저가 얼마나 보관할지 + 보안 헤더
#
# override = true 라 S3 오브젝트에 Cache-Control 이 없거나 잘못 박혀 있어도 엣지가 덮어쓴다.
# 덕분에 배포 워크플로는 aws s3 sync 한 번으로 끝나고, 캐시 정책 조정은 FE 재배포 없이
# terraform apply 만으로 된다(종전 nginx.conf 를 고쳐 이미지를 다시 굽던 것과 대비).
# ---------------------------------------------------------------------------
resource "aws_cloudfront_response_headers_policy" "html" {
  name    = "${var.name_prefix}-html"
  comment = "index.html — 절대 캐시하지 않는다"

  custom_headers_config {
    items {
      header   = "Cache-Control"
      value    = "no-cache"
      override = true
    }
  }

  # nginx 시절에는 server_tokens off 뿐이었다. 엣지에서 공짜로 얻을 수 있는 것만 켠다.
  security_headers_config {
    content_type_options {
      override = true
    }
    frame_options {
      frame_option = "DENY"
      override     = true
    }
    referrer_policy {
      referrer_policy = "strict-origin-when-cross-origin"
      override        = true
    }
    strict_transport_security {
      access_control_max_age_sec = 31536000
      include_subdomains         = false # 서브도메인 전체를 HTTPS 로 못 박지 않는다(향후 여지)
      override                   = true
    }
  }
}

resource "aws_cloudfront_response_headers_policy" "assets" {
  name    = "${var.name_prefix}-assets"
  comment = "해시 자산 — 영구 캐시(immutable)"

  custom_headers_config {
    items {
      header   = "Cache-Control"
      value    = "public, max-age=31536000, immutable"
      override = true
    }
  }
}

# ---------------------------------------------------------------------------
# 6) CloudFront 배포
# ---------------------------------------------------------------------------
resource "aws_cloudfront_distribution" "this" {
  enabled             = true
  is_ipv6_enabled     = true
  comment             = "${var.name_prefix} FE(S3) + API(ALB) 단일 진입점"
  default_root_object = "index.html"
  price_class         = var.price_class
  aliases             = [var.domain_name]

  origin {
    origin_id                = local.s3_origin_id
    domain_name              = aws_s3_bucket.fe.bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.fe.id
  }

  origin {
    origin_id   = local.alb_origin_id
    domain_name = var.origin_domain_name

    custom_origin_config {
      http_port  = 80
      https_port = 443
      # ⚠ https-only 여야 한다. Bearer 토큰이 CloudFront→ALB 구간을 평문으로 건너가지 않게 한다.
      #   Ingress 의 ssl-redirect 어노테이션과도 맞물린다 — HTTP 로 붙으면 301 이 되돌아온다.
      origin_protocol_policy   = "https-only"
      origin_ssl_protocols     = ["TLSv1.2"]
      origin_read_timeout      = var.origin_read_timeout
      origin_keepalive_timeout = 5
    }
  }

  # 기본 = 정적 자산(index.html 등). 함수가 확장자 없는 경로를 index.html 로 돌린다.
  default_cache_behavior {
    target_origin_id       = local.s3_origin_id
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD"]
    cached_methods         = ["GET", "HEAD"]
    compress               = true # nginx.conf 의 gzip on 대체. ALB 는 압축을 대신해 주지 않는다

    cache_policy_id            = aws_cloudfront_cache_policy.html.id
    response_headers_policy_id = aws_cloudfront_response_headers_policy.html.id

    function_association {
      event_type   = "viewer-request"
      function_arn = aws_cloudfront_function.spa_fallback.arn
    }
  }

  # ⚠ 이 behavior 에는 SPA fallback 함수를 붙이지 않는다. 붙으면 없는 청크 요청이 index.html + 200 으로
  #   돌아와 "Unexpected token '<'" 로 번진다(nginx 의 try_files =404 와 같은 방어).
  ordered_cache_behavior {
    path_pattern           = "/assets/*"
    target_origin_id       = local.s3_origin_id
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD"]
    cached_methods         = ["GET", "HEAD"]
    compress               = true

    cache_policy_id            = aws_cloudfront_cache_policy.assets.id
    response_headers_policy_id = aws_cloudfront_response_headers_policy.assets.id
  }

  # API — ALB 로 통과. 캐시 없음, Authorization 전달, 쓰기 메서드 허용.
  dynamic "ordered_cache_behavior" {
    for_each = var.api_path_patterns

    content {
      path_pattern           = ordered_cache_behavior.value
      target_origin_id       = local.alb_origin_id
      viewer_protocol_policy = "redirect-to-https"

      # ⚠ 기본값(GET·HEAD)으로 두면 로그인·회원가입 등 모든 POST 가 405 로 막힌다.
      allowed_methods = ["GET", "HEAD", "OPTIONS", "PUT", "POST", "PATCH", "DELETE"]
      cached_methods  = ["GET", "HEAD"]

      # ⚠ 압축을 켜지 않는다. 채팅 SSE(text/event-stream)가 엣지에서 버퍼링될 여지를 만들지 않는 쪽을
      #   택했다 — JSON 압축 이득보다 이미 도는 실시간 스트림을 흔들지 않는 것이 우선이다.
      compress = false

      cache_policy_id          = data.aws_cloudfront_cache_policy.caching_disabled.id
      origin_request_policy_id = data.aws_cloudfront_origin_request_policy.all_viewer.id
    }
  }

  viewer_certificate {
    acm_certificate_arn      = var.certificate_arn
    ssl_support_method       = "sni-only"
    minimum_protocol_version = "TLSv1.2_2021"
  }

  restrictions {
    geo_restriction {
      restriction_type = "none"
    }
  }

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-fe"
  })
}

# ---------------------------------------------------------------------------
# 7) apex ALIAS — 실서비스 전환 스위치 (attach_apex_alias)
#
# ⚠ allow_overwrite 는 ExternalDNS 가 만들어 둔 기존 A 레코드를 덮어써 소유권을 가져오기 위한 것이다.
#   이것이 동작하려면 Ingress 의 host 가 먼저 origin.<domain> 으로 내려가 ExternalDNS 가 apex 를
#   더 이상 감시하지 않아야 한다. 순서를 뒤집으면 ExternalDNS 가 1분 내 ALB 로 되돌려 계속 다툰다.
# ---------------------------------------------------------------------------
resource "aws_route53_record" "apex_a" {
  count = var.attach_apex_alias ? 1 : 0

  zone_id         = var.route53_zone_id
  name            = var.domain_name
  type            = "A"
  allow_overwrite = true

  alias {
    name                   = aws_cloudfront_distribution.this.domain_name
    zone_id                = aws_cloudfront_distribution.this.hosted_zone_id
    evaluate_target_health = false # CloudFront ALIAS 는 헬스체크를 지원하지 않는다
  }
}

resource "aws_route53_record" "apex_aaaa" {
  count = var.attach_apex_alias ? 1 : 0

  zone_id         = var.route53_zone_id
  name            = var.domain_name
  type            = "AAAA"
  allow_overwrite = true

  alias {
    name                   = aws_cloudfront_distribution.this.domain_name
    zone_id                = aws_cloudfront_distribution.this.hosted_zone_id
    evaluate_target_health = false
  }
}
