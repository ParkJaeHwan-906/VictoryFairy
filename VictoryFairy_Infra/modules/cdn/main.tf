# cdn 모듈: FE 정적 자산 버킷(S3) + CloudFront 배포.
#
# 이 모듈이 대체한 것: nginx 파드(k8s/24-fe-app.yaml — 2026-08-07 제거)와 그 nginx.conf. 정적 서버를 직접
# 운영하지 않고 S3 가 파일을 갖고 CloudFront 가 내보낸다. nginx.conf 가 하던 캐시 정책·
# SPA fallback·압축은 아래 Response Headers Policy / CloudFront Function / compress 로 옮겨왔다.
#
# 오리진이 3개인 이유: 사용자 대면 도메인을 하나로 유지하기 위해서다. CloudFront 가 진입점이 되어
#   /api/*, /rt/*                → ALB (기존 EKS 앱)
#   /user-profile-img/*, /temp/* → S3 asset 버킷 (사용자 업로드 이미지, modules/asset)
#   /characters/*, /items/*, /stores/*
#                                → S3 asset 버킷 (캐릭터 꾸미기 에셋, 같은 모듈·다른 접두사)
#   그 외                        → S3 fe 버킷 (정적 자산)
# 로 갈라 보내므로 FE·API·이미지가 계속 같은 오리진이고, CORS 도 FE 번들의 절대 URL 도 필요 없다.
#
# ⚠ 세 갈래는 경로가 서로 겹치지 않는다. 새 behavior 를 끼워도 기존 /assets/*·/api/*·/rt/* 의
#   매칭 결과는 바뀌지 않는다(precedence 번호만 밀린다).
#
# 구성과 주의점은 docs/fe-hosting.md.

locals {
  s3_origin_id    = "s3-fe"
  asset_origin_id = "s3-asset"
  alb_origin_id   = "alb-api"

  # 접두사(temp/)로 받아 경로 패턴(/temp/*)으로 바꾼다. 버킷 키와 URL 경로가 같은 문자열이라
  # 한 곳(루트 locals)에서 온 값을 양쪽이 나눠 쓰게 한다 — 어긋나면 이미지가 404 다.
  asset_profile_path_pattern = "/${var.asset_profile_prefix}*"
  asset_temp_path_pattern    = "/${var.asset_temp_prefix}*"

  # 캐릭터 꾸미기 에셋(characters/·items/·stores/). 위 둘과 같은 변환이지만 개수가 늘 수 있어
  # 목록으로 받는다 — behavior 는 아래에서 dynamic 블록으로 편다.
  asset_static_path_patterns = [for prefix in var.asset_static_prefixes : "/${prefix}*"]

  # 사용자 업로드 이미지 쪽 OAC·캐시 정책·응답 헤더 정책이 공유하는 AWS 리소스 이름.
  #
  # ⚠ 여기에 "-asset" 을 쓰지 말 것. 이 계정에는 FE 정적 자산용 victoryfairy-dev-assets 가
  #   이미 있다(캐시 정책·응답 헤더 정책 양쪽 — 아래 aws_*.assets). "-asset" 은 그것과 글자
  #   하나 차이라 콘솔 목록에 나란히 뜨면 사람이 잘못 고른다. 정책을 잘못 붙이면 이미지가
  #   깨지는 정도가 아니라 FE 전체의 캐시·보안 헤더가 바뀐다.
  #   그래서 용도가 그대로 드러나는 -profile-img 로 못 박는다.
  # (Terraform 라벨은 asset/asset_temp 그대로 둔다 — 버킷·오리진·modules/asset 과 짝을
  #  맞추기 위해서다. 콘솔에 보이는 이름만 다르며, 그 이름의 출처는 이 두 local 뿐이다.)
  asset_name        = "${var.name_prefix}-profile-img"
  asset_temp_name   = "${var.name_prefix}-profile-img-temp"
  asset_static_name = "${var.name_prefix}-character-asset"
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

# ⚠ releases/ 에 만료 규칙을 걸지 않는다.
#   누적량은 배포당 수백 KB 수준이라 필요할 때 수동 정리하는 편이 안전하다.
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

# asset 버킷용 OAC. 버킷 자체(+퍼블릭 차단·라이프사이클·버킷 정책)는 modules/asset 소유이고,
# 여기는 '이 배포가 그 버킷을 어떻게 읽는가' 만 갖는다 — 배포를 소유한 모듈이 오리진 배선을 갖는다.
# 상대편 버킷 정책은 이 배포 ARN 을 SourceArn 으로 받아 걸린다(루트에서 서로의 출력을 교차 주입).
resource "aws_cloudfront_origin_access_control" "asset" {
  name                              = local.asset_name
  description                       = "CloudFront → S3(${var.asset_bucket_name}) 전용 읽기"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
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

# 프로필 이미지 — Vite 해시 자산과 같은 계약이다. 업로드마다 키가 새 UUID 로 생기고 같은 키를
# 덮어쓰지 않으므로(교체하면 새 UUID + 옛 객체 삭제) 엣지에 오래 둬도 낡은 그림이 나올 수 없다.
# 그래서 무효화(create-invalidation)를 배포 파이프라인에 걸 필요도 없다.
resource "aws_cloudfront_cache_policy" "asset" {
  name        = local.asset_name
  comment     = "프로필 이미지 — UUID 키라 덮어쓰기가 없다(영구 캐시 안전)"
  min_ttl     = 1
  default_ttl = 31536000
  max_ttl     = 31536000

  parameters_in_cache_key_and_forwarded_to_origin {
    # 이미지는 이미 압축 포맷이라 브로틀리/gzip 이득이 없다. 캐시 키를 인코딩별로 쪼개
    # 히트율만 떨어뜨리지 않도록 둘 다 끈다(정적 번들과 다른 점).
    enable_accept_encoding_brotli = false
    enable_accept_encoding_gzip   = false

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

# temp/ — 하루면 사라지는 객체다. 길게 잡을 실익이 없어 따로 뺐다.
#   · 이 URL 은 가입 절차 몇 분 동안 본인 한 명이 한두 번 볼 뿐이라 장기 캐시의 히트가 없다.
#   · 반대로 TTL 이 길면 S3 에서 만료된 뒤에도 엣지가 사본을 계속 내보내, "지웠는데 아직 보이는"
#     구간이 하루 이상 길어진다. 임시 업로드에는 그쪽 위험이 더 크다.
# 캐릭터 꾸미기 에셋 — 고정 슬러그 키(items/cloth/basic.svg)라 디자인 교체가 같은 키를 덮어쓴다.
#   · 그래서 프로필 이미지(1년 immutable)와 같은 정책을 재사용하지 않는다. 재사용하면 브라우저가
#     1년을 물고 있어 무효화를 돌려도 옛 그림이 남는다.
#   · SVG 는 텍스트라 엣지 압축이 실제로 효과가 크다(프로필 이미지가 압축을 끈 것과 반대). 그래서
#     Accept-Encoding 을 캐시 키에 넣는다 — 인코딩별로 쪼개지지만 원본이 23종뿐이라 히트율 손실이
#     무시할 수준이다.
#   · min_ttl 을 0 으로 둬 오리진(S3)이 보낸 Cache-Control 을 그대로 존중한다. 업로드 스크립트가
#     max-age=86400 을 함께 올리므로 실제 TTL 의 출처는 한 곳이다.
resource "aws_cloudfront_cache_policy" "asset_static" {
  name        = local.asset_static_name
  comment     = "캐릭터 꾸미기 에셋 — 고정 키라 교체 가능, 하루 캐시 + 엣지 압축"
  min_ttl     = 0
  default_ttl = var.asset_static_ttl_seconds
  max_ttl     = var.asset_static_ttl_seconds

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

resource "aws_cloudfront_cache_policy" "asset_temp" {
  name        = local.asset_temp_name
  comment     = "가입 전 임시 업로드 — 하루살이 객체라 짧게만 담는다"
  min_ttl     = 0
  default_ttl = var.asset_temp_ttl_seconds
  max_ttl     = var.asset_temp_ttl_seconds

  parameters_in_cache_key_and_forwarded_to_origin {
    enable_accept_encoding_brotli = false
    enable_accept_encoding_gzip   = false

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

# 사용자 업로드 이미지 전용 헤더 정책 2종.
#
# ⚠ nosniff 가 여기서는 장식이 아니다. 이 오브젝트들은 '사용자가 올린 바이트' 이고, 그것을
#   FE 와 같은 오리진(victoryfairy.com)으로 내보낸다. HTML 을 .jpg 로 위장해 올린 뒤 브라우저가
#   내용을 스니핑해 HTML 로 렌더하면 동일 출처 XSS 가 된다. Content-Type 을 곧이곧대로 지키게
#   막고, 프레임 삽입도 함께 거절한다. (파일 내용이 진짜 이미지인지 검사하는 것은 BE 몫이다.)
resource "aws_cloudfront_response_headers_policy" "asset" {
  name    = local.asset_name
  comment = "프로필 이미지 — 영구 캐시(immutable) + 스니핑 차단"

  custom_headers_config {
    items {
      header   = "Cache-Control"
      value    = "public, max-age=31536000, immutable"
      override = true
    }
  }

  security_headers_config {
    content_type_options {
      override = true
    }
    frame_options {
      frame_option = "DENY"
      override     = true
    }
  }
}

# immutable 을 붙이지 않는 것이 이 정책의 요점이다 — 근거는 위 cache_policy.asset_static 주석.
resource "aws_cloudfront_response_headers_policy" "asset_static" {
  name    = local.asset_static_name
  comment = "캐릭터 꾸미기 에셋 — 하루 캐시(교체 가능) + 스니핑 차단"

  custom_headers_config {
    items {
      header   = "Cache-Control"
      value    = "public, max-age=${var.asset_static_ttl_seconds}"
      override = true
    }
  }

  security_headers_config {
    content_type_options {
      override = true
    }
    frame_options {
      frame_option = "DENY"
      override     = true
    }
  }
}

resource "aws_cloudfront_response_headers_policy" "asset_temp" {
  name    = local.asset_temp_name
  comment = "임시 업로드 — 짧은 캐시 + 스니핑 차단"

  custom_headers_config {
    items {
      header   = "Cache-Control"
      value    = "public, max-age=${var.asset_temp_ttl_seconds}"
      override = true
    }
  }

  security_headers_config {
    content_type_options {
      override = true
    }
    frame_options {
      frame_option = "DENY"
      override     = true
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

  # 사용자 업로드 이미지(modules/asset). 새 배포를 만들지 않고 이 배포에 오리진만 더한다 —
  # 이미지가 apex 도메인으로 나가야 FE 가 상대경로로 참조할 수 있고, 인증서·DNS 도 그대로 쓴다.
  origin {
    origin_id                = local.asset_origin_id
    domain_name              = var.asset_bucket_regional_domain_name
    origin_access_control_id = aws_cloudfront_origin_access_control.asset.id
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

  # ── 사용자 업로드 이미지 ────────────────────────────────────────────────────
  # 기존 behavior 뒤에 붙인다. 경로가 겹치지 않아 순서는 결과에 영향을 주지 않지만, 뒤에 두면
  # /api/*·/rt/* 의 precedence 번호가 그대로라 배포 diff 가 작다.
  #
  # ⚠ SPA fallback 함수를 붙이지 않는다. 붙으면 없는 이미지 요청이 index.html + 200 으로 돌아와
  #   깨진 그림 대신 HTML 이 <img> 에 들어간다(/assets/* 와 같은 이유).
  # ⚠ 읽기 전용이다(GET/HEAD). 업로드는 브라우저가 아니라 user-app 파드가 SDK 로 S3 에 직접
  #   PUT 한다(IRSA: modules/user-irsa) — 그래서 이 경로로 쓰기 메서드를 열 이유가 없고,
  #   버킷에 CORS 설정도 필요 없다. 브라우저 presigned PUT 방식으로 바꾸면 둘 다 다시 볼 것.
  ordered_cache_behavior {
    path_pattern           = local.asset_profile_path_pattern
    target_origin_id       = local.asset_origin_id
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD"]
    cached_methods         = ["GET", "HEAD"]

    # 이미지는 이미 압축돼 있어 엣지 압축이 CPU 만 쓰고 이득이 없다.
    compress = false

    cache_policy_id            = aws_cloudfront_cache_policy.asset.id
    response_headers_policy_id = aws_cloudfront_response_headers_policy.asset.id
  }

  # ⚠ temp/ 도 공개적으로 읽힌다(인증 없음). 프론트가 '가입을 마치기 전에' 방금 올린 사진을
  #   미리보기로 다시 읽어야 하는데, 그 시점에는 아직 토큰이 없기 때문이다.
  #   한계 — 키가 UUID 라 목록을 훑어 찾아낼 수는 없지만(버킷은 비공개, 이 배포도 목록을
  #   내보내지 않는다), **링크를 아는 사람은 누구나 그 이미지를 읽을 수 있다.** 하루면 만료되고
  #   가입 절차에만 쓰이는 사진이라 감수한다. 서명 URL(CloudFront signed URL)로 좁히려면
  #   키 그룹·서명 발급이 BE 에 붙어야 하므로 별건으로 판단할 것.
  ordered_cache_behavior {
    path_pattern           = local.asset_temp_path_pattern
    target_origin_id       = local.asset_origin_id
    viewer_protocol_policy = "redirect-to-https"
    allowed_methods        = ["GET", "HEAD"]
    cached_methods         = ["GET", "HEAD"]
    compress               = false

    cache_policy_id            = aws_cloudfront_cache_policy.asset_temp.id
    response_headers_policy_id = aws_cloudfront_response_headers_policy.asset_temp.id
  }

  # 캐릭터 꾸미기 에셋 — 상점·캐릭터 이미지는 인증 없이 읽힌다. 어떤 아이템이 있는지는 상점 목록
  # API 가 이미 전부 공개하는 정보라 숨길 것이 없고, 이미지가 토큰 뒤에 있으면 <img> 로 못 그린다.
  #
  # ⚠ SPA fallback 함수를 붙이지 않는다(/assets/*·프로필 이미지와 같은 이유) — 붙으면 없는 이미지가
  #   index.html + 200 으로 돌아와 깨진 그림 대신 HTML 이 <img> 에 들어간다.
  # ⚠ 읽기 전용이다(GET/HEAD). 이 접두사는 사람이 CLI 로 올리는 영역이라 앱조차 쓰지 않는다.
  # ⚠ 프로필 이미지 behavior 보다 뒤에 둔다 — 경로가 서로 겹치지 않아 순서가 매칭 결과를 바꾸지는
  #   않지만, 앞에 끼우면 기존 behavior 의 precedence 번호가 전부 밀려 배포 diff 가 커진다.
  dynamic "ordered_cache_behavior" {
    for_each = local.asset_static_path_patterns

    content {
      path_pattern           = ordered_cache_behavior.value
      target_origin_id       = local.asset_origin_id
      viewer_protocol_policy = "redirect-to-https"
      allowed_methods        = ["GET", "HEAD"]
      cached_methods         = ["GET", "HEAD"]

      # SVG 는 텍스트라 압축이 크게 듣는다(프로필 이미지와 반대).
      compress = true

      cache_policy_id            = aws_cloudfront_cache_policy.asset_static.id
      response_headers_policy_id = aws_cloudfront_response_headers_policy.asset_static.id
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
