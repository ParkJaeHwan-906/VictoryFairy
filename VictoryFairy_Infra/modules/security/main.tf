# security 모듈: 환경 공용 IAM — CI/CD(GitHub Actions) 배포 자격
#
# 구성:
#   1) GitHub OIDC 프로바이더 — GitHub Actions 가 장기 액세스 키 없이(keyless)
#      단기 토큰으로 AWS 역할을 맡을 수 있게 하는 신뢰 앵커(계정당 1개).
#   2) 배포 역할 — 지정된 레포/브랜치의 워크플로만 맡을 수 있고(최소 신뢰),
#      권한은 ECR push(지정 리포지토리만) + eks:DescribeCluster 로 한정(최소 권한, SKILL §7).
#   3) EKS Access Entry — 배포 역할에 k8s 권한(victoryfairy 네임스페이스 Edit)을 부여.
#      전제: eks 모듈 authentication_mode = API_AND_CONFIG_MAP.

data "aws_caller_identity" "current" {}
data "aws_region" "current" {}

# ---------------------------------------------------------------------------
# 1) GitHub OIDC 프로바이더 (token.actions.githubusercontent.com)
# ---------------------------------------------------------------------------
resource "aws_iam_openid_connect_provider" "github" {
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
  # AWS 는 GitHub OIDC 에 대해 자체 신뢰 검증을 수행하지만 필드는 필수 — 표준 값 지정.
  thumbprint_list = ["6938fd4d98bab03faadb97b34396831e3780aea1"]

  tags = merge(var.tags, {
    Name = "github-actions-oidc"
  })
}

# ---------------------------------------------------------------------------
# 2) 배포 역할 — 지정 레포/브랜치의 GitHub Actions 만 AssumeRole 가능
# ---------------------------------------------------------------------------
data "aws_iam_policy_document" "github_assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    # 이 레포의 지정 브랜치에서 실행된 워크플로만 허용.
    condition {
      test     = "StringLike"
      variable = "token.actions.githubusercontent.com:sub"
      values   = [for ref in var.github_allowed_refs : "repo:${var.github_repository}:ref:refs/heads/${ref}"]
    }
  }
}

resource "aws_iam_role" "github_actions" {
  name               = "${var.name_prefix}-github-actions"
  assume_role_policy = data.aws_iam_policy_document.github_assume.json

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-github-actions"
  })
}

data "aws_iam_policy_document" "github_actions" {
  # ECR 로그인 토큰 발급은 리소스 단위 제한 미지원 → "*" 필요.
  statement {
    sid       = "EcrAuth"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  # 이미지 push/pull 은 지정 리포지토리로만 제한.
  statement {
    sid = "EcrPush"
    actions = [
      "ecr:BatchCheckLayerAvailability",
      "ecr:BatchGetImage",
      "ecr:CompleteLayerUpload",
      "ecr:DescribeImages",
      "ecr:GetDownloadUrlForLayer",
      "ecr:InitiateLayerUpload",
      "ecr:PutImage",
      "ecr:UploadLayerPart",
    ]
    resources = var.ecr_repository_arns
  }

  # kubeconfig 구성(aws eks update-kubeconfig)에 필요.
  statement {
    sid       = "EksDescribe"
    actions   = ["eks:DescribeCluster"]
    resources = ["arn:aws:eks:${data.aws_region.current.name}:${data.aws_caller_identity.current.account_id}:cluster/${var.cluster_name}"]
  }

  # FE 정적 배포: 번들을 S3 로 sync 한다(종전 ECR push + kubectl set image 경로를 대체).
  #
  # ⚠ s3:DeleteObject 를 일부러 주지 않는다. 배포는 --delete 없이 sync 하므로 필요가 없고,
  #   없으면 CI 권한만으로는 사이트를 지울 수 없다. 옛 자산 정리는 사람이 별도 권한으로 한다.
  #   (--delete 를 쓰면 캐시된 옛 index.html 이 참조하는 청크가 사라져 화면이 깨진다 —
  #    docs/fe-cdn-migration.md §6)
  # ⚠ ListBucket 은 버킷 ARN 에, 오브젝트 조작은 <ARN>/* 에 붙는다. 두 형태를 섞으면
  #   sync 가 조용히 전체 업로드로 퇴화하거나 AccessDenied 로 죽는다.
  dynamic "statement" {
    for_each = var.fe_bucket_arn != "" ? [1] : []

    content {
      sid       = "FeBucketList"
      actions   = ["s3:ListBucket"]
      resources = [var.fe_bucket_arn]
    }
  }

  dynamic "statement" {
    for_each = var.fe_bucket_arn != "" ? [1] : []

    content {
      sid = "FeBucketWrite"
      actions = [
        "s3:GetObject", # sync 의 변경 비교(ETag·크기)에 필요
        "s3:PutObject",
      ]
      resources = ["${var.fe_bucket_arn}/*"]
    }
  }

  # index.html 은 엣지 TTL 0 이라 배포·롤백에 무효화가 필요하지 않다(오브젝트를 바꾸면 바로 반영된다).
  # 그래도 남겨두는 것은 비상용이다 — 엣지에 잘못된 응답이 붙었을 때 강제로 떼어내야 할 수 있다.
  dynamic "statement" {
    for_each = var.fe_distribution_arn != "" ? [1] : []

    content {
      sid = "FeInvalidation"
      actions = [
        "cloudfront:CreateInvalidation",
        "cloudfront:GetInvalidation",
      ]
      resources = [var.fe_distribution_arn]
    }
  }

  # 정제 파이프라인 Lambda 배포: CI 가 새 이미지를 push 한 뒤 함수 코드를 교체한다.
  # 컨테이너 Lambda 는 태그를 생성 시점에 digest 로 고정하므로 push 만으로는 반영되지
  # 않는다 — UpdateFunctionCode 호출이 반드시 필요하다.
  # Get* 는 롤백용 직전 image_uri 조회와 `aws lambda wait function-updated-v2` 에 쓰인다.
  # 지정 함수 ARN 으로만 제한하며, 목록이 비면 statement 자체를 만들지 않는다
  # (빈 resources 는 IAM 정책 생성이 거부된다).
  dynamic "statement" {
    for_each = length(var.lambda_function_arns) > 0 ? [1] : []

    content {
      sid = "LambdaDeploy"
      actions = [
        "lambda:GetFunction",
        "lambda:GetFunctionConfiguration",
        "lambda:UpdateFunctionCode",
      ]
      resources = var.lambda_function_arns
    }
  }
}

resource "aws_iam_role_policy" "github_actions" {
  name   = "${var.name_prefix}-github-actions"
  role   = aws_iam_role.github_actions.id
  policy = data.aws_iam_policy_document.github_actions.json
}

# ---------------------------------------------------------------------------
# 3) EKS Access Entry — CI 역할에 k8s 권한 부여 (앱 네임스페이스 Edit 로 한정)
# ---------------------------------------------------------------------------
resource "aws_eks_access_entry" "github_actions" {
  cluster_name  = var.cluster_name
  principal_arn = aws_iam_role.github_actions.arn
  type          = "STANDARD"

  tags = merge(var.tags, {
    Name = "${var.name_prefix}-github-actions"
  })
}

resource "aws_eks_access_policy_association" "github_actions" {
  cluster_name  = var.cluster_name
  principal_arn = aws_iam_role.github_actions.arn
  policy_arn    = "arn:aws:eks::aws:cluster-access-policy/AmazonEKSEditPolicy"

  access_scope {
    type       = "namespace"
    namespaces = var.deploy_namespaces
  }

  depends_on = [aws_eks_access_entry.github_actions]
}
