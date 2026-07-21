locals {
  # EKS 클러스터 이름의 단일 출처(single source of truth).
  # network 모듈(서브넷의 kubernetes.io/cluster/<name>=shared 태그)과 eks 모듈이
  # 반드시 같은 값을 써야 하므로 여기서 한 번만 정의해 양쪽에 전달한다.
  cluster_name = "victoryfairy-${var.environment}"
}
