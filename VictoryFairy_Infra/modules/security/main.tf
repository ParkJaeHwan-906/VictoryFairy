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
