locals {
  # Cluster Autoscaler 발견 태그가 필요한 노드그룹(quiz/batch)만 추림.
  ca_node_groups = { for k, ng in var.node_groups : k => ng if ng.cluster_autoscaler }

  # 각 CA 노드그룹의 ASG에 붙일 발견 태그 2종(enabled, owned)을 평탄화한 맵.
  # CA는 관리형 노드그룹의 실제 ASG 태그를 읽어 대상 노드그룹을 발견한다.
  # (aws_eks_node_group 의 tags 는 ASG로 전파되지 않으므로 aws_autoscaling_group_tag 로 직접 태깅.)
  ca_asg_tags = merge([
    for name in keys(local.ca_node_groups) : {
      "${name}/enabled" = {
        asg_name = aws_eks_node_group.this[name].resources[0].autoscaling_groups[0].name
        key      = "k8s.io/cluster-autoscaler/enabled"
        value    = "true"
      }
      "${name}/owned" = {
        asg_name = aws_eks_node_group.this[name].resources[0].autoscaling_groups[0].name
        key      = "k8s.io/cluster-autoscaler/${var.cluster_name}"
        value    = "owned"
      }
    }
  ]...)
}
