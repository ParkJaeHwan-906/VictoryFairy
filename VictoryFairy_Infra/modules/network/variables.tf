variable "azs" {
  description = "사용할 가용영역 목록. 목록의 첫 번째가 운영 AZ(NAT 배치), 나머지는 예비. EKS 요건상 최소 2개."
  type        = list(string)
  validation {
    condition     = length(var.azs) >= 2
    error_message = "EKS 컨트롤플레인 요건상 최소 2개 이상의 AZ가 필요합니다."
  }
}

variable "cluster_name" {
  description = "이 VPC를 사용할 EKS 클러스터 이름. 서브넷 태그 kubernetes.io/cluster/<name>=shared 에 쓰여 EKS/로드밸런서 컨트롤러의 서브넷 자동 발견에 사용된다. eks 모듈에 넣는 클러스터 이름과 반드시 동일해야 한다."
  type        = string
  validation {
    condition     = length(var.cluster_name) > 0
    error_message = "cluster_name은 비어 있을 수 없습니다."
  }
}

variable "environment" {
  description = "배포 환경 (dev / prod). 리소스 Name 태그 접두사로 사용."
  type        = string
  validation {
    condition     = contains(["dev", "prod"], var.environment)
    error_message = "environment는 dev 또는 prod 여야 합니다."
  }
}

variable "private_subnet_cidrs" {
  description = "프라이빗 서브넷 CIDR 목록. var.azs 와 같은 순서·개수여야 하며 i번째가 azs[i]에 매핑된다. EKS 노드 / MySQL EC2 배치용."
  type        = list(string)
  validation {
    condition     = alltrue([for c in var.private_subnet_cidrs : can(cidrhost(c, 0))])
    error_message = "private_subnet_cidrs의 모든 항목이 유효한 IPv4 CIDR 블록이어야 합니다."
  }
}

variable "public_subnet_cidrs" {
  description = "퍼블릭 서브넷 CIDR 목록. var.azs 와 같은 순서·개수여야 하며 i번째가 azs[i]에 매핑된다. ALB / NAT 배치용."
  type        = list(string)
  validation {
    condition     = alltrue([for c in var.public_subnet_cidrs : can(cidrhost(c, 0))])
    error_message = "public_subnet_cidrs의 모든 항목이 유효한 IPv4 CIDR 블록이어야 합니다."
  }
}

variable "tags" {
  description = "리소스에 병합할 추가 태그(프로바이더 default_tags 위에 merge)."
  type        = map(string)
  default     = {}
}

variable "vpc_cidr" {
  description = "VPC CIDR 블록 (예: 10.0.0.0/16)."
  type        = string
  validation {
    condition     = can(cidrhost(var.vpc_cidr, 0))
    error_message = "유효한 IPv4 CIDR 블록이어야 합니다."
  }
}
