# 변수는 알파벳 순 (SKILL §1 파일 분리 규약)

variable "api_targets" {
  description = <<-EOT
    상시 점검할 BE 모듈과 헬스 경로. 키가 지표 차원(Target)이 되어 Slack 알림에 어느 모듈이
    죽었는지 그대로 드러난다.
      { user = "/api/actuator/health/readiness", quiz = "/rt/actuator/health/readiness" }

    ⚠ 이 실패는 FE 롤백을 유발하지 않고 알림만 보낸다 — 원인이 BE 인데 FE 를 되돌리면
      정상 FE 만 잃는다.
    ⚠ ALB 타깃그룹 지표(UnHealthyHostCount) 대신 이 방식을 쓴 이유: 타깃그룹은 AWS Load Balancer
      Controller 가 소유해 이름이 Ingress 재생성 때 바뀌므로 Terraform 이 안정적으로 붙잡을 수 없다.
      사용자 대면 URL 로 때리는 이 점검은 DNS→CloudFront→ALB→파드→앱 전 구간을 통과하므로
      타깃이 죽으면 503 으로 잡힌다.
    ⚠ 다만 '일부 파드만 죽은' 상태는 ALB 가 우회해 이 점검을 통과한다. 지금은 user·quiz 가 각
      1 레플리카라 1대 실패 = 전면 장애여서 차이가 없지만, 상시 2대 이상으로 올리면 그때는
      타깃그룹 지표가 따로 필요하다.
  EOT
  type        = map(string)
  default     = {}
}

variable "alarm_period_seconds" {
  description = "알람이 지표를 묶어 보는 구간. 점검 주기와 같거나 조금 길어야 한다 — 더 짧으면 지표가 없는 구간이 생겨 treat_missing_data(breaching)에 걸려 오탐이 난다."
  type        = number
  default     = 300
  validation {
    condition     = contains([60, 300, 900], var.alarm_period_seconds)
    error_message = "alarm_period_seconds 는 60·300·900 중 하나여야 합니다(표준 해상도)."
  }
}

variable "datapoints_to_alarm" {
  description = <<-EOT
    ALARM 으로 전이하기까지 필요한 연속 실패 횟수. 이것이 오탐 방어의 핵심이다.

    점검 주기 × 이 값 = 장애 감지까지 걸리는 시간. 5분 주기 × 2 = 최대 10분.
    1 로 낮추면 일시적 네트워크 흔들림 한 번에 정상 버전을 되돌린다 — 자동 롤백이 장애 원인이 된다.
  EOT
  type        = number
  default     = 2
  validation {
    condition     = var.datapoints_to_alarm >= 2
    error_message = "datapoints_to_alarm 은 2 이상이어야 합니다(1 이면 단발 실패에 롤백합니다)."
  }
}

variable "fe_bucket_arn" {
  description = "FE 정적 자산 버킷 ARN. responder 가 index.html 을 되돌려 쓴다."
  type        = string
}

variable "fe_bucket_name" {
  description = "FE 정적 자산 버킷 이름 (modules/cdn 의 bucket_name)."
  type        = string
}

variable "github_repo" {
  description = "티켓을 발행할 GitHub 레포 (owner/name). 비우면 티켓 발행을 생략한다."
  type        = string
  default     = ""
}

variable "github_token_param" {
  description = "GitHub 토큰을 담은 SSM 파라미터 이름(SecureString). 비우거나 파라미터가 없으면 티켓 발행만 생략되고 나머지는 동작한다 — 감시가 토큰 때문에 멈추지 않게 하려는 것이다. issues:write 권한만 있는 fine-grained 토큰을 쓸 것."
  type        = string
  default     = ""
}

variable "log_retention_days" {
  description = "Lambda 로그 보존 일수. 무기한 보존은 비용만 늘린다."
  type        = number
  default     = 14
}

variable "name_prefix" {
  description = "리소스 이름 접두사 (예: victoryfairy-dev)"
  type        = string
}

variable "releases_prefix" {
  description = "릴리스 아카이브 S3 접두사. 배포 워크플로가 쓰는 값과 일치해야 한다(.github/workflows/deploy-fe.yml)."
  type        = string
  default     = "releases/"
}

variable "rollback_cooldown_seconds" {
  description = "직전 전환(배포·롤백) 이후 이 시간 안에는 다시 롤백하지 않는다. 알람이 OK↔ALARM 로 요동칠 때 연속 되감기를 막는 2차 방어다(1차는 알람이 상태 전이에만 통보한다는 성질)."
  type        = number
  default     = 600
}

variable "schedule_expression" {
  description = "점검 주기 (EventBridge 표현식). 짧게 하면 감지가 빨라지지만 호출 수가 늘어난다 — Lambda 무료 한도 안이라 비용 영향은 사실상 없다."
  type        = string
  default     = "rate(5 minutes)"
}

variable "site_url" {
  description = "점검 대상 사용자 대면 URL (예: https://victoryfairy.com). ⚠ CloudFront 배포 도메인이 아니라 apex 여야 한다 — Host 헤더가 ALB 규칙과 맞아야 /api 점검이 성립한다."
  type        = string
}

variable "slack_webhook_param" {
  description = "Slack Incoming Webhook URL 을 담은 SSM 파라미터 이름(SecureString). 비우거나 파라미터가 없으면 Slack 알림만 생략되고 롤백은 동작한다."
  type        = string
  default     = ""
}

variable "tags" {
  description = "리소스에 병합할 추가 태그"
  type        = map(string)
  default     = {}
}
