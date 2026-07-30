# GitHub Actions OIDC 배포 롤 — "머지 = 배포" 파이프라인의 AWS 로그인.
#
# - image-ci     : dev_ai 머지 → 이미지 빌드 → ECR 푸시 → Lambda 코드 갱신
#                  (.github/workflows/collector-image.yml, dev_ai 브랜치)
# - terraform-ci : dev_infra 머지 → 이 스택 plan/apply
#                  (.github/workflows/collector-terraform.yml, dev_infra 브랜치)
#
# 장기 액세스키 없이 브랜치 단위로 신뢰를 건다(sub 조건). OIDC 프로바이더
# (token.actions.githubusercontent.com)는 계정에 이미 존재 — 참조만 한다.
# terraform-ci 가 이 파일의 롤들(자기 자신 포함)도 관리한다 — dev_infra 머지 권한이
# 곧 이 롤 권한 수정 권한이므로, 게이트는 PR 리뷰다.

data "aws_iam_openid_connect_provider" "github" {
  url = "https://token.actions.githubusercontent.com"
}

locals {
  account_id = data.aws_caller_identity.current.account_id
  tf_state   = "arn:aws:s3:::victoryfairy-tfstate"
}

# GitHub OIDC trust — 지정 브랜치의 Actions 런만 이 롤을 쓸 수 있다.
locals {
  gh_trust = {
    for branch in ["dev_ai", "dev_infra"] : branch => jsonencode({
      Version = "2012-10-17"
      Statement = [{
        Effect    = "Allow"
        Principal = { Federated = data.aws_iam_openid_connect_provider.github.arn }
        Action    = "sts:AssumeRoleWithWebIdentity"
        Condition = {
          StringEquals = {
            "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
            "token.actions.githubusercontent.com:sub" = "repo:${var.github_repo}:ref:refs/heads/${branch}"
          }
        }
      }]
    })
  }
}

# --- image-ci: ECR 푸시 + 함수 코드 갱신만 (테라폼·IAM 권한 없음) ---
resource "aws_iam_role" "image_ci" {
  name               = "${var.name}-image-ci"
  assume_role_policy = local.gh_trust["dev_ai"]
  tags               = var.tags
}

resource "aws_iam_role_policy" "image_ci" {
  name = "push-image-and-update-code"
  role = aws_iam_role.image_ci.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "EcrAuth"
        Effect   = "Allow"
        Action   = ["ecr:GetAuthorizationToken"]
        Resource = "*"
      },
      {
        Sid    = "EcrPush"
        Effect = "Allow"
        Action = [
          "ecr:BatchCheckLayerAvailability", "ecr:InitiateLayerUpload",
          "ecr:UploadLayerPart", "ecr:CompleteLayerUpload",
          "ecr:PutImage", "ecr:BatchGetImage", "ecr:DescribeImages",
        ]
        Resource = aws_ecr_repository.this.arn
      },
      {
        Sid      = "UpdateFunctionCode"
        Effect   = "Allow"
        Action   = ["lambda:UpdateFunctionCode", "lambda:GetFunction"]
        Resource = "arn:aws:lambda:${var.region}:${local.account_id}:function:${var.name}*"
      },
    ]
  })
}

# --- terraform-ci: 이 스택의 plan/apply 에 필요한 전부 (deployer-iam-policy.json 과
#     동일 범위 + backend state/lock + CI 롤 자기관리 + provider 데이터 조회) ---
resource "aws_iam_role" "terraform_ci" {
  name               = "${var.name}-terraform-ci"
  assume_role_policy = local.gh_trust["dev_infra"]
  tags               = var.tags
}

resource "aws_iam_role_policy" "terraform_ci" {
  name = "terraform-plan-apply"
  role = aws_iam_role.terraform_ci.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid    = "TfStateS3"
        Effect = "Allow"
        Action = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
        # 이 스택의 key 만 — environments/dev state 에는 접근 불가
        Resource = "${local.tf_state}/collector-lambda/*"
      },
      {
        Sid      = "TfStateS3List"
        Effect   = "Allow"
        Action   = ["s3:ListBucket"]
        Resource = local.tf_state
      },
      {
        Sid      = "TfLock"
        Effect   = "Allow"
        Action   = ["dynamodb:GetItem", "dynamodb:PutItem", "dynamodb:DeleteItem", "dynamodb:DescribeTable"]
        Resource = "arn:aws:dynamodb:${var.region}:${local.account_id}:table/victoryfairy-tflock"
      },
      {
        Sid    = "Ecr"
        Effect = "Allow"
        Action = [
          "ecr:CreateRepository", "ecr:DeleteRepository", "ecr:DescribeRepositories",
          "ecr:TagResource", "ecr:ListTagsForResource", "ecr:PutImageScanningConfiguration",
          "ecr:BatchGetImage", "ecr:DescribeImages", "ecr:ListImages", "ecr:BatchDeleteImage",
        ]
        Resource = "*"
      },
      {
        Sid    = "Lambda"
        Effect = "Allow"
        Action = [
          "lambda:CreateFunction", "lambda:DeleteFunction", "lambda:GetFunction",
          "lambda:UpdateFunctionCode", "lambda:UpdateFunctionConfiguration",
          "lambda:AddPermission", "lambda:RemovePermission", "lambda:GetPolicy",
          "lambda:TagResource", "lambda:ListVersionsByFunction", "lambda:ListTags",
          "lambda:GetFunctionCodeSigningConfig",
          "lambda:PutFunctionEventInvokeConfig", "lambda:GetFunctionEventInvokeConfig",
          "lambda:DeleteFunctionEventInvokeConfig",
        ]
        Resource = "arn:aws:lambda:*:${local.account_id}:function:${var.name}*"
      },
      {
        Sid    = "Ec2ForDbLambda"
        Effect = "Allow"
        Action = [
          "ec2:CreateSecurityGroup", "ec2:DeleteSecurityGroup",
          "ec2:DescribeSecurityGroups", "ec2:DescribeSecurityGroupRules",
          "ec2:AuthorizeSecurityGroupIngress", "ec2:RevokeSecurityGroupIngress",
          "ec2:AuthorizeSecurityGroupEgress", "ec2:RevokeSecurityGroupEgress",
          "ec2:DescribeSubnets", "ec2:DescribeVpcs",
          "ec2:DescribeNetworkInterfaces", "ec2:CreateTags", "ec2:DeleteTags",
        ]
        Resource = "*"
      },
      {
        Sid    = "EventBridge"
        Effect = "Allow"
        Action = [
          "events:PutRule", "events:DeleteRule", "events:DescribeRule",
          "events:PutTargets", "events:RemoveTargets", "events:ListTargetsByRule",
          "events:TagResource", "events:ListTagsForResource",
        ]
        Resource = "arn:aws:events:*:${local.account_id}:rule/${var.name}-*"
      },
      {
        Sid    = "IamRoles"
        Effect = "Allow"
        Action = [
          "iam:CreateRole", "iam:DeleteRole", "iam:GetRole", "iam:TagRole",
          "iam:PutRolePolicy", "iam:DeleteRolePolicy", "iam:GetRolePolicy",
          "iam:AttachRolePolicy", "iam:DetachRolePolicy", "iam:ListRolePolicies",
          "iam:ListAttachedRolePolicies", "iam:ListInstanceProfilesForRole",
        ]
        Resource = "arn:aws:iam::${local.account_id}:role/${var.name}-*"
      },
      {
        Sid      = "PassExecRoleToLambda"
        Effect   = "Allow"
        Action   = "iam:PassRole"
        Resource = "arn:aws:iam::${local.account_id}:role/${var.name}-lambda"
        Condition = {
          StringEquals = { "iam:PassedToService" = "lambda.amazonaws.com" }
        }
      },
      {
        Sid      = "ReadOidcProvider"
        Effect   = "Allow"
        Action   = ["iam:GetOpenIDConnectProvider"]
        Resource = data.aws_iam_openid_connect_provider.github.arn
      },
    ]
  })
}

output "image_ci_role_arn" {
  description = "collector-image.yml 이 assume 하는 롤 (dev_ai)"
  value       = aws_iam_role.image_ci.arn
}

output "terraform_ci_role_arn" {
  description = "collector-terraform.yml 이 assume 하는 롤 (dev_infra)"
  value       = aws_iam_role.terraform_ci.arn
}
