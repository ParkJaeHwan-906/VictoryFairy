# 변수는 알파벳 순 (SKILL §1 파일 분리 규약)

variable "bucket_name" {
  description = <<-EOT
    자산 버킷 이름(전역 유일). 예: victoryfairy-asset.

    ⚠ 다른 모듈처럼 name_prefix 로 조립하지 않고 이름을 통째로 받는다. 이 저장소의
      name_prefix 는 victoryfairy-dev(= local.cluster_name)라 "$${name_prefix}-asset" 으로는
      victoryfairy-dev-asset 이 되는데, BE·프론트가 쓰기로 한 이름은 victoryfairy-asset 이다.
      버킷 이름은 사용자에게 노출되지 않지만(CloudFront 뒤에 있다) BE 설정·IRSA 정책이
      이 문자열을 그대로 쓰므로 여기서 못 박는다.

    ⚠ 이 값의 변경은 버킷 재생성(=업로드된 이미지 전부 유실)이라 버킷에 prevent_destroy 를
      걸어 뒀다. 그래서 값을 고치면 apply 가 아니라 **plan 이 에러로 멈춘다** — 의도된 방어다.
      정말 바꿔야 하면 데이터 이전부터 하고 가드를 걷는다(절차는 main.tf §1 주석).
  EOT
  type        = string

  validation {
    condition     = can(regex("^[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]$", var.bucket_name))
    error_message = "bucket_name 은 S3 버킷 명명 규칙(소문자·숫자·하이픈, 3~63자)을 따라야 합니다."
  }
}

variable "cloudfront_distribution_arn" {
  description = "이 버킷을 읽도록 허용할 CloudFront 배포 ARN(modules/cdn 출력). 버킷 정책의 AWS:SourceArn 조건 값 — 이 배포 외에는 아무도 읽지 못한다."
  type        = string
}

variable "profile_prefix" {
  description = "확정 프로필 이미지 키 접두사(슬래시로 끝난다). BE 업로드 키·CloudFront 경로 패턴·IRSA 정책이 같은 값을 써야 한다. ⚠ 여기에는 만료 규칙을 걸지 않는다."
  type        = string
  default     = "user-profile-img/"

  validation {
    condition     = endswith(var.profile_prefix, "/") && !startswith(var.profile_prefix, "/")
    error_message = "profile_prefix 는 슬래시로 끝나고 슬래시로 시작하지 않아야 합니다(예: user-profile-img/)."
  }
}

variable "static_prefixes" {
  description = <<-EOT
    앱이 아니라 사람이 미리 올려 두는 정적 자산의 키 접두사 목록(각각 슬래시로 끝난다).
    캐릭터 꾸미기 에셋이 여기 해당한다 — characters/ · items/ · stores/.

    profile_prefix·temp_prefix 와 성격이 다르다: 저자가 user-app 파드가 아니라 사람이고
    (이 리포의 scripts/upload-character-assets.sh 로 올린다), 만료 규칙도 걸지 않는다. 그래서 IRSA
    (modules/user-irsa)에도 이 접두사에 대한 쓰기 권한을 주지 않는다 — 앱이 건드릴 이유가 없다.

    ⚠ 이 목록에서 빠진 접두사는 버킷 정책이 CloudFront 에게 읽기를 허용하지 않는다. 객체를
      올려도 배포를 통해서는 403 이므로, CloudFront 경로 패턴(modules/cdn 의
      asset_static_prefixes)과 반드시 같은 값이어야 한다.
  EOT
  type        = list(string)
  default     = []

  validation {
    condition = alltrue([
      for prefix in var.static_prefixes : endswith(prefix, "/") && !startswith(prefix, "/")
    ])
    error_message = "static_prefixes 의 각 항목은 슬래시로 끝나고 슬래시로 시작하지 않아야 합니다(예: characters/)."
  }
}

variable "tags" {
  description = "리소스에 병합할 추가 태그 (프로바이더 default_tags 위에 merge)"
  type        = map(string)
  default     = {}
}

variable "temp_expiration_days" {
  description = "temp/ 객체 만료 일수. 앱 스케줄러(24시간 경과분 삭제)의 안전망이라 최소값 1을 쓴다 — S3 가 표현할 수 있는 가장 짧은 주기다."
  type        = number
  default     = 1

  validation {
    condition     = var.temp_expiration_days >= 1
    error_message = "temp_expiration_days 는 1 이상이어야 합니다(S3 만료는 일 단위)."
  }
}

variable "temp_prefix" {
  description = "가입 전 임시 업로드 키 접두사(슬래시로 끝난다). 만료 규칙·CloudFront 경로 패턴·IRSA 정책이 같은 값을 써야 한다."
  type        = string
  default     = "temp/"

  validation {
    condition     = endswith(var.temp_prefix, "/") && !startswith(var.temp_prefix, "/")
    error_message = "temp_prefix 는 슬래시로 끝나고 슬래시로 시작하지 않아야 합니다(예: temp/)."
  }
}
