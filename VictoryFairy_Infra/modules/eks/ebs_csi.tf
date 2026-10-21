# EBS CSI 드라이버 — PVC 를 gp3 EBS 로 프로비저닝한다(Prometheus·Grafana 데이터 보존용).
#
# 왜 필요한가: EKS 1.23+ 의 in-tree EBS 프로비저너는 제거됐다. 이 애드온 없이 PVC 를 만들면
#   Pending 에서 영원히 멈춘다. 지금까지는 PVC 를 쓰는 워크로드가 없어서 설치하지 않았다.
# ⚠ EBS 는 AZ 에 묶인다. 노드가 2a 에만 있으므로(node_subnet_ids) 볼륨도 2a 에 생긴다 —
#   나중에 노드를 2c 로 옮기면 기존 PV 를 쓰는 파드가 스케줄되지 않는다.

data "aws_iam_policy_document" "ebs_csi_assume" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.this.arn]
    }

    # 애드온 컨트롤러의 SA(kube-system/ebs-csi-controller-sa)만 이 역할을 맡는다.
    condition {
      test     = "StringEquals"
      variable = "${replace(aws_eks_cluster.this.identity[0].oidc[0].issuer, "https://", "")}:sub"
      values   = ["system:serviceaccount:kube-system:ebs-csi-controller-sa"]
    }

    condition {
      test     = "StringEquals"
      variable = "${replace(aws_eks_cluster.this.identity[0].oidc[0].issuer, "https://", "")}:aud"
      values   = ["sts.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "ebs_csi" {
  name               = "${var.cluster_name}-ebs-csi"
  assume_role_policy = data.aws_iam_policy_document.ebs_csi_assume.json

  tags = merge(var.tags, {
    Name = "${var.cluster_name}-ebs-csi"
  })
}

# AWS 관리형 정책. 볼륨 생성·삭제를 CSI 가 붙인 태그(ebs.csi.aws.com/cluster=true)로 한정하고 있어
# 직접 쓰는 것보다 범위가 좁다.
resource "aws_iam_role_policy_attachment" "ebs_csi" {
  role       = aws_iam_role.ebs_csi.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonEBSCSIDriverPolicy"
}

locals {
  ebs_csi_controller_ng = var.ebs_csi_controller_node_group == null ? null : var.node_groups[var.ebs_csi_controller_node_group]
}

resource "aws_eks_addon" "ebs_csi" {
  cluster_name             = aws_eks_cluster.this.name
  addon_name               = "aws-ebs-csi-driver"
  service_account_role_arn = aws_iam_role.ebs_csi.arn

  # 기본값(컨트롤러 2개·taint 없는 노드 아무 데나)이면 app 노드에 232Mi × 2 가 얹힌다.
  # PVC 생성은 드물어 1개면 충분하고, 지정한 노드그룹으로 보낸다. node DaemonSet 은 손대지 않는다
  # (볼륨을 붙일 모든 노드에 떠야 한다).
  configuration_values = local.ebs_csi_controller_ng == null ? null : jsonencode({
    controller = {
      replicaCount = 1
      nodeSelector = local.ebs_csi_controller_ng.labels
      tolerations = [for t in values(local.ebs_csi_controller_ng.taints) : {
        key      = t.key
        operator = "Equal"
        value    = t.value
        effect   = { NO_SCHEDULE = "NoSchedule", NO_EXECUTE = "NoExecute", PREFER_NO_SCHEDULE = "PreferNoSchedule" }[t.effect]
      }]
    }
  })

  resolve_conflicts_on_create = "OVERWRITE"
  resolve_conflicts_on_update = "OVERWRITE"

  tags = merge(var.tags, {
    Name = "${var.cluster_name}-aws-ebs-csi-driver"
  })

  depends_on = [aws_eks_node_group.this, aws_iam_role_policy_attachment.ebs_csi]
}
