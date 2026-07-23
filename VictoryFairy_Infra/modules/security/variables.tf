# 변수는 알파벳 순 (SKILL §1 파일 분리 규약)

variable "cluster_name" {
  description = "EKS 클러스터 이름 (Access Entry 대상 · eks:DescribeCluster 스코프)"
  type        = string
}

variable "deploy_namespaces" {
  description = "CI 배포 역할에 Edit 권한을 줄 k8s 네임스페이스 목록 (그 외 네임스페이스는 접근 불가)"
  type        = list(string)
  default     = ["victoryfairy"]
}

variable "ecr_repository_arns" {
  description = "CI 가 push 할 수 있는 ECR 리포지토리 ARN 목록 (이외 리포지토리는 거부)"
  type        = list(string)
  validation {
    condition     = length(var.ecr_repository_arns) > 0
    error_message = "ecr_repository_arns 는 최소 1개가 필요합니다."
  }
}

variable "github_allowed_refs" {
  description = "AssumeRole 을 허용할 GitHub 브랜치 목록 (예: [\"main\"]). 좁을수록 안전."
  type        = list(string)
  default     = ["main"]
}

variable "github_repository" {
  description = "GitHub 레포 (owner/name 형식). 이 레포의 워크플로만 역할을 맡을 수 있다."
  type        = string
  validation {
    condition     = can(regex("^[^/]+/[^/]+$", var.github_repository))
    error_message = "github_repository 는 owner/name 형식이어야 합니다."
  }
}

variable "name_prefix" {
  description = "IAM 리소스 이름 접두사 (예: victoryfairy-dev)"
  type        = string
}

variable "tags" {
  description = "리소스에 병합할 추가 태그"
  type        = map(string)
  default     = {}
}
