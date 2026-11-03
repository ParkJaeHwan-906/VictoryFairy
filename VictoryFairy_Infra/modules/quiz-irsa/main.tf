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

  # 이닝 종료 이벤트 SQS 큐 소비 — py-collector 가 S3 에 쓴 이닝 종료 알림을 받아
  # 예측 퀴즈를 정산한다(modules/refine-pipeline 이 큐와 S3→SQS 알림 배선을 소유).
  # SendMessage 는 S3 서비스 principal 몫이라 여기엔 없다 — quiz-app 은 소비만 한다.
  statement {
    sid       = "ConsumeInningEvents"
    actions   = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes"]
    resources = [var.inning_events_queue_arn]
  }

  # SQS 메시지가 가리키는 inning-events/ 문서 본문 읽기. SQS 권한만 주고 이걸 빠뜨려서
  # (2026-10-04 라이브 운영 중 발견) 리스너가 메시지는 받되 매번 s3:GetObject
  # AccessDenied 로 정산을 못 하고 있었다 — ListBucket 은 필요 없다(메시지가 정확한
  # 키를 주므로 나열이 아니라 단건 조회).
  statement {
    sid       = "GetInningEvents"
    actions   = ["s3:GetObject"]
    resources = ["${local.crawl_bucket_arn}/inning-events/*"]
  }
}

resource "aws_iam_role_policy" "quiz_app" {
  name   = "${var.name_prefix}-quiz-app"
  role   = aws_iam_role.quiz_app.id
  policy = data.aws_iam_policy_document.quiz_app.json
}
