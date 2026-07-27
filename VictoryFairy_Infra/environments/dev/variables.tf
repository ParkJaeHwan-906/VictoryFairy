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

variable "dev_db_use_eip" {
  description = "true 면 dev DB 에 Elastic IP(고정 퍼블릭 IP)를 할당·연결. 인스턴스 중지 중엔 미사용 EIP 요금 발생."
  type        = bool
  default     = false
}

variable "mysql_public_access_cidrs" {
  description = <<-EOT
    운영 MySQL EC2 에 개발자 PC 가 '직접' 접속(3306)하도록 허용할 CIDR 목록
    (예: ["1.2.3.4/32"]). 비우면([]) 퍼블릭 경로(보조 ENI·EIP·SG)를 만들지 않고
    종전처럼 SSM 포트포워딩만 남는다.
    ⚠ 운영 데이터 호스트를 인터넷에 노출하는 선택이다. /32 로 좁게 유지할 것.
  EOT
  type        = list(string)
  default     = []
}

variable "domain_name" {
  description = "서비스 루트 도메인. Route53 호스팅영역 + ACM 인증서 기준. (dns 모듈)"
  type        = string
  default     = "victoryfairy.com"
}

variable "crawl_bucket_name" {
  description = "크롤 원본과 정제 산출물이 함께 있는 S3 버킷. ⚠ Terraform 관리 밖이며 refine-pipeline 은 알림 설정만 붙인다"
  type        = string
  default     = "victoryfairy-crawl-dev"
}

variable "refine_image_tag" {
  description = "정제 러너 Lambda 컨테이너 이미지 태그. VictoryFairy_AI 의 커밋 SHA 를 쓴다(불변 태그)"
  type        = string

  # VictoryFairy_AI dev_ai 계열 커밋 aea6fca5 로 빌드해 push 한 이미지.
  # 리포지토리가 IMMUTABLE 이라 같은 태그 재push 가 막히므로, 이 값이 곧 배포된 코드다.
  #
  # ⚠ 이미지를 바꾸려면 **AI 저장소에서 빌드·push 한 뒤 이 값을 갱신**한다. 태그가 ECR 에
  #   없으면 apply 가 Lambda 생성에서 실패한다(plan 은 통과하므로 plan 만으로는 못 잡는다).
  #   아키텍처도 arm64 여야 한다 — 다르면 함수 생성 자체가 실패한다(modules/refine-pipeline 주석).
  default = "aea6fca5"
}
