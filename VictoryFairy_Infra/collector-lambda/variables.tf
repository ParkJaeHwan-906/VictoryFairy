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
  description = "일정 선적재(오늘~+N일). 기본 08:00 KST = 23:00 UTC."
  type        = string
  default     = "cron(0 23 * * ? *)"
}

# 라이브 윈도(~23:59 KST)가 닫힌 직후. 순연·편성 변경은 대체로 그날 경기가 끝날
# 무렵 확정되는데, 아침 룰만 있으면 그게 다음 날 08:00 까지 반영되지 않는다.
variable "games_sync_nightly_schedule" {
  description = "일정 선적재 2회차(오늘~+N일). 기본 00:30 KST = 15:30 UTC."
  type        = string
  default     = "cron(30 15 * * ? *)"
}

# 1분 간격인 이유: 이 룰이 상태뿐 아니라 **경기 전 선발 라인업**(네이버 preview)도
# 따라간다. 공시는 경기 직전에 한 번 뜨고, 10분 주기면 화면에 뜨기까지 최대 10분이
# 밀린다. 헛도는 비용은 스케줄 API 1회뿐이다 — 라인업이 다 들어온 경기는 py-collector
# 가 DB 판정으로 preview 호출 자체를 건너뛰고, 구장도 이미 아는 경기는 다시 받지 않는다.
variable "games_sync_live_schedule" {
  description = "경기 시간대 상태·선발라인업 폴링(당일만). 기본 17:00~23:59 KST 1분 간격 = 08:00~14:59 UTC."
  type        = string
  default     = "cron(* 8-14 * * ? *)"
}

# 상한 14 는 handler.py 의 MAX_SYNC_DAYS 와 같은 값이다. 핸들러가 어차피 자르므로
# 여기서 막지 않아도 사고는 안 나지만, plan 단계에서 걸리는 편이 낫다.
variable "games_sync_lookahead_days" {
  description = "일정 선적재 윈도(오늘~+N일). 라이브 폴링 룰은 이 값과 무관하게 당일만 훑는다."
  type        = number
  default     = 7

  validation {
    condition     = var.games_sync_lookahead_days >= 1 && var.games_sync_lookahead_days <= 14
    error_message = "games_sync_lookahead_days 는 1~14 이어야 한다 (handler.py MAX_SYNC_DAYS 상한)."
  }
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
