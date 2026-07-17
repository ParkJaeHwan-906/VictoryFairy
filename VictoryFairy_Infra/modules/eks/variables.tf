variable "environment" {
  description = "배포 환경 (dev / prod)"
  type        = string
}

variable "cluster_name" {
  description = "EKS 클러스터 이름"
  type        = string
}

variable "cluster_version" {
  description = "EKS 쿠버네티스 버전"
  type        = string
}

variable "vpc_id" {
  description = "클러스터를 배치할 VPC ID (network 모듈 출력)"
  type        = string
}

variable "private_subnet_ids" {
  description = "워커 노드를 배치할 프라이빗 서브넷 ID 목록 (다중 AZ)"
  type        = list(string)
}

variable "node_scaling" {
  description = "노드그룹 스케일링 설정 (Cluster Autoscaler 연동)"
  type = object({
    min_size     = number
    desired_size = number
    max_size     = number
  })
}

variable "node_instance_types" {
  description = "워커 노드 인스턴스 타입 목록"
  type        = list(string)
}

variable "tags" {
  description = "리소스에 병합할 추가 태그"
  type        = map(string)
  default     = {}
}
