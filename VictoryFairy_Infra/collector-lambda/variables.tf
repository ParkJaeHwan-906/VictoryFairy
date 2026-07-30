variable "region" {
  type    = string
  default = "ap-northeast-2"
}

variable "name" {
  description = "Name for the Lambda, ECR repo, and rules"
  type        = string
  default     = "kbo-collector"
}

variable "github_repo" {
  description = "GitHub Actions OIDC 를 신뢰할 리포 (owner/name) — CI 배포 롤의 trust 조건"
  type        = string
  default     = "ParkJaeHwan-906/VictoryFairy"

  validation {
    condition     = can(regex("^[^/]+/[^/]+$", var.github_repo))
    error_message = "github_repo 는 owner/name 형식이어야 합니다."
  }
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

  validation {
    condition     = contains(["x86_64", "arm64"], var.architecture)
    error_message = "architecture 는 x86_64 또는 arm64 여야 합니다."
  }
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

variable "kbo_records_schedule" {
  description = "KBO 기록실 스냅샷 (07:00 KST)"
  type        = string
  default     = "cron(0 22 * * ? *)"
}

# 이름에 "_export_"를 넣어 위 game_schedule(= "game" 잡의 schedule/result/relay cron)과
# 구분한다 — 이 변수는 "game_schedule" 잡(당일 예정경기 export)의 cron이다.
variable "game_schedule_export_schedule" {
  description = "당일 예정경기 export (08:30 KST)"
  type        = string
  default     = "cron(30 23 * * ? *)"
}

# --- DB 적재 잡 (records/registrations) — lambda_db.tf ---
# db_subnet_ids 가 비어 있으면(기본) DB 잡 리소스는 아무것도 만들지 않는다.
# 값들은 VictoryFairy_Infra(environments/dev) 스택에서 가져온다 — 프라이빗 서브넷 id,
# VPC id, 데이터 EC2(MySQL)의 SG id·프라이빗 IP. (infra 쪽 terraform output 참고)

variable "db_subnet_ids" {
  description = "DB 잡 Lambda 를 붙일 VPC 프라이빗 서브넷 id 목록. 비우면 DB 잡 비활성."
  type        = list(string)
  default     = []
}

variable "db_vpc_id" {
  description = "위 서브넷이 속한 VPC id (Lambda SG 생성용)"
  type        = string
  default     = ""
}

variable "db_ingress_sg_id" {
  description = "데이터 EC2(MySQL) 의 보안그룹 id — 여기에 'Lambda SG 로부터 3306 허용' 인바운드 규칙을 단다"
  type        = string
  default     = ""
}

variable "db_host" {
  description = "운영 MySQL 호스트 (데이터 EC2 프라이빗 IP — 인스턴스 재생성 시 갱신 필요)"
  type        = string
  default     = ""
}

variable "db_port" {
  type    = number
  default = 3306
}

variable "db_name" {
  type    = string
  default = "victoryfairy"
}

variable "db_user" {
  type    = string
  default = "vf"
}

variable "db_password" {
  type      = string
  default   = ""
  sensitive = true
}

variable "records_schedule" {
  description = "경기 기록 DB 적재 스케줄. 기본 03:30 KST = 18:30 UTC (그 시각 UTC 날짜 = 전날 저녁 끝난 경기의 KST 날짜)."
  type        = string
  default     = "cron(30 18 * * ? *)"
}

variable "registrations_schedule" {
  description = "KBO 1군 등록명단 DB 적재 스케줄. 기본 11:00 KST = 02:00 UTC (당일 등록 변동 반영 후)."
  type        = string
  default     = "cron(0 2 * * ? *)"
}

variable "export_game_result_schedule" {
  description = "game_result envelope export (04:00 KST, records 03:30 이후)"
  type        = string
  default     = "cron(0 19 * * ? *)"
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
