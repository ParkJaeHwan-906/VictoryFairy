# eks 모듈: EKS 클러스터(1.30) + 관리형 노드그룹 3개 + IRSA(OIDC) + 애드온
#
# 경계(ARCHITECTURE §5): Terraform은 클러스터·노드그룹까지. Deployment/HPA/CronJob/
# 배치 워커, taint↔toleration/nodeSelector 의 파드 측은 앱 레포 k8s 매니페스트 소관.
#
# ⚠ 커플링: 노드그룹의 labels(workload=<name>)·taints(workload=quiz|batch:NoSchedule)는
# 앱 레포의 nodeSelector/toleration 과 정확히 일치해야 파드가 스케줄된다. 한쪽만 바꾸면
# 파드가 Pending 상태로 남는다.

# ---------------------------------------------------------------------------
# IAM: 클러스터(컨트롤플레인) 역할
# ---------------------------------------------------------------------------
data "aws_iam_policy_document" "cluster_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["eks.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "cluster" {
  name               = "${var.cluster_name}-cluster-role"
  assume_role_policy = data.aws_iam_policy_document.cluster_assume.json

  tags = merge(var.tags, {
    Name = "${var.cluster_name}-cluster-role"
  })
}

resource "aws_iam_role_policy_attachment" "cluster_eks" {
  role       = aws_iam_role.cluster.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKSClusterPolicy"
}

# ---------------------------------------------------------------------------
# IAM: 노드 역할 (3개 노드그룹 공용) — 필수 관리형 정책만(최소 권한, SKILL §4)
# ---------------------------------------------------------------------------
data "aws_iam_policy_document" "node_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["ec2.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "node" {
  name               = "${var.cluster_name}-node-role"
  assume_role_policy = data.aws_iam_policy_document.node_assume.json

  tags = merge(var.tags, {
    Name = "${var.cluster_name}-node-role"
  })
}

resource "aws_iam_role_policy_attachment" "node" {
  for_each = toset([
    "arn:aws:iam::aws:policy/AmazonEKSWorkerNodePolicy",
    "arn:aws:iam::aws:policy/AmazonEKS_CNI_Policy",
    "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly",
  ])

  role       = aws_iam_role.node.name
  policy_arn = each.value
}

# ---------------------------------------------------------------------------
# EKS 클러스터 (컨트롤플레인) — 프라이빗 서브넷 2 AZ(2a+2c)
# ---------------------------------------------------------------------------
resource "aws_eks_cluster" "this" {
  name     = var.cluster_name
  version  = var.cluster_version
  role_arn = aws_iam_role.cluster.arn

  vpc_config {
    # 컨트롤플레인 ENI는 2 AZ 요건 충족을 위해 프라이빗 서브넷 2개(2a+2c) 모두 사용.
    subnet_ids              = var.cluster_subnet_ids
    endpoint_private_access = var.cluster_endpoint_private_access
    endpoint_public_access  = var.cluster_endpoint_public_access
  }

  access_config {
    # API_AND_CONFIG_MAP: 기존 aws-auth ConfigMap 을 유지하면서 EKS Access Entry API 를
    # 함께 사용(CI 역할 등 IAM 주체에 k8s 권한을 코드로 부여 — security 모듈이 소비).
    # ⚠ CONFIG_MAP 으로의 다운그레이드는 AWS 가 지원하지 않는다(단방향 전환).
    authentication_mode = "API_AND_CONFIG_MAP"
    # ⚠ 생성 시점 기본값(true)과 일치시켜야 한다 — 생략하면 provider 가 true→null 변경으로
    #   간주해 "클러스터 교체(replace)"를 계획한다(파괴적!). 절대 제거 금지.
    bootstrap_cluster_creator_admin_permissions = true
  }

  tags = merge(var.tags, {
    Name = var.cluster_name
  })

  # 역할에 정책이 붙기 전에 클러스터가 생성되면 실패할 수 있으므로 명시적 의존.
  depends_on = [aws_iam_role_policy_attachment.cluster_eks]
}

# ---------------------------------------------------------------------------
# IRSA: OIDC 프로바이더 — 파드 단위 최소 권한(IAM Roles for Service Accounts)
# ---------------------------------------------------------------------------
data "tls_certificate" "oidc" {
  url = aws_eks_cluster.this.identity[0].oidc[0].issuer
}

resource "aws_iam_openid_connect_provider" "this" {
  url             = aws_eks_cluster.this.identity[0].oidc[0].issuer
  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = [data.tls_certificate.oidc.certificates[0].sha1_fingerprint]

  tags = merge(var.tags, {
    Name = "${var.cluster_name}-oidc"
  })
}

# ---------------------------------------------------------------------------
# 관리형 노드그룹 3개 (user / quiz / batch) — for_each 로 반복(SKILL §8)
# 노드는 node_subnet_ids(운영 AZ 2a)에만 배치. 2c는 예비(컨트롤플레인만 인지).
# ---------------------------------------------------------------------------
resource "aws_eks_node_group" "this" {
  for_each = var.node_groups

  cluster_name    = aws_eks_cluster.this.name
  node_group_name = "${var.cluster_name}-${each.key}"
  node_role_arn   = aws_iam_role.node.arn
  subnet_ids      = var.node_subnet_ids

  instance_types = each.value.instance_types
  capacity_type  = each.value.capacity_type

  scaling_config {
    min_size     = each.value.min_size
    desired_size = each.value.desired_size
    max_size     = each.value.max_size
  }

  update_config {
    max_unavailable = 1
  }

  labels = each.value.labels

  dynamic "taint" {
    for_each = each.value.taints
    content {
      key    = taint.value.key
      value  = taint.value.value
      effect = taint.value.effect
    }
  }

  tags = merge(
    var.tags,
    { Name = "${var.cluster_name}-${each.key}" },
    # Cluster Autoscaler 발견 태그(노드그룹 레벨). 실제 발견은 ASG 태그(아래)로 이뤄진다.
    each.value.cluster_autoscaler ? {
      "k8s.io/cluster-autoscaler/enabled"             = "true"
      "k8s.io/cluster-autoscaler/${var.cluster_name}" = "owned"
    } : {}
  )

  lifecycle {
    # desired_size 는 Cluster Autoscaler(quiz/batch)나 운영 중 스케일에 의해 바뀔 수 있으므로
    # Terraform이 매번 원복하지 않도록 무시한다. min/max 는 계속 Terraform이 관리.
    ignore_changes = [scaling_config[0].desired_size]
  }

  depends_on = [aws_iam_role_policy_attachment.node]
}

# ---------------------------------------------------------------------------
# Cluster Autoscaler 발견 태그 — 노드그룹의 실제 ASG에 직접 부여(quiz/batch)
# (aws_eks_node_group.tags 는 ASG로 전파되지 않아 CA가 읽지 못하므로 별도 태깅)
# ---------------------------------------------------------------------------
resource "aws_autoscaling_group_tag" "ca" {
  for_each = local.ca_asg_tags

  autoscaling_group_name = each.value.asg_name

  tag {
    key   = each.value.key
    value = each.value.value
    # ASG 레벨 발견 태그(CA가 ASG에서 읽음). 개별 인스턴스로 전파할 필요는 없다.
    propagate_at_launch = false
  }
}

# ---------------------------------------------------------------------------
# 애드온: vpc-cni / coredns / kube-proxy
# coredns 등은 스케줄 가능한 노드가 있어야 ACTIVE가 되므로 노드그룹 이후에 관리.
# ---------------------------------------------------------------------------
resource "aws_eks_addon" "this" {
  for_each = toset(["vpc-cni", "coredns", "kube-proxy"])

  cluster_name = aws_eks_cluster.this.name
  addon_name   = each.value

  resolve_conflicts_on_create = "OVERWRITE"
  resolve_conflicts_on_update = "OVERWRITE"

  tags = merge(var.tags, {
    Name = "${var.cluster_name}-${each.value}"
  })

  depends_on = [aws_eks_node_group.this]
}
