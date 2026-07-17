variable "environment" {
  description = "배포 환경 (dev / prod)"
  type        = string
}

variable "vpc_id" {
  description = "MySQL EC2를 배치할 VPC ID"
  type        = string
}

variable "subnet_id" {
  description = "MySQL EC2를 배치할 프라이빗 서브넷 ID (단일 AZ)"
  type        = string
}

variable "instance_type" {
  description = "MySQL 호스트 EC2 인스턴스 타입"
  type        = string
  default     = "t3.small"
}

variable "allowed_source_sg_ids" {
  description = "3306 접근을 허용할 소스 보안그룹 ID 목록 (EKS 노드 SG). 이 외 인입은 차단."
  type        = list(string)
}

variable "data_volume_size_gb" {
  description = "MySQL 데이터용 EBS(gp3) 볼륨 크기(GB)"
  type        = number
  default     = 20
}

variable "backup_s3_bucket" {
  description = "일 단위 mysqldump 백업을 업로드할 S3 버킷 이름"
  type        = string
}

variable "tags" {
  description = "리소스에 병합할 추가 태그"
  type        = map(string)
  default     = {}
}
