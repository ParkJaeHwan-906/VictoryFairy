variable "aws_region" {
  description = "리소스를 생성할 AWS 리전"
  type        = string
  default     = "ap-northeast-2" # 서울
}

variable "environment" {
  description = "배포 환경"
  type        = string
  default     = "dev"
  validation {
    condition     = contains(["dev", "prod"], var.environment)
    error_message = "environment는 dev 또는 prod 여야 합니다."
  }
}

variable "vpc_cidr" {
  description = "VPC CIDR 블록"
  type        = string
  default     = "10.0.0.0/16"
}

variable "azs" {
  description = "사용할 가용영역 (다중 AZ)"
  type        = list(string)
  default     = ["ap-northeast-2a", "ap-northeast-2c"]
}

variable "backup_s3_bucket" {
  description = "MySQL 일 단위 백업을 저장할 S3 버킷 이름"
  type        = string
}

variable "dev_db_allowed_cidrs" {
  description = "dev DB SSH·3306·6379 를 허용할 CIDR 목록(예: [\"1.2.3.4/32\", \"5.6.7.8/32\"]). 비우면([]) dev DB 미생성."
  type        = list(string)
  default     = []
}

variable "domain_name" {
  description = "서비스 루트 도메인. Route53 호스팅영역 + ACM 인증서 기준. (dns 모듈)"
  type        = string
  default     = "victoryfairy.com"
}
