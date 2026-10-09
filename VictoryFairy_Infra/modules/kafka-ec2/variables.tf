# 변수는 알파벳 순 (SKILL §1 파일 분리 규약)

variable "data_volume_device_name" {
  description = "Kafka 데이터 EBS를 붙일 논리 디바이스 이름(Nitro에서는 NVMe로 재매핑될 수 있음)."
  type        = string
  default     = "/dev/sdf"
}

variable "data_volume_size_gb" {
  description = <<-EOT
    Kafka 로그 디렉터리용 EBS(gp3) 볼륨 크기(GB).
    보존 48h 기준 산정: 채팅 메시지 ~400B × 하루 수십만 건 수준이면 수백 MB 라 20GB 면 넉넉하다.
  EOT
  type        = number
  default     = 20
  validation {
    condition     = var.data_volume_size_gb >= 8
    error_message = "data_volume_size_gb 는 8 이상이어야 합니다."
  }
}

variable "environment" {
  description = "배포 환경 (dev / prod)"
  type        = string
  validation {
    condition     = contains(["dev", "prod"], var.environment)
    error_message = "environment는 dev 또는 prod 여야 합니다."
  }
}

variable "instance_type" {
  description = <<-EOT
    Kafka 브로커 EC2 인스턴스 타입. 기본 t3.small(2 vCPU 버스트 / 2GB).
    근거: 단일 브로커·토픽 2개·채팅 트래픽이라 CPU 는 기준선(20%) 안에서 돈다. Kafka 는 힙보다
    OS 페이지 캐시로 읽기를 받으므로 힙 512MB + 컨테이너 상한 1GB 를 잡고 나머지 ~1GB 를
    OS·페이지 캐시에 남긴다. t3.micro(1GB)는 JVM + 페이지 캐시 + docker 에 모자라 제외.
    ARM(t4g.small)이 더 싸지만 다른 EC2 와 같은 x86_64 AL2023 AMI·운영 절차를 유지하려고 쓰지 않았다.
  EOT
  type        = string
  default     = "t3.small"
}

variable "kafka_container_memory" {
  description = <<-EOT
    kafka 컨테이너 cgroup 메모리 상한(docker --memory 표기, 예: "1g").
    kafka_heap_size 보다 넉넉해야 한다(메타스페이스·다이렉트 버퍼·스레드 스택 몫).
    상한이 없으면 JVM 바깥 메모리가 자라 호스트를 굶길 수 있다(2026-08-17 DB 박스 사례와 같은 이유).
  EOT
  type        = string
  default     = "1g"
}

variable "kafka_heap_size" {
  description = "Kafka JVM 힙 크기. -Xms/-Xmx 에 같은 값으로 들어간다(JVM 표기, 예: \"512m\")."
  type        = string
  default     = "512m"
  validation {
    condition     = can(regex("^[0-9]+[mMgG]$", var.kafka_heap_size))
    error_message = "kafka_heap_size 는 JVM 크기 표기여야 합니다(예: 512m, 1g)."
  }
}

variable "kafka_image" {
  description = "Kafka 컨테이너 이미지(불변 태그). BE docker-compose.yml 의 로컬 Kafka 와 같은 버전을 쓴다."
  type        = string
  default     = "apache/kafka:3.9.1"
  validation {
    condition     = !endswith(var.kafka_image, ":latest") && length(split(":", var.kafka_image)) == 2
    error_message = "kafka_image 는 latest 가 아닌 명시적 태그를 가져야 합니다(예: apache/kafka:3.9.1)."
  }
}

variable "kafka_ingress_sg_ids" {
  description = <<-EOT
    9092(Kafka 클라이언트) 인입을 허용할 소스 보안그룹 맵. 키 = 논리 이름(예: "eks_nodes"),
    값 = 소스 SG ID. apply-time 값이라 for_each 키로 못 쓰므로 map 으로 받는다(mysql-ec2 와 동일).
  EOT
  type        = map(string)
  validation {
    condition     = length(var.kafka_ingress_sg_ids) > 0
    error_message = "kafka_ingress_sg_ids 는 최소 1개의 소스 SG가 필요합니다."
  }
}

variable "log_retention_hours" {
  description = "브로커 기본 로그 보존 시간(log.retention.hours). 부팅 시 만드는 토픽에도 retention.ms 로 같은 값을 건다."
  type        = number
  default     = 48
  validation {
    condition     = var.log_retention_hours >= 1
    error_message = "log_retention_hours 는 1 이상이어야 합니다."
  }
}

variable "root_volume_size_gb" {
  description = "EC2 루트 EBS(gp3) 크기(GB). OS·docker 이미지·스왑 파일용(데이터는 별도 볼륨)."
  type        = number
  default     = 20
  validation {
    condition     = var.root_volume_size_gb >= 8
    error_message = "root_volume_size_gb 는 8 이상이어야 합니다."
  }
}

variable "subnet_id" {
  description = "Kafka EC2 를 배치할 '프라이빗' 서브넷 ID (운영 AZ = 2a). 변경 시 인스턴스가 재생성되고 프라이빗 IP(=bootstrap 주소)가 바뀐다."
  type        = string
}

variable "swap_size_mb" {
  description = "스왑 파일 크기(MB). 메모리 고갈 시 라이브락 대신 OOM 으로 끝나게 하는 안전망(mysql-ec2 와 같은 이유)."
  type        = number
  default     = 1024
  validation {
    condition     = var.swap_size_mb >= 512
    error_message = "swap_size_mb 는 512 이상이어야 합니다."
  }
}

variable "tags" {
  description = "리소스에 병합할 추가 태그"
  type        = map(string)
  default     = {}
}

variable "topics" {
  description = <<-EOT
    부팅 시 --if-not-exists 로 만들 토픽 → 파티션 수. 복제 계수는 단일 브로커라 1 고정.
    ⚠ 이 값은 '최초 생성'에만 쓰인다. 이미 있는 토픽의 파티션 수는 바뀌지 않고, user_data 는
      ignore_changes 라 실행 중 브로커에는 반영도 되지 않는다.
    ⚠ chat-messages 의 파티션 수 변경·토픽 재생성은 00:00 KST 방 재생성 직후에만 한다
      (game-chat.md 제약 7 — 운영 중 바꾸면 그날 히스토리 XADD 가 자정까지 거부된다).
  EOT
  type        = map(number)
  default = {
    "chat-control"  = 3
    "chat-messages" = 3
  }
  validation {
    condition     = alltrue([for name, p in var.topics : p >= 1 && can(regex("^[A-Za-z0-9._-]+$", name))])
    error_message = "topics 의 키는 Kafka 토픽 이름 규칙([A-Za-z0-9._-]), 값은 1 이상의 파티션 수여야 합니다."
  }
}

variable "vpc_id" {
  description = "Kafka EC2를 배치할 VPC ID"
  type        = string
}
