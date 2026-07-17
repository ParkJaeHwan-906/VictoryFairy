# eks 모듈: EKS 클러스터 + 관리형 노드그룹 + IRSA + 오토스케일 준비
#
# TODO: 아래를 구현합니다. (직접 리소스 또는 terraform-aws-modules/eks/aws 활용)
#   - aws_eks_cluster (프라이빗 서브넷, 컨트롤플레인)
#   - aws_eks_node_group
#       * subnet_ids = var.private_subnet_ids  (다중 AZ 분산)
#       * scaling_config = var.node_scaling
#       * IAM: AmazonEKSWorkerNodePolicy / AmazonEKS_CNI_Policy /
#              AmazonEC2ContainerRegistryReadOnly (최소 권한)
#   - IRSA: aws_iam_openid_connect_provider (파드 단위 최소 권한)
#   - 애드온: vpc-cni, coredns, kube-proxy
#
# 오토스케일 2계층 (SKILL §4):
#   - Pod:  HPA           -> k8s 매니페스트/Helm 로 관리 (인프라 밖)
#   - Node: Cluster Autoscaler -> ASG 에 아래 발견용 태그 필요
#       "k8s.io/cluster-autoscaler/${var.cluster_name}" = "owned"
#       "k8s.io/cluster-autoscaler/enabled"             = "true"
#
# CronJob 은 쿠버네티스 CronJob 리소스로 앱 레포에서 관리 (인프라는 클러스터까지만).
