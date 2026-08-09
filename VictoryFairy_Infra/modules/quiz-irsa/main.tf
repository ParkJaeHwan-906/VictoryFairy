# quiz-app 파드용 IRSA — S3 quiz-candidates/ 읽기 전용 (파드 단위 최소 권한, SKILL §4).
#
# 왜 필요한가: 퀴즈 적재기(BE :quiz 모듈)가 매일 crawl 버킷의 quiz-candidates/{date}/*.json 을
#   읽어 RDB 로 옮긴다. 노드 인스턴스 롤에는 S3 권한이 없고 몰아주지도 않는다(스킬 §4) —
#   이 역할을 quiz-app ServiceAccount 에만 붙인다.
# 배선: 역할 ARN 을 k8s ServiceAccount(victoryfairy/quiz-app)의 eks.amazonaws.com/role-arn
#   어노테이션에 지정한다(매니페스트: k8s/21-quiz-app.yaml). 파드 쪽 코드 변경은 없다 —
#   AWS SDK 기본 자격증명 체인이 EKS 웹훅이 주입한 토큰을 집어 쓴다.

locals {
  crawl_bucket_arn = "arn:aws:s3:::${var.crawl_bucket_name}"
}

data "aws_iam_policy_document" "quiz_app_assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [var.oidc_provider_arn]
    }

    # 이 SA(victoryfairy/quiz-app)의 토큰만 이 역할을 맡을 수 있다.
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

resource "aws_iam_role" "quiz_app" {
  name               = "${var.name_prefix}-quiz-app"
  assume_role_policy = data.aws_iam_policy_document.quiz_app_assume.json

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-quiz-app"
  })
}

data "aws_iam_policy_document" "quiz_app" {
  # 날짜 폴더 나열 — 적재기가 listObjectsV2 로 quiz-candidates/{date}/ 를 훑는다.
  # ListBucket 은 버킷 ARN 에 걸리는 액션이라 prefix 조건으로 범위를 좁힌다.
  statement {
    sid       = "ListQuizCandidates"
    actions   = ["s3:ListBucket"]
    resources = [local.crawl_bucket_arn]

    condition {
      test     = "StringLike"
      variable = "s3:prefix"
      values   = ["quiz-candidates/*"]
    }
  }

  # 후보 JSON 읽기. 쓰기 액션은 없다 — 이 버킷의 저자는 수집·정제 파이프라인뿐이다.
  statement {
    sid       = "GetQuizCandidates"
    actions   = ["s3:GetObject"]
    resources = ["${local.crawl_bucket_arn}/quiz-candidates/*"]
  }
}

resource "aws_iam_role_policy" "quiz_app" {
  name   = "${var.name_prefix}-quiz-app"
  role   = aws_iam_role.quiz_app.id
  policy = data.aws_iam_policy_document.quiz_app.json
}
