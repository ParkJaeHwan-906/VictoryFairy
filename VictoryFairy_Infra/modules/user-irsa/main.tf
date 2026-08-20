# user-app 파드용 IRSA — asset 버킷의 프로필 이미지 접두사 2개만 읽고 쓴다
# (파드 단위 최소 권한, SKILL §4).
#
# 왜 필요한가: 프로필 이미지 업로드(BE :user 모듈)가 사용자가 올린 파일을 S3 에 넣고, 가입이
#   확정되면 temp/ → user-profile-img/ 로 옮기고, 스케줄러가 미완주분을 지운다. 노드 인스턴스
#   롤에는 S3 권한이 없고 몰아주지도 않는다(스킬 §4) — 이 역할을 user-app SA 에만 붙인다.
#
# 왜 quiz-irsa 를 일반화하지 않았나: 두 역할은 대상 버킷도 액션도 다르다(quiz 는 크롤 버킷
#   읽기 전용, 여기는 자산 버킷 읽기·쓰기·삭제). 공용 모듈로 묶으려면 "버킷 + 접두사 + 액션
#   목록" 을 통째로 변수로 받아야 하는데, 그러면 정책이 호출부 값에 따라 아무 모양이나 될 수
#   있어 '최소 권한' 을 코드로 못 박지 못한다. 이 저장소가 alb·dns·quiz 마다 IRSA 모듈을
#   따로 두는 이유와 같다 — 파드 하나의 권한을 한 파일에서 통째로 읽을 수 있게 한다.
#
# 배선(이 모듈 밖): 역할 ARN 을 k8s ServiceAccount(victoryfairy/user-app)의
#   eks.amazonaws.com/role-arn 어노테이션에 지정하고, Deployment 에 serviceAccountName 을
#   건다(매니페스트: k8s/20-user-app.yaml — 현재 SA 가 없어 새로 만들어야 한다).
#   파드 쪽 코드 변경은 없다 — AWS SDK 기본 자격증명 체인이 EKS 웹훅이 주입한 토큰을 집어 쓴다.

locals {
  bucket_arn = "arn:aws:s3:::${var.asset_bucket_name}"
}

data "aws_iam_policy_document" "user_app_assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [var.oidc_provider_arn]
    }

    # 이 SA(victoryfairy/user-app)의 토큰만 이 역할을 맡을 수 있다.
    condition {
      test     = "StringEquals"
      variable = "${var.oidc_provider_url}:sub"
      values   = ["system:serviceaccount:${var.service_account_namespace}:${var.service_account_name}"]
    }

    condition {
      test     = "StringEquals"
      variable = "${var.oidc_provider_url}:aud"
      values   = ["sts.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "user_app" {
  name               = "${var.name_prefix}-user-app"
  assume_role_policy = data.aws_iam_policy_document.user_app_assume.json

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-user-app"
  })
}

data "aws_iam_policy_document" "user_app" {
  # 임시 업로드 나열 — 정리 스케줄러가 listObjectsV2 로 temp/ 를 훑어 24시간 경과분을 찾는다.
  # ListBucket 은 버킷 ARN 에 걸리는 액션이라 prefix 조건으로 범위를 좁힌다.
  # ⚠ 앱은 요청에 prefix=temp/ 를 반드시 실어야 한다. 접두사 없이 부르면 조건에 걸려 AccessDenied 다
  #   (그게 의도다 — 이 역할로 버킷 전체를 훑을 수 없다).
  statement {
    sid       = "ListTempUploads"
    actions   = ["s3:ListBucket"]
    resources = [local.bucket_arn]

    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = ["${var.temp_prefix}*"]
    }
  }

  # 두 접두사에 한해 읽기·쓰기·삭제.
  #   temp/            업로드(Put) → 미리보기·확정 이동을 위한 읽기(Get) → 정리·이동 후 삭제(Delete)
  #   user-profile-img/ 확정 저장(Put) → 이동을 위한 읽기(Get) → 교체·탈퇴 시 삭제(Delete)
  # ⚠ 이 밖의 접두사·액션은 주지 않는다. 특히 버킷 정책/BPA 를 만질 수 있는 s3:Put*Policy 류와
  #   접두사 없는 ListBucket 은 의도적으로 빠져 있다.
  statement {
    sid = "ReadWriteProfileObjects"

    actions = [
      "s3:DeleteObject",
      "s3:GetObject",
      "s3:PutObject",
    ]

    resources = [
      "${local.bucket_arn}/${var.profile_prefix}*",
      "${local.bucket_arn}/${var.temp_prefix}*",
    ]
  }
}

resource "aws_iam_role_policy" "user_app" {
  name   = "${var.name_prefix}-user-app"
  role   = aws_iam_role.user_app.id
  policy = data.aws_iam_policy_document.user_app.json
}
