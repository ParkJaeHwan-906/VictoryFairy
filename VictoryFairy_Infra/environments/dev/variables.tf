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

variable "bedrock_batch_post_size" {
  description = "Bedrock Lambda 가 한 번의 모델 호출에 묶는 게시글 수(= SQS batch_size). 처리량 부족은 이 값으로 푼다 — 예약 동시성은 올리지 않는다"
  type        = number
  default     = 10

  # 5 → 10 (2026-07-29). 예약 동시성이 1이라 처리량을 늘리는 합법적 레버는 이 값뿐이다.
  # 비용은 부수 효과로 함께 내려간다 — 비용이 사실상 전부 시스템 프롬프트(2,470토큰)라
  # 하루 소비액이 대략 $44.8/N 로 움직인다(N=5 → $8.97, N=10 → $4.5, 상한 $30).
  #
  # ⚠ **더 올리기 전에 반드시 확인할 것 — 출력 토큰이 진짜 상한이다.**
  #   pipeline/lambda_bedrock.py 의 handler 는 배치를 쪼개지 않고 **전건을 한 번의
  #   judge_batch() 로 부른다.** 게다가 게시글 1건은 `1 + 댓글 수` 개의 판정 단위로
  #   펼쳐진다(`results[start : start + 1 + len(comments)]`). 그 판정 전부가
  #   bedrock/core/config.py 의 BEDROCK_MAX_TOKENS = 2048 을 나눠 쓴다.
  #   초과하면 응답이 잘려 배치 전체가 실패하고, 3회 재시도 뒤 DLQ 로 간다 —
  #   그동안 모델 호출 비용은 매번 나간다.
  #   20 까지 올리려면 AI 저장소에서 BEDROCK_MAX_TOKENS 를 먼저 키워야 한다.
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
