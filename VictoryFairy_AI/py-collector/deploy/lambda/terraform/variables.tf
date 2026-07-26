variable "region" {
  type    = string
  default = "ap-northeast-2"
}

variable "name" {
  description = "Name for the Lambda, ECR repo, and rules"
  type        = string
  default     = "kbo-collector"
}

variable "data_bucket_name" {
  description = "Existing S3 bronze landing bucket the crawler writes to"
  type        = string
  default     = "victoryfairy-crawl-local"
}

variable "architecture" {
  description = "x86_64 or arm64 (arm64 is cheaper; native on Apple Silicon)"
  type        = string
  default     = "arm64"
}

variable "memory_mb" {
  type    = number
  default = 512
}

variable "timeout_s" {
  description = "Lambda timeout. Community is incremental (fast); keep < 900."
  type        = number
  default     = 840
}

variable "image_tag" {
  type    = string
  default = "latest"
}

variable "community_schedule" {
  description = "EventBridge schedule for the community crawl"
  type        = string
  default     = "rate(10 minutes)"
}

variable "community_concurrency" {
  description = "Parallel detail fetches per target (incremental runs stay small)"
  type        = number
  default     = 3
}

variable "community_delay_ms" {
  description = "Per-request polite delay for the community crawl (ms)"
  type        = number
  default     = 400
}

variable "community_max_pages" {
  description = "Pages to walk per DCInside gallery per run. Bounds the full-board date-walk so a run stays well under the Lambda timeout; the ~10-min re-scan catches posts while they're still in these recent pages (measured ~86s for 10 galleries at 20)."
  type        = number
  default     = 20
}

variable "game_schedule" {
  description = "EventBridge schedule for game data (schedule/result/relay). Default 03:00 KST = 18:00 UTC (games finish the prior KST evening; UTC 'today' at 18:00 UTC equals that KST game date)."
  type        = string
  default     = "cron(0 18 * * ? *)"
}

variable "pii_salt" {
  description = "Comment-author masking salt. If empty, a random one is generated."
  type        = string
  default     = ""
  sensitive   = true
}

variable "tags" {
  type    = map(string)
  default = { project = "kbo-collector", managed_by = "terraform" }
}
