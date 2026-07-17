variable "environment" {
  description = "배포 환경 (dev / prod)"
  type        = string
}

variable "vpc_cidr" {
  description = "VPC CIDR 블록"
  type        = string
  validation {
    condition     = can(cidrhost(var.vpc_cidr, 0))
    error_message = "유효한 IPv4 CIDR 블록이어야 합니다."
  }
}

variable "azs" {
  description = "사용할 가용영역 목록 (다중 AZ, 최소 2개)"
  type        = list(string)
  validation {
    condition     = length(var.azs) >= 2
    error_message = "고가용성을 위해 최소 2개 이상의 AZ가 필요합니다."
  }
}

variable "public_subnet_cidrs" {
  description = "퍼블릭 서브넷 CIDR 목록 (AZ 수와 일치)"
  type        = list(string)
}

variable "private_subnet_cidrs" {
  description = "프라이빗 서브넷 CIDR 목록 (AZ 수와 일치)"
  type        = list(string)
}

variable "tags" {
  description = "리소스에 병합할 추가 태그"
  type        = map(string)
  default     = {}
}
