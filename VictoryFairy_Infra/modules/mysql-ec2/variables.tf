# 변수는 알파벳 순 (SKILL §1 파일 분리 규약)

variable "backup_s3_bucket" {
  description = "일 단위 mysqldump 백업을 업로드할 S3 버킷 이름"
  type        = string
}

variable "backup_s3_prefix" {
  description = "백업 객체를 올릴 버킷 내 prefix(폴더). IAM s3:PutObject 를 이 prefix 하위로만 제한한다."
  type        = string
  default     = "mysql/"
  validation {
    condition     = !startswith(var.backup_s3_prefix, "/")
    error_message = "backup_s3_prefix 는 '/' 로 시작하지 않아야 합니다(예: mysql/)."
  }
}

variable "data_volume_device_name" {
  description = "MySQL 데이터 EBS를 붙일 논리 디바이스 이름(Nitro에서는 NVMe로 재매핑될 수 있음)."
  type        = string
  default     = "/dev/sdf"
}

variable "data_volume_size_gb" {
  description = "MySQL 데이터용 EBS(gp3) 볼륨 크기(GB)"
  type        = number
  default     = 20
  validation {
    condition     = var.data_volume_size_gb >= 8
    error_message = "data_volume_size_gb 는 8 이상이어야 합니다."
  }
}

variable "dlm_snapshot_retain_count" {
  description = "DLM EBS 스냅샷 보관 개수(일 단위 정책이므로 사실상 보관 일수)."
  type        = number
  default     = 7
  validation {
    condition     = var.dlm_snapshot_retain_count >= 1 && var.dlm_snapshot_retain_count <= 1000
    error_message = "dlm_snapshot_retain_count 는 1~1000 사이여야 합니다."
  }
}

variable "dlm_snapshot_time_utc" {
  description = "일 단위 EBS 스냅샷 생성 시각(UTC, HH:MM). 기본 18:00 UTC = 03:00 KST."
  type        = string
  default     = "18:00"
  validation {
    condition     = can(regex("^([01][0-9]|2[0-3]):[0-5][0-9]$", var.dlm_snapshot_time_utc))
    error_message = "dlm_snapshot_time_utc 는 HH:MM(24시간) 형식이어야 합니다."
  }
}

variable "enable_dlm_snapshot" {
  description = "DLM 라이프사이클 정책으로 데이터 EBS 일 단위 스냅샷을 자동화할지 여부."
  type        = bool
  default     = true
}

variable "environment" {
  description = "배포 환경 (dev / prod)"
  type        = string
  validation {
    condition     = contains(["dev", "prod"], var.environment)
    error_message = "environment는 dev 또는 prod 여야 합니다."
  }
}

variable "innodb_buffer_pool_size" {
  description = "MySQL innodb_buffer_pool_size (t3.small 2GB에서 Redis와 메모리 분할, ARCHITECTURE §3)."
  type        = string
  default     = "512M"
}

variable "instance_type" {
  description = "MySQL 호스트 EC2 인스턴스 타입"
  type        = string
  default     = "t3.small"
}

variable "mysql_ingress_sg_ids" {
  description = <<-EOT
    3306(MySQL) 인입을 허용할 소스 보안그룹 맵. 키 = 논리 이름(예: "eks_nodes"),
    값 = 소스 SG ID. 값은 apply 시점에 결정되는 module.eks 출력이라 for_each '키'로
    쓸 수 없으므로 map(정적 키 / apply-time 값)으로 받는다.
    ARCHITECTURE §3: user·quiz·batch EKS 노드 SG 모두(배치는 최종 저장으로 3306 접근).
    현재 eks 는 공용 노드 SG 하나라 { eks_nodes = node_security_group_id } 를 넘긴다.
  EOT
  type        = map(string)
  validation {
    condition     = length(var.mysql_ingress_sg_ids) > 0
    error_message = "mysql_ingress_sg_ids 는 최소 1개의 소스 SG가 필요합니다."
  }
}

variable "mysql_root_password_ssm_parameter_name" {
  description = <<-EOT
    MySQL root 비밀번호가 담긴 SSM Parameter Store(SecureString) 파라미터 '이름'.
    비밀번호 값 자체가 아니다(코드에 비밀번호 금지, SKILL §3). 인스턴스는 부팅 시
    이 이름으로 ssm:GetParameter 하여 값을 읽는다. 파라미터는 사전 생성 필요.
  EOT
  type        = string
  default     = "/victoryfairy/mysql/root-password"
}

variable "redis_ingress_sg_ids" {
  description = <<-EOT
    6379(서비스 Redis) 인입을 허용할 소스 보안그룹 맵. 키 = 논리 이름, 값 = 소스 SG ID.
    (apply-time 값을 for_each 키로 못 쓰므로 map 으로 받는다.)
    ARCHITECTURE §3/§4: user·quiz 노드 SG '만'. batch 는 6379 접근 없음
    (서비스 Redis는 배치가 쓰지 않음 — 배치는 전용 임시 Redis를 k8s에서 띄운다).
    현재 eks 는 공용 노드 SG 하나라 mysql_ingress_sg_ids 와 같은 SG가 들어갈 수 있으나,
    노드그룹이 전용 SG로 분리되면 이 맵에서 batch SG를 제외해 의도가 실효를 갖는다.
  EOT
  type        = map(string)
  validation {
    condition     = length(var.redis_ingress_sg_ids) > 0
    error_message = "redis_ingress_sg_ids 는 최소 1개의 소스 SG가 필요합니다."
  }
}

variable "redis_maxmemory" {
  description = "서비스 Redis maxmemory 상한(2GB 박스에서 MySQL과 분할, ARCHITECTURE §3)."
  type        = string
  default     = "256mb"
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
  description = "MySQL EC2를 배치할 프라이빗 서브넷 ID (운영 AZ = 2a, 단일 AZ)"
  type        = string
}

variable "tags" {
  description = "리소스에 병합할 추가 태그"
  type        = map(string)
  default     = {}
}

variable "vpc_id" {
  description = "MySQL EC2를 배치할 VPC ID"
  type        = string
}
