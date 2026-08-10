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

# --- 퀴즈 원천 잡 (kbo_records / game_schedule / export ×2) ---
# 네 룰 모두 quiz_source_jobs_enabled 하나로 켜고 끈다. 켜기 전 조건은
# schedules.tf 의 "퀴즈 원천 잡" 주석 참고.
#
# export 는 docType 하나당 룰 하나 — game_result / player_profile. 둘 다 DB 를
# 읽으므로 lambda_db.tf 쪽(-db 함수)에 있다. 나머지 docType 인 player_meme 은
# 시드 파일이 원본이라 크론을 두지 않는다(lambda_db.tf 의 해당 주석 참고).

variable "quiz_source_jobs_enabled" {
  description = "퀴즈 원천 잡(kbo_records/game_schedule/export ×2)의 EventBridge 룰 생성 여부. 배포 이미지의 handler.py 가 이 job 값들을 아는 뒤에 true 로 올린다."
  type        = bool
  default     = false
}

variable "kbo_records_schedule" {
  description = "KBO 기록실 스냅샷 -> S3. 기본 07:00 KST = 22:00 UTC (전날 경기가 기록실에 반영된 뒤)."
  type        = string
  default     = "cron(0 22 * * ? *)"
}

# 이름에 "_export_" 를 넣어 위 game_schedule 과 구분한다 — 저건 "game" 잡
# (schedule/result/relay)의 cron 이고, 이건 "game_schedule" 잡의 cron 이다.
variable "game_schedule_export_schedule" {
  description = "당일(KST) 예정경기 -> S3 question-source/. 기본 08:30 KST = 23:30 UTC."
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

variable "games_sync_morning_schedule" {
  description = "당일 경기 상태 선반영(SCHEDULED). 기본 08:00 KST = 23:00 UTC."
  type        = string
  default     = "cron(0 23 * * ? *)"
}

variable "games_sync_live_schedule" {
  description = "경기 시간대 상태 폴링. 기본 17:00~23:50 KST 10분 간격 = 08:00~14:50 UTC."
  type        = string
  default     = "cron(0/10 8-14 * * ? *)"
}

# 켜기 전 선행 조건 둘은 lambda_db.tf 의 cancel_reasons 블록 주석 참고
# (games.cancel_reason 컬럼 + 해당 잡을 아는 배포 이미지).
variable "cancel_reasons_enabled" {
  description = "KBO 취소 사유 잡의 EventBridge 룰 생성 여부. 선행 조건 둘을 확인한 뒤 true 로 올린다."
  type        = bool
  default     = false
}

variable "cancel_reasons_schedule" {
  description = "KBO 일정표 취소 사유 반영. 기본 01:00 KST = 16:00 UTC (00:30 games_sync 가 경기 행을 만든 뒤)."
  type        = string
  default     = "cron(0 16 * * ? *)"
}

variable "export_game_result_schedule" {
  description = "game_result envelope -> S3 question-source/. 기본 04:00 KST = 19:00 UTC (records 03:30 이후)."
  type        = string
  default     = "cron(0 19 * * ? *)"
}

variable "export_player_profile_schedule" {
  description = "player_profile envelope -> S3 question-source/. 기본 11:30 KST = 02:30 UTC (registrations 11:00 이후)."
  type        = string
  default     = "cron(30 2 * * ? *)"
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
