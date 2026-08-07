# Cluster Autoscaler 용 IRSA(IAM Roles for Service Accounts) — 파드 단위 최소 권한(SKILL §4).
#
# 배선: 이 역할 ARN 을 k8s ServiceAccount(kube-system/cluster-autoscaler)의
#   eks.amazonaws.com/role-arn 어노테이션에 지정한다(매니페스트: k8s/50-cluster-autoscaler.yaml).
# CA 는 ASG 태그(k8s.io/cluster-autoscaler/enabled, .../<cluster>=owned — main.tf 의
#   aws_autoscaling_group_tag.ca)로 대상 노드그룹을 자동 발견한다.

data "aws_iam_policy_document" "cluster_autoscaler_assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.this.arn]
    }

    # 이 SA(kube-system/cluster-autoscaler)의 토큰만 이 역할을 맡을 수 있다.
    condition {
      test     = "StringEquals"
      variable = "${replace(aws_eks_cluster.this.identity[0].oidc[0].issuer, "https://", "")}:sub"
      values   = ["system:serviceaccount:kube-system:cluster-autoscaler"]
    }

    condition {
      test     = "StringEquals"
      variable = "${replace(aws_eks_cluster.this.identity[0].oidc[0].issuer, "https://", "")}:aud"
      values   = ["sts.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "cluster_autoscaler" {
  name               = "${var.cluster_name}-cluster-autoscaler"
  assume_role_policy = data.aws_iam_policy_document.cluster_autoscaler_assume.json

  tags = merge(var.tags, {
    Name = "${var.cluster_name}-cluster-autoscaler"
  })
}

data "aws_iam_policy_document" "cluster_autoscaler" {
  # 읽기(발견·상태 조회): Describe 계열은 리소스 단위 제한을 지원하지 않아 "*" 필요.
  statement {
    sid = "Describe"
    actions = [
      "autoscaling:DescribeAutoScalingGroups",
      "autoscaling:DescribeAutoScalingInstances",
      "autoscaling:DescribeLaunchConfigurations",
      "autoscaling:DescribeScalingActivities",
      "autoscaling:DescribeTags",
      "ec2:DescribeImages",
      "ec2:DescribeInstanceTypes",
      "ec2:DescribeLaunchTemplateVersions",
      "ec2:GetInstanceTypesFromInstanceRequirements",
      "eks:DescribeNodegroup",
    ]
    resources = ["*"]
  }

  # 쓰기(스케일 조작): CA 발견 태그가 붙은 이 클러스터의 ASG 로만 제한(최소 권한, SKILL §7).
  statement {
    sid = "Scale"
    actions = [
      "autoscaling:SetDesiredCapacity",
      "autoscaling:TerminateInstanceInAutoScalingGroup",
    ]
    resources = ["*"]
    condition {
      test     = "StringEquals"
      variable = "autoscaling:ResourceTag/k8s.io/cluster-autoscaler/${var.cluster_name}"
      values   = ["owned"]
    }
  }
}

resource "aws_iam_role_policy" "cluster_autoscaler" {
  name   = "${var.cluster_name}-cluster-autoscaler"
  role   = aws_iam_role.cluster_autoscaler.id
  policy = data.aws_iam_policy_document.cluster_autoscaler.json
}
