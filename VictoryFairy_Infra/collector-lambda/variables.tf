variable "region" {
  type    = string
  default = "ap-northeast-2"
}

variable "name" {
  description = "Name for the Lambda, ECR repo, and rules"
  type        = string
  default     = "kbo-collector"
}

variable "collector_src" {
  description = "py-collector 소스 루트 경로(이미지 빌드 원천). 비우면 이 모듈 기준 ../../VictoryFairy_AI/py-collector — VictoryFairy_AI 가 나란히 있는 체크아웃(main)에서만 유효. 이미지는 apply 시점에 이 경로의 코드로 빌드되므로, 배포하려는 최신 코드가 있는 체크아웃을 가리켜야 한다(오래된 브랜치를 가리키면 옛 코드가 배포됨)."
  type        = string
  default     = ""
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
