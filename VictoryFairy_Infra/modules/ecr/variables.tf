# 변수는 알파벳 순 (SKILL §1 파일 분리 규약)

variable "keep_image_count" {
  description = "리포지토리당 보관할 최근 이미지 개수 (초과분은 수명주기 정책이 삭제)"
  type        = number
  default     = 10
  validation {
    condition     = var.keep_image_count >= 1
    error_message = "keep_image_count 는 1 이상이어야 합니다."
  }
}

variable "name_prefix" {
  description = "리포지토리 이름 접두사 (예: victoryfairy → victoryfairy-user)"
  type        = string
}

variable "repository_names" {
  description = "생성할 리포지토리 이름 목록 (접두사 뒤에 붙음. 예: [\"user\", \"quiz\"])"
  type        = list(string)
  validation {
    condition     = length(var.repository_names) > 0
    error_message = "repository_names 는 최소 1개가 필요합니다."
  }
}

variable "tags" {
  description = "리소스에 병합할 추가 태그"
  type        = map(string)
  default     = {}
}
