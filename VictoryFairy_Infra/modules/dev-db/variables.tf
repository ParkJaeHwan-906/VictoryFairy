# 변수는 알파벳 순 (SKILL §1 파일 분리 규약). 모든 변수에 description + type,
# 제약은 validation. 비밀값은 코드에 굽지 않는다(SSM Parameter Store 이름만 받음).

variable "allowed_cidrs" {
  description = <<-EOT
    dev DB 의 22(SSH)·3306(MySQL)·6379(Redis) 인입을 허용할 CIDR 목록.
    프로덕션과 달리 EKS 노드 SG 가 아니라 개발자 IP CIDR 들에서만 연다(예: ["1.2.3.4/32", "5.6.7.8/32"]).
    0.0.0.0/0 등 전체 개방은 금지(퍼블릭 서브넷 + 퍼블릭 IP 라 공격 표면이 크다).
  EOT
  type        = list(string)
  validation {
    condition     = length(var.allowed_cidrs) > 0 && alltrue([for c in var.allowed_cidrs : can(cidrhost(c, 0))])
    error_message = "allowed_cidrs 는 최소 1개 이상의 유효한 CIDR 표기여야 합니다(예: 1.2.3.4/32)."
  }
  validation {
    condition     = alltrue([for c in var.allowed_cidrs : c != "0.0.0.0/0"])
    error_message = "allowed_cidrs 에 0.0.0.0/0(전체 개방)은 허용하지 않습니다."
  }
}

variable "backup_s3_bucket" {
  description = "복원(restore) 원본이 되는 프로덕션 mysqldump 백업 S3 버킷 이름(읽기 전용)."
  type        = string
}

variable "backup_s3_prefix" {
  description = "백업 객체가 저장된 버킷 내 prefix(폴더). 프로덕션 mysql-ec2 기본값과 동일해야 최신 덤프를 찾는다. IAM s3:GetObject/ListBucket 을 이 prefix 하위로만 제한한다."
  type        = string
  default     = "mysql/"
  validation {
    condition     = !startswith(var.backup_s3_prefix, "/")
    error_message = "backup_s3_prefix 는 '/' 로 시작하지 않아야 합니다(예: mysql/)."
  }
}

variable "data_volume_device_name" {
  description = "MySQL 데이터 EBS 를 붙일 논리 디바이스 이름(Nitro 에서는 NVMe 로 재매핑될 수 있음)."
  type        = string
  default     = "/dev/sdf"
}

variable "data_volume_size_gb" {
  description = "MySQL 데이터용 EBS(gp3) 볼륨 크기(GB). dev 는 매일 restore 로 갱신되는 소모성 데이터라 prevent_destroy 를 걸지 않는다."
  type        = number
  default     = 20
  validation {
    condition     = var.data_volume_size_gb >= 8
    error_message = "data_volume_size_gb 는 8 이상이어야 합니다."
  }
}

variable "environment" {
  description = "배포 환경 (dev / prod). dev DB 모듈은 dev 사용을 전제한다."
  type        = string
  validation {
    condition     = contains(["dev", "prod"], var.environment)
    error_message = "environment는 dev 또는 prod 여야 합니다."
  }
}

variable "innodb_buffer_pool_size" {
  description = "MySQL innodb_buffer_pool_size (t3.small 2GB 에서 Redis 와 메모리 분할)."
  type        = string
  default     = "512M"
}

variable "instance_type" {
  description = "dev DB 호스트 EC2 인스턴스 타입."
  type        = string
  default     = "t3.small"
}

variable "mysql_root_password_ssm_parameter_name" {
  description = <<-EOT
    MySQL root 비밀번호가 담긴 SSM Parameter Store(SecureString) 파라미터 '이름'.
    비밀번호 값이 아니다(코드에 비밀번호 금지, SKILL §3). 프로덕션 mysql-ec2 와
    '동일한' 파라미터여야 한다 — --all-databases 덤프(mysql 권한 테이블 포함) 복원
    후에도 root 비밀번호가 일관되게 유지되도록.
  EOT
  type        = string
  default     = "/victoryfairy/mysql/root-password"
}

variable "redis_maxmemory" {
  description = "서비스 Redis(fresh) maxmemory 상한(2GB 박스에서 MySQL 과 분할)."
  type        = string
  default     = "256mb"
}

variable "restore_cron" {
  description = "매일 최신 덤프를 복원하는 cron 스케줄(UTC). 기본 '0 19 * * *' = 04:00 KST — 프로덕션 백업(18:10 UTC = 03:10 KST) 이후라 당일 아침 최신 덤프를 복원."
  type        = string
  default     = "0 19 * * *"
}

variable "ssh_key_name" {
  description = "SSH 접속용 EC2 키페어 이름. dev 는 퍼블릭 SSH 라 키페어 필수(.pem 개인키로 접속). 기본은 계정에 이미 있는 'VictoryFairy'(EKS 노드와 동일 pem)."
  type        = string
  default     = "VictoryFairy"
}

variable "root_volume_size_gb" {
  description = "EC2 루트 EBS(gp3) 크기(GB). OS·docker 이미지용(데이터는 별도 볼륨)."
  type        = number
  default     = 20
  validation {
    condition     = var.root_volume_size_gb >= 8
    error_message = "root_volume_size_gb 는 8 이상이어야 합니다."
  }
}

variable "subnet_id" {
  description = "dev DB EC2 를 배치할 '퍼블릭' 서브넷 ID (운영 AZ = 2a). 퍼블릭 IP 자동 할당."
  type        = string
}

variable "tags" {
  description = "리소스에 병합할 추가 태그."
  type        = map(string)
  default     = {}
}

variable "vpc_id" {
  description = "dev DB EC2 를 배치할 VPC ID."
  type        = string
}
