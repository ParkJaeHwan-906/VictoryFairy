# asset 모듈: 사용자 업로드 자산(프로필 이미지) 버킷.
#
# fe 버킷(modules/cdn §1)과 같은 원칙이다 — 퍼블릭 접근 전면 차단, 유일한 독자는 OAC 를 든
# CloudFront. 다른 점은 '저자'다: fe 는 CI 가 배포 때 sync 하고, 여기는 user-app 파드가
# 런타임에 쓴다(권한은 modules/user-irsa 의 IRSA 역할).
#
# 키 구조 — BE 의 업로드 키 접두사와 문자 그대로 일치해야 한다:
#   temp/<uuid>              가입 전 임시 업로드. 앱 스케줄러(매일 04:00 KST)가 24시간 경과분 삭제.
#   user-profile-img/<uuid>  확정된 프로필 이미지. 지우는 주체는 사용자(교체·탈퇴)뿐이다.
# 두 접두사는 CloudFront 의 경로 패턴(modules/cdn)과도 짝이다 — 한쪽만 바꾸면 이미지가 404 가 된다.
#
# 왜 별도 모듈인가: fe 버킷과 라이프사이클이 다르다(배포 산출물 vs 사용자 데이터). 한 모듈에
# 섞으면 "FE 를 재배포하려다 사용자 사진 규칙을 건드리는" 일이 생긴다(SKILL §8 단일 책임).
# 다만 CloudFront 쪽 배선(오리진·OAC·behavior)은 배포를 소유한 modules/cdn 에 있다 —
# 배포는 하나뿐이고 새로 만들지 않기 때문이다.

# ---------------------------------------------------------------------------
# 1) 버킷 — 퍼블릭 접근 완전 차단
#
# ⚠ 버저닝을 켜지 않았다(fe 버킷과 다른 점). 키가 업로드마다 새 UUID 라 같은 키를 덮어쓰는
#   일이 없어 '이전 버전' 이라는 개념이 성립하지 않고, 오히려 사용자가 사진을 지워도
#   비현행 버전으로 원본이 남는다(탈퇴·교체 요청과 어긋난다). 되돌릴 이유가 생기면
#   노후 버전 만료 규칙을 함께 넣을 것.
#
# ⚠ prevent_destroy 를 건다 — mysql-ec2 의 데이터 EBS 와 같은 이유이고, 여기가 더 취약하다.
#   그 EBS 에는 DLM 스냅샷과 S3 덤프가 있지만 이 버킷에는 **버저닝도 백업도 없다.**
#   bucket 이름을 한 글자만 고쳐도 그것은 force-replacement 라, 가드가 없으면 plan 이
#   조용히 "destroy + create" 로 나오고 apply 하는 순간 업로드된 사진이 전량 사라진다.
#   (S3 는 비어 있지 않은 버킷의 destroy 가 실패하지만, 그 실패를 믿고 설계하지 않는다.)
#
#   가드가 하는 일: destroy/replace 가 계획에 들어가면 **plan 단계에서 에러로 멈춘다.**
#   즉 앞으로 var.bucket_name 변경은 "실수로 통과"가 불가능하다 — 그게 목적이다.
#   ⚠ 부수효과: 이 환경 전체의 terraform destroy 도 이 버킷 때문에 실패한다(의도된 것).
#
#   정말로 지워야 할 때(=의도적으로 가드를 걷어내는 절차):
#     1. 먼저 데이터를 옮긴다 — aws s3 sync s3://<현재 버킷> s3://<새 버킷 또는 백업 위치>
#     2. 이 lifecycle 블록을 지우는 커밋을 따로 만든다(리뷰가 보이도록 코드 변경 하나만).
#     3. terraform apply 로 가드 해제를 먼저 반영한다(이 단계에서는 아무것도 파괴되지 않는다).
#     4. 그 다음에 이름 변경·삭제를 plan 으로 확인하고 apply 한다.
#     5. 끝나면 lifecycle 블록을 되돌려 다시 잠근다.
#   ⚠ 이름을 바꾸면 BE 설정과 k8s 의 USER_PROFILE_IMAGE_BUCKET 도 같이 바꿔야 한다.
# ---------------------------------------------------------------------------
resource "aws_s3_bucket" "this" {
  bucket = var.bucket_name

  tags = merge(var.tags, {
    Name = var.bucket_name
  })

  lifecycle {
    prevent_destroy = true
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "this" {
  bucket = aws_s3_bucket.this.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

# 이 버킷은 절대 퍼블릭이 아니다. 브라우저는 CloudFront 도메인으로만 이미지를 읽는다.
resource "aws_s3_bucket_public_access_block" "this" {
  bucket = aws_s3_bucket.this.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# ---------------------------------------------------------------------------
# 2) 라이프사이클
# ---------------------------------------------------------------------------
resource "aws_s3_bucket_lifecycle_configuration" "this" {
  bucket = aws_s3_bucket.this.id

  # temp/ 만료 — 1차 방어는 앱 스케줄러(매일 04:00 KST, 24시간 경과분 삭제)이고
  # 이 규칙은 그 스케줄러가 며칠 멈췄을 때를 위한 2차 방어(안전망)다. 스케줄러가 죽어도
  # 가입을 완주하지 않은 사진이 무한 축적되지 않는다.
  #
  # ⚠ 정확히 24시간이 아니다 — S3 만료는 일 단위로 UTC 자정 이후 비동기 평가되므로 실제 삭제는
  #   업로드 24~48시간 뒤다. '정시 정리' 는 앱의 몫이고 여기는 상한선일 뿐이니, 이 값을
  #   줄여 앱 스케줄러를 대체하려 하지 말 것(1일이 S3 가 표현할 수 있는 최소 단위다).
  rule {
    id     = "expire-temp"
    status = "Enabled"

    filter {
      prefix = var.temp_prefix
    }

    expiration {
      days = var.temp_expiration_days
    }
  }

  # ⚠ user-profile-img/ 에는 어떤 만료 규칙도 걸지 않는다(의도적 부재 — 지운 것이 아니다).
  #   걸면 사용자의 프로필 사진이 조용히 사라지고, DB 에는 URL 만 남아 이미지가 깨질 때까지
  #   아무도 모른다. 용량이 문제가 되면 만료가 아니라 스토리지 클래스 전환으로 풀 것.

  # 멈춘 멀티파트 업로드가 요금만 먹는 것을 막는다(fe 버킷과 동일).
  rule {
    id     = "abort-incomplete-multipart"
    status = "Enabled"

    filter {}

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}

# ---------------------------------------------------------------------------
# 3) 버킷 정책 — 지정한 CloudFront 배포만, 지정한 두 접두사만 읽는다
#
# SourceArn 조건이 없으면 다른 계정의 CloudFront 도 이 버킷을 읽는다(fe 와 동일한 방어).
# 리소스도 접두사 두 개로 좁혔다 — behavior 를 잘못 만들어도 그 밖의 키는 엣지로 새지 않는다.
# ---------------------------------------------------------------------------
data "aws_iam_policy_document" "this" {
  statement {
    sid     = "AllowCloudFrontRead"
    actions = ["s3:GetObject"]

    resources = [
      "${aws_s3_bucket.this.arn}/${var.profile_prefix}*",
      "${aws_s3_bucket.this.arn}/${var.temp_prefix}*",
    ]

    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [var.cloudfront_distribution_arn]
    }
  }
}

resource "aws_s3_bucket_policy" "this" {
  bucket = aws_s3_bucket.this.id
  policy = data.aws_iam_policy_document.this.json

  # 퍼블릭 차단이 먼저 걸린 뒤 정책을 붙인다(BlockPublicPolicy 판정 순서 안정화).
  depends_on = [aws_s3_bucket_public_access_block.this]
}
