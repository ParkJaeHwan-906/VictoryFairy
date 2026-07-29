variable "bedrock_batch_post_size" {
  description = "Bedrock Lambda 가 한 번의 모델 호출에 묶는 게시글 수. SQS batch_size 와 같은 값이어야 한다"
  type        = number
  default     = 5

  validation {
    # 1이면 시스템 프롬프트(2,470토큰)가 게시글마다 붙어 하루 $44.8 로 상한 $30 을 넘긴다.
    # 너무 크면 한 번의 실패로 버리는 작업량이 커지고 응답 정합성 위험도 올라간다.
    condition     = var.bedrock_batch_post_size >= 2 && var.bedrock_batch_post_size <= 20
    error_message = "bedrock_batch_post_size 는 2~20 이어야 합니다(1이면 프롬프트 비용이 상한을 넘습니다)."
  }
}

variable "bedrock_max_output_tokens" {
  description = "모델 응답의 출력 토큰 상한(BEDROCK_MAX_TOKENS). 한 호출의 판정 결과 전부가 이 안에 들어가야 한다"
  type        = number
  default     = 2048 # 앱 기본값(bedrock/core/config.py)과 동일 — 환경이 명시하지 않으면 동작이 안 바뀐다

  validation {
    # 상한 4096: bedrock/core/client.py 는 Converse API 를 베타 헤더 없이 부른다.
    # claude-3-5-sonnet-20240620 의 8192 출력은 anthropic-beta 헤더가 있어야 하므로
    # 그 값을 넣으면 ValidationException 으로 전 호출이 죽는다.
    condition     = var.bedrock_max_output_tokens >= 512 && var.bedrock_max_output_tokens <= 4096
    error_message = "bedrock_max_output_tokens 는 512~4096 이어야 합니다(8192 는 베타 헤더가 필요한데 클라이언트가 보내지 않습니다)."
  }
}

variable "bedrock_model_id" {
  description = "Bedrock 베어 모델 ID. 조직 SCP 가 서울 외 리전 InvokeModel 을 거부하므로 추론 프로파일(apac.*)은 쓸 수 없다"
  type        = string
  default     = "anthropic.claude-3-5-sonnet-20240620-v1:0"

  validation {
    condition     = !can(regex("^(apac|global|us|eu)\\.", var.bedrock_model_id))
    error_message = "추론 프로파일은 사용할 수 없습니다. SCP p-meobeew3 가 서울 외 리전 호출을 거부하므로 베어 모델 ID 를 쓰세요."
  }
}

variable "bedrock_spend_limit_usd" {
  description = "하루 누적 Bedrock 소비액 상한(USD). Lambda 가 호출 전에 DynamoDB 카운터와 대조한다"
  type        = number
  default     = 30
}

variable "crawl_bucket_name" {
  description = "크롤 원본과 정제 산출물이 함께 있는 S3 버킷 이름. ⚠ 이 버킷은 Terraform 관리 밖이며 이 모듈은 알림 설정만 붙인다"
  type        = string
}

variable "image_tag" {
  description = "Lambda 컨테이너 이미지 태그(커밋 SHA 등 불변 태그). ⚠ 해당 태그가 ECR 에 push 되기 전에는 apply 가 실패한다"
  type        = string
}

variable "log_retention_days" {
  description = "Lambda CloudWatch 로그 보관 기간(일). 무기한 보관은 비용이 계속 는다"
  type        = number
  default     = 14
}

variable "name_prefix" {
  description = "리소스 이름 접두사 (예: victoryfairy-dev)"
  type        = string
}

variable "pipeline_repository_url" {
  description = "정제 러너 컨테이너 이미지 ECR 리포지토리 URL (modules/ecr 출력)"
  type        = string
}

variable "tags" {
  description = "리소스에 병합할 추가 태그"
  type        = map(string)
  default     = {}
}
