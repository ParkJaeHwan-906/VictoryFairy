variable "cluster_endpoint_private_access" {
  description = "EKS API 서버의 VPC 내부(프라이빗) 엔드포인트 접근 허용 여부. 노드·클러스터 내부 통신에 필요."
  type        = bool
  default     = true
}

variable "cluster_endpoint_public_access" {
  description = "EKS API 서버의 퍼블릭 엔드포인트 접근 허용 여부. kubectl 등 외부 관리자 접근용(요청은 IAM 인증됨). 프라이빗 전용으로 잠그려면 false + 배스천/VPN 필요."
  type        = bool
  default     = true
}

variable "cluster_name" {
  description = "EKS 클러스터 이름. network 모듈의 서브넷 태그 kubernetes.io/cluster/<name>=shared 와 반드시 동일해야 한다(루트 local.cluster_name 으로 단일 출처 관리)."
  type        = string
  validation {
    condition     = length(var.cluster_name) > 0
    error_message = "cluster_name은 비어 있을 수 없습니다."
  }
}

variable "cluster_subnet_ids" {
  description = "EKS 컨트롤플레인 ENI를 배치할 프라이빗 서브넷 ID 목록. 컨트롤플레인 요건상 서로 다른 2개 AZ(2a+2c)를 모두 넣어야 한다. 노드 배치와는 별개(노드는 node_subnet_ids)."
  type        = list(string)
  validation {
    condition     = length(var.cluster_subnet_ids) >= 2
    error_message = "EKS 컨트롤플레인은 최소 2개 AZ의 서브넷이 필요합니다."
  }
}

variable "cluster_version" {
  description = "EKS 쿠버네티스 버전 (예: 1.30)."
  type        = string
  validation {
    condition     = can(regex("^1\\.[0-9]+$", var.cluster_version))
    error_message = "cluster_version은 1.xx 형식이어야 합니다 (예: 1.30)."
  }
}

variable "environment" {
  description = "배포 환경 (dev / prod). 리소스 Name 태그 등에 사용."
  type        = string
  validation {
    condition     = contains(["dev", "prod"], var.environment)
    error_message = "environment는 dev 또는 prod 여야 합니다."
  }
}

variable "node_groups" {
  description = <<-EOT
    관리형 노드그룹 정의 맵 (키 = 노드그룹 이름, 예: user/quiz/batch).
    - labels: 노드에 붙는 k8s 라벨. 파드의 nodeSelector 가 이 값과 일치해야 해당 노드로 스케줄된다.
    - taints: 노드 오염. 파드에 동일한 toleration 이 없으면 스케줄되지 않는다(effect: NO_SCHEDULE | NO_EXECUTE | PREFER_NO_SCHEDULE).
    - cluster_autoscaler: true 면 노드그룹/ASG에 Cluster Autoscaler 발견 태그를 부여한다.
    ⚠ labels/taints 값은 앱 레포(k8s 매니페스트)의 nodeSelector/toleration 과 정확히 일치해야 한다.
  EOT
  type = map(object({
    instance_types     = list(string)
    capacity_type      = string # ON_DEMAND | SPOT
    min_size           = number
    desired_size       = number
    max_size           = number
    labels             = map(string)
    cluster_autoscaler = bool
    taints = map(object({
      key    = string
      value  = string
      effect = string
    }))
  }))

  validation {
    condition     = alltrue([for _, ng in var.node_groups : contains(["ON_DEMAND", "SPOT"], ng.capacity_type)])
    error_message = "각 노드그룹의 capacity_type은 ON_DEMAND 또는 SPOT 이어야 합니다."
  }
  validation {
    condition     = alltrue([for _, ng in var.node_groups : ng.min_size <= ng.desired_size && ng.desired_size <= ng.max_size])
    error_message = "각 노드그룹은 min_size <= desired_size <= max_size 여야 합니다."
  }
  validation {
    condition = alltrue([
      for _, ng in var.node_groups : alltrue([
        for _, t in ng.taints : contains(["NO_SCHEDULE", "NO_EXECUTE", "PREFER_NO_SCHEDULE"], t.effect)
      ])
    ])
    error_message = "taint effect는 NO_SCHEDULE / NO_EXECUTE / PREFER_NO_SCHEDULE 중 하나여야 합니다."
  }
}

variable "node_ssh_key_name" {
  description = "노드 SSH용 EC2 키페어 이름. null이면 remote_access 미설정. 접속 경로는 SSM 터널 전제(22 인입 개방 없음 — SG는 클러스터 SG 소스로만 한정)."
  type        = string
  default     = null
}

variable "node_subnet_ids" {
  description = "워커 노드를 배치할 프라이빗 서브넷 ID 목록. ARCHITECTURE §1에 따라 운영 AZ(2a) 서브넷만 넣어 노드를 2a에 집중시킨다(2c는 예비). 컨트롤플레인 서브넷(cluster_subnet_ids)과 구분."
  type        = list(string)
  validation {
    condition     = length(var.node_subnet_ids) >= 1
    error_message = "node_subnet_ids는 최소 1개 이상이어야 합니다."
  }
}

variable "tags" {
  description = "리소스에 병합할 추가 태그(프로바이더 default_tags 위에 merge)."
  type        = map(string)
  default     = {}
}
